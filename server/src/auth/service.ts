import type { PrismaClient } from '../generated/prisma/client.js';
import type { Config } from '../config.js';
import { ApiError } from '../errors.js';
import { hashSecret, newDeviceToken } from './secrets.js';
import { dummyPasswordHash, normalizeUsername, verifyPassword } from './passwords.js';
import { withAuthLock, type AuthTransaction } from './locking.js';

type Tx = AuthTransaction;
export type Principal = { userId: string; deviceId: string; pairId: string };
export type LoginInput = { username: string; password: string; deviceName: string };
const unauthorized = () => new ApiError(401, 'UNAUTHORIZED', 'Invalid username or password');
const deviceUnauthorized = () => new ApiError(401, 'UNAUTHORIZED', 'Valid device credentials required');
const notFound = () => new ApiError(404, 'NOT_FOUND', 'Resource not found');
const userView = (user: { id: string; displayName: string; username: string }) =>
  ({ id: user.id, username: user.username, displayName: user.displayName });
const deviceView = (device: { id: string; name: string; createdAt: Date; revokedAt: Date | null }) =>
  ({ id: device.id, name: device.name, createdAt: device.createdAt, revokedAt: device.revokedAt });

export function createAuthService(client: PrismaClient, _config: Config) {
  async function requireActive(tx: Tx, principal: Principal) {
    const actor = await tx.device.findFirst({ where: {
      id: principal.deviceId, userId: principal.userId, revokedAt: null,
      user: { pairId: principal.pairId },
    }, include: { user: true } });
    if (!actor) throw deviceUnauthorized();
    return actor;
  }

  async function enroll(user: { id: string; displayName: string; username: string }, deviceName: string) {
    const accessToken = newDeviceToken();
    const device = await client.device.create({ data: {
      userId: user.id, name: deviceName, tokenHash: hashSecret(accessToken, 'device'),
    } });
    return { user: userView(user), device: deviceView(device), accessToken, tokenType: 'Bearer' as const };
  }

  return {
    async authenticate(authorization: string | undefined): Promise<Principal> {
      const match = /^Bearer (psd_[A-Za-z0-9_-]{43})$/i.exec(authorization ?? '');
      if (!match?.[1]) throw deviceUnauthorized();
      const device = await client.device.findUnique({
        where: { tokenHash: hashSecret(match[1], 'device') }, include: { user: true },
      });
      if (!device || device.revokedAt) throw deviceUnauthorized();
      return { userId: device.userId, deviceId: device.id, pairId: device.user.pairId };
    },

    async login(body: LoginInput) {
      const username = normalizeUsername(body.username);
      const user = username
        ? await client.user.findUnique({ where: { username } })
        : null;
      const valid = await verifyPassword(user?.passwordHash ?? await dummyPasswordHash, body.password);
      if (!user || !user.passwordHash || !valid) throw unauthorized();
      return enroll(user, body.deviceName);
    },

    async me(principal: Principal) {
      const device = await client.device.findFirst({ where: { id: principal.deviceId, revokedAt: null }, include: { user: true } });
      if (!device) throw deviceUnauthorized();
      const partner = await client.user.findFirst({
        where: { pairId: principal.pairId, id: { not: principal.userId } },
        select: { id: true, username: true, displayName: true },
      });
      return { user: userView(device.user), device: deviceView(device), partner: partner ? userView(partner) : null };
    },

    async updateProfile(principal: Principal, body: { displayName: string; deviceName: string }) {
      return client.$transaction(async (tx) => {
        await requireActive(tx, principal);
        const [user, device] = await Promise.all([
          tx.user.update({ where: { id: principal.userId }, data: { displayName: body.displayName } }),
          tx.device.update({ where: { id: principal.deviceId }, data: { name: body.deviceName } }),
        ]);
        const partner = await tx.user.findFirst({
          where: { pairId: principal.pairId, id: { not: principal.userId } },
          select: { id: true, username: true, displayName: true },
        });
        return { user: userView(user), device: deviceView(device), partner: partner ? userView(partner) : null };
      });
    },

    async devices(principal: Principal) {
      const devices = await client.device.findMany({ where: { userId: principal.userId }, orderBy: { createdAt: 'asc' } });
      return { devices: devices.map(deviceView) };
    },

    async revokeDevice(principal: Principal, id: string) {
      return withAuthLock(client, async (tx, _pairId, now) => {
        await requireActive(tx, principal);
        const target = await tx.device.findFirst({ where: { id, userId: principal.userId } });
        if (!target) throw notFound();
        if (!target.revokedAt) {
          await tx.device.update({ where: { id }, data: { tokenHash: null, revokedAt: now } });
        }
      });
    },
  };
}
export type AuthService = ReturnType<typeof createAuthService>;
