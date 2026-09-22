import { createHash, randomBytes } from 'node:crypto';

const hash = (purpose: string, secret: string) =>
  createHash('sha256').update('photosync:' + purpose + ':', 'utf8').update(secret, 'utf8').digest('hex');

export function hashSecret(secret: string, purpose: 'device'): string {
  return hash(purpose, secret);
}

export function newDeviceToken(): string {
  return 'psd_' + randomBytes(32).toString('base64url');
}
