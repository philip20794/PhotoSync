import assert from 'node:assert/strict';
import test from 'node:test';
import { loadConfig } from '../../dist/config.js';
import { createDatabase } from '../../dist/database.js';
import { buildApp } from '../../dist/app.js';
import { createLogger } from '../../dist/logger.js';
import { createAuthService } from '../../dist/auth/service.js';
import { hashSecret, newDeviceToken, newSetupToken, normalizePairingCode } from '../../dist/auth/secrets.js';

if (process.env.PHOTOSYNC_INTEGRATION_TEST !== '1' ||
    new URL(process.env.DATABASE_URL).pathname !== '/photosync_test') {
  throw new Error('Auth integration tests require the isolated photosync_test database');
}
const bearer = (token) => ({ authorization: `Bearer ${token}` });
async function fixture(t, extra = {}) {
  const setupToken = newSetupToken();
  const config = loadConfig({ ...process.env, SETUP_TOKEN_HASH: hashSecret(setupToken, 'setup'), AUTH_RATE_LIMIT_MAX: '1000', ...extra });
  const database = createDatabase(config);
  const client = database.client;
  const clear = async () => {
    await client.asset.deleteMany();
    await client.album.deleteMany();
    await client.pairingCode.deleteMany();
    await client.device.deleteMany();
    await client.user.deleteMany();
    await client.pair.updateMany({ data: { setupCompletedAt: null } });
  };
  await clear();
  let logs = '';
  const app = buildApp(config, database, createLogger('info', { write(chunk) { logs += chunk; } }));
  t.after(async () => { await clear(); await app.close(); });
  const setup = async (name = 'Alice') => {
    const response = await app.inject({ method: 'POST', url: '/v1/auth/setup', headers: bearer(setupToken),
      payload: { displayName: name, deviceName: `${name} phone` } });
    assert.equal(response.statusCode, 201, response.body);
    return response.json();
  };
  const invite = async (token, purpose) => {
    const response = await app.inject({ method: 'POST', url: '/v1/auth/pairing-codes', headers: bearer(token), payload: { purpose } });
    assert.equal(response.statusCode, 201, response.body);
    return response.json();
  };
  const redeem = (code, fields = {}, instance = app) => instance.inject({ method: 'POST', url: '/v1/auth/pair',
    payload: { code, deviceName: 'New phone', ...fields } });
  return { app, client, config, setupToken, setup, invite, redeem, logs: () => logs };
}

test('setup is operator-authorized, single-use and stores only device hashes', async (t) => {
  const { app, client, setupToken, setup, logs } = await fixture(t);
  for (const headers of [{}, bearer(newSetupToken())]) {
    const response = await app.inject({ method: 'POST', url: '/v1/auth/setup', headers,
      payload: { displayName: 'Alice', deviceName: 'Phone' } });
    assert.equal(response.statusCode, 403);
  }
  const alice = await setup();
  assert.equal((await app.inject({ method: 'POST', url: '/v1/auth/setup', headers: bearer(setupToken),
    payload: { displayName: 'Intruder', deviceName: 'Phone' } })).statusCode, 409);
  assert.equal(await client.user.count(), 1);
  const stored = await client.device.findUnique({ where: { id: alice.device.id } });
  assert.equal(stored.tokenHash, hashSecret(alice.accessToken, 'device'));
  assert.ok(!JSON.stringify(stored).includes(alice.accessToken));
  const me = await app.inject({ url: '/v1/me', headers: bearer(alice.accessToken) });
  assert.equal(me.statusCode, 200);
  assert.deepEqual(me.json().user, alice.user);
  assert.equal(me.headers['cache-control'], 'no-store');
  assert.equal((await app.inject({ url: '/v1/me', headers: bearer(setupToken) })).statusCode, 401);
  assert.ok(!logs().includes(setupToken));
  assert.ok(!logs().includes(alice.accessToken));
});

test('setup is disabled when no setup hash is configured', async (t) => {
  const { app, setupToken, client } = await fixture(t, { SETUP_TOKEN_HASH: '' });
  assert.equal((await app.inject({ method: 'POST', url: '/v1/auth/setup', headers: bearer(setupToken),
    payload: { displayName: 'Alice', deviceName: 'Phone' } })).statusCode, 403);
  assert.equal(await client.user.count(), 0);
});

test('concurrent first setup creates exactly one account and device', async (t) => {
  const { app, client, setupToken } = await fixture(t);
  const responses = await Promise.all(['Alice', 'Other'].map((displayName) => app.inject({ method: 'POST',
    url: '/v1/auth/setup', headers: bearer(setupToken), payload: { displayName, deviceName: 'Phone' } })));
  assert.deepEqual(responses.map((r) => r.statusCode).sort(), [201, 409]);
  assert.equal(await client.user.count(), 1);
  assert.equal(await client.device.count(), 1);
});

test('partner creates a distinct account; invitation is hashed, expiring and single-use', async (t) => {
  const { app, client, setup, invite, redeem, logs } = await fixture(t);
  const alice = await setup();
  const code = await invite(alice.accessToken, 'partner');
  const stored = await client.pairingCode.findUnique({ where: { id: code.id } });
  assert.equal(stored.codeHash, hashSecret(normalizePairingCode(code.code), 'pairing'));
  assert.equal(stored.expiresAt.getTime() - stored.createdAt.getTime(), 600000);
  assert.ok(!JSON.stringify(stored).includes(code.code));
  assert.equal((await redeem(code.code)).statusCode, 400);
  assert.equal((await client.pairingCode.findUnique({ where: { id: code.id } })).consumedAt, null);
  const paired = await redeem(code.code.toLowerCase(), { displayName: 'Bob' });
  assert.equal(paired.statusCode, 201, paired.body);
  const bob = paired.json();
  assert.notEqual(bob.user.id, alice.user.id);
  assert.notEqual(bob.device.id, alice.device.id);
  assert.notEqual(bob.accessToken, alice.accessToken);
  assert.equal((await redeem(code.code, { displayName: 'Third' })).statusCode, 400);
  assert.equal((await app.inject({ method: 'POST', url: '/v1/auth/pairing-codes', headers: bearer(alice.accessToken),
    payload: { purpose: 'partner' } })).statusCode, 409);
  assert.equal(await client.user.count(), 2);
  const list = await app.inject({ url: '/v1/devices', headers: bearer(bob.accessToken) });
  assert.deepEqual(list.json().devices.map((d) => d.id), [bob.device.id]);
  assert.doesNotMatch(list.body, /tokenHash|accessToken/);
  assert.equal((await app.inject({ method: 'DELETE', url: `/v1/devices/${alice.device.id}`, headers: bearer(bob.accessToken) })).statusCode, 404);
  assert.equal((await app.inject({ method: 'DELETE', url: `/v1/auth/pairing-codes/${code.id}`, headers: bearer(bob.accessToken) })).statusCode, 404);
  for (const secret of [code.code, alice.accessToken, bob.accessToken, stored.codeHash]) assert.ok(!logs().includes(secret));
});

test('both accounts can enroll more devices; credentials survive a fresh app/database client', async (t) => {
  const { client, config, setup, invite, redeem } = await fixture(t);
  const alice = await setup();
  const partnerCode = await invite(alice.accessToken, 'partner');
  const bob = (await redeem(partnerCode.code, { displayName: 'Bob' })).json();
  for (const account of [alice, bob]) {
    const code = await invite(account.accessToken, 'device');
    assert.equal((await redeem(code.code, { displayName: 'Override' })).statusCode, 400);
    const paired = await redeem(code.code.replaceAll('-', ''));
    assert.equal(paired.statusCode, 201, paired.body);
    const device = paired.json();
    assert.equal(device.user.id, account.user.id);
    const anotherApp = buildApp(config, createDatabase(config));
    try {
      assert.equal((await anotherApp.inject({ url: '/v1/me', headers: bearer(device.accessToken) })).statusCode, 200);
    } finally { await anotherApp.close(); }
  }
  assert.equal(await client.user.count(), 2);
  const devices = await client.device.findMany();
  assert.equal(devices.length, 4);
  assert.equal(new Set(devices.map((d) => d.tokenHash)).size, 4);
});

test('expired, malformed, unknown and explicitly revoked pairing codes cannot enroll', async (t) => {
  const { app, client, setup, invite, redeem } = await fixture(t);
  const alice = await setup();
  const expired = await invite(alice.accessToken, 'device');
  await client.pairingCode.update({ where: { id: expired.id }, data: { createdAt: new Date(0), expiresAt: new Date(1000) } });
  for (const code of [expired.code, 'invalid', '00000000000000000000000000000000']) {
    const response = await redeem(code);
    assert.equal(response.statusCode, 400);
    assert.equal(response.json().error.code, 'INVALID_PAIRING_CODE');
  }
  const revoked = await invite(alice.accessToken, 'device');
  assert.equal((await app.inject({ method: 'DELETE', url: `/v1/auth/pairing-codes/${revoked.id}`,
    headers: bearer(alice.accessToken) })).statusCode, 204);
  assert.equal((await redeem(revoked.code)).statusCode, 400);
  assert.equal(await client.device.count(), 1);
});

test('revocation removes credentials and invalidates outstanding invitations from that device', async (t) => {
  const { app, client, config, setup, invite, redeem } = await fixture(t);
  const alice = await setup();
  const ownCode = await invite(alice.accessToken, 'device');
  const second = (await redeem(ownCode.code)).json();
  const pending = await invite(alice.accessToken, 'partner');
  const auth = createAuthService(client, config);
  const stalePrincipal = await auth.authenticate(`Bearer ${alice.accessToken}`);
  const revoke = () => app.inject({ method: 'DELETE', url: `/v1/devices/${alice.device.id}`, headers: bearer(second.accessToken) });
  assert.equal((await revoke()).statusCode, 204);
  assert.equal((await revoke()).statusCode, 204);
  const record = await client.device.findUnique({ where: { id: alice.device.id } });
  assert.equal(record.tokenHash, null);
  assert.ok(record.revokedAt);
  assert.equal((await app.inject({ url: '/v1/me', headers: bearer(alice.accessToken) })).statusCode, 401);
  assert.equal((await redeem(pending.code, { displayName: 'Bob' })).statusCode, 400);
  await assert.rejects(auth.createCode(stalePrincipal, 'device'), (error) => error.statusCode === 401);
  assert.equal((await app.inject({ url: '/v1/me', headers: bearer(second.accessToken) })).statusCode, 200);
  assert.equal((await app.inject({ method: 'DELETE', url: `/v1/devices/${second.device.id}`, headers: bearer(second.accessToken) })).statusCode, 204);
  assert.equal((await app.inject({ url: '/v1/devices', headers: bearer(second.accessToken) })).statusCode, 401);
});

test('concurrent code redemption across app instances consumes once; competing partner codes cannot create a third user', async (t) => {
  const { app, client, config, setup, invite, redeem } = await fixture(t);
  const alice = await setup();
  const other = buildApp(config, createDatabase(config));
  try {
    const code = await invite(alice.accessToken, 'device');
    const responses = await Promise.all([redeem(code.code, {}, app), redeem(code.code, {}, other)]);
    assert.deepEqual(responses.map((r) => r.statusCode).sort(), [201, 400]);
    assert.equal(await client.device.count(), 2);
    const first = await invite(alice.accessToken, 'partner');
    const second = await invite(alice.accessToken, 'partner');
    const partners = await Promise.all([redeem(first.code, { displayName: 'Bob' }, app), redeem(second.code, { displayName: 'Other' }, other)]);
    assert.deepEqual(partners.map((r) => r.statusCode).sort(), [201, 409]);
    assert.equal(await client.user.count(), 2);
    const pair = await client.pair.findFirstOrThrow();
    await assert.rejects(client.user.create({ data: { pairId: pair.id, memberSlot: 3, displayName: 'Third' } }));
    await assert.rejects(client.pair.create({ data: { singleton: 2 } }));
  } finally { await other.close(); }
});

test('private APIs reject missing/forged tokens and untrusted enrollment fields', async (t) => {
  const { app, setup, setupToken, client } = await fixture(t);
  for (const url of ['/v1/me', '/v1/devices', '/future-private']) {
    for (const headers of [{}, bearer(newDeviceToken()), { authorization: 'Basic invalid' }]) {
      assert.equal((await app.inject({ url, headers })).statusCode, 401);
    }
  }
  const id = '00000000-0000-4000-8000-000000000000';
  for (const url of [`/v1/devices/${id}`, `/v1/auth/pairing-codes/${id}`]) {
    assert.equal((await app.inject({ method: 'DELETE', url })).statusCode, 401);
  }
  assert.equal((await app.inject({ method: 'POST', url: '/v1/auth/pairing-codes', payload: { purpose: 'partner' } })).statusCode, 401);
  assert.equal((await app.inject({ method: 'POST', url: '/v1/auth/setup', headers: bearer(setupToken),
    payload: { displayName: 'Alice', deviceName: 'Phone', userId: id } })).statusCode, 400);
  assert.equal(await client.user.count(), 0);
  const alice = await setup();
  for (const payload of [{ purpose: 'partner', targetUserId: id }, { purpose: 'invalid' }]) {
    assert.equal((await app.inject({ method: 'POST', url: '/v1/auth/pairing-codes', headers: bearer(alice.accessToken), payload })).statusCode, 400);
  }
  assert.equal((await app.inject({ url: '/unknown', headers: bearer(alice.accessToken) })).statusCode, 404);
});

test('pairing endpoint limits repeated attempts and does not trust forwarded IP headers', async (t) => {
  const { app } = await fixture(t, { AUTH_RATE_LIMIT_MAX: '2' });
  for (let i = 0; i < 3; i++) {
    const response = await app.inject({ method: 'POST', url: '/v1/auth/pair', remoteAddress: '192.0.2.1',
      headers: { 'x-forwarded-for': `192.0.2.${10+i}` }, payload: { code: 'invalid', deviceName: 'Phone' } });
    assert.equal(response.statusCode, i < 2 ? 400 : 429, response.body);
    if (i === 2) {
      assert.equal(response.json().error.code, 'RATE_LIMITED');
      assert.ok(Number(response.headers['retry-after']) > 0);
    }
  }
});
