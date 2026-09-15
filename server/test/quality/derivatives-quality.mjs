import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { copyFile, mkdir, mkdtemp, readFile, rm, stat } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { basename, join } from 'node:path';
import { spawnSync } from 'node:child_process';
import sharp from 'sharp';
import { loadConfig } from '../../dist/config.js';
import { generateDerivative } from '../../dist/media/derivatives.js';

const inputs = process.argv.slice(2);
if (inputs.length < 4) throw new Error('Pass at least two image and two video paths');

function hash(bytes) { return createHash('sha256').update(bytes).digest('hex'); }
function probe(path) {
  const result = spawnSync('ffprobe', ['-v', 'error', '-select_streams', 'v:0', '-show_entries',
    'stream=width,height,codec_name:format=duration', '-of', 'json', path], { encoding: 'utf8' });
  if (result.status !== 0) throw new Error(result.stderr);
  const data = JSON.parse(result.stdout);
  return { ...data.streams[0], durationMillis: BigInt(Math.round(Number(data.format.duration) * 1000)) };
}
async function imagePsnr(source, optimized) {
  const reference = await sharp(source).autoOrient().resize({ width: 2560, height: 2560,
    fit: 'inside', withoutEnlargement: true }).toColorspace('srgb').removeAlpha().raw().toBuffer();
  const actual = await sharp(optimized).toColorspace('srgb').removeAlpha().raw().toBuffer();
  assert.equal(reference.length, actual.length);
  let squaredError = 0;
  for (let index = 0; index < reference.length; index += 1) {
    const delta = reference[index] - actual[index];
    squaredError += delta * delta;
  }
  const mse = squaredError / reference.length;
  return mse === 0 ? Infinity : 10 * Math.log10((255 * 255) / mse);
}
function videoSsim(source, optimized, width, height) {
  const result = spawnSync('ffmpeg', ['-nostdin', '-hide_banner', '-i', source, '-i', optimized,
    '-lavfi', `[0:v]scale=${width}:${height}:flags=lanczos[reference];[reference][1:v]ssim`,
    '-f', 'null', '-'], { encoding: 'utf8', maxBuffer: 2_000_000 });
  if (result.status !== 0) throw new Error(result.stderr);
  const matches = [...result.stderr.matchAll(/All:([0-9.]+)/g)];
  if (!matches.length) throw new Error('No SSIM result');
  return Number(matches.at(-1)[1]);
}

const root = await mkdtemp(join(tmpdir(), 'photosync-quality-'));
try {
  const config = loadConfig({ NODE_ENV: 'test', DATABASE_URL: 'postgresql://unused:unused@localhost/unused',
    MEDIA_DEV_ROOT: root, MEDIA_PROD_ROOT: '/unused-production', DERIVATIVE_WORKER_ENABLED: 'false',
    DERIVATIVE_TOOL_TIMEOUT_MS: '600000' });
  const results = [];
  for (const [index, input] of inputs.entries()) {
    const video = /\.(?:mp4|mov|mkv|webm)$/i.test(input);
    const id = `00000000-0000-4000-8000-${String(index + 1).padStart(12, '0')}`;
    const target = join(root, 'originals', 'quality', id, 'original');
    await mkdir(join(root, 'originals', 'quality', id), { recursive: true });
    await copyFile(input, target);
    const beforeBytes = await readFile(target);
    const beforeHash = hash(beforeBytes);
    const metadata = video ? probe(target) : await sharp(target).metadata();
    const asset = { id, ownerId: 'quality', mimeType: video ? 'video/mp4' : 'image/jpeg',
      storagePath: `originals/quality/${id}/original`, durationMillis: video ? metadata.durationMillis : null };
    const thumbnail = await generateDerivative(config, asset, 'thumbnail');
    const optimized = await generateDerivative(config, asset, 'optimized');
    const afterHash = hash(await readFile(target));
    assert.equal(afterHash, beforeHash, 'original changed');
    const originalSize = (await stat(target)).size;
    const optimizedPath = join(root, optimized.storagePath);
    const ratio = Number(optimized.fileSize) / originalSize;
    assert.ok(ratio < 0.8, `optimized file is not at least 20% smaller: ${input}`);
    if (video) {
      const quality = videoSsim(target, optimizedPath, optimized.width, optimized.height);
      assert.ok(quality >= 0.90, `video SSIM too low: ${quality}`);
      results.push({ file: basename(input), type: 'video', originalBytes: originalSize,
        optimizedBytes: Number(optimized.fileSize), savingPercent: Number(((1 - ratio) * 100).toFixed(1)),
        qualityMetric: `SSIM ${quality.toFixed(4)}`, output: `${optimized.width}x${optimized.height}`,
        thumbnailBytes: Number(thumbnail.fileSize) });
    } else {
      const quality = await imagePsnr(target, optimizedPath);
      assert.ok(quality >= 30, `image PSNR too low: ${quality}`);
      results.push({ file: basename(input), type: 'image', originalBytes: originalSize,
        optimizedBytes: Number(optimized.fileSize), savingPercent: Number(((1 - ratio) * 100).toFixed(1)),
        qualityMetric: `PSNR ${quality.toFixed(2)} dB`, output: `${optimized.width}x${optimized.height}`,
        thumbnailBytes: Number(thumbnail.fileSize) });
    }
  }
  console.log(JSON.stringify({ policyVerified: true, files: results }, null, 2));
} finally {
  await rm(root, { recursive: true, force: true });
}
