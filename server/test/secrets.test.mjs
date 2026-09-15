import assert from 'node:assert/strict';
import test from 'node:test';
import { hashSecret, matchesSetupToken, newDeviceToken, newSetupToken, newPairingCode, normalizePairingCode } from '../dist/auth/secrets.js';

test('device and bootstrap tokens are 256-bit random credentials with separate hash domains', () => {
  const device = newDeviceToken();
  const setup = newSetupToken();
  assert.match(device, /^psd_[A-Za-z0-9_-]{43}$/);
  assert.match(setup, /^pss_[A-Za-z0-9_-]{43}$/);
  assert.equal(Buffer.from(device.slice(4), 'base64url').length, 32);
  assert.notEqual(hashSecret(device, 'device'), hashSecret(device, 'setup'));
  assert.equal(matchesSetupToken(setup, hashSecret(setup, 'setup')), true);
  assert.equal(matchesSetupToken(newSetupToken(), hashSecret(setup, 'setup')), false);
  assert.equal(matchesSetupToken(device, hashSecret(device, 'setup')), false);
  assert.equal(matchesSetupToken(setup, ''), false);
});

test('128-bit pairing codes accept compact or grouped case-insensitive input only', () => {
  const code = newPairingCode();
  assert.match(code, /^[A-F0-9]{4}(?:-[A-F0-9]{4}){7}$/);
  const compact = code.replaceAll('-', '');
  assert.equal(normalizePairingCode(code), compact);
  assert.equal(normalizePairingCode(compact.toLowerCase()), compact);
  assert.equal(normalizePairingCode(code + '!'), null);
  assert.equal(normalizePairingCode('123456'), null);
});
