import { createHash, randomBytes, timingSafeEqual } from 'node:crypto';

export function hashSecret(value: string, purpose: 'device' | 'pairing' | 'setup'): string {
  return createHash('sha256').update(`photosync:${purpose}:v1:`).update(value).digest('hex');
}
export function newDeviceToken(): string {
  return `psd_${randomBytes(32).toString('base64url')}`;
}
export function newSetupToken(): string {
  return `pss_${randomBytes(32).toString('base64url')}`;
}
export function newPairingCode(): string {
  return randomBytes(16).toString('hex').toUpperCase().match(/.{4}/g)!.join('-');
}
export function normalizePairingCode(value: string): string | null {
  // Accept exactly the displayed groups or compact hex; no arbitrary punctuation.
  if (!/^(?:[a-f0-9]{32}|[a-f0-9]{4}(?:-[a-f0-9]{4}){7})$/i.test(value)) return null;
  return value.replaceAll('-', '').toUpperCase();
}
export function matchesSetupToken(token: string, expectedHash: string): boolean {
  if (!/^pss_[A-Za-z0-9_-]{43}$/.test(token) || !/^[a-f0-9]{64}$/.test(expectedHash)) return false;
  return timingSafeEqual(Buffer.from(hashSecret(token, 'setup'), 'hex'), Buffer.from(expectedHash, 'hex'));
}
