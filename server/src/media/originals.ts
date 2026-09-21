import { createHash } from 'node:crypto';
import { createReadStream } from 'node:fs';
import { stat } from 'node:fs/promises';
import { isAbsolute, relative, resolve, sep } from 'node:path';
import type { Logger } from 'pino';
import type { PrismaClient } from '../generated/prisma/client.js';
import type { Config } from '../config.js';

type OriginalAsset = {
  id: string;
  storagePath: string;
  fileSize: bigint;
  sha256: string | null;
  integrityStatus: string;
  integrityError: string | null;
};

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
  for await (const chunk of createReadStream(path)) digest.update(chunk as Buffer);
  return digest.digest('hex');
}

export async function inspectOriginal(config: Config, asset: OriginalAsset): Promise<string | null> {
  if (!asset.sha256) return 'Original has no stored SHA-256';
  const path = withinMediaRoot(config.mediaRoot, asset.storagePath);
  try {
    const file = await stat(path);
    if (!file.isFile()) return 'Original path is not a regular file';
    if (BigInt(file.size) !== asset.fileSize) return 'Original size does not match metadata';
    if (await sha256File(path) !== asset.sha256) return 'Original SHA-256 does not match metadata';
    return null;
  } catch (error) {
    const code = error instanceof Error && 'code' in error ? String(error.code) : '';
    return code === 'ENOENT' ? 'Original file is missing' : 'Original file cannot be verified';
  }
}

export async function verifyOriginal(
  client: PrismaClient,
  config: Config,
  asset: OriginalAsset,
  logger?: Logger,
): Promise<boolean> {
  const error = await inspectOriginal(config, asset);
  const now = new Date();
  if (error === null) {
    if (asset.integrityStatus === 'error') {
      await client.asset.updateMany({
        where: { id: asset.id, status: 'ready', sha256: asset.sha256 },
        data: { integrityStatus: 'healthy', integrityError: null, integrityCheckedAt: now },
      });
      logger?.info({ event: 'original_integrity_recovered', assetId: asset.id }, 'Original integrity recovered');
    }
    return true;
  }
  if (asset.integrityStatus !== 'error' || asset.integrityError !== error) {
    await client.asset.updateMany({
      where: { id: asset.id, status: 'ready', sha256: asset.sha256 },
      data: { integrityStatus: 'error', integrityError: error, integrityCheckedAt: now },
    });
    logger?.error({ event: 'original_integrity_failed', assetId: asset.id, reason: error }, 'Original integrity check failed');
  }
  return false;
}

export function createOriginalIntegrityWorker(client: PrismaClient, config: Config, logger: Logger) {
  let timer: NodeJS.Timeout | undefined;
  let active: Promise<number> | undefined;
  let stopping = false;
  let cursor: string | undefined;
  let lastCycleAt: Date | undefined;
  let checkedInCycle = 0;
  const intervalMs = 60_000;

  async function runOnce(limit = 25): Promise<number> {
    let assets = await client.asset.findMany({
      where: { status: 'ready' },
      orderBy: { id: 'asc' },
      ...(cursor ? { cursor: { id: cursor }, skip: 1 } : {}),
      take: limit,
      select: {
        id: true, storagePath: true, fileSize: true, sha256: true, integrityStatus: true, integrityError: true,
      },
    });
    if (assets.length === 0 && cursor) {
      cursor = undefined;
      checkedInCycle = 0;
      lastCycleAt = new Date();
      assets = await client.asset.findMany({
        where: { status: 'ready' }, orderBy: { id: 'asc' }, take: limit,
        select: { id: true, storagePath: true, fileSize: true, sha256: true, integrityStatus: true, integrityError: true },
      });
    }
    for (const asset of assets) await verifyOriginal(client, config, asset, logger);
    checkedInCycle += assets.length;
    cursor = assets.length === limit ? assets.at(-1)?.id : undefined;
    if (assets.length < limit) {
      lastCycleAt = new Date();
      checkedInCycle = 0;
    }
    return assets.length;
  }

  function schedule(): void {
    if (stopping) return;
    timer = setTimeout(() => {
      active = runOnce()
        .catch((error) => {
          logger.error({ event: 'original_integrity_worker_failed' }, 'Original integrity worker cycle failed');
          throw error;
        })
        .finally(() => { active = undefined; schedule(); });
      active.catch(() => undefined);
    }, intervalMs);
    timer.unref();
  }

  return {
    async start() {
      stopping = false;
      if (active) return;
      active = runOnce()
        .catch(() => {
          logger.error({ event: 'original_integrity_worker_failed' },
            'Original integrity worker startup cycle failed');
          return 0;
        })
        .finally(() => {
          active = undefined;
          schedule();
        });
    },
    runOnce,
    async diagnostics() {
      const errors = await client.asset.count({ where: { status: 'ready', integrityStatus: 'error' } });
      return {
        status: errors > 0 ? 'degraded' : 'ok',
        errors,
        checkedInCycle,
        lastCycleAt: lastCycleAt?.toISOString() ?? null,
      } as const;
    },
    async stop() {
      stopping = true;
      if (timer) clearTimeout(timer);
      await active;
    },
  };
}

export type OriginalIntegrityWorker = ReturnType<typeof createOriginalIntegrityWorker>;
