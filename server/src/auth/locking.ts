import type { Prisma, PrismaClient } from '../generated/prisma/client.js';

export type AuthTransaction = Prisma.TransactionClient;

// All onboarding and recovery writes serialize on the singleton instance row.
// READ COMMITTED then sees the previous lock holder's committed state.
export function withAuthLock<T>(
  client: PrismaClient,
  action: (tx: AuthTransaction, pairId: string, now: Date) => Promise<T>,
): Promise<T> {
  return client.$transaction(async (tx) => {
    const rows = await tx.$queryRaw<{ id: string }[]>`SELECT "id" FROM "pairs" WHERE "singleton" = 1 FOR UPDATE`;
    if (!rows[0]) throw new Error('Missing instance row');
    const times = await tx.$queryRaw<{ now: Date }[]>`SELECT clock_timestamp() AS now`;
    return action(tx, rows[0].id, times[0]!.now);
  }, { maxWait: 5000, timeout: 10000 });
}
