import assert from 'node:assert/strict';
import test from 'node:test';
import { loadConfig } from '../../dist/config.js';
import { createDatabase } from '../../dist/database.js';
import { buildApp } from '../../dist/app.js';
import { createLogger } from '../../dist/logger.js';
import { createOperatorService, OperatorCommandError } from '../../dist/auth/operator.js';
import { hashSecret, newSetupToken, normalizePairingCode } from '../../dist/auth/secrets.js';

if (process.env.PHOTOSYNC_INTEGRATION_TEST !== '1' ||
    new URL(process.env.DATABASE_URL).pathname !== '/photosync_test') {
  throw new Error('Operator integration tests require the isolated photosync_test database');
}

const bearer = (token) => ({ authorization: `Bearer ${token}` });

async function fixture(t, extra = {}) {
  const setupToken = newSetupToken();
  const config = loadConfig({
    ...process.env,
    SETUP_TOKEN_HASH: hashSecret(setupToken, 'setup'),
    AUTH_RATE_LIMIT_MAX: '1000',
    ...extra,
  });
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
  const app = buildApp(config, database, createLogger('silent'));
  t.after(async () => { await clear(); await app.close(); });
  const setup = async (displayName = 'Alice') => {
    const response = await app.inject({
      method: 'POST',
      url: '/v1/auth/setup',
      headers: bearer(setupToken),
      payload: { displayName, deviceName: `${displayName} phone` },
    });
    assert.equal(response.statusCode, 201, response.body);
    return response.json();
  };
  const redeem = (code, displayName) => app.inject({
    method: 'POST',
    url: '/v1/auth/pair',
    payload: { code, deviceName: 'Recovered phone', ...(displayName ? { displayName } : {}) },
  });
  return { app, client, config, setup, redeem, operator: createOperatorService(client, config) };
}

test('operator creates a hashed, expiring device code for an existing user without changing domain data', async (t) => {
  const { client, config, setup, operator } = await fixture(t);
  const alice = await setup();
  const before = {
    users: await client.user.count(),
    devices: await client.device.count(),
    albums: await client.album.count(),
    assets: await client.asset.count(),
  };
  const result = await operator.createPairingCode({ userId: alice.user.id });
  assert.deepEqual(result.user, alice.user);
  assert.equal(result.purpose, 'device');
  assert.equal(result.validForSeconds, config.pairingCodeTtlSeconds);
  const stored = await client.pairingCode.findUniqueOrThrow({ where: { id: result.id } });
  assert.equal(stored.targetUserId, alice.user.id);
  assert.equal(stored.createdByDeviceId, null);
  assert.equal(stored.createdByOperator, true);
  assert.equal(stored.codeHash, hashSecret(normalizePairingCode(result.code), 'pairing'));
  assert.equal(stored.expiresAt.getTime() - stored.createdAt.getTime(), config.pairingCodeTtlSeconds * 1000);
  assert.ok(!JSON.stringify(stored).includes(result.code));
  assert.deepEqual({
    users: await client.user.count(),
    devices: await client.device.count(),
    albums: await client.album.count(),
    assets: await client.asset.count(),
  }, before);

  const byName = await operator.createPairingCode({ displayName: 'Alice' });
  assert.equal(byName.user.id, alice.user.id);
});

test('operator rejects an unknown user without creating a pairing code', async (t) => {
  const { client, operator } = await fixture(t);
  await assert.rejects(
    operator.createPairingCode({ userId: '00000000-0000-4000-8000-000000000000' }),
    (error) => error instanceof OperatorCommandError && error.code === 'USER_NOT_FOUND',
  );
  assert.equal(await client.pairingCode.count(), 0);
});

test('operator rejects an ambiguous display name instead of guessing', async (t) => {
  const { app, client, setup, operator, redeem } = await fixture(t);
  const alice = await setup('Same name');
  const invitation = await app.inject({
    method: 'POST',
    url: '/v1/auth/pairing-codes',
    headers: bearer(alice.accessToken),
    payload: { purpose: 'partner' },
  });
  assert.equal(invitation.statusCode, 201, invitation.body);
  assert.equal((await redeem(invitation.json().code, 'Same name')).statusCode, 201);
  const before = await client.pairingCode.count();
  await assert.rejects(
    operator.createPairingCode({ displayName: 'Same name' }),
    (error) => error instanceof OperatorCommandError && error.code === 'AMBIGUOUS_USER',
  );
  assert.equal(await client.pairingCode.count(), before);
});

test('operator recovery code can be redeemed only once', async (t) => {
  const { client, setup, operator, redeem } = await fixture(t);
  const alice = await setup();
  const code = await operator.createPairingCode({ userId: alice.user.id });
  assert.equal((await redeem(code.code)).statusCode, 201);
  const second = await redeem(code.code);
  assert.equal(second.statusCode, 400);
  assert.equal(second.json().error.code, 'INVALID_PAIRING_CODE');
  assert.equal(await client.device.count(), 2);
  assert.ok((await client.pairingCode.findUniqueOrThrow({ where: { id: code.id } })).consumedAt);
});

test('expired operator recovery code cannot be redeemed', async (t) => {
  const { client, setup, operator, redeem } = await fixture(t);
  const alice = await setup();
  const code = await operator.createPairingCode({ userId: alice.user.id });
  await client.pairingCode.update({
    where: { id: code.id },
    data: { createdAt: new Date(0), expiresAt: new Date(1000) },
  });
  const response = await redeem(code.code);
  assert.equal(response.statusCode, 400);
  assert.equal(response.json().error.code, 'INVALID_PAIRING_CODE');
  assert.equal(await client.device.count(), 1);
});
