import { createReadStream } from 'node:fs';
import type { Readable } from 'node:stream';
import type { FastifyInstance, FastifyReply, FastifyRequest } from 'fastify';
import { z } from 'zod';
import type { Config } from '../config.js';
import { ApiError } from '../errors.js';
import type { MediaService } from './service.js';

const idParams = z.object({ id: z.uuid() }).strict();
const albumIdParams = z.object({ albumId: z.uuid() }).strict();
const assetPageQuery = z.object({
  limit: z.coerce.number().int().min(1).max(100).default(60),
  cursor: z.string().min(1).max(256).optional(),
}).strict();
const albumBody = z.object({
  clientAlbumId: z.string().trim().min(1).max(255),
  sourceVolume: z.string().trim().min(1).max(80),
  sourceRelativePath: z.string().trim().min(1).max(512),
  title: z.string().trim().min(1).max(200),
  shared: z.boolean().default(true),
  backedUp: z.boolean().default(false),
}).strict();
const albumSharingBody = z.object({ shared: z.boolean() }).strict();
const trashIdsBody = z.object({ assetIds: z.array(z.uuid()).min(1).max(1000) }).strict();
const trashBulkBody = z.object({ assetIds: z.array(z.uuid()).max(1000).optional() }).strict();
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

function parseAssetPage(input: unknown): { limit: number; cursor?: { createdAt: Date; id: string } } {
  const query = parse(assetPageQuery, input);
  if (!query.cursor) return { limit: query.limit };
  try {
    const decoded = JSON.parse(Buffer.from(query.cursor, 'base64url').toString('utf8'));
    const value = z.object({ createdAt: z.iso.datetime({ offset: true }), id: z.uuid() }).strict().parse(decoded);
    return { limit: query.limit, cursor: { createdAt: new Date(value.createdAt), id: value.id } };
  } catch {
    throw new ApiError(400, 'INVALID_REQUEST', 'Invalid asset cursor');
  }
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
  id: string, variant: 'original' | 'thumbnail' | 'optimized', trash = false) {
  const file = trash ? await service.trashThumbnail(request.principal!, id)
    : await service.mediaFile(request.principal!, id, variant);
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

  app.get('/v1/backups', async (request) => service().listBackups(request.principal!));

  // The partner tab deliberately uses this narrower endpoint instead of filtering the
  // caller's own albums in the client.
  app.get('/v1/partner/albums', async (request) => service().listPartnerAlbums(request.principal!));

  app.get('/v1/trash', async (request) => service().listTrash(request.principal!));

  app.post('/v1/trash/assets/:id/restore', async (request) =>
    service().restoreTrashAsset(request.principal!, parse(idParams, request.params).id));
  app.get('/v1/trash/assets/:id/thumbnail', async (request, reply) =>
    sendMedia(request, reply, service(), parse(idParams, request.params).id, 'thumbnail', true));


  app.post('/v1/trash/restore', async (request) => service().restoreTrashAssets(
    request.principal!, parse(trashIdsBody, request.body).assetIds,
  ));

  app.delete('/v1/trash/assets/:id', async (request) =>
    service().purgeTrashAsset(request.principal!, parse(idParams, request.params).id));

  app.delete('/v1/trash/assets', async (request) => service().purgeTrashAssets(
    request.principal!, parse(trashIdsBody, request.body).assetIds,
  ));

  app.delete('/v1/trash', async (request) => {
    const ids = parse(trashBulkBody, request.body ?? {}).assetIds;
    return service().purgeTrashAssets(request.principal!, ids && ids.length > 0 ? ids : undefined);
  });

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
    service().listAssets(request.principal!, parse(albumIdParams, request.params).albumId, parseAssetPage(request.query)));

  app.get('/v1/assets/:id', async (request) =>
    service().getAsset(request.principal!, parse(idParams, request.params).id));

  app.delete('/v1/assets/:id', async (request) =>
    service().trashAsset(request.principal!, parse(idParams, request.params).id));

  app.delete('/v1/assets/:id/upload', async (request) =>
    service().cancelUpload(request.principal!, parse(idParams, request.params).id));

  app.post('/v1/assets/:id/upload-session', async (request, reply) =>
    reply.code(200).send(await service().createUploadSession(
      request.principal!, parse(idParams, request.params).id,
    )));

  app.patch('/v1/upload-sessions/:id', {
    bodyLimit: config.maxUploadChunkBytes,
    config: { longRunningUpload: true },
  }, async (request, reply) => {
    if (request.headers['content-type'] !== 'application/octet-stream') {
      throw new ApiError(415, 'UNSUPPORTED_MEDIA_TYPE', 'Use application/octet-stream');
    }
    const lengthValue = request.headers['content-length'];
    const offsetValue = request.headers['upload-offset'];
    if (typeof lengthValue !== 'string' || !/^\d+$/.test(lengthValue) ||
        typeof offsetValue !== 'string' || !/^\d+$/.test(offsetValue)) {
      throw new ApiError(400, 'INVALID_UPLOAD_HEADERS', 'Content-Length and Upload-Offset are required');
    }
    const length = Number(lengthValue);
    const offset = BigInt(offsetValue);
    if (!Number.isSafeInteger(length) || length <= 0 || length > config.maxUploadChunkBytes) {
      throw new ApiError(413, 'UPLOAD_CHUNK_TOO_LARGE', 'Upload chunk exceeds configured limit');
    }
    return reply.code(200).send(await service().uploadChunk(
      request.principal!,
      parse(idParams, request.params).id,
      offset,
      request.body as Readable,
      length,
    ));
  });

  app.put('/v1/assets/:id/original', { bodyLimit: config.maxUploadBytes, config: { longRunningUpload: true } }, async (request, reply) => {
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
