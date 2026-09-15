import { createReadStream } from 'node:fs';
import type { Readable } from 'node:stream';
import type { FastifyInstance, FastifyReply, FastifyRequest } from 'fastify';
import { z } from 'zod';
import type { Config } from '../config.js';
import { ApiError } from '../errors.js';
import type { MediaService } from './service.js';

const idParams = z.object({ id: z.uuid() }).strict();
const albumIdParams = z.object({ albumId: z.uuid() }).strict();
const albumBody = z.object({
  clientAlbumId: z.string().trim().min(1).max(255),
  title: z.string().trim().min(1).max(200),
}).strict();
const albumSharingBody = z.object({ shared: z.boolean() }).strict();
const decimal = z.string().regex(/^(?:0|[1-9]\d*)$/).transform(BigInt);
const mime = z.string().trim().toLowerCase().regex(/^(?:image|video)\/[a-z0-9][a-z0-9!#$&^_.+\-]*$/).max(127);
const assetBody = z.object({
  originalFileName: z.string().trim().min(1).max(255).refine((value) => !/[\u0000-\u001f\u007f]/.test(value)),
  mimeType: mime,
  capturedAt: z.iso.datetime({ offset: true }).transform((value) => new Date(value)).optional(),
  fileSize: decimal.refine((value) => value > 0n),
  width: z.number().int().positive().max(100000),
  height: z.number().int().positive().max(100000),
  durationMillis: decimal.optional(),
  clientAssetId: z.string().trim().min(1).max(255).optional(),
  expectedSha256: z.string().regex(/^[a-f0-9]{64}$/).optional(),
}).strict().superRefine((value, ctx) => {
  const video = value.mimeType.startsWith('video/');
  if (video !== (value.durationMillis !== undefined)) {
    ctx.addIssue({ code: 'custom', path: ['durationMillis'],
      message: video ? 'Required for video' : 'Allowed for video only' });
  }
  if ((value.clientAssetId === undefined) !== (value.expectedSha256 === undefined)) {
    ctx.addIssue({ code: 'custom', path: ['clientAssetId'],
      message: 'clientAssetId and expectedSha256 must be supplied together' });
  }
});

function parse<T>(schema: z.ZodType<T>, input: unknown): T {
  const result = schema.safeParse(input);
  if (!result.success) throw new ApiError(400, 'INVALID_REQUEST', 'Invalid request');
  return result.data;
}

function contentDisposition(fileName: string, disposition: 'attachment' | 'inline'): string {
  const encoded = encodeURIComponent(fileName).replace(/['()*]/g, (value) =>
    `%${value.charCodeAt(0).toString(16).toUpperCase()}`);
  return `${disposition}; filename="media"; filename*=UTF-8''${encoded}`;
}

function byteRange(value: string | undefined, size: number): { start: number; end: number } | undefined {
  if (value === undefined) return undefined;
  const match = /^bytes=(\d*)-(\d*)$/.exec(value);
  if (!match || (!match[1] && !match[2])) throw new ApiError(416, 'RANGE_NOT_SATISFIABLE', 'Invalid byte range');
  let start: number;
  let end: number;
  if (!match[1]) {
    const suffix = Number(match[2]);
    if (!Number.isSafeInteger(suffix) || suffix <= 0) throw new ApiError(416, 'RANGE_NOT_SATISFIABLE', 'Invalid byte range');
    start = Math.max(0, size - suffix);
    end = size - 1;
  } else {
    start = Number(match[1]);
    end = match[2] ? Number(match[2]) : size - 1;
    if (!Number.isSafeInteger(start) || !Number.isSafeInteger(end) || start < 0 || start >= size || end < start) {
      throw new ApiError(416, 'RANGE_NOT_SATISFIABLE', 'Invalid byte range');
    }
    end = Math.min(end, size - 1);
  }
  return { start, end };
}

async function sendMedia(request: FastifyRequest, reply: FastifyReply, service: MediaService,
  id: string, variant: 'original' | 'thumbnail' | 'optimized') {
  const file = await service.mediaFile(request.principal!, id, variant);
  let range;
  try { range = byteRange(typeof request.headers.range === 'string' ? request.headers.range : undefined, file.size); }
  catch (error) {
    reply.header('content-range', `bytes */${file.size}`);
    throw error;
  }
  reply
    .type(file.mimeType)
    .header('accept-ranges', 'bytes')
    .header('etag', `"${file.sha256}"`)
    .header('content-disposition', contentDisposition(file.fileName, variant === 'original' ? 'attachment' : 'inline'));
  if (range) {
    reply.code(206)
      .header('content-range', `bytes ${range.start}-${range.end}/${file.size}`)
      .header('content-length', String(range.end - range.start + 1));
    return reply.send(createReadStream(file.path, range));
  }
  reply.header('content-length', String(file.size));
  return reply.send(createReadStream(file.path));
}

export function registerMediaRoutes(app: FastifyInstance, config: Config, media: MediaService | undefined) {
  const service = () => {
    if (!media) throw new ApiError(503, 'MEDIA_UNAVAILABLE', 'Media service unavailable');
    return media;
  };

  app.post('/v1/albums', { bodyLimit: 4096 }, async (request, reply) =>
    reply.code(201).send(await service().createAlbum(request.principal!, parse(albumBody, request.body))));

  app.get('/v1/albums', async (request) => service().listAlbums(request.principal!));

  app.get('/v1/albums/:id', async (request) =>
    service().getAlbum(request.principal!, parse(idParams, request.params).id));

  app.patch('/v1/albums/:id', { bodyLimit: 1024 }, async (request) =>
    service().setAlbumSharing(
      request.principal!,
      parse(idParams, request.params).id,
      parse(albumSharingBody, request.body).shared,
    ));

  app.post('/v1/albums/:albumId/assets', { bodyLimit: 8192 }, async (request, reply) =>
    reply.code(201).send(await service().createAsset(
      request.principal!,
      parse(albumIdParams, request.params).albumId,
      parse(assetBody, request.body),
    )));

  app.get('/v1/albums/:albumId/assets', async (request) =>
    service().listAssets(request.principal!, parse(albumIdParams, request.params).albumId));

  app.get('/v1/assets/:id', async (request) =>
    service().getAsset(request.principal!, parse(idParams, request.params).id));

  app.put('/v1/assets/:id/original', { bodyLimit: config.maxUploadBytes }, async (request, reply) => {
    if (request.headers['content-type'] !== 'application/octet-stream') {
      throw new ApiError(415, 'UNSUPPORTED_MEDIA_TYPE', 'Use application/octet-stream');
    }
    const value = request.headers['content-length'];
    if (typeof value !== 'string' || !/^\d+$/.test(value)) {
      throw new ApiError(411, 'LENGTH_REQUIRED', 'A valid Content-Length header is required');
    }
    const length = Number(value);
    if (!Number.isSafeInteger(length) || length <= 0 || length > config.maxUploadBytes) {
      throw new ApiError(413, 'UPLOAD_TOO_LARGE', 'Upload exceeds configured limit');
    }
    const asset = await service().uploadOriginal(
      request.principal!,
      parse(idParams, request.params).id,
      request.body as Readable,
      length,
    );
    request.log.info({ event: 'original_uploaded', assetId: asset.id }, 'Original uploaded');
    return reply.code(200).send(asset);
  });

  for (const variant of ['original', 'thumbnail', 'optimized'] as const) {
    app.get(`/v1/assets/:id/${variant}`, async (request, reply) =>
      sendMedia(request, reply, service(), parse(idParams, request.params).id, variant));
  }

  app.post('/v1/assets/:id/derivatives/retry', async (request, reply) =>
    reply.code(202).send(await service().retryDerivatives(
      request.principal!, parse(idParams, request.params).id,
    )));
}
