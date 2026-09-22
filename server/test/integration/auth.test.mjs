import assert from 'node:assert/strict';
import test from 'node:test';
import { loadConfig } from '../../dist/config.js';
import { createDatabase } from '../../dist/database.js';
import { buildApp } from '../../dist/app.js';
import { createLogger } from '../../dist/logger.js';
import { createOperatorService } from '../../dist/auth/operator.js';
import { hashSecret } from '../../dist/auth/secrets.js';

if (process.env.PHOTOSYNC_INTEGRATION_TEST !== '1' ||
    new URL(process.env.DATABASE_URL).pathname !== '/photosync_test') {
  throw new Error('Auth integration tests require the isolated photosync_test database');
}

const bearer = (token) => ({ authorization: `Bearer ${token}` });

async function fixture(t, extra = {}) {
  const config = loadConfig({ ...process.env, AUTH_RATE_LIMIT_MAX: '1000', ...extra });
  const database = createDatabase(config);
  const client = database.client;
  const clear = async () => {
    await client.asset.deleteMany();
    await client.album.deleteMany();
    await client.device.deleteMany();
    await client.user.deleteMany();
    await client.pair.updateMany({ data: { setupCompletedAt: null } });
  };
  await clear();
  const operator = createOperatorService(client);
  await operator.createUser('Philip');
  await operator.createUser('Runa');
  await operator.setPassword('Philip', 'philip-test-password');
  await operator.setPassword('Runa', 'runa-test-password');
  let logs = '';
  const app = buildApp(config, database, createLogger('info', { write(chunk) { logs += chunk; } }));
  t.after(async () => { await clear(); await app.close(); });
  const login = (username, password, deviceName = 'Test phone') => app.inject({
    method: 'POST',
    url: '/v1/auth/login',
    payload: { username, password, deviceName },
  });
  return { app, client, operator, login, logs: () => logs };
}

test('Philip and Runa log in to their existing normalized accounts', async (t) => {
  const { app, client, login, logs } = await fixture(t);
  const philipResponse = await login('  PHILIP  ', 'philip-test-password', 'Philip Pixel');
  const runaResponse = await login('Runa', 'runa-test-password', 'Runa phone');
  assert.equal(philipResponse.statusCode, 201, philipResponse.body);
  assert.equal(runaResponse.statusCode, 201, runaResponse.body);
  const philip = philipResponse.json();
  const runa = runaResponse.json();
  assert.equal(philip.user.username, 'philip');
  assert.equal(runa.user.username, 'runa');
  assert.notEqual(philip.user.id, runa.user.id);
  assert.equal(await client.user.count(), 2);
  assert.equal(await client.device.count(), 2);
  assert.equal(
    (await client.device.findUniqueOrThrow({ where: { id: philip.device.id } })).tokenHash,
    hashSecret(philip.accessToken, 'device'),
  );
  assert.equal((await app.inject({ url: '/v1/me', headers: bearer(philip.accessToken) })).statusCode, 200);
  assert.ok(!logs().includes('philip-test-password'));
  assert.ok(!logs().includes(philip.accessToken));
});

test('wrong and unknown passwords use the same unauthorized response', async (t) => {
  const { login, client } = await fixture(t);
  for (const [username, password] of [
    ['Philip', 'wrong-password'],
    ['Nobody', 'wrong-password'],
  ]) {
    const response = await login(username, password);
    assert.equal(response.statusCode, 401);
    assert.equal(response.json().error.code, 'UNAUTHORIZED');
    assert.equal(response.json().error.message, 'Invalid username or password');
  }
  assert.equal(await client.device.count(), 0);
});

test('reinstall and multiple devices create devices, never users', async (t) => {
  const { login, client } = await fixture(t);
  const first = (await login('Philip', 'philip-test-password', 'Old phone')).json();
  const reinstalled = (await login('Philip', 'philip-test-password', 'Reinstalled phone')).json();
  const tablet = (await login('Philip', 'philip-test-password', 'Tablet')).json();
  assert.equal(first.user.id, reinstalled.user.id);
  assert.equal(reinstalled.user.id, tablet.user.id);
  assert.equal(new Set([first.device.id, reinstalled.device.id, tablet.device.id]).size, 3);
  assert.equal(await client.user.count(), 2);
  assert.equal(await client.device.count({ where: { userId: first.user.id } }), 3);
});

test('a device can be revoked without invalidating another device', async (t) => {
  const { app, login, client } = await fixture(t);
  const first = (await login('Philip', 'philip-test-password', 'Lost phone')).json();
  const second = (await login('Philip', 'philip-test-password', 'New phone')).json();
  const revoke = await app.inject({
    method: 'DELETE',
    url: `/v1/devices/${first.device.id}`,
    headers: bearer(second.accessToken),
  });
  assert.equal(revoke.statusCode, 204);
  assert.equal((await app.inject({ url: '/v1/me', headers: bearer(first.accessToken) })).statusCode, 401);
  assert.equal((await app.inject({ url: '/v1/me', headers: bearer(second.accessToken) })).statusCode, 200);
  const stored = await client.device.findUniqueOrThrow({ where: { id: first.device.id } });
  assert.equal(stored.tokenHash, null);
  assert.ok(stored.revokedAt);
});

test('login endpoint is rate limited', async (t) => {
  const { app } = await fixture(t, { AUTH_RATE_LIMIT_MAX: '2' });
  const request = () => app.inject({
    method: 'POST',
    url: '/v1/auth/login',
    remoteAddress: '192.0.2.10',
    payload: { username: 'Philip', password: 'wrong-password', deviceName: 'Phone' },
  });
  assert.equal((await request()).statusCode, 401);
  assert.equal((await request()).statusCode, 401);
  const limited = await request();
  assert.equal(limited.statusCode, 429);
  assert.equal(limited.json().error.code, 'RATE_LIMITED');
});
