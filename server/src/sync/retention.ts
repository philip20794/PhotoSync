import type { PrismaClient } from '../generated/prisma/client.js';
import type { Logger } from 'pino';

/**
 * Compaction is an epoch boundary: every old cursor is rejected and clients run
 * their existing full partner-album reconciliation before accepting a new cursor.
 */
export async function compactChangeJournal(db: PrismaClient, retentionDays: number): Promise<boolean> {
  return db.$transaction(async (tx) => {
    await tx.$queryRaw`SELECT id FROM sync_head WHERE id = 1 FOR UPDATE`;
    const [old] = await tx.$queryRaw<{ revision: bigint | null }[]>`
      SELECT max(revision) AS revision FROM sync_changes
      WHERE "createdAt" < now() - make_interval(days => ${retentionDays})`;
    if (old?.revision == null) return false;
    await tx.$executeRaw`DELETE FROM sync_changes WHERE revision <= ${old.revision}`;
    await tx.$executeRaw`UPDATE sync_head SET epoch = gen_random_uuid(),
      "minRevision" = GREATEST("minRevision", ${old.revision}) WHERE id = 1`;
    return true;
  });
}

export function createChangeJournalWorker(db: PrismaClient, retentionDays: number, logger: Logger) {
  let timer: NodeJS.Timeout | undefined;
  let running: Promise<void> | undefined;
  const tick = () => {
    if (running) return;
    running = compactChangeJournal(db, retentionDays)
      .then(() => undefined)
      .catch(() => logger.warn({ event: 'change_journal_compaction_failed' }, 'Change journal compaction will retry'))
      .finally(() => { running = undefined; });
  };
  return {
    start() { tick(); timer = setInterval(tick, 24 * 60 * 60 * 1000); timer.unref(); },
    async stop() { clearInterval(timer); await running; },
  };
}
