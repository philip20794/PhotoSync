import type { Config } from '../config.js';
import type { AuthTransaction } from './locking.js';
import { hashSecret, newPairingCode, normalizePairingCode } from './secrets.js';

type PairingCodeInput = {
  pairId: string;
  purpose: 'partner' | 'device';
  targetUserId: string | null;
  createdByDeviceId: string | null;
  createdByOperator: boolean;
};

export async function persistPairingCode(
  tx: AuthTransaction,
  config: Config,
  now: Date,
  input: PairingCodeInput,
) {
  const code = newPairingCode();
  const record = await tx.pairingCode.create({ data: {
    ...input,
    codeHash: hashSecret(normalizePairingCode(code)!, 'pairing'),
    createdAt: now,
    expiresAt: new Date(now.getTime() + config.pairingCodeTtlSeconds * 1000),
  } });
  return { id: record.id, code, purpose: input.purpose, expiresAt: record.expiresAt };
}
