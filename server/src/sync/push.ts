import { applicationDefault, initializeApp, deleteApp } from 'firebase-admin/app';
import { getMessaging } from 'firebase-admin/messaging';
import type { PrismaClient } from '../generated/prisma/client.js';
import type { Logger } from 'pino';

export type WakeSender = (token: string) => Promise<void>;
export const wakeMessage = (token: string) => ({
  token, data: { type: 'sync' },
  android: { priority: 'normal' as const, collapseKey: 'photosync-sync', ttl: 60 * 60 * 1000 },
});

/** Delivery checkpoints are only hints, never client change-feed cursors. */
export async function dispatchWakeups(db: PrismaClient, send: WakeSender) {
  const rows = await db.$queryRaw<{ deviceId: string; token: string; revision: bigint }[]>`
    SELECT p."deviceId", p.token, relevant.revision FROM sync_push_devices p
    JOIN devices d ON d.id = p."deviceId"
    JOIN users u ON u.id = d."userId"
    JOIN LATERAL (
      SELECT max(c.revision) AS revision FROM sync_changes c
      WHERE c."pairId" = u."pairId" AND c."ownerId" <> u.id AND c.shared
        AND c.revision > p.revision
    ) relevant ON relevant.revision IS NOT NULL
    WHERE d."revokedAt" IS NULL AND d."tokenHash" IS NOT NULL
      AND p."nextAttemptAt" <= now()
    ORDER BY p."nextAttemptAt" LIMIT 50`;
  for (const row of rows) {
    try {
      await send(row.token);
      await db.$executeRaw`UPDATE sync_push_devices SET revision = GREATEST(revision, ${row.revision}), attempts = 0
        WHERE "deviceId" = ${row.deviceId}::uuid AND token = ${row.token}`;
    } catch (error) {
      const code = typeof error === 'object' && error !== null && 'code' in error ? error.code : undefined;
      if (code === 'messaging/registration-token-not-registered' || code === 'messaging/invalid-registration-token') {
        await db.$executeRaw`DELETE FROM sync_push_devices WHERE "deviceId" = ${row.deviceId}::uuid AND token = ${row.token}`;
      } else {
        await db.$executeRaw`UPDATE sync_push_devices SET attempts = LEAST(attempts + 1, 10),
          "nextAttemptAt" = now() + make_interval(secs => LEAST(3600, 30 * power(2, LEAST(attempts, 7)))::int)
          WHERE "deviceId" = ${row.deviceId}::uuid AND token = ${row.token}`;
      }
    }
  }
}

export function createPushWorker(db: PrismaClient, logger: Logger) {
  const firebase = initializeApp({ credential: applicationDefault() }, 'photosync-wake');
  let timer: NodeJS.Timeout | undefined;
  let running: Promise<void> | undefined;
  const tick = () => {
    if (running) return;
    running = dispatchWakeups(db, async (token) => { await getMessaging(firebase).send(wakeMessage(token)); })
      .catch(() => { logger.warn({ event: 'push_dispatch_failed' }, 'Wakeup delivery will retry'); })
      .finally(() => { running = undefined; });
  };
  return {
    start() { tick(); timer = setInterval(tick, 5000); timer.unref(); },
    async stop() { clearInterval(timer); await running; await deleteApp(firebase); },
  };
}
