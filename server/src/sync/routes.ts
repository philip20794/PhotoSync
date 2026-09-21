import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import type { PrismaClient } from '../generated/prisma/client.js';
import { ApiError } from '../errors.js';

const decimal = z.string().regex(/^(0|[1-9][0-9]{0,18})$/);
const cursorSchema = z.object({ epoch: z.uuid(), user: z.uuid(), pair: z.uuid(), after: decimal }).strict();
const querySchema = z.object({ cursor: z.string().max(1024).optional(), limit: z.coerce.number().int().min(1).max(100).default(100) }).strict();
type Change = { revision: bigint; albumId: string; assetId: string | null; kind: string; operation: string };

export function registerSyncRoutes(app: FastifyInstance, db?: PrismaClient) {
  app.put('/v1/sync/push-token', async (request, reply) => {
    if (!db || !request.principal) throw new ApiError(401, 'UNAUTHORIZED', 'Valid device credentials required');
    const parsed = z.object({ token: z.string().min(16).max(4096) }).strict().safeParse(request.body);
    if (!parsed.success) throw new ApiError(400, 'INVALID_REQUEST', 'Invalid push token');
    const principal = request.principal;
    await db.$transaction(async (tx) => {
      // One token belongs to one active device; registration is safe to repeat
      // after a response is lost, or after an account switch on this installation.
      await tx.$executeRaw`DELETE FROM sync_push_devices WHERE token = ${parsed.data.token} AND "deviceId" <> ${principal.deviceId}::uuid`;
      await tx.$executeRaw`
        INSERT INTO sync_push_devices ("deviceId", token) VALUES (${principal.deviceId}::uuid, ${parsed.data.token})
        ON CONFLICT ("deviceId") DO UPDATE SET token = EXCLUDED.token,
          revision = CASE WHEN sync_push_devices.token = EXCLUDED.token THEN sync_push_devices.revision ELSE 0 END,
          "nextAttemptAt" = now()`;
    });
    return reply.code(204).send();
  });
  app.get('/v1/sync/changes', async (request) => {
    if (!db || !request.principal) throw new ApiError(401, 'UNAUTHORIZED', 'Valid device credentials required');
    const parsed = querySchema.safeParse(request.query);
    if (!parsed.success) throw new ApiError(400, 'INVALID_REQUEST', 'Invalid change query');
    const principal = request.principal;
    return db.$transaction(async (tx) => {
      const [head] = await tx.$queryRaw<{ epoch: string; revision: bigint; minRevision: bigint }[]>`
        SELECT epoch::text, revision, "minRevision" FROM sync_head WHERE id = 1`;
      if (!head) throw new Error('Missing sync head');
      let after = 0n;
      if (parsed.data.cursor) {
        try {
          const cursor = cursorSchema.parse(JSON.parse(Buffer.from(parsed.data.cursor, 'base64url').toString('utf8')));
          after = BigInt(cursor.after);
          if (cursor.epoch !== head.epoch || cursor.user !== principal.userId || cursor.pair !== principal.pairId
            || after > head.revision || after < head.minRevision) throw new Error('Scope');
        } catch { throw new ApiError(410, 'CURSOR_RESET_REQUIRED', 'Restart change reconciliation without a cursor'); }
      }
      // Only partner-visible invalidations consume a page.
      const scanned = await tx.$queryRaw<{ revision: bigint }[]>`
        SELECT revision FROM sync_changes
        WHERE revision > ${after} AND revision <= ${head.revision}
          AND "pairId" = ${principal.pairId}::uuid AND "ownerId" <> ${principal.userId}::uuid AND shared
        ORDER BY revision LIMIT ${parsed.data.limit}`;
      const end = scanned.at(-1)?.revision ?? head.revision;
      const changes = await tx.$queryRaw<Change[]>`
        SELECT revision, "albumId", "assetId", kind, operation FROM sync_changes
        WHERE revision > ${after} AND revision <= ${end} AND "pairId" = ${principal.pairId}::uuid
          AND "ownerId" <> ${principal.userId}::uuid AND shared
        ORDER BY revision`;
      return {
        changes: changes.map((change) => ({ ...change, revision: change.revision.toString() })),
        nextCursor: Buffer.from(JSON.stringify({ epoch: head.epoch, user: principal.userId, pair: principal.pairId, after: end.toString() })).toString('base64url'),
        hasMore: end < head.revision,
      };
    }, { isolationLevel: 'RepeatableRead' });
  });
}
