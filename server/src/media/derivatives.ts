import { createHash, randomUUID } from 'node:crypto';
import { createReadStream } from 'node:fs';
import { mkdir, open, readdir, rename, rmdir, stat, unlink } from 'node:fs/promises';
import { basename, dirname, isAbsolute, relative, resolve, sep } from 'node:path';
import { spawn } from 'node:child_process';
import sharp from 'sharp';
import type { Logger } from 'pino';
import type { PrismaClient } from '../generated/prisma/client.js';
import type { Config } from '../config.js';

export const DERIVATIVE_POLICY = Object.freeze({
  thumbnail: Object.freeze({ maxPixels: 512, imageMimeType: 'image/webp', imageQuality: 76 }),
  optimizedImage: Object.freeze({ maxPixels: 2560, mimeType: 'image/webp', quality: 82 }),
  optimizedVideo: Object.freeze({ maxLongEdge: 1920, mimeType: 'video/mp4', crf: 23, audioKbps: 128 }),
});

const kinds = ['thumbnail', 'optimized'] as const;
type DerivativeKind = typeof kinds[number];
type SourceAsset = {
  id: string;
  ownerId: string;
  mimeType: string;
  storagePath: string;
  durationMillis: bigint | null;
};
type OutputInfo = {
  mimeType: string;
  storagePath: string;
  fileSize: bigint;
  width: number;
  height: number;
  durationMillis: bigint | null;
  sha256: string;
};


function withinMediaRoot(root: string, storagePath: string): string {
  const absolute = resolve(root, storagePath);
  const path = relative(root, absolute);
  if (isAbsolute(storagePath) || path === '' || path === '..' || path.startsWith(`..${sep}`)) {
    throw new Error('Unsafe storage path');
  }
  return absolute;
}

function safeMessage(error: unknown): string {
  const message = error instanceof Error ? error.message : 'Derivative generation failed';
  return message.replace(/[\r\n\t]+/g, ' ').slice(0, 500);
}

async function sha256File(path: string): Promise<string> {
  const digest = createHash('sha256');
  for await (const chunk of createReadStream(path)) digest.update(chunk as Buffer);
  return digest.digest('hex');
}

async function fsyncDirectory(path: string): Promise<void> {
  const handle = await open(path, 'r');
  try { await handle.sync(); } finally { await handle.close(); }
}

async function durableRename(temporaryPath: string, finalPath: string): Promise<void> {
  const handle = await open(temporaryPath, 'r');
  try { await handle.sync(); } finally { await handle.close(); }
  await rename(temporaryPath, finalPath);
  await fsyncDirectory(dirname(finalPath));
}

async function discardFinalOutput(config: Config, storagePath: string): Promise<void> {
  const path = withinMediaRoot(config.mediaRoot, storagePath);
  await unlink(path).catch(() => undefined);
  await rmdir(dirname(path)).catch(() => undefined);
}

function runTool(command: string, args: string[], timeoutMs: number): Promise<string> {
  return new Promise((resolvePromise, reject) => {
    const child = spawn(command, args, { stdio: ['ignore', 'ignore', 'pipe'] });
    let stderr = '';
    child.stderr.setEncoding('utf8');
    child.stderr.on('data', (chunk: string) => { stderr = (stderr + chunk).slice(-4000); });
    const timeout = setTimeout(() => {
      child.kill('SIGKILL');
      reject(new Error(`${command} timed out`));
    }, timeoutMs);
    timeout.unref();
    child.once('error', (error) => { clearTimeout(timeout); reject(error); });
    child.once('close', (code, signal) => {
      clearTimeout(timeout);
      if (code === 0) resolvePromise(stderr);
      else reject(new Error(`${command} failed (${signal ?? code}): ${stderr}`));
    });
  });
}

async function probeVideo(path: string, timeoutMs: number) {
  const args = ['-v', 'error', '-select_streams', 'v:0', '-show_entries',
    'stream=width,height:format=duration', '-of', 'json', path];
  return new Promise<{ width: number; height: number; durationMillis: bigint }>((resolvePromise, reject) => {
    const child = spawn('ffprobe', args, { stdio: ['ignore', 'pipe', 'pipe'] });
    let stdout = '';
    let stderr = '';
    child.stdout.setEncoding('utf8');
    child.stderr.setEncoding('utf8');
    child.stdout.on('data', (chunk: string) => { stdout = (stdout + chunk).slice(-100_000); });
    child.stderr.on('data', (chunk: string) => { stderr = (stderr + chunk).slice(-4000); });
    const timeout = setTimeout(() => { child.kill('SIGKILL'); reject(new Error('ffprobe timed out')); }, timeoutMs);
    timeout.unref();
    child.once('error', (error) => { clearTimeout(timeout); reject(error); });
    child.once('close', (code) => {
      clearTimeout(timeout);
      if (code !== 0) { reject(new Error(`ffprobe failed (${code}): ${stderr}`)); return; }
      try {
        const parsed = JSON.parse(stdout) as { streams?: Array<{ width?: number; height?: number }>; format?: { duration?: string } };
        const stream = parsed.streams?.[0];
        const duration = Number(parsed.format?.duration);
        if (!stream?.width || !stream.height || !Number.isFinite(duration) || duration < 0) throw new Error('Incomplete video metadata');
        resolvePromise({ width: stream.width, height: stream.height, durationMillis: BigInt(Math.round(duration * 1000)) });
      } catch (error) { reject(error); }
    });
  });
}

async function createImageDerivative(sourcePath: string, temporaryPath: string, kind: DerivativeKind, applyOrientation = true): Promise<Omit<OutputInfo, 'storagePath' | 'sha256' | 'fileSize'>> {
  const thumbnail = kind === 'thumbnail';
  const limit = thumbnail ? DERIVATIVE_POLICY.thumbnail.maxPixels : DERIVATIVE_POLICY.optimizedImage.maxPixels;
  const quality = thumbnail ? DERIVATIVE_POLICY.thumbnail.imageQuality : DERIVATIVE_POLICY.optimizedImage.quality;
  const pipeline = sharp(sourcePath, { failOn: 'error', limitInputPixels: 200_000_000 });
  if (applyOrientation) pipeline.autoOrient();
  const info = await pipeline
    .resize({ width: limit, height: limit, fit: 'inside', withoutEnlargement: true })
    .webp({ quality, effort: thumbnail ? 4 : 5, preset: 'photo', smartSubsample: true })
    .toFile(temporaryPath);
  return { mimeType: 'image/webp', width: info.width, height: info.height, durationMillis: null };
}

/**
 * Sharp's bundled libheif lacks the HEVC decoder needed by iPhone HEIC files.
 * Debian's heif-convert carries libheif with libde265, then Sharp performs the
 * same orientation, resize and WebP encoding as for every other image.
 */
async function createHeifDerivative(sourcePath: string, temporaryPath: string, kind: DerivativeKind, timeoutMs: number): Promise<Omit<OutputInfo, 'storagePath' | 'sha256' | 'fileSize'>> {
  const decodedPath = `${temporaryPath}.${randomUUID()}.jpg`;
  try {
    await runTool('heif-convert', [sourcePath, decodedPath], timeoutMs);
    // heif-convert has already applied the source EXIF orientation to pixels;
    // do not apply the retained Orientation tag a second time in Sharp.
    return await createImageDerivative(decodedPath, temporaryPath, kind, false);
  } finally {
    await unlink(decodedPath).catch(() => undefined);
  }
}

async function createVideoDerivative(sourcePath: string, temporaryPath: string, kind: DerivativeKind, asset: SourceAsset, timeoutMs: number): Promise<Omit<OutputInfo, 'storagePath' | 'sha256' | 'fileSize'>> {
  if (kind === 'thumbnail') {
    const seek = asset.durationMillis && asset.durationMillis > 2000n ? '1' : '0';
    await runTool('ffmpeg', ['-nostdin', '-hide_banner', '-loglevel', 'error', '-ss', seek, '-i', sourcePath,
      '-map', '0:v:0', '-frames:v', '1', '-vf',
      "scale=w='min(512,iw)':h='min(512,ih)':force_original_aspect_ratio=decrease:force_divisible_by=2",
      '-q:v', '3', '-f', 'image2', temporaryPath], timeoutMs);
    const metadata = await sharp(temporaryPath).metadata();
    if (!metadata.width || !metadata.height) throw new Error('Incomplete video thumbnail metadata');
    return { mimeType: 'image/jpeg', width: metadata.width, height: metadata.height, durationMillis: null };
  }
  await runTool('ffmpeg', ['-nostdin', '-hide_banner', '-loglevel', 'error', '-i', sourcePath,
    '-map', '0:v:0', '-map', '0:a?', '-map_metadata', '-1', '-map_chapters', '-1',
    '-vf', "scale=w='if(gte(iw,ih),min(1920,iw),-2)':h='if(gte(iw,ih),-2,min(1920,ih))'",
    '-c:v', 'libx264', '-preset', 'medium', '-crf', '23', '-profile:v', 'high', '-level:v', '4.1', '-pix_fmt', 'yuv420p',
    '-fpsmax', '30', '-c:a', 'aac', '-b:a', '128k', '-ac', '2', '-movflags', '+faststart', '-f', 'mp4', temporaryPath], timeoutMs);
  const metadata = await probeVideo(temporaryPath, timeoutMs);
  return { mimeType: 'video/mp4', ...metadata };
}

export async function generateDerivative(config: Config, asset: SourceAsset, kind: DerivativeKind, attemptId = randomUUID()): Promise<OutputInfo> {
  const sourcePath = withinMediaRoot(config.mediaRoot, asset.storagePath);
  const extension = asset.mimeType.startsWith('video/')
    ? kind === 'thumbnail' ? 'jpg' : 'mp4'
    : 'webp';
  // A final path belongs to exactly one claim. A worker which loses its lease may
  // delete this path, but can never delete a newer worker's committed output.
  const storagePath = `derivatives/${asset.ownerId}/${asset.id}/${kind}-${attemptId}.${extension}`;
  const finalPath = withinMediaRoot(config.mediaRoot, storagePath);
  const temporaryPath = `${finalPath}.part`;
  await mkdir(dirname(finalPath), { recursive: true, mode: 0o700 });
  // Pre-lease builds used a different .part naming scheme. Those names cannot
  // belong to a live claim in this build and are safe to reap immediately.
  for (const name of await readdir(dirname(finalPath))) {
    if (name.endsWith('.part') &&
        !/^(?:thumbnail|optimized)-[0-9a-f-]{36}\.(?:webp|jpg|mp4)\.part$/.test(name)) {
      await unlink(resolve(dirname(finalPath), name)).catch(() => undefined);
    }
  }
  try {
    const metadata = asset.mimeType === 'image/heic' || asset.mimeType === 'image/heif'
      ? await createHeifDerivative(sourcePath, temporaryPath, kind, config.derivativeToolTimeoutMs)
      : asset.mimeType.startsWith('image/')
        ? await createImageDerivative(sourcePath, temporaryPath, kind)
      : await createVideoDerivative(sourcePath, temporaryPath, kind, asset, config.derivativeToolTimeoutMs);
    const file = await stat(temporaryPath);
    if (!file.isFile() || file.size <= 0) throw new Error('Derivative output is empty');
    const sha256 = await sha256File(temporaryPath);
    await durableRename(temporaryPath, finalPath);
    return { ...metadata, storagePath, fileSize: BigInt(file.size), sha256 };
  } catch (error) {
    await unlink(temporaryPath).catch(() => undefined);
    throw error;
  }
}

export async function enqueueDerivatives(client: PrismaClient, assetId: string): Promise<void> {
  await client.assetDerivative.createMany({
    data: kinds.map((kind) => ({ id: randomUUID(), assetId, kind })),
    skipDuplicates: true,
  });
}

type DerivativeWorkerHooks = {
  beforeGenerate?: (assetId: string, kind: DerivativeKind, leaseId: string) => Promise<void>;
  afterFileFinalized?: (assetId: string, storagePath: string) => Promise<void>;
  processingLeaseMs?: number;
};

function transientDerivativeError(error: unknown): boolean {
  if (!(error instanceof Error)) return false;
  const code = 'code' in error && typeof error.code === 'string' ? error.code : '';
  if (['ENOSPC', 'EDQUOT', 'EMFILE', 'ENFILE', 'EAGAIN', 'EBUSY', 'EIO', 'ECONNRESET', 'ETIMEDOUT'].includes(code)) return true;
  return /no space left|disk quota|timed out|temporar|database|connection|lease renewal/i.test(error.message);
}

export function createDerivativeWorker(
  client: PrismaClient,
  config: Config,
  logger: Logger,
  hooks: DerivativeWorkerHooks = {},
) {
  let timer: NodeJS.Timeout | undefined;
  let active: Promise<void> | undefined;
  let stopping = false;
  let started = false;
  let reconciliationCursor: string | undefined;
  let nextReconciliationAt = 0;
  const processingLeaseMs = hooks.processingLeaseMs ??
    (config.derivativeToolTimeoutMs + Math.max(30_000, config.derivativePollIntervalMs * 2));
  const heartbeatIntervalMs = Math.max(25, Math.floor(processingLeaseMs / 3));

  async function cleanupOldClaimOutputs(kind: string, committedStoragePath: string): Promise<number> {
    const committedPath = withinMediaRoot(config.mediaRoot, committedStoragePath);
    const directory = dirname(committedPath);
    const keep = basename(committedPath);
    let names: string[];
    try { names = await readdir(directory); } catch { return 0; }
    const cutoff = Date.now() - processingLeaseMs * 2;
    let removed = 0;
    for (const name of names) {
      if (name === keep || !name.startsWith(kind + '-')) continue;
      const path = resolve(directory, name);
      try {
        const file = await stat(path);
        if (file.isFile() && file.mtimeMs <= cutoff) {
          await unlink(path);
          removed += 1;
        }
      } catch { /* A concurrent cleanup or purge already won. */ }
    }
    return removed;
  }

  async function recoverStaleJobs(now = new Date()): Promise<number> {
    const recovered = await client.assetDerivative.updateMany({
      where: {
        status: 'processing',
        OR: [
          { processingLeaseExpiresAt: null },
          { processingLeaseExpiresAt: { lte: now } },
        ],
      },
      data: {
        status: 'pending',
        processingLeaseId: null,
        processingLeaseExpiresAt: null,
        nextAttemptAt: now,
      },
    });
    return recovered.count;
  }

  async function ensureJobs(): Promise<void> {
    for (const kind of kinds) {
      const assets = await client.asset.findMany({
        where: { status: 'ready', derivatives: { none: { kind } } },
        select: { id: true }, take: 100,
      });
      if (assets.length) await client.assetDerivative.createMany({
        data: assets.map((asset) => ({ id: randomUUID(), assetId: asset.id, kind })), skipDuplicates: true,
      });
    }
  }

  async function reconcileReadyDerivatives(limit = 100): Promise<number> {
    let candidates = await client.assetDerivative.findMany({
      where: { status: 'ready', asset: { status: 'ready' } },
      orderBy: { id: 'asc' },
      ...(reconciliationCursor ? { cursor: { id: reconciliationCursor }, skip: 1 } : {}),
      take: limit,
    });
    if (candidates.length === 0 && reconciliationCursor) {
      reconciliationCursor = undefined;
      candidates = await client.assetDerivative.findMany({
        where: { status: 'ready', asset: { status: 'ready' } },
        orderBy: { id: 'asc' },
        take: limit,
      });
    }
    reconciliationCursor = candidates.length === limit ? candidates.at(-1)?.id : undefined;
    let repaired = 0;
    for (const candidate of candidates) {
      if (!candidate.storagePath || candidate.fileSize === null || !candidate.sha256) continue;
      const path = withinMediaRoot(config.mediaRoot, candidate.storagePath);
      let valid = false;
      try {
        const file = await stat(path);
        valid = file.isFile() && BigInt(file.size) === candidate.fileSize &&
          await sha256File(path) === candidate.sha256;
      } catch { valid = false; }
      if (valid) {
        await cleanupOldClaimOutputs(candidate.kind, candidate.storagePath);
        continue;
      }
      const reset = await client.assetDerivative.updateMany({
        where: { id: candidate.id, status: 'ready', sha256: candidate.sha256, asset: { status: 'ready' } },
        data: {
          status: 'pending', mimeType: null, storagePath: null, fileSize: null,
          width: null, height: null, durationMillis: null, sha256: null,
          attempts: 0, lastError: 'Derivative storage reconciliation', nextAttemptAt: new Date(),
          processingLeaseId: null, processingLeaseExpiresAt: null,
        },
      });
      if (reset.count === 1) {
        await unlink(path).catch((error) => {
          if (!(error instanceof Error && 'code' in error && error.code === 'ENOENT')) {
            logger.warn({ event: 'derivative_reconcile_unlink_failed', derivativeId: candidate.id },
              'Invalid derivative will be replaced by the retry');
          }
        });
      }
      repaired += reset.count;
    }
    return repaired;
  }

  async function processOne(): Promise<boolean> {
    const now = new Date();
    const candidate = await client.assetDerivative.findFirst({
      where: {
        OR: [
          { attempts: { lt: config.derivativeMaxAttempts } },
          { lastError: { startsWith: '[transient] ' } },
        ],
        nextAttemptAt: { lte: now },
        status: { in: ['pending', 'failed'] },
        asset: { status: 'ready' },
      },
      include: { asset: true },
      orderBy: [{ nextAttemptAt: 'asc' }, { createdAt: 'asc' }],
    });
    if (!candidate) return false;
    const leaseId = randomUUID();
    const claimed = await client.assetDerivative.updateMany({
      where: { id: candidate.id, status: candidate.status, attempts: candidate.attempts },
      data: {
        status: 'processing',
        processingLeaseId: leaseId,
        processingLeaseExpiresAt: new Date(now.getTime() + processingLeaseMs),
      },
    });
    if (claimed.count !== 1) return true;
    let output: OutputInfo | undefined;
    let committed = false;
    let heartbeatError: unknown;
    let heartbeatInFlight = Promise.resolve();
    const heartbeat = setInterval(() => {
      heartbeatInFlight = heartbeatInFlight.then(async () => {
        const renewed = await client.assetDerivative.updateMany({
          where: { id: candidate.id, status: 'processing', processingLeaseId: leaseId },
          data: { processingLeaseExpiresAt: new Date(Date.now() + processingLeaseMs) },
        });
        if (renewed.count !== 1) throw new Error('Derivative lease was lost');
      }).catch((error) => { heartbeatError = error; });
    }, heartbeatIntervalMs);
    heartbeat.unref();
    try {
      await hooks.beforeGenerate?.(candidate.assetId, candidate.kind as DerivativeKind, leaseId);
      output = await generateDerivative(config, candidate.asset, candidate.kind as DerivativeKind, leaseId);
      await hooks.afterFileFinalized?.(candidate.assetId, output.storagePath);
      clearInterval(heartbeat);
      await heartbeatInFlight;
      if (heartbeatError) throw heartbeatError;
      const updated = await client.assetDerivative.updateMany({ where: {
        id: candidate.id, status: 'processing', processingLeaseId: leaseId,
        asset: { status: 'ready' },
      }, data: {
        status: 'ready', mimeType: output.mimeType, storagePath: output.storagePath,
        fileSize: output.fileSize, width: output.width, height: output.height,
        durationMillis: output.durationMillis, sha256: output.sha256, lastError: null,
        processingLeaseId: null, processingLeaseExpiresAt: null,
      } });
      if (updated.count !== 1) {
        await discardFinalOutput(config, output.storagePath);
        await client.assetDerivative.updateMany({
          where: { id: candidate.id, status: 'processing', processingLeaseId: leaseId },
          data: {
            status: 'pending', processingLeaseId: null, processingLeaseExpiresAt: null,
            nextAttemptAt: new Date(),
          },
        }).catch(() => undefined);
        return true;
      }
      committed = true;
      await cleanupOldClaimOutputs(candidate.kind, output.storagePath);
      logger.info({ event: 'derivative_ready', assetId: candidate.assetId, kind: candidate.kind,
        bytes: output.fileSize.toString() }, 'Derivative generated');
    } catch (error) {
      if (output && !committed) {
        await discardFinalOutput(config, output.storagePath);
      }
      const attempts = candidate.attempts + 1;
      const transient = output !== undefined || transientDerivativeError(error);
      const delayMs = Math.min(24 * 60 * 60 * 1000,
        30_000 * (2 ** Math.min(11, Math.max(0, attempts - 1))));
      const message = safeMessage(error);
      await client.assetDerivative.updateMany({ where: {
        id: candidate.id, status: 'processing', processingLeaseId: leaseId,
      }, data: {
        status: 'failed', attempts, lastError: transient ? `[transient] ${message}` : message,
        nextAttemptAt: new Date(Date.now() + delayMs),
        processingLeaseId: null, processingLeaseExpiresAt: null,
      } }).catch(() => undefined);
      logger.warn({ event: 'derivative_failed', assetId: candidate.assetId, kind: candidate.kind,
        attempts, transient }, 'Derivative generation failed');
    } finally {
      clearInterval(heartbeat);
      await heartbeatInFlight.catch(() => undefined);
    }
    return true;
  }

  async function runOnce(): Promise<number> {
    await recoverStaleJobs();
    if (Date.now() >= nextReconciliationAt) {
      await reconcileReadyDerivatives();
      nextReconciliationAt = Date.now() + 6 * 60 * 60 * 1000;
    }
    await ensureJobs();
    let count = 0;
    while (!stopping && await processOne()) count += 1;
    return count;
  }

  function schedule(): void {
    if (stopping) return;
    timer = setTimeout(() => {
      active = runOnce().then(() => undefined).catch(() => logger.error({ event: 'derivative_worker_failed' }, 'Derivative worker cycle failed'))
        .finally(() => { active = undefined; schedule(); });
    }, config.derivativePollIntervalMs);
    timer.unref();
  }

  return {
    async start() {
      stopping = false;
      await recoverStaleJobs();
      started = true;
      schedule();
    },
    runOnce,
    reconcileReadyDerivatives,
    async diagnostics() {
      const timeout = Math.min(5000, config.derivativeToolTimeoutMs);
      const tools = await Promise.allSettled([
        runTool('ffmpeg', ['-version'], timeout),
        runTool('ffprobe', ['-version'], timeout),
      ]);
      const ffmpegAvailable = tools[0]?.status === 'fulfilled';
      const ffprobeAvailable = tools[1]?.status === 'fulfilled';
      const toolsAvailable = ffmpegAvailable && ffprobeAvailable;
      const now = new Date();
      const [pending, failed, processing, stuck] = await Promise.all([
        client.assetDerivative.count({ where: { status: 'pending' } }),
        client.assetDerivative.count({ where: { status: 'failed' } }),
        client.assetDerivative.count({ where: { status: 'processing' } }),
        client.assetDerivative.count({ where: {
          status: 'processing',
          OR: [{ processingLeaseExpiresAt: null }, { processingLeaseExpiresAt: { lte: now } }],
        } }),
      ]);
      const backlog = pending + failed;
      const status = !toolsAvailable ? 'error'
        : !started || stuck > 0 || backlog >= config.derivativeBacklogWarning ? 'degraded'
          : 'ok';
      return {
        status,
        worker: started && !stopping ? 'running' : 'stopped',
        ffmpeg: ffmpegAvailable ? 'ok' : 'error',
        ffprobe: ffprobeAvailable ? 'ok' : 'error',
        queue: { pending, failed, processing, stuck, backlog },
      } as const;
    },
    recoverStaleJobs,
    async stop() {
      stopping = true;
      started = false;
      if (timer) clearTimeout(timer);
      await active;
    },
  };
}

export type DerivativeWorker = ReturnType<typeof createDerivativeWorker>;
