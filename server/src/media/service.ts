import { createHash, randomUUID } from 'node:crypto';
import { createReadStream, createWriteStream } from 'node:fs';
import { mkdir, open, readdir, rename, rm, rmdir, stat, unlink } from 'node:fs/promises';
import { dirname, isAbsolute, relative, resolve, sep } from 'node:path';
import { Transform, type Readable } from 'node:stream';
import { pipeline } from 'node:stream/promises';
import type { PrismaClient } from '../generated/prisma/client.js';
import type { Config } from '../config.js';
import { ApiError } from '../errors.js';
import type { Principal } from '../auth/service.js';
import { verifyOriginal } from './originals.js';

export type AssetInput = {
  originalFileName: string;
  mimeType: string;
  capturedAt?: Date;
  fileSize: bigint;
  width: number;
  height: number;
  durationMillis?: bigint;
  clientAssetId?: string;
  expectedSha256?: string;
};

type DerivativeViewInput = {
  kind: string; status: string; mimeType: string | null; fileSize: bigint | null;
  width: number | null; height: number | null; durationMillis: bigint | null;
  attempts: number; nextAttemptAt: Date; updatedAt: Date; sha256?: string | null;
};
type AssetViewInput = {
  id: string; ownerId: string; albumId: string; originalFileName: string; mimeType: string;
  capturedAt: Date | null; fileSize: bigint; width: number; height: number;
  durationMillis: bigint | null; sha256: string | null; status: string;
  integrityStatus: string; integrityError: string | null;
  createdAt: Date; updatedAt: Date; derivatives?: DerivativeViewInput[];
};
type TrashAssetInput = AssetViewInput & {
  deletedAt: Date | null; purgeAfter: Date | null; cleanupLastError: string | null;
  album: { id: string; title: string };
};

const notFound = () => new ApiError(404, 'NOT_FOUND', 'Resource not found');
const conflict = (message: string) => new ApiError(409, 'CONFLICT', message);

function normalizeSourceVolume(value: string): string {
  const normalized = value.normalize('NFKC').trim().toLowerCase();
  return normalized === 'external' ? 'external_primary' : normalized;
}

function normalizeSourceRelativePath(value: string): string {
  let parts = value.normalize('NFKC').replaceAll('\\', '/').split('/')
    .map((part) => part.trim()).filter(Boolean);
  const lower = parts.map((part) => part.toLowerCase());
  const prefix = lower.slice(0, 3).join('/');
  if (prefix === 'storage/emulated/0' || prefix === 'storage/self/primary') parts = parts.slice(3);
  else if (lower[0] === 'sdcard') parts = parts.slice(1);
  if (parts.length === 0 || parts.some((part) => part === '.' || part === '..')) {
    throw new ApiError(400, 'INVALID_REQUEST', 'Invalid album source path');
  }
  return parts.join('/').toLowerCase();
}

function albumView(album: {
  id: string; ownerId: string; sourceDeviceId: string; clientAlbumId: string; sourceVolume: string; sourceRelativePath: string; title: string;
  sharedAt: Date | null; backedUpAt: Date | null; createdAt: Date; updatedAt: Date;
  owner: { id: string; displayName: string };
}, principal: Principal) {
  return {
    id: album.id,
    owner: album.owner,
    title: album.title,
    ownedByMe: album.ownerId === principal.userId,
    shared: album.sharedAt !== null,
    ...(album.ownerId === principal.userId
      ? {
          sourceDeviceId: album.sourceDeviceId,
          clientAlbumId: album.clientAlbumId,
          sourceVolume: album.sourceVolume,
          sourceRelativePath: album.sourceRelativePath,
          backedUp: album.backedUpAt !== null,
        }
      : {}),
    createdAt: album.createdAt,
    updatedAt: album.updatedAt,
  };
}

function assetView(asset: AssetViewInput) {
  return {
    id: asset.id,
    ownerId: asset.ownerId,
    albumId: asset.albumId,
    originalFileName: asset.originalFileName,
    mimeType: asset.mimeType,
    capturedAt: asset.capturedAt,
    fileSize: asset.fileSize.toString(),
    width: asset.width,
    height: asset.height,
    durationMillis: asset.durationMillis?.toString() ?? null,
    sha256: asset.sha256,
    status: asset.status,
    integrityStatus: asset.integrityStatus,
    integrityError: asset.integrityError,
    derivatives: [...(asset.derivatives ?? [])]
      .sort((left, right) => left.kind.localeCompare(right.kind))
      .map((derivative) => ({
        kind: derivative.kind,
        status: derivative.status,
        mimeType: derivative.mimeType,
        fileSize: derivative.fileSize?.toString() ?? null,
        width: derivative.width,
        height: derivative.height,
        durationMillis: derivative.durationMillis?.toString() ?? null,
        sha256: derivative.sha256 ?? null,
        attempts: derivative.attempts,
        nextAttemptAt: derivative.nextAttemptAt,
        updatedAt: derivative.updatedAt,
      })),
    createdAt: asset.createdAt,
    updatedAt: asset.updatedAt,
  };
}

function trashView(asset: TrashAssetInput, now = new Date()) {
  const derivatives = [...(asset.derivatives ?? [])]
    .sort((left, right) => left.kind.localeCompare(right.kind));
  const availableVariants = [
    ...(asset.sha256 ? ['original'] : []),
    ...derivatives.filter((item) => item.status === 'ready').map((item) => item.kind),
  ];
  const remainingRetentionSeconds = asset.purgeAfter
    ? Math.max(0, Math.ceil((asset.purgeAfter.getTime() - now.getTime()) / 1000))
    : 0;
  return {
    ...assetView(asset),
    deletedAt: asset.deletedAt,
    purgeAfter: asset.purgeAfter,
    remainingRetentionSeconds,
    originalAlbum: asset.album,
    availableVariants,
    cleanupLastError: asset.cleanupLastError,
  };
}

function albumSummaryView(album: Parameters<typeof albumView>[0] & {
  _count: { assets: number };
  assets: Array<{ id: string; updatedAt: Date; derivatives: Array<{ updatedAt: Date; sha256: string | null }> }>
}, principal: Principal, sizes?: { original: bigint; optimized: bigint }) {
  const cover = album.assets[0];
  const thumbnail = cover?.derivatives[0];
  return {
    ...albumView(album, principal),
    assetCount: album._count.assets,
    originalBytes: (sizes?.original ?? 0n).toString(),
    optimizedBytes: (sizes?.optimized ?? 0n).toString(),
    cover: cover && thumbnail ? {
      assetId: cover.id,
      version: thumbnail.updatedAt,
      sha256: thumbnail.sha256,
    } : null,
  };
}

function withinMediaRoot(root: string, storagePath: string): string {
  const absolute = resolve(root, storagePath);
  const path = relative(root, absolute);
  if (isAbsolute(storagePath) || path === '' || path === '..' || path.startsWith(`..${sep}`)) {
    throw new Error('Unsafe storage path');
  }
  return absolute;
}

async function sha256File(path: string): Promise<string> {
  const digest = createHash('sha256');
  const input = createReadStream(path);
  for await (const chunk of input) digest.update(chunk as Buffer);
  return digest.digest('hex');
}

async function fsyncDirectory(path: string): Promise<void> {
  const handle = await open(path, 'r');
  try { await handle.sync(); } finally { await handle.close(); }
}

type MediaServiceHooks = {
  afterUploadRenamed?: (assetId: string, storagePath: string) => Promise<void>;
};

export function createMediaService(client: PrismaClient, config: Config, hooks: MediaServiceHooks = {}) {
  let recoveryTimer: NodeJS.Timeout | undefined;
  let recoveryActive: Promise<number> | undefined;
  let recoveryStopping = false;
  let trashTimer: NodeJS.Timeout | undefined;
  let trashActive: Promise<number> | undefined;
  let trashStopping = false;
  const trashRetentionMs = 90 * 24 * 60 * 60 * 1000;
  const trashCleanupLeaseMs = Math.max(15 * 60 * 1000, config.derivativeToolTimeoutMs * 2);
  const ownerSelection = { id: true, displayName: true } as const;
  const derivatives = { orderBy: { kind: 'asc' as const } } as const;

  async function accessibleAlbum(principal: Principal, albumId: string) {
    const album = await client.album.findFirst({
      where: { id: albumId, owner: { pairId: principal.pairId } },
      include: { owner: { select: ownerSelection } },
    });
    if (!album || (album.ownerId !== principal.userId && album.sharedAt === null)) throw notFound();
    return album;
  }

  async function accessibleAsset(
    principal: Principal,
    assetId: string,
    readyOnly = false,
    allowOwnTrash = false,
  ) {
    const asset = await client.asset.findFirst({
      where: { id: assetId },
      include: { derivatives, album: { include: { owner: { select: { pairId: true } } } } },
    });
    const own = asset?.ownerId === principal.userId;
    if (asset?.status === 'ready' && asset.integrityStatus === 'error') {
      if (!own) throw notFound();
      throw new ApiError(503, 'ORIGINAL_UNAVAILABLE', 'Original file failed its integrity check');
    }
    if (!asset || asset.album.owner.pairId !== principal.pairId ||
        (!own && (asset.album.sharedAt === null || asset.status !== 'ready')) ||
        (own && ['deleted', 'purging', 'purged'].includes(asset.status) && !(allowOwnTrash && asset.status === 'deleted')) ||
        (readyOnly && asset.status !== 'ready' && !(allowOwnTrash && asset.status === 'deleted'))) throw notFound();
    return asset;
  }

  async function reusableAsset(principal: Principal, albumId: string, input: AssetInput) {
    if (!input.clientAssetId && !input.expectedSha256) return null;
    const duplicate = await client.asset.findFirst({ where: {
      OR: [
        ...(input.clientAssetId ? [{ sourceDeviceId: principal.deviceId, clientAssetId: input.clientAssetId }] : []),
        ...(input.expectedSha256 ? [{ albumId, expectedSha256: input.expectedSha256 }] : []),
      ],
    }, include: { derivatives } });
    if (!duplicate) return null;
    if (duplicate.ownerId !== principal.userId || duplicate.albumId !== albumId) {
      throw conflict('Client asset identity is already in use');
    }
    if (input.expectedSha256 && duplicate.expectedSha256 && input.expectedSha256 !== duplicate.expectedSha256) {
      throw conflict('Client asset identity has different content');
    }
    if (['deleted', 'purging', 'purged'].includes(duplicate.status)) {
      throw conflict('Client asset identity is retained as a tombstone');
    }
    if (duplicate.status === 'ready' && duplicate.integrityStatus === 'error') {
      throw new ApiError(503, 'ORIGINAL_UNAVAILABLE', 'Stored original failed its integrity check');
    }
    if (duplicate.status === 'failed') {
      return client.asset.update({ where: { id: duplicate.id }, data: { status: 'pending' }, include: { derivatives } });
    }
    return duplicate;
  }

  const uploadDirectory = resolve(config.mediaRoot, 'uploads');

  function uploadSessionView(asset: any, completedAsset?: AssetViewInput) {
    return {
      id: asset.uploadSessionId,
      assetId: asset.id,
      offset: (completedAsset ? completedAsset.fileSize : asset.uploadOffset).toString(),
      size: asset.fileSize.toString(),
      maxChunkBytes: config.maxUploadChunkBytes.toString(),
      expiresAt: asset.uploadExpiresAt,
      completed: completedAsset !== undefined,
      asset: completedAsset ? assetView(completedAsset) : null,
    };
  }

  const clearedUploadState = {
    uploadStartedAt: null,
    uploadLeaseId: null,
    uploadLeaseExpiresAt: null,
    uploadSessionId: null,
    uploadOffset: 0n,
    uploadPartPath: null,
    uploadExpiresAt: null,
  } as const;

  async function completeUpload(
    asset: any,
    leaseId: string,
    sha256: string,
  ): Promise<AssetViewInput | null> {
    let completed = false;
    await client.$transaction(async (transaction) => {
      const result = await transaction.asset.updateMany({
        where: {
          id: asset.id,
          status: 'uploading',
          uploadSessionId: asset.uploadSessionId,
          uploadLeaseId: leaseId,
        },
        data: { status: 'ready', sha256, ...clearedUploadState },
      });
      if (result.count !== 1) return;
      completed = true;
      await transaction.assetDerivative.createMany({
        data: ['thumbnail', 'optimized'].map((kind) => ({
          id: randomUUID(), assetId: asset.id, kind,
        })),
        skipDuplicates: true,
      });
    });
    if (!completed) return null;
    return client.asset.findUniqueOrThrow({ where: { id: asset.id }, include: { derivatives } });
  }

  async function cleanupOrphanUploadParts(): Promise<number> {
    let names: string[];
    try { names = await readdir(uploadDirectory); } catch { return 0; }
    const referenced = new Set((await client.asset.findMany({
      where: { status: 'uploading', uploadPartPath: { not: null } },
      select: { uploadPartPath: true },
    })).flatMap((asset) => asset.uploadPartPath ? [resolve(config.mediaRoot, asset.uploadPartPath)] : []));
    let removed = 0;
    for (const name of names.filter((item) => item.endsWith('.part'))) {
      const path = resolve(uploadDirectory, name);
      if (!referenced.has(path)) {
        await unlink(path).catch(() => undefined);
        removed += 1;
      }
    }
    return removed;
  }

  async function recoverStaleUploads(now = new Date(), onlyId?: string): Promise<number> {
    const stale = await client.asset.findMany({
      where: {
        ...(onlyId ? { id: onlyId } : {}),
        status: 'uploading',
        OR: [
          { uploadSessionId: null },
          {
            uploadExpiresAt: { lte: now },
            OR: [
              { uploadLeaseId: null },
              { uploadLeaseExpiresAt: null },
              { uploadLeaseExpiresAt: { lte: now } },
            ],
          },
          {
            uploadLeaseId: { not: null },
            OR: [{ uploadLeaseExpiresAt: null }, { uploadLeaseExpiresAt: { lte: now } }],
          },
        ],
      },
      orderBy: [{ uploadLeaseExpiresAt: 'asc' }, { id: 'asc' }],
      take: 100,
    });
    let recovered = 0;
    for (const asset of stale) {
      const recoveryLeaseId = randomUUID();
      const claimed = await client.asset.updateMany({
        where: {
          id: asset.id,
          status: 'uploading',
          uploadSessionId: asset.uploadSessionId,
          uploadLeaseId: asset.uploadLeaseId,
          OR: [
            { uploadSessionId: null },
            {
              uploadExpiresAt: { lte: now },
              OR: [
                { uploadLeaseId: null },
                { uploadLeaseExpiresAt: null },
                { uploadLeaseExpiresAt: { lte: now } },
              ],
            },
            {
              uploadLeaseId: { not: null },
              OR: [{ uploadLeaseExpiresAt: null }, { uploadLeaseExpiresAt: { lte: now } }],
            },
          ],
        },
        data: {
          uploadLeaseId: recoveryLeaseId,
          uploadLeaseExpiresAt: new Date(now.getTime() + config.uploadLeaseMs),
        },
      });
      if (claimed.count !== 1) continue;

      const finalPath = withinMediaRoot(config.mediaRoot, asset.storagePath);
      const partPath = asset.uploadPartPath
        ? withinMediaRoot(config.mediaRoot, asset.uploadPartPath)
        : null;
      let completed: AssetViewInput | null = null;
      for (const candidate of [finalPath, partPath]) {
        if (!candidate) continue;
        try {
          const file = await stat(candidate);
          if (!file.isFile() || file.size !== Number(asset.fileSize)) continue;
          const sha256 = await sha256File(candidate);
          if (asset.expectedSha256 && sha256 !== asset.expectedSha256) continue;
          if (candidate !== finalPath) {
            await mkdir(dirname(finalPath), { recursive: true, mode: 0o700 });
            await rename(candidate, finalPath);
            await fsyncDirectory(dirname(finalPath));
          }
          completed = await completeUpload(asset, recoveryLeaseId, sha256);
          if (completed) break;
        } catch {
          // The next candidate or the normal offset reconciliation is authoritative.
        }
      }

      if (completed) {
        recovered += 1;
        continue;
      }

      if (!asset.uploadSessionId || !asset.uploadPartPath || !asset.uploadExpiresAt ||
          asset.uploadExpiresAt <= now) {
        if (partPath) await unlink(partPath).catch(() => undefined);
        await unlink(finalPath).catch(() => undefined);
        await client.asset.updateMany({
          where: { id: asset.id, status: 'uploading', uploadLeaseId: recoveryLeaseId },
          data: {
            status: 'pending',
            sha256: null,
            ...clearedUploadState,
          },
        });
      } else {
        let actualOffset = 0n;
        if (partPath) {
          try {
            const file = await stat(partPath);
            if (file.isFile()) {
              actualOffset = BigInt(file.size);
              if (actualOffset > asset.uploadOffset) {
                const handle = await open(partPath, 'r+');
                try { await handle.truncate(Number(asset.uploadOffset)); }
                finally { await handle.close(); }
                actualOffset = asset.uploadOffset;
              }
            }
          } catch { actualOffset = 0n; }
        }
        await client.asset.updateMany({
          where: { id: asset.id, status: 'uploading', uploadLeaseId: recoveryLeaseId },
          data: {
            uploadOffset: actualOffset,
            uploadLeaseId: null,
            uploadLeaseExpiresAt: null,
          },
        });
      }
      recovered += 1;
    }
    await cleanupOrphanUploadParts();
    return recovered;
  }

  function scheduleRecovery(): void {
    if (recoveryStopping) return;
    recoveryTimer = setTimeout(() => {
      recoveryActive = recoverStaleUploads()
        .catch(() => 0)
        .finally(() => {
          recoveryActive = undefined;
          scheduleRecovery();
        });
    }, config.uploadRecoveryIntervalMs);
    recoveryTimer.unref();
  }

  const trashInclude = {
    derivatives,
    album: { select: { id: true, title: true } },
  } as const;

  function trashAssetView(asset: any, now = new Date()) {
    return trashView(asset as TrashAssetInput, now);
  }

  async function unlinkStoragePath(storagePath: string | null): Promise<void> {
    if (!storagePath) return;
    const path = withinMediaRoot(config.mediaRoot, storagePath);
    try { await unlink(path); }
    catch (error) {
      if (error instanceof Error && 'code' in error && error.code === 'ENOENT') return;
      throw error;
    }
  }

  async function removeEmptyAssetDirectories(storagePaths: Array<string | null>): Promise<void> {
    const directories = new Set(storagePaths.filter((path): path is string => path !== null)
      .map((path) => dirname(withinMediaRoot(config.mediaRoot, path))));
    for (const directory of directories) {
      try { await rmdir(directory); }
      catch (error) {
        if (error instanceof Error && 'code' in error &&
          (error.code === 'ENOENT' || error.code === 'ENOTEMPTY')) continue;
        throw error;
      }
    }
  }

  async function purgeClaimed(assetId: string, ownerId: string, leaseId: string): Promise<void> {
    const asset = await client.asset.findFirst({
      where: { id: assetId, ownerId, status: 'purging', cleanupLeaseId: leaseId },
      include: { derivatives: true },
    });
    if (!asset) return;
    try {
      await unlinkStoragePath(asset.storagePath);
      for (const derivative of asset.derivatives) await unlinkStoragePath(derivative.storagePath);
      const derivativeDirectory = withinMediaRoot(config.mediaRoot,
        `derivatives/${asset.ownerId}/${asset.id}`);
      await rm(derivativeDirectory, { recursive: true, force: true });
      await client.$transaction(async (transaction) => {
        const completed = await transaction.asset.updateMany({
          where: { id: asset.id, ownerId, status: 'purging', cleanupLeaseId: leaseId },
          data: {
            status: 'purged', sha256: null, deletedAt: null, purgeAfter: null,
            cleanupRequestedAt: null, cleanupLastError: null, cleanupLeaseId: null,
            cleanupLeaseExpiresAt: null, purgedAt: new Date(),
          },
        });
        if (completed.count === 1) await transaction.assetDerivative.deleteMany({ where: { assetId: asset.id } });
      });
      await removeEmptyAssetDirectories([asset.storagePath, ...asset.derivatives.map((item) => item.storagePath)])
        .catch(() => undefined);
    } catch (error) {
      const message = error instanceof Error ? error.message.slice(0, 500) : 'Dateilöschung fehlgeschlagen';
      await client.asset.updateMany({
        where: { id: asset.id, ownerId, status: 'purging', cleanupLeaseId: leaseId },
        data: { cleanupLastError: message, cleanupLeaseId: null, cleanupLeaseExpiresAt: null },
      });
      throw new ApiError(503, 'CLEANUP_FAILED', 'Physische Dateilöschung fehlgeschlagen');
    }
  }

  async function claimAndPurge(assetId: string, ownerId: string, now = new Date()): Promise<void> {
    const leaseId = randomUUID();
    const claimed = await client.asset.updateMany({
      where: {
        id: assetId, ownerId,
        OR: [
          { status: 'deleted', OR: [{ purgeAfter: { lte: now } }, { cleanupRequestedAt: { not: null } }] },
          { status: 'purging', OR: [{ cleanupLeaseExpiresAt: { lte: now } }, { cleanupLeaseExpiresAt: null }] },
        ],
      },
      data: {
        status: 'purging', cleanupAttempts: { increment: 1 }, cleanupLastError: null,
        cleanupLeaseId: leaseId, cleanupLeaseExpiresAt: new Date(now.getTime() + trashCleanupLeaseMs),
      },
    });
    if (claimed.count === 1) await purgeClaimed(assetId, ownerId, leaseId);
  }

  async function purgeExpired(now = new Date()): Promise<number> {
    const assets = await client.asset.findMany({
      where: {
        OR: [
          { status: 'deleted', OR: [{ purgeAfter: { lte: now } }, { cleanupRequestedAt: { not: null } }] },
          { status: 'purging', OR: [{ cleanupLeaseExpiresAt: { lte: now } }, { cleanupLeaseExpiresAt: null }] },
        ],
      },
      orderBy: [{ purgeAfter: 'asc' }, { id: 'asc' }],
      take: 100,
      select: { id: true, ownerId: true },
    });
    let purged = 0;
    for (const asset of assets) {
      try {
        await claimAndPurge(asset.id, asset.ownerId, now);
        const current = await client.asset.findUnique({ where: { id: asset.id }, select: { status: true } });
        if (current?.status === 'purged') purged += 1;
      } catch { /* Tombstone für nächsten Versuch erhalten */ }
    }
    return purged;
  }

  function scheduleTrashCleanup(): void {
    if (trashStopping) return;
    trashTimer = setTimeout(() => {
      trashActive = purgeExpired()
        .catch(() => 0)
        .finally(() => { trashActive = undefined; scheduleTrashCleanup(); });
    }, 24 * 60 * 60 * 1000);
    trashTimer.unref();
  }

  async function beginUploadSession(principal: Principal, id: string, now = new Date()) {
    let asset = await client.asset.findFirst({ where: { id, ownerId: principal.userId }, include: { derivatives } });
    if (!asset) throw notFound();
    if (asset.status === 'uploading' && (
      !asset.uploadSessionId ||
      !asset.uploadExpiresAt ||
      (asset.uploadExpiresAt <= now && (!asset.uploadLeaseId ||
        !asset.uploadLeaseExpiresAt || asset.uploadLeaseExpiresAt <= now)) ||
      (asset.uploadLeaseId && (!asset.uploadLeaseExpiresAt || asset.uploadLeaseExpiresAt <= now))
    )) {
      await recoverStaleUploads(now, asset.id);
      asset = await client.asset.findFirst({ where: { id, ownerId: principal.userId }, include: { derivatives } });
      if (!asset) throw notFound();
    }
    if (asset.status === 'uploading') {
      if (!asset.uploadSessionId || !asset.uploadPartPath || !asset.uploadExpiresAt) {
        throw conflict('Upload session is being recovered');
      }
      if (!asset.uploadLeaseId) {
        const partPath = withinMediaRoot(config.mediaRoot, asset.uploadPartPath);
        let actual = 0n;
        try {
          const file = await stat(partPath);
          if (file.isFile()) actual = BigInt(file.size);
        } catch { actual = 0n; }
        if (actual !== asset.uploadOffset) {
          if (actual > asset.uploadOffset) {
            const handle = await open(partPath, 'r+');
            try { await handle.truncate(Number(asset.uploadOffset)); }
            finally { await handle.close(); }
            actual = asset.uploadOffset;
          }
          asset = await client.asset.update({
            where: { id: asset.id },
            data: { uploadOffset: actual },
            include: { derivatives },
          });
        }
      }
      return uploadSessionView(asset);
    }
    if (asset.status !== 'pending') throw conflict('Asset is not awaiting an upload');
    const sessionId = randomUUID();
    const uploadPartPath = `uploads/${asset.id}-${sessionId}.part`;
    const claimed = await client.asset.updateMany({
      where: { id: asset.id, ownerId: principal.userId, status: 'pending' },
      data: {
        status: 'uploading',
        uploadStartedAt: now,
        uploadSessionId: sessionId,
        uploadOffset: 0n,
        uploadPartPath,
        uploadExpiresAt: new Date(now.getTime() + config.uploadSessionTtlMs),
        uploadLeaseId: null,
        uploadLeaseExpiresAt: null,
      },
    });
    if (claimed.count !== 1) return beginUploadSession(principal, id, now);
    asset = await client.asset.findUniqueOrThrow({ where: { id: asset.id }, include: { derivatives } });
    return uploadSessionView(asset);
  }

  async function appendUploadChunk(
    principal: Principal,
    sessionId: string,
    expectedOffset: bigint,
    input: Readable,
    contentLength: number,
    allowFullSize = false,
    now = new Date(),
  ) {
    const asset = await client.asset.findFirst({
      where: { uploadSessionId: sessionId, ownerId: principal.userId, status: 'uploading' },
      include: { derivatives },
    });
    if (!asset) throw notFound();
    if (!asset.uploadPartPath || !asset.uploadExpiresAt || asset.uploadExpiresAt <= now) {
      throw conflict('Upload session has expired');
    }
    if (expectedOffset < 0n || expectedOffset !== asset.uploadOffset) {
      throw new ApiError(409, 'UPLOAD_OFFSET_MISMATCH', 'Upload offset does not match server state');
    }
    if (contentLength <= 0 || (!allowFullSize && contentLength > config.maxUploadChunkBytes) ||
        expectedOffset + BigInt(contentLength) > asset.fileSize) {
      throw new ApiError(422, 'UPLOAD_CHUNK_INVALID', 'Upload chunk exceeds the declared asset size');
    }

    const leaseId = randomUUID();
    const claimed = await client.asset.updateMany({
      where: {
        id: asset.id,
        ownerId: principal.userId,
        status: 'uploading',
        uploadSessionId: sessionId,
        uploadOffset: expectedOffset,
        uploadExpiresAt: { gt: now },
        OR: [
          { uploadLeaseId: null },
          { uploadLeaseExpiresAt: null },
          { uploadLeaseExpiresAt: { lte: now } },
        ],
      },
      data: {
        uploadLeaseId: leaseId,
        uploadLeaseExpiresAt: new Date(now.getTime() + config.uploadLeaseMs),
      },
    });
    if (claimed.count !== 1) throw conflict('Upload session is busy');

    const partPath = withinMediaRoot(config.mediaRoot, asset.uploadPartPath);
    const finalPath = withinMediaRoot(config.mediaRoot, asset.storagePath);
    let renamed = false;
    let heartbeatError: unknown;
    let heartbeatInFlight = Promise.resolve();
    const heartbeat = setInterval(() => {
      heartbeatInFlight = heartbeatInFlight.then(async () => {
        const renewed = await client.asset.updateMany({
          where: {
            id: asset.id, status: 'uploading',
            uploadSessionId: sessionId, uploadLeaseId: leaseId,
          },
          data: { uploadLeaseExpiresAt: new Date(Date.now() + config.uploadLeaseMs) },
        });
        if (renewed.count !== 1) throw new Error('Upload lease was lost');
      }).catch((error) => {
        heartbeatError = error;
        input.destroy(error instanceof Error ? error : new Error('Upload lease renewal failed'));
      });
    }, Math.max(1000, Math.floor(config.uploadLeaseMs / 3)));
    heartbeat.unref();

    try {
      await mkdir(dirname(partPath), { recursive: true, mode: 0o700 });
      await mkdir(dirname(finalPath), { recursive: true, mode: 0o700 });
      let actualOffset = 0n;
      try {
        const file = await stat(partPath);
        if (file.isFile()) actualOffset = BigInt(file.size);
      } catch { actualOffset = 0n; }
      if (actualOffset !== expectedOffset) {
        await client.asset.updateMany({
          where: { id: asset.id, status: 'uploading', uploadSessionId: sessionId, uploadLeaseId: leaseId },
          data: {
            uploadOffset: actualOffset <= asset.fileSize ? actualOffset : 0n,
            uploadLeaseId: null,
            uploadLeaseExpiresAt: null,
          },
        });
        throw new ApiError(409, 'UPLOAD_OFFSET_MISMATCH', 'Upload part and database offset differ');
      }

      let bytes = 0;
      const verifier = new Transform({
        transform(chunk: Buffer, _encoding, callback) {
          bytes += chunk.length;
          if (bytes > contentLength) {
            callback(new ApiError(422, 'UPLOAD_CHUNK_INVALID', 'Chunk exceeds Content-Length'));
            return;
          }
          callback(null, chunk);
        },
      });
      await pipeline(input, verifier, createWriteStream(partPath, {
        flags: expectedOffset === 0n ? 'w' : 'r+',
        start: Number(expectedOffset),
        mode: 0o600,
      }));
      clearInterval(heartbeat);
      await heartbeatInFlight;
      if (heartbeatError) throw heartbeatError;
      if (bytes !== contentLength) {
        throw new ApiError(422, 'UPLOAD_SIZE_MISMATCH', 'Chunk bytes do not match Content-Length');
      }
      const handle = await open(partPath, 'r');
      try { await handle.sync(); } finally { await handle.close(); }
      const nextOffset = expectedOffset + BigInt(bytes);
      if (nextOffset < asset.fileSize) {
        const advanced = await client.asset.updateMany({
          where: {
            id: asset.id, status: 'uploading', uploadSessionId: sessionId,
            uploadLeaseId: leaseId, uploadOffset: expectedOffset,
          },
          data: {
            uploadOffset: nextOffset,
            uploadExpiresAt: new Date(Date.now() + config.uploadSessionTtlMs),
            uploadLeaseId: null,
            uploadLeaseExpiresAt: null,
          },
        });
        if (advanced.count !== 1) throw new Error('Upload lease was lost before progress commit');
        return uploadSessionView({ ...asset, uploadOffset: nextOffset });
      }

      const sha256 = await sha256File(partPath);
      if (asset.expectedSha256 && sha256 !== asset.expectedSha256) {
        await unlink(partPath).catch(() => undefined);
        await client.asset.updateMany({
          where: { id: asset.id, status: 'uploading', uploadSessionId: sessionId, uploadLeaseId: leaseId },
          data: { status: 'failed', sha256: null, ...clearedUploadState },
        });
        throw new ApiError(422, 'UPLOAD_HASH_MISMATCH', 'Uploaded bytes do not match declared SHA-256');
      }
      await rename(partPath, finalPath);
      await fsyncDirectory(dirname(finalPath));
      renamed = true;
      await hooks.afterUploadRenamed?.(asset.id, asset.storagePath);
      const completed = await completeUpload(asset, leaseId, sha256);
      if (!completed) throw new Error('Upload lease was lost before completion');
      return uploadSessionView(asset, completed);
    } catch (error) {
      clearInterval(heartbeat);
      await heartbeatInFlight.catch(() => undefined);
      if (!renamed) {
        try {
          const file = await stat(partPath);
          if (file.isFile() && BigInt(file.size) > expectedOffset) {
            const handle = await open(partPath, 'r+');
            try { await handle.truncate(Number(expectedOffset)); }
            finally { await handle.close(); }
          }
        } catch { /* no partial file */ }
        await client.asset.updateMany({
          where: { id: asset.id, status: 'uploading', uploadSessionId: sessionId, uploadLeaseId: leaseId },
          data: { uploadLeaseId: null, uploadLeaseExpiresAt: null },
        }).catch(() => undefined);
      }
      if (error instanceof Error && 'code' in error && error.code === 'ENOSPC') {
        throw new ApiError(507, 'STORAGE_FULL', 'Server storage is full');
      }
      throw error;
    }
  }

  return {
    async createAlbum(principal: Principal, input: {
      clientAlbumId: string; sourceVolume: string; sourceRelativePath: string;
      title: string; shared: boolean; backedUp: boolean;
    }) {
      const sourceVolume = normalizeSourceVolume(input.sourceVolume);
      const sourceRelativePath = normalizeSourceRelativePath(input.sourceRelativePath);
      const album = await client.album.upsert({
        where: { ownerId_sourceVolume_sourceRelativePath: {
          ownerId: principal.userId, sourceVolume, sourceRelativePath,
        } },
        update: {
          sourceDeviceId: principal.deviceId,
          clientAlbumId: input.clientAlbumId,
          title: input.title,
          ...(input.backedUp ? { backedUpAt: new Date() } : {}),
        },
        create: {
          ownerId: principal.userId,
          sourceDeviceId: principal.deviceId,
          clientAlbumId: input.clientAlbumId,
          sourceVolume,
          sourceRelativePath,
          title: input.title,
          sharedAt: input.shared ? new Date() : null,
          backedUpAt: input.backedUp ? new Date() : null,
        },
        include: { owner: { select: ownerSelection } },
      });
      return albumView(album, principal);
    },

    async setAlbumSharing(principal: Principal, id: string, shared: boolean) {
      const existing = await client.album.findFirst({ where: { id, ownerId: principal.userId } });
      if (!existing) throw notFound();
      const album = await client.album.update({
        where: { id }, data: { sharedAt: shared ? new Date() : null },
        include: { owner: { select: ownerSelection } },
      });
      return albumView(album, principal);
    },

    async listAlbums(principal: Principal) {
      const albums = await client.album.findMany({
        where: {
          owner: { pairId: principal.pairId },
          OR: [{ ownerId: principal.userId }, { sharedAt: { not: null } }],
        },
        include: { owner: { select: ownerSelection } },
        orderBy: [{ createdAt: 'asc' }, { id: 'asc' }],
      });
      return { albums: albums.map((album) => albumView(album, principal)) };
    },

    async listBackups(principal: Principal) {
      const albums = await client.album.findMany({
        where: { ownerId: principal.userId, backedUpAt: { not: null } },
        include: {
          owner: { select: ownerSelection },
          _count: { select: { assets: { where: { status: 'ready', integrityStatus: 'healthy' } } } },
          assets: {
            where: { status: 'ready', integrityStatus: 'healthy', derivatives: { some: { kind: 'thumbnail', status: 'ready' } } },
            orderBy: [{ createdAt: 'desc' }, { id: 'desc' }], take: 1,
            select: {
              id: true, updatedAt: true,
              derivatives: {
                where: { kind: 'thumbnail', status: 'ready' },
                orderBy: { updatedAt: 'desc' }, take: 1,
                select: { updatedAt: true, sha256: true },
              },
            },
          },
        },
        orderBy: [{ updatedAt: 'desc' }, { id: 'asc' }],
      });
      return { albums: await Promise.all(albums.map(async (album) => {
        const [original, optimized] = await Promise.all([
          client.asset.aggregate({ where: { albumId: album.id, status: 'ready', integrityStatus: 'healthy' }, _sum: { fileSize: true } }),
          client.assetDerivative.aggregate({
            where: { kind: 'optimized', status: 'ready', asset: { albumId: album.id, status: 'ready', integrityStatus: 'healthy' } },
            _sum: { fileSize: true },
          }),
        ]);
        return albumSummaryView(album, principal, {
          original: original._sum.fileSize ?? 0n, optimized: optimized._sum.fileSize ?? 0n,
        });
      })) };
    },

    async listPartnerAlbums(principal: Principal) {
      const albums = await client.album.findMany({
        where: {
          owner: { pairId: principal.pairId },
          ownerId: { not: principal.userId },
          sharedAt: { not: null },
        },
        include: {
          owner: { select: ownerSelection },
          _count: { select: { assets: { where: { status: 'ready', integrityStatus: 'healthy' } } } },
          assets: {
            where: { status: 'ready', integrityStatus: 'healthy', derivatives: { some: { kind: 'thumbnail', status: 'ready' } } },
            orderBy: [{ createdAt: 'desc' }, { id: 'desc' }],
            take: 1,
            select: {
              id: true, updatedAt: true,
              derivatives: {
                where: { kind: 'thumbnail', status: 'ready' },
                orderBy: { updatedAt: 'desc' }, take: 1,
                select: { updatedAt: true, sha256: true },
              },
            },
          },
        },
        orderBy: [{ updatedAt: 'desc' }, { id: 'asc' }],
      });
      return { albums: await Promise.all(albums.map(async (album) => {
        const [original, optimized] = await Promise.all([
          client.asset.aggregate({ where: { albumId: album.id, status: 'ready', integrityStatus: 'healthy' }, _sum: { fileSize: true } }),
          client.assetDerivative.aggregate({
            where: { kind: 'optimized', status: 'ready', asset: { albumId: album.id, status: 'ready', integrityStatus: 'healthy' } },
            _sum: { fileSize: true },
          }),
        ]);
        return albumSummaryView(album, principal, {
          original: original._sum.fileSize ?? 0n, optimized: optimized._sum.fileSize ?? 0n,
        });
      })) };
    },

    async getAlbum(principal: Principal, id: string) {
      return albumView(await accessibleAlbum(principal, id), principal);
    },

    async createAsset(principal: Principal, albumId: string, input: AssetInput) {
      const album = await accessibleAlbum(principal, albumId);
      if (album.ownerId !== principal.userId) throw notFound();
      if (input.fileSize > BigInt(config.maxUploadBytes)) {
        throw new ApiError(413, 'UPLOAD_TOO_LARGE', 'Declared file size exceeds the upload limit');
      }
      const reusable = await reusableAsset(principal, albumId, input);
      if (reusable) return assetView(reusable);

      const id = randomUUID();
      const storagePath = `originals/${principal.userId}/${id}/original`;
      const asset = await client.asset.create({
        data: {
          id,
          ownerId: principal.userId,
          albumId,
          originalFileName: input.originalFileName,
          mimeType: input.mimeType,
          capturedAt: input.capturedAt,
          fileSize: input.fileSize,
          width: input.width,
          height: input.height,
          durationMillis: input.durationMillis,
          sourceDeviceId: input.clientAssetId ? principal.deviceId : null,
          clientAssetId: input.clientAssetId,
          expectedSha256: input.expectedSha256,
          storagePath,
        },
        include: { derivatives },
      });
      return assetView(asset);
    },

    async listAssets(principal: Principal, albumId: string, page: { limit: number; cursor?: { createdAt: Date; id: string } }) {
      const album = await accessibleAlbum(principal, albumId);
      const assets = await client.asset.findMany({
        where: {
          albumId,
          status: 'ready',
          integrityStatus: 'healthy',
          ...(page.cursor ? { OR: [
            { createdAt: { lt: page.cursor.createdAt } },
            { createdAt: page.cursor.createdAt, id: { lt: page.cursor.id } },
          ] } : {}),
        },
        include: { derivatives },
        orderBy: [{ createdAt: 'desc' }, { id: 'desc' }],
        take: page.limit + 1,
      });
      const hasMore = assets.length > page.limit;
      const pageAssets = hasMore ? assets.slice(0, page.limit) : assets;
      const last = pageAssets.at(-1);
      return {
        assets: pageAssets.map(assetView),
        nextCursor: hasMore && last
          ? Buffer.from(JSON.stringify({ createdAt: last.createdAt.toISOString(), id: last.id })).toString('base64url')
          : null,
      };
    },

    async getAsset(principal: Principal, id: string) {
      return assetView(await accessibleAsset(principal, id));
    },

    async cancelUpload(principal: Principal, id: string, now = new Date()) {
      let asset = await client.asset.findFirst({ where: { id, ownerId: principal.userId } });
      if (!asset) throw notFound();
      if (asset.status === 'ready') {
        const deleted = await client.asset.updateMany({
          where: { id, ownerId: principal.userId, status: 'ready' },
          data: {
            status: 'deleted', deletedAt: now,
            purgeAfter: new Date(now.getTime() + trashRetentionMs),
            cleanupRequestedAt: null, cleanupLastError: null,
          },
        });
        if (deleted.count !== 1) throw conflict('Asset state changed during upload cancellation');
        return { id, cancelled: true };
      }
      if (['deleted', 'purging', 'purged'].includes(asset.status)) return { id, cancelled: true };
      if (asset.status === 'pending' || asset.status === 'failed') {
        await unlinkStoragePath(asset.storagePath);
        const removed = await client.asset.deleteMany({
          where: { id, ownerId: principal.userId, status: asset.status },
        });
        if (removed.count !== 1) throw conflict('Asset state changed during upload cancellation');
        await removeEmptyAssetDirectories([asset.storagePath]).catch(() => undefined);
        return { id, cancelled: true };
      }
      if (asset.status !== 'uploading') throw conflict('Asset cannot be cancelled');

      const leaseId = randomUUID();
      const claimed = await client.asset.updateMany({
        where: {
          id, ownerId: principal.userId, status: 'uploading',
          OR: [
            { uploadLeaseId: null },
            { uploadLeaseExpiresAt: null },
            { uploadLeaseExpiresAt: { lte: now } },
          ],
        },
        data: {
          uploadLeaseId: leaseId,
          uploadLeaseExpiresAt: new Date(now.getTime() + config.uploadLeaseMs),
        },
      });
      if (claimed.count !== 1) throw conflict('Upload session is busy');
      asset = await client.asset.findUniqueOrThrow({ where: { id } });
      try {
        await unlinkStoragePath(asset.uploadPartPath);
        await unlinkStoragePath(asset.storagePath);
        const removed = await client.asset.deleteMany({
          where: {
            id, ownerId: principal.userId, status: 'uploading', uploadLeaseId: leaseId,
          },
        });
        if (removed.count !== 1) throw conflict('Upload cancellation lost its lease');
        await removeEmptyAssetDirectories([asset.uploadPartPath, asset.storagePath]).catch(() => undefined);
        return { id, cancelled: true };
      } catch (error) {
        await client.asset.updateMany({
          where: { id, status: 'uploading', uploadLeaseId: leaseId },
          data: { uploadLeaseId: null, uploadLeaseExpiresAt: null },
        }).catch(() => undefined);
        throw error;
      }
    },

    async createUploadSession(principal: Principal, id: string) {
      return beginUploadSession(principal, id);
    },

    async uploadChunk(
      principal: Principal,
      sessionId: string,
      offset: bigint,
      input: Readable,
      contentLength: number,
    ) {
      return appendUploadChunk(principal, sessionId, offset, input, contentLength);
    },

    async uploadOriginal(principal: Principal, id: string, input: Readable, contentLength: number) {
      const existing = await client.asset.findFirst({ where: { id, ownerId: principal.userId } });
      if (!existing) throw notFound();
      if (BigInt(contentLength) !== existing.fileSize) {
        throw new ApiError(422, 'UPLOAD_SIZE_MISMATCH', 'Content-Length does not match declared file size');
      }
      const session = await beginUploadSession(principal, id);
      if (session.offset !== '0') {
        throw new ApiError(409, 'UPLOAD_OFFSET_MISMATCH', 'Legacy full upload cannot replace a resumed session');
      }
      const result = await appendUploadChunk(
        principal, session.id, 0n, input, contentLength, true,
      );
      if (!result.completed || !result.asset) throw new Error('Full upload did not complete');
      return result.asset;
    },

    async mediaFile(principal: Principal, id: string, variant: 'original' | 'thumbnail' | 'optimized') {
      const asset = await accessibleAsset(principal, id, true);
      if (variant === 'original') {
        if (!await verifyOriginal(client, config, asset)) {
          throw new ApiError(503, 'ORIGINAL_UNAVAILABLE', 'Original file failed its integrity check');
        }
        const path = withinMediaRoot(config.mediaRoot, asset.storagePath);
        let file;
        try { file = await stat(path); } catch { throw new ApiError(503, 'ORIGINAL_UNAVAILABLE', 'Original file is unavailable'); }
        if (!file.isFile() || file.size !== Number(asset.fileSize)) {
          throw new ApiError(503, 'ORIGINAL_UNAVAILABLE', 'Original file is unavailable');
        }
        return { path, size: file.size, mimeType: asset.mimeType, sha256: asset.sha256!, fileName: asset.originalFileName };
      }
      const derivative = asset.derivatives.find((item) => item.kind === variant);
      if (!derivative || derivative.status !== 'ready' || !derivative.storagePath || !derivative.fileSize || !derivative.mimeType || !derivative.sha256) {
        throw new ApiError(409, 'DERIVATIVE_NOT_READY', 'Requested derivative is not ready');
      }
      const path = withinMediaRoot(config.mediaRoot, derivative.storagePath);
      let file;
      try { file = await stat(path); } catch { throw new ApiError(503, 'DERIVATIVE_UNAVAILABLE', 'Derivative file is unavailable'); }
      if (!file.isFile() || file.size !== Number(derivative.fileSize)) {
        throw new ApiError(503, 'DERIVATIVE_UNAVAILABLE', 'Derivative file is unavailable');
      }
      return { path, size: file.size, mimeType: derivative.mimeType, sha256: derivative.sha256,
        fileName: variant === 'thumbnail' ? `${id}-thumbnail` : `${id}-optimized` };
    },

    async trashThumbnail(principal: Principal, id: string) {
      const asset = await accessibleAsset(principal, id, false, true);
      if (asset.status !== 'deleted') throw notFound();
      const derivative = asset.derivatives.find((item) => item.kind === 'thumbnail');
      if (!derivative || derivative.status !== 'ready' || !derivative.storagePath ||
          !derivative.fileSize || !derivative.mimeType || !derivative.sha256) {
        throw new ApiError(409, 'DERIVATIVE_NOT_READY', 'Requested derivative is not ready');
      }
      const path = withinMediaRoot(config.mediaRoot, derivative.storagePath);
      let file;
      try { file = await stat(path); }
      catch { throw new ApiError(503, 'DERIVATIVE_UNAVAILABLE', 'Derivative file is unavailable'); }
      if (!file.isFile() || file.size !== Number(derivative.fileSize)) {
        throw new ApiError(503, 'DERIVATIVE_UNAVAILABLE', 'Derivative file is unavailable');
      }
      return { path, size: file.size, mimeType: derivative.mimeType, sha256: derivative.sha256, fileName: `${id}-thumbnail` };
    },

    async recoverStaleUploads(now?: Date) {
      return recoverStaleUploads(now);
    },

    async startUploadRecovery() {
      recoveryStopping = false;
      await recoverStaleUploads().catch(() => 0);
      scheduleRecovery();
    },

    async stopUploadRecovery() {
      recoveryStopping = true;
      if (recoveryTimer) clearTimeout(recoveryTimer);
      await recoveryActive;
    },

    async trashAsset(principal: Principal, id: string, now = new Date()) {
      const existing = await client.asset.findFirst({ where: { id, ownerId: principal.userId }, include: trashInclude });
      if (!existing || existing.status === 'purged') throw notFound();
      if (existing.status === 'deleted') return trashAssetView(existing, now);
      if (existing.status !== 'ready') throw conflict('Asset is not available for deletion');
      const deletedAt = now;
      const changed = await client.asset.updateMany({
        where: { id, ownerId: principal.userId, status: 'ready' },
        data: {
          status: 'deleted', deletedAt, purgeAfter: new Date(now.getTime() + trashRetentionMs),
          cleanupRequestedAt: null, cleanupLastError: null,
        },
      });
      if (changed.count !== 1) throw conflict('Asset state changed during deletion');
      return trashAssetView(await client.asset.findUniqueOrThrow({ where: { id }, include: trashInclude }), now);
    },

    async listTrash(principal: Principal, now = new Date()) {
      const assets = await client.asset.findMany({
        where: { ownerId: principal.userId, status: 'deleted' },
        include: trashInclude,
        orderBy: [{ deletedAt: 'desc' }, { id: 'desc' }],
      });
      return { assets: assets.map((asset) => trashAssetView(asset, now)) };
    },

    async restoreTrashAsset(principal: Principal, id: string, now = new Date()) {
      const existing = await client.asset.findFirst({ where: { id, ownerId: principal.userId }, include: trashInclude });
      if (!existing || existing.status === 'purged') throw notFound();
      if (existing.status === 'ready') return assetView(existing);
      if (existing.status !== 'deleted') throw conflict('Asset cleanup is in progress');
      if (!existing.purgeAfter || existing.purgeAfter <= now) throw conflict('Asset retention period has expired');
      const originalPath = withinMediaRoot(config.mediaRoot, existing.storagePath);
      try {
        const original = await stat(originalPath);
        if (!original.isFile() || BigInt(original.size) !== existing.fileSize ||
          !existing.sha256 || await sha256File(originalPath) !== existing.sha256) {
          throw conflict('Asset original is unavailable or inconsistent');
        }
      } catch (error) {
        if (error instanceof ApiError) throw error;
        throw conflict('Asset original is unavailable or inconsistent');
      }
      const restored = await client.asset.updateMany({
        where: { id, ownerId: principal.userId, status: 'deleted', purgeAfter: { gt: now } },
        data: {
          status: 'ready', deletedAt: null, purgeAfter: null, cleanupRequestedAt: null,
          cleanupLastError: null, cleanupLeaseId: null, cleanupLeaseExpiresAt: null, purgedAt: null,
          integrityStatus: 'healthy', integrityError: null, integrityCheckedAt: now,
        },
      });
      if (restored.count !== 1) throw conflict('Asset cleanup is in progress');
      return assetView(await client.asset.findUniqueOrThrow({ where: { id }, include: { derivatives } }));
    },

    async restoreTrashAssets(principal: Principal, ids: string[]) {
      const restored: unknown[] = [];
      const skipped: string[] = [];
      for (const id of ids) {
        try { restored.push(await this.restoreTrashAsset(principal, id)); }
        catch (error) {
          if (error instanceof ApiError && error.statusCode === 404) skipped.push(id);
          else throw error;
        }
      }
      return { restored, skipped };
    },

    async purgeTrashAsset(principal: Principal, id: string) {
      const existing = await client.asset.findFirst({ where: { id, ownerId: principal.userId }, select: { status: true } });
      if (!existing || existing.status === 'purged') throw notFound();
      if (existing.status !== 'deleted') throw conflict('Asset is not in the trash');
      const requested = await client.asset.updateMany({
        where: { id, ownerId: principal.userId, status: 'deleted' },
        data: { cleanupRequestedAt: new Date(), cleanupLastError: null },
      });
      if (requested.count !== 1) throw conflict('Asset cleanup is already in progress');
      await claimAndPurge(id, principal.userId);
      const current = await client.asset.findUnique({ where: { id }, select: { status: true } });
      if (current?.status !== 'purged') throw new ApiError(503, 'CLEANUP_FAILED', 'Physische Dateilöschung fehlgeschlagen');
      return { id, purged: true };
    },

    async purgeTrashAssets(principal: Principal, ids?: string[]) {
      const targets = ids ?? (await client.asset.findMany({
        where: { ownerId: principal.userId, status: 'deleted' },
        select: { id: true },
      })).map((asset) => asset.id);
      const purged: string[] = [];
      const failed: Array<{ id: string; message: string }> = [];
      for (const id of targets) {
        try { await this.purgeTrashAsset(principal, id); purged.push(id); }
        catch (error) {
          if (error instanceof ApiError && error.statusCode === 404) continue;
          failed.push({ id, message: error instanceof Error ? error.message : 'Cleanup fehlgeschlagen' });
        }
      }
      return { purged, failed };
    },

    async cleanupExpired(now = new Date()) {
      return purgeExpired(now);
    },

    async startTrashCleanup() {
      trashStopping = false;
      await purgeExpired().catch(() => 0);
      scheduleTrashCleanup();
    },

    async stopTrashCleanup() {
      trashStopping = true;
      if (trashTimer) clearTimeout(trashTimer);
      await trashActive;
    },

    async retryDerivatives(principal: Principal, id: string) {
      const asset = await client.asset.findFirst({ where: { id, ownerId: principal.userId, status: 'ready' } });
      if (!asset) throw notFound();
      await client.assetDerivative.updateMany({
        where: { assetId: id, status: 'failed' },
        data: { status: 'pending', attempts: 0, nextAttemptAt: new Date(), lastError: null },
      });
      return assetView(await client.asset.findUniqueOrThrow({ where: { id }, include: { derivatives } }));
    },
  };
}

export type MediaService = ReturnType<typeof createMediaService>;
