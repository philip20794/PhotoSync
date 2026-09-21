import type { Config } from '../config.js';
import type { PrismaClient } from '../generated/prisma/client.js';
import { withAuthLock } from './locking.js';
import { persistPairingCode } from './pairing.js';

export type UserSelector = { userId: string } | { displayName: string };

export class OperatorCommandError extends Error {
  constructor(readonly code: 'USER_NOT_FOUND' | 'AMBIGUOUS_USER', message: string) {
    super(message);
    this.name = 'OperatorCommandError';
  }
}

export function createOperatorService(client: PrismaClient, config: Config) {
  return {
    async createPairingCode(selector: UserSelector) {
      return withAuthLock(client, async (tx, pairId, now) => {
        const users = await tx.user.findMany({
          where: 'userId' in selector
            ? { id: selector.userId, pairId }
            : { displayName: selector.displayName, pairId },
          select: { id: true, displayName: true },
          orderBy: { id: 'asc' },
          take: 2,
        });
        if (users.length === 0) {
          throw new OperatorCommandError('USER_NOT_FOUND', 'No matching user exists');
        }
        if (users.length !== 1) {
          throw new OperatorCommandError('AMBIGUOUS_USER', 'The display name matches more than one user');
        }
        const user = users[0]!;
        const pairing = await persistPairingCode(tx, config, now, {
          pairId,
          purpose: 'device',
          targetUserId: user.id,
          createdByDeviceId: null,
          createdByOperator: true,
        });
        return { user, ...pairing, validForSeconds: config.pairingCodeTtlSeconds };
      });
    },
  };
}
