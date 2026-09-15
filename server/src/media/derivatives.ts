import { createHash, randomUUID } from 'node:crypto';
import { createReadStream } from 'node:fs';
import { mkdir, open, rename, stat, unlink } from 'node:fs/promises';
import { dirname, isAbsolute, relative, resolve, sep } from 'node:path';
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

async function durableRename(temporaryPath: string, finalPath: string): Promise<void> {
  const handle = await open(temporaryPath, 'r');
  try { await handle.sync(); } finally { await handle.close(); }
  await rename(temporaryPath, finalPath);
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

async function createImageDerivative(sourcePath: string, temporaryPath: string, kind: DerivativeKind): Promise<Omit<OutputInfo, 'storagePath' | 'sha256' | 'fileSize'>> {
  const thumbnail = kind === 'thumbnail';
  const limit = thumbnail ? DERIVATIVE_POLICY.thumbnail.maxPixels : DERIVATIVE_POLICY.optimizedImage.maxPixels;
  const quality = thumbnail ? DERIVATIVE_POLICY.thumbnail.imageQuality : DERIVATIVE_POLICY.optimizedImage.quality;
  const info = await sharp(sourcePath, { failOn: 'error', limitInputPixels: 200_000_000 })
    .autoOrient()
    .resize({ width: limit, height: limit, fit: 'inside', withoutEnlargement: true })
    .webp({ quality, effort: thumbnail ? 4 : 5, preset: 'photo', smartSubsample: true })
    .toFile(temporaryPath);
  return { mimeType: 'image/webp', width: info.width, height: info.height, durationMillis: null };
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

export async function generateDerivative(config: Config, asset: SourceAsset, kind: DerivativeKind): Promise<OutputInfo> {
  const sourcePath = withinMediaRoot(config.mediaRoot, asset.storagePath);
  const extension = asset.mimeType.startsWith('video/')
    ? kind === 'thumbnail' ? 'jpg' : 'mp4'
    : 'webp';
  const storagePath = `derivatives/${asset.ownerId}/${asset.id}/${kind}.${extension}`;
  const finalPath = withinMediaRoot(config.mediaRoot, storagePath);
  const temporaryPath = `${finalPath}.${randomUUID()}.part`;
  await mkdir(dirname(finalPath), { recursive: true, mode: 0o700 });
  try {
    const metadata = asset.mimeType.startsWith('image/')
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

export function createDerivativeWorker(client: PrismaClient, config: Config, logger: Logger) {
  let timer: NodeJS.Timeout | undefined;
  let active: Promise<void> | undefined;
  let stopping = false;

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

  async function processOne(): Promise<boolean> {
    const now = new Date();
    const candidate = await client.assetDerivative.findFirst({
      where: {
        attempts: { lt: config.derivativeMaxAttempts },
        nextAttemptAt: { lte: now },
        status: { in: ['pending', 'failed'] },
        asset: { status: 'ready' },
      },
      include: { asset: true },
      orderBy: [{ nextAttemptAt: 'asc' }, { createdAt: 'asc' }],
    });
    if (!candidate) return false;
    const claimed = await client.assetDerivative.updateMany({
      where: { id: candidate.id, status: candidate.status, attempts: candidate.attempts },
      data: { status: 'processing', lastError: null },
    });
    if (claimed.count !== 1) return true;
    try {
      const output = await generateDerivative(config, candidate.asset, candidate.kind as DerivativeKind);
      await client.assetDerivative.update({ where: { id: candidate.id }, data: {
        status: 'ready', mimeType: output.mimeType, storagePath: output.storagePath,
        fileSize: output.fileSize, width: output.width, height: output.height,
        durationMillis: output.durationMillis, sha256: output.sha256, lastError: null,
      } });
      logger.info({ event: 'derivative_ready', assetId: candidate.assetId, kind: candidate.kind,
        bytes: output.fileSize.toString() }, 'Derivative generated');
    } catch (error) {
      const attempts = candidate.attempts + 1;
      const delayMs = Math.min(3_600_000, 30_000 * (2 ** Math.max(0, attempts - 1)));
      await client.assetDerivative.update({ where: { id: candidate.id }, data: {
        status: 'failed', attempts, lastError: safeMessage(error), nextAttemptAt: new Date(Date.now() + delayMs),
      } }).catch(() => undefined);
      logger.warn({ event: 'derivative_failed', assetId: candidate.assetId, kind: candidate.kind, attempts }, 'Derivative generation failed');
    }
    return true;
  }

  async function runOnce(): Promise<number> {
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
      await client.assetDerivative.updateMany({ where: { status: 'processing' }, data: { status: 'pending', nextAttemptAt: new Date() } });
      schedule();
    },
    runOnce,
    async stop() {
      stopping = true;
      if (timer) clearTimeout(timer);
      await active;
    },
  };
}

export type DerivativeWorker = ReturnType<typeof createDerivativeWorker>;
