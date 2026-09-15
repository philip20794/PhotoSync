import type { Prisma, PrismaClient } from '../generated/prisma/client.js';
import type { Config } from '../config.js';
import { ApiError } from '../errors.js';
import { hashSecret, matchesSetupToken, newDeviceToken, newPairingCode, normalizePairingCode } from './secrets.js';

type Tx = Prisma.TransactionClient;
export type Principal = { userId: string; deviceId: string; pairId: string };
export type Enrollment = { displayName?: string; deviceName: string };
const unauthorized = () => new ApiError(401, 'UNAUTHORIZED', 'Valid device credentials required');
const invalidCode = () => new ApiError(400, 'INVALID_PAIRING_CODE', 'Pairing code is invalid or unavailable');
const notFound = () => new ApiError(404, 'NOT_FOUND', 'Resource not found');
const userView = (user: { id: string; displayName: string }) => ({ id: user.id, displayName: user.displayName });
const deviceView = (device: { id: string; name: string; createdAt: Date; revokedAt: Date | null }) =>
  ({ id: device.id, name: device.name, createdAt: device.createdAt, revokedAt: device.revokedAt });

export function createAuthService(client: PrismaClient, config: Config) {
  // All onboarding/revocation writes serialize on the existing singleton row.
  // READ COMMITTED then sees the previous lock holder's committed state.
  async function locked<T>(action: (tx: Tx, pairId: string, now: Date) => Promise<T>): Promise<T> {
    return client.$transaction(async (tx) => {
      const rows = await tx.$queryRaw<{ id: string }[]>`SELECT "id" FROM "pairs" WHERE "singleton" = 1 FOR UPDATE`;
      if (!rows[0]) throw new Error('Missing instance row');
      const times = await tx.$queryRaw<{ now: Date }[]>`SELECT clock_timestamp() AS now`;
      return action(tx, rows[0].id, times[0]!.now);
    }, { maxWait: 5000, timeout: 10000 });
  }
  async function requireActive(tx: Tx, principal: Principal) {
    const actor = await tx.device.findFirst({ where: {
      id: principal.deviceId, userId: principal.userId, revokedAt: null,
      user: { pairId: principal.pairId },
    }, include: { user: true } });
    if (!actor) throw unauthorized();
    return actor;
  }
  async function enroll(tx: Tx, user: { id: string; displayName: string }, deviceName: string) {
    const accessToken = newDeviceToken();
    const device = await tx.device.create({ data: {
      userId: user.id, name: deviceName, tokenHash: hashSecret(accessToken, 'device'),
    } });
    return { user: userView(user), device: deviceView(device), accessToken, tokenType: 'Bearer' as const };
  }

  return {
    async authenticate(authorization: string | undefined): Promise<Principal> {
      const match = /^Bearer (psd_[A-Za-z0-9_-]{43})$/i.exec(authorization ?? '');
      if (!match?.[1]) throw unauthorized();
      const device = await client.device.findUnique({
        where: { tokenHash: hashSecret(match[1], 'device') }, include: { user: true },
      });
      if (!device || device.revokedAt) throw unauthorized();
      return { userId: device.userId, deviceId: device.id, pairId: device.user.pairId };
    },

    async setup(authorization: string | undefined, body: Enrollment & { displayName: string }) {
      const token = /^Bearer (pss_[A-Za-z0-9_-]{43})$/i.exec(authorization ?? '')?.[1] ?? '';
      if (!matchesSetupToken(token, config.setupTokenHash)) {
        throw new ApiError(403, 'SETUP_FORBIDDEN', 'Setup is disabled or credentials are invalid');
      }
      return locked(async (tx, pairId, now) => {
        const pair = await tx.pair.findUniqueOrThrow({ where: { id: pairId } });
        if (pair.setupCompletedAt) throw new ApiError(409, 'ALREADY_CONFIGURED', 'Instance is already configured');
        const user = await tx.user.create({ data: { pairId, memberSlot: 1, displayName: body.displayName } });
        const result = await enroll(tx, user, body.deviceName);
        await tx.pair.update({ where: { id: pairId }, data: { setupCompletedAt: now } });
        return result;
      });
    },

    async createCode(principal: Principal, purpose: 'partner' | 'device') {
      return locked(async (tx, pairId, now) => {
        await requireActive(tx, principal);
        if (purpose === 'partner' && await tx.user.count({ where: { pairId } }) >= 2) {
          throw new ApiError(409, 'PAIR_FULL', 'Both accounts already exist');
        }
        const code = newPairingCode();
        const record = await tx.pairingCode.create({ data: {
          pairId, purpose, createdByDeviceId: principal.deviceId,
          targetUserId: purpose === 'device' ? principal.userId : null,
          codeHash: hashSecret(normalizePairingCode(code)!, 'pairing'),
          createdAt: now, expiresAt: new Date(now.getTime() + config.pairingCodeTtlSeconds * 1000),
        } });
        return { id: record.id, code, purpose, expiresAt: record.expiresAt };
      });
    },

    async redeem(code: string, body: Enrollment) {
      const normalized = normalizePairingCode(code);
      if (!normalized) throw invalidCode();
      return locked(async (tx, pairId, now) => {
        const record = await tx.pairingCode.findUnique({ where: { codeHash: hashSecret(normalized, 'pairing') },
          include: { createdByDevice: { include: { user: true } } } });
        if (!record || record.pairId !== pairId || record.consumedAt || record.revokedAt ||
            record.expiresAt <= now || record.createdByDevice.revokedAt) throw invalidCode();
        let user;
        if (record.purpose === 'partner') {
          if (!body.displayName) throw new ApiError(400, 'INVALID_REQUEST', 'Partner displayName is required');
          if (await tx.user.count({ where: { pairId } }) >= 2) {
            throw new ApiError(409, 'PAIR_FULL', 'Both accounts already exist');
          }
          user = await tx.user.create({ data: { pairId, memberSlot: 2, displayName: body.displayName } });
        } else {
          if (body.displayName !== undefined) throw new ApiError(400, 'INVALID_REQUEST', 'Device pairing does not change the account');
          if (record.targetUserId !== record.createdByDevice.userId) throw invalidCode();
          user = await tx.user.findFirst({ where: { id: record.targetUserId!, pairId } });
          if (!user) throw invalidCode();
        }
        const result = await enroll(tx, user, body.deviceName);
        await tx.pairingCode.update({ where: { id: record.id }, data: { consumedAt: now } });
        return result;
      });
    },

    async me(principal: Principal) {
      const device = await client.device.findFirst({ where: { id: principal.deviceId, revokedAt: null }, include: { user: true } });
      if (!device) throw unauthorized();
      return { user: userView(device.user), device: deviceView(device) };
    },
    async devices(principal: Principal) {
      const devices = await client.device.findMany({ where: { userId: principal.userId }, orderBy: { createdAt: 'asc' } });
      return { devices: devices.map(deviceView) };
    },
    async revokeDevice(principal: Principal, id: string) {
      return locked(async (tx, _pairId, now) => {
        await requireActive(tx, principal);
        const target = await tx.device.findFirst({ where: { id, userId: principal.userId } });
        if (!target) throw notFound();
        if (!target.revokedAt) await tx.device.update({ where: { id }, data: { tokenHash: null, revokedAt: now } });
        await tx.pairingCode.updateMany({ where: { createdByDeviceId: id, revokedAt: null, consumedAt: null }, data: { revokedAt: now } });
      });
    },
    async revokeCode(principal: Principal, id: string) {
      return locked(async (tx, _pairId, now) => {
        await requireActive(tx, principal);
        const code = await tx.pairingCode.findFirst({ where: { id, createdByDevice: { userId: principal.userId } } });
        if (!code) throw notFound();
        if (!code.revokedAt) await tx.pairingCode.update({ where: { id }, data: { revokedAt: now } });
      });
    },
  };
}
export type AuthService = ReturnType<typeof createAuthService>;
