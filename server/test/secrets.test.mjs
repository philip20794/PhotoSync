import assert from 'node:assert/strict';
import test from 'node:test';
import { hashSecret, newDeviceToken } from '../dist/auth/secrets.js';

test('device tokens are 256-bit random credentials with a purpose-specific hash domain', () => {
  const device = newDeviceToken();
  assert.match(device, /^psd_[A-Za-z0-9_-]{43}$/);
  assert.equal(Buffer.from(device.slice(4), 'base64url').length, 32);
  assert.equal(hashSecret(device, 'device'), hashSecret(device, 'device'));
  assert.notEqual(newDeviceToken(), device);
});
