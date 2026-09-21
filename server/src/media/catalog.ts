import { randomUUID } from 'node:crypto';
import { lstat, mkdir, readFile, readdir, readlink, rename, rm, stat, symlink, writeFile } from 'node:fs/promises';
import { dirname, extname, isAbsolute, join, relative, resolve, sep } from 'node:path';
import type { Logger } from 'pino';
import type { PrismaClient } from '../generated/prisma/client.js';
import type { Config } from '../config.js';

const markerName = '.photosync-catalog';
const markerContents = 'photosync-local-catalog-v1\n';
const stagingPrefix = '.photosync-catalog-staging-';
const previousPrefix = '.photosync-catalog-previous-';

type CatalogueAsset = {
  id: string;
  originalFileName: string;
  storagePath: string;
  fileSize: bigint;
  status: string;
  integrityStatus: string;
  capturedAt: Date | null;
  createdAt: Date;
};
type CatalogueAlbum = {
  id: string;
  title: string;
  backedUpAt: Date | null;
  owner: { id: string; displayName: string };
  assets: CatalogueAsset[];
};

function withinMediaRoot(root: string, storagePath: string): string {
  const absolute = resolve(root, storagePath);
  const path = relative(root, absolute);
  if (isAbsolute(storagePath) || path === '' || path === '..' || path.startsWith(`..${sep}`)) {
    throw new Error('Unsafe storage path');
  }
  return absolute;
}

function safeName(value: string, fallback: string): string {
  const cleaned = value.normalize('NFC')
    .replace(/[\\/\u0000-\u001f\u007f]/g, '_')
    .replace(/^\.+$/, '')
    .trim()
    .slice(0, 160);
  return cleaned || fallback;
}

function uniqueFileName(fileName: string, assetId: string, names: Set<string>): string {
  const safe = safeName(fileName, `asset-${assetId.slice(0, 8)}`);
  if (!names.has(safe)) {
    names.add(safe);
    return safe;
  }
  const extension = extname(safe);
  const base = extension ? safe.slice(0, -extension.length) : safe;
  const candidate = `${base.slice(0, Math.max(1, 145 - extension.length))} [${assetId.slice(0, 8)}]${extension}`;
  names.add(candidate);
  return candidate;
}

async function isKnownCatalogueDirectory(path: string): Promise<boolean> {
  try { return (await readFile(join(path, markerName), 'utf8')) === markerContents; }
  catch { return false; }
}

async function isRegularOriginal(mediaRoot: string, asset: CatalogueAsset): Promise<boolean> {
  try {
    const file = await stat(withinMediaRoot(mediaRoot, asset.storagePath));
    return file.isFile() && BigInt(file.size) === asset.fileSize;
  } catch { return false; }
}

async function ensureMediaAnchor(catalogueRoot: string): Promise<void> {
  const anchor = join(catalogueRoot, '.media');
  try {
    const entry = await lstat(anchor);
    if (!entry.isSymbolicLink() || await readlink(anchor) !== '../media') {
      throw new Error('Catalogue .media anchor is not managed by PhotoSync');
    }
  } catch (error) {
    if (error instanceof Error && 'code' in error && error.code === 'ENOENT') {
      await symlink('../media', anchor);
      return;
    }
    throw error;
  }
}

async function addAssetLink(
  catalogueRoot: string,
  mediaRoot: string,
  directory: string,
  liveDirectory: string,
  asset: CatalogueAsset,
  names: Set<string>,
): Promise<void> {
  if (!await isRegularOriginal(mediaRoot, asset)) return;
  const fileName = uniqueFileName(asset.originalFileName, asset.id, names);
  const linkPath = join(directory, fileName);
  // The host-visible root contains .media -> ../media. Resolving the target via
  // that anchor keeps links valid on the host although Docker mounts media and
  // catalogue at different container paths.
  const target = relative(liveDirectory, join(catalogueRoot, '.media', asset.storagePath));
  await symlink(target, linkPath);
}

async function writeOwnerCatalogue(
  catalogueRoot: string,
  mediaRoot: string,
  stageRoot: string,
  ownerName: string,
  albums: CatalogueAlbum[],
): Promise<void> {
  const ownerRoot = join(stageRoot, ownerName);
  const liveOwnerRoot = join(catalogueRoot, ownerName);
  const albumsRoot = join(ownerRoot, 'Alben');
  const liveAlbumsRoot = join(liveOwnerRoot, 'Alben');
  const backupsRoot = join(ownerRoot, 'Auto-Backup');
  const liveBackupsRoot = join(liveOwnerRoot, 'Auto-Backup');
  const trashRoot = join(ownerRoot, 'Papierkorb');
  const liveTrashRoot = join(liveOwnerRoot, 'Papierkorb');
  await mkdir(albumsRoot, { recursive: true, mode: 0o755 });
  await mkdir(backupsRoot, { recursive: true, mode: 0o755 });
  await mkdir(trashRoot, { recursive: true, mode: 0o755 });
  await writeFile(join(ownerRoot, markerName), markerContents, { mode: 0o644 });

  const albumNames = new Set<string>();
  const backupNames = new Map<string, Set<string>>();
  const trashNames = new Map<string, Set<string>>();
  for (const album of albums.sort((left, right) => left.title.localeCompare(right.title) || left.id.localeCompare(right.id))) {
    let albumName = safeName(album.title, `Album-${album.id.slice(0, 8)}`);
    if (albumNames.has(albumName)) albumName = `${albumName.slice(0, 145)} [${album.id.slice(0, 8)}]`;
    albumNames.add(albumName);
    const albumDirectory = join(albumsRoot, albumName);
    const liveAlbumDirectory = join(liveAlbumsRoot, albumName);
    await mkdir(albumDirectory, { recursive: true, mode: 0o755 });
    const albumFiles = new Set<string>();
    const trashDirectory = join(trashRoot, albumName);
    const liveTrashDirectory = join(liveTrashRoot, albumName);
    const trashFiles = new Set<string>();

    for (const asset of album.assets) {
      if (asset.status === 'ready' && asset.integrityStatus === 'healthy') {
        await addAssetLink(catalogueRoot, mediaRoot, albumDirectory, liveAlbumDirectory, asset, albumFiles);
        if (album.backedUpAt) {
          const timestamp = asset.capturedAt ?? asset.createdAt;
          const year = String(timestamp.getUTCFullYear());
          const month = String(timestamp.getUTCMonth() + 1).padStart(2, '0');
          const backupDirectory = join(backupsRoot, year, month);
          const liveBackupDirectory = join(liveBackupsRoot, year, month);
          await mkdir(backupDirectory, { recursive: true, mode: 0o755 });
          const key = `${year}/${month}`;
          const names = backupNames.get(key) ?? new Set<string>();
          backupNames.set(key, names);
          await addAssetLink(catalogueRoot, mediaRoot, backupDirectory, liveBackupDirectory, asset, names);
        }
      } else if (asset.status === 'deleted') {
        await mkdir(trashDirectory, { recursive: true, mode: 0o755 });
        const names = trashNames.get(albumName) ?? trashFiles;
        trashNames.set(albumName, names);
        await addAssetLink(catalogueRoot, mediaRoot, trashDirectory, liveTrashDirectory, asset, names);
      }
    }
  }
}

async function replaceOwnerDirectory(catalogueRoot: string, ownerName: string, stagedOwner: string): Promise<void> {
  const current = join(catalogueRoot, ownerName);
  const previous = join(catalogueRoot, `${previousPrefix}${randomUUID()}`);
  let movedCurrent = false;
  try {
    try {
      await lstat(current);
      if (!await isKnownCatalogueDirectory(current)) {
        throw new Error(`Refusing to replace unmanaged catalogue directory: ${ownerName}`);
      }
      await rename(current, previous);
      movedCurrent = true;
    } catch (error) {
      if (!(error instanceof Error && 'code' in error && error.code === 'ENOENT')) throw error;
    }
    await rename(stagedOwner, current);
    if (movedCurrent) await rm(previous, { recursive: true, force: true });
  } catch (error) {
    if (movedCurrent) {
      try { await rename(previous, current); } catch { /* Next cycle can safely retry. */ }
    }
    throw error;
  }
}

async function removeStaleGeneratedDirectories(catalogueRoot: string, activeOwners: Set<string>): Promise<void> {
  const entries = await readdir(catalogueRoot, { withFileTypes: true });
  for (const entry of entries) {
    if (!entry.isDirectory() || entry.name.startsWith('.')) continue;
    if (activeOwners.has(entry.name)) continue;
    const path = join(catalogueRoot, entry.name);
    if (await isKnownCatalogueDirectory(path)) await rm(path, { recursive: true, force: true });
  }
}

export function createMediaCatalogue(client: PrismaClient, config: Config, logger: Logger) {
  if (!config.catalogRoot) throw new Error('Catalogue root is not configured');
  const catalogueRoot = config.catalogRoot;
  const intervalMs = 60_000;
  let timer: NodeJS.Timeout | undefined;
  let active: Promise<void> | undefined;
  let stopping = false;
  let lastSuccessfulAt: Date | undefined;

  async function rebuild(): Promise<void> {
    await mkdir(catalogueRoot, { recursive: true, mode: 0o755 });
    await ensureMediaAnchor(catalogueRoot);
    const entries = await readdir(catalogueRoot, { withFileTypes: true });
    for (const entry of entries) {
      if (entry.isDirectory() && (entry.name.startsWith(stagingPrefix) || entry.name.startsWith(previousPrefix))) {
        await rm(join(catalogueRoot, entry.name), { recursive: true, force: true });
      }
    }

    const albums = await client.album.findMany({
      include: {
        owner: { select: { id: true, displayName: true } },
        assets: {
          where: { status: { in: ['ready', 'deleted'] } },
          select: {
            id: true, originalFileName: true, storagePath: true, fileSize: true, status: true,
            integrityStatus: true, capturedAt: true, createdAt: true,
          },
        },
      },
      orderBy: { id: 'asc' },
    }) as CatalogueAlbum[];
    const byOwner = new Map<string, CatalogueAlbum[]>();
    for (const album of albums) byOwner.set(album.owner.id, [...(byOwner.get(album.owner.id) ?? []), album]);
    const displayCounts = new Map<string, number>();
    for (const ownerAlbums of byOwner.values()) {
      const display = safeName(ownerAlbums[0]!.owner.displayName, 'Eigentümer');
      displayCounts.set(display, (displayCounts.get(display) ?? 0) + 1);
    }

    const stageRoot = join(catalogueRoot, `${stagingPrefix}${randomUUID()}`);
    await mkdir(stageRoot, { mode: 0o755 });
    const ownerNames = new Set<string>();
    try {
      for (const [ownerId, ownerAlbums] of byOwner) {
        const display = safeName(ownerAlbums[0]!.owner.displayName, 'Eigentümer');
        const ownerName = (displayCounts.get(display) ?? 0) > 1 ? `${display} [${ownerId.slice(0, 8)}]` : display;
        ownerNames.add(ownerName);
        await writeOwnerCatalogue(catalogueRoot, config.mediaRoot, stageRoot, ownerName, ownerAlbums);
        await replaceOwnerDirectory(catalogueRoot, ownerName, join(stageRoot, ownerName));
      }
      await removeStaleGeneratedDirectories(catalogueRoot, ownerNames);
      lastSuccessfulAt = new Date();
    } finally {
      await rm(stageRoot, { recursive: true, force: true });
    }
  }

  async function runOnce(): Promise<void> {
    if (active) return active;
    active = rebuild().finally(() => { active = undefined; });
    return active;
  }

  function schedule(): void {
    if (stopping) return;
    timer = setTimeout(() => {
      void runOnce().catch(() => {
        logger.error({ event: 'local_catalogue_failed' }, 'Local media catalogue update failed');
      }).finally(schedule);
    }, intervalMs);
    timer.unref();
  }

  return {
    async start() {
      stopping = false;
      await runOnce().catch(() => {
        logger.error({ event: 'local_catalogue_failed' }, 'Local media catalogue startup update failed');
      });
      schedule();
    },
    runOnce,
    diagnostics() {
      return { lastSuccessfulAt: lastSuccessfulAt?.toISOString() ?? null };
    },
    async stop() {
      stopping = true;
      if (timer) clearTimeout(timer);
      await active;
    },
  };
}

export type MediaCatalogue = ReturnType<typeof createMediaCatalogue>;
