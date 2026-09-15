import { createHash, randomUUID } from 'node:crypto';
import { createWriteStream } from 'node:fs';
import { mkdir, open, rename, stat, unlink } from 'node:fs/promises';
import { dirname, isAbsolute, relative, resolve, sep } from 'node:path';
import { Transform, type Readable } from 'node:stream';
import { pipeline } from 'node:stream/promises';
import type { PrismaClient } from '../generated/prisma/client.js';
import type { Config } from '../config.js';
import { ApiError } from '../errors.js';
import type { Principal } from '../auth/service.js';

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
  attempts: number; nextAttemptAt: Date; updatedAt: Date;
};
type AssetViewInput = {
  id: string; ownerId: string; albumId: string; originalFileName: string; mimeType: string;
  capturedAt: Date | null; fileSize: bigint; width: number; height: number;
  durationMillis: bigint | null; sha256: string | null; status: string;
  createdAt: Date; updatedAt: Date; derivatives?: DerivativeViewInput[];
};

const notFound = () => new ApiError(404, 'NOT_FOUND', 'Resource not found');
const conflict = (message: string) => new ApiError(409, 'CONFLICT', message);

function albumView(album: {
  id: string; ownerId: string; sourceDeviceId: string; clientAlbumId: string; title: string;
  sharedAt: Date | null; createdAt: Date; updatedAt: Date;
  owner: { id: string; displayName: string };
}, principal: Principal) {
  return {
    id: album.id,
    owner: album.owner,
    title: album.title,
    ownedByMe: album.ownerId === principal.userId,
    shared: album.sharedAt !== null,
    ...(album.ownerId === principal.userId
      ? { sourceDeviceId: album.sourceDeviceId, clientAlbumId: album.clientAlbumId }
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
        attempts: derivative.attempts,
        nextAttemptAt: derivative.nextAttemptAt,
        updatedAt: derivative.updatedAt,
      })),
    createdAt: asset.createdAt,
    updatedAt: asset.updatedAt,
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

export function createMediaService(client: PrismaClient, config: Config) {
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

  async function accessibleAsset(principal: Principal, assetId: string, readyOnly = false) {
    const asset = await client.asset.findFirst({
      where: { id: assetId },
      include: { derivatives, album: { include: { owner: { select: { pairId: true } } } } },
    });
    const own = asset?.ownerId === principal.userId;
    if (!asset || asset.album.owner.pairId !== principal.pairId ||
        (!own && (asset.album.sharedAt === null || asset.status !== 'ready')) ||
        (readyOnly && asset.status !== 'ready')) throw notFound();
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
    if (duplicate.status === 'failed') {
      return client.asset.update({ where: { id: duplicate.id }, data: { status: 'pending' }, include: { derivatives } });
    }
    return duplicate;
  }

  return {
    async createAlbum(principal: Principal, input: { clientAlbumId: string; title: string }) {
      const duplicate = await client.album.findUnique({
        where: { sourceDeviceId_clientAlbumId: {
          sourceDeviceId: principal.deviceId, clientAlbumId: input.clientAlbumId,
        } },
      });
      const album = duplicate
        ? await client.album.update({
          where: { id: duplicate.id },
          data: { title: input.title, sharedAt: new Date() },
          include: { owner: { select: ownerSelection } },
        })
        : await client.album.create({
          data: {
            ownerId: principal.userId,
            sourceDeviceId: principal.deviceId,
            clientAlbumId: input.clientAlbumId,
            title: input.title,
            sharedAt: new Date(),
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

    async listAssets(principal: Principal, albumId: string) {
      const album = await accessibleAlbum(principal, albumId);
      const assets = await client.asset.findMany({
        where: {
          albumId,
          ...(album.ownerId === principal.userId ? {} : { status: 'ready' }),
        },
        include: { derivatives },
        orderBy: [{ createdAt: 'asc' }, { id: 'asc' }],
      });
      return { assets: assets.map(assetView) };
    },

    async getAsset(principal: Principal, id: string) {
      return assetView(await accessibleAsset(principal, id));
    },

    async uploadOriginal(principal: Principal, id: string, input: Readable, contentLength: number) {
      const existing = await client.asset.findFirst({ where: { id, ownerId: principal.userId } });
      if (!existing) throw notFound();
      if (existing.status !== 'pending') throw conflict('Asset is not awaiting an upload');
      if (BigInt(contentLength) !== existing.fileSize) {
        throw new ApiError(422, 'UPLOAD_SIZE_MISMATCH', 'Content-Length does not match declared file size');
      }

      const claimed = await client.asset.updateMany({
        where: { id, ownerId: principal.userId, status: 'pending' },
        data: { status: 'uploading' },
      });
      if (claimed.count !== 1) throw conflict('Asset is not awaiting an upload');

      const uploadDirectory = resolve(config.mediaRoot, 'uploads');
      const finalPath = withinMediaRoot(config.mediaRoot, existing.storagePath);
      const temporaryPath = resolve(uploadDirectory, `${id}-${randomUUID()}.part`);
      let renamed = false;
      try {
        await mkdir(uploadDirectory, { recursive: true, mode: 0o700 });
        await mkdir(dirname(finalPath), { recursive: true, mode: 0o700 });
        const digest = createHash('sha256');
        let bytes = 0;
        const verifier = new Transform({
          transform(chunk: Buffer, _encoding, callback) {
            bytes += chunk.length;
            if (bytes > contentLength || bytes > config.maxUploadBytes) {
              callback(new ApiError(413, 'UPLOAD_TOO_LARGE', 'Upload exceeds declared size or configured limit'));
              return;
            }
            digest.update(chunk);
            callback(null, chunk);
          },
        });
        await pipeline(input, verifier, createWriteStream(temporaryPath, { flags: 'wx', mode: 0o600 }));
        if (bytes !== contentLength) {
          throw new ApiError(422, 'UPLOAD_SIZE_MISMATCH', 'Uploaded bytes do not match declared file size');
        }
        const sha256 = digest.digest('hex');
        if (existing.expectedSha256 && sha256 !== existing.expectedSha256) {
          throw new ApiError(422, 'UPLOAD_HASH_MISMATCH', 'Uploaded bytes do not match declared SHA-256');
        }
        const handle = await open(temporaryPath, 'r');
        try { await handle.sync(); } finally { await handle.close(); }
        await rename(temporaryPath, finalPath);
        renamed = true;
        await client.$transaction(async (transaction) => {
          const completed = await transaction.asset.updateMany({
            where: { id, ownerId: principal.userId, status: 'uploading' },
            data: { status: 'ready', sha256 },
          });
          if (completed.count !== 1) throw new Error('Upload state changed before completion');
          await transaction.assetDerivative.createMany({
            data: ['thumbnail', 'optimized'].map((kind) => ({ id: randomUUID(), assetId: id, kind })),
            skipDuplicates: true,
          });
        });
        return assetView(await client.asset.findUniqueOrThrow({ where: { id }, include: { derivatives } }));
      } catch (error) {
        await unlink(renamed ? finalPath : temporaryPath).catch(() => undefined);
        await client.asset.updateMany({
          where: { id, ownerId: principal.userId, status: 'uploading' },
          data: { status: 'failed' },
        }).catch(() => undefined);
        throw error;
      }
    },

    async mediaFile(principal: Principal, id: string, variant: 'original' | 'thumbnail' | 'optimized') {
      const asset = await accessibleAsset(principal, id, true);
      if (variant === 'original') {
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
