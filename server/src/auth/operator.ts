import type { PrismaClient } from '../generated/prisma/client.js';
import { withAuthLock } from './locking.js';
import { hashPassword, normalizeUsername } from './passwords.js';

export class OperatorCommandError extends Error {
  constructor(
    readonly code: 'USER_NOT_FOUND' | 'USER_EXISTS' | 'PAIR_FULL' | 'DEVICE_NOT_FOUND' | 'INVALID_PASSWORD',
    message: string,
  ) {
    super(message);
    this.name = 'OperatorCommandError';
  }
}

const userView = (user: { id: string; username: string; displayName: string; createdAt: Date }) => ({
  id: user.id,
  username: user.username,
  displayName: user.displayName,
  createdAt: user.createdAt,
});

export function createOperatorService(client: PrismaClient) {
  async function findUser(username: string) {
    const user = await client.user.findUnique({ where: { username: normalizeUsername(username) } });
    if (!user) throw new OperatorCommandError('USER_NOT_FOUND', 'No matching user exists');
    return user;
  }

  return {
    async listUsers() {
      return (await client.user.findMany({ orderBy: { memberSlot: 'asc' } })).map(userView);
    },

    async createUser(input: string) {
      const username = normalizeUsername(input);
      if (!username || username.length > 80) {
        throw new OperatorCommandError('USER_NOT_FOUND', 'Username must contain between 1 and 80 characters');
      }
      return withAuthLock(client, async (tx, pairId, now) => {
        if (await tx.user.findUnique({ where: { username } })) {
          throw new OperatorCommandError('USER_EXISTS', 'Username already exists');
        }
        const used = new Set((await tx.user.findMany({ where: { pairId }, select: { memberSlot: true } }))
          .map((user) => user.memberSlot));
        const memberSlot = [1, 2].find((slot) => !used.has(slot));
        if (!memberSlot) throw new OperatorCommandError('PAIR_FULL', 'Both account slots already exist');
        const displayName = input.normalize('NFKC').trim();
        const user = await tx.user.create({ data: { pairId, memberSlot, username, displayName } });
        await tx.pair.update({ where: { id: pairId }, data: { setupCompletedAt: now } });
        return userView(user);
      });
    },

    async setPassword(username: string, password: string) {
      let passwordHash: string;
      try {
        passwordHash = await hashPassword(password);
      } catch {
        throw new OperatorCommandError('INVALID_PASSWORD', 'Password must contain between 12 and 1024 characters');
      }
      const user = await findUser(username);
      await client.user.update({ where: { id: user.id }, data: { passwordHash } });
      return userView(user);
    },

    async listDevices(username?: string) {
      const user = username === undefined ? undefined : await findUser(username);
      return client.device.findMany({
        where: user ? { userId: user.id } : undefined,
        include: { user: { select: { username: true } } },
        orderBy: [{ user: { memberSlot: 'asc' } }, { createdAt: 'asc' }],
      });
    },

    async revokeDevice(id: string) {
      const result = await client.device.updateMany({
        where: { id },
        data: { tokenHash: null, revokedAt: new Date() },
      });
      if (result.count !== 1) throw new OperatorCommandError('DEVICE_NOT_FOUND', 'No matching device exists');
    },
  };
}
