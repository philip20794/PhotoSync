import assert from 'node:assert/strict';
import test from 'node:test';
import { loadConfig } from '../../dist/config.js';
import { createDatabase } from '../../dist/database.js';
import { createOperatorService, OperatorCommandError } from '../../dist/auth/operator.js';
import { verifyPassword } from '../../dist/auth/passwords.js';

if (process.env.PHOTOSYNC_INTEGRATION_TEST !== '1' ||
    new URL(process.env.DATABASE_URL).pathname !== '/photosync_test') {
  throw new Error('Operator integration tests require the isolated photosync_test database');
}

async function fixture(t) {
  const database = createDatabase(loadConfig(process.env));
  const client = database.client;
  const clear = async () => {
    await client.asset.deleteMany();
    await client.album.deleteMany();
    await client.device.deleteMany();
    await client.user.deleteMany();
    await client.pair.updateMany({ data: { setupCompletedAt: null } });
  };
  await clear();
  t.after(async () => { await clear(); await database.close(); });
  return { client, operator: createOperatorService(client) };
}

test('operator creates and lists exactly two normalized users', async (t) => {
  const { client, operator } = await fixture(t);
  const philip = await operator.createUser(' Philip ');
  const runa = await operator.createUser('Runa');
  assert.equal(philip.username, 'philip');
  assert.equal(runa.username, 'runa');
  assert.deepEqual((await operator.listUsers()).map((user) => user.username), ['philip', 'runa']);
  assert.equal(await client.user.count(), 2);
  await assert.rejects(operator.createUser('PHILIP'),
    (error) => error instanceof OperatorCommandError && error.code === 'USER_EXISTS');
  await assert.rejects(operator.createUser('Third'),
    (error) => error instanceof OperatorCommandError && error.code === 'PAIR_FULL');
});

test('set-password stores only Argon2id hash and replaces it', async (t) => {
  const { client, operator } = await fixture(t);
  const user = await operator.createUser('Philip');
  await operator.setPassword('PHILIP', 'first-test-password');
  const first = await client.user.findUniqueOrThrow({ where: { id: user.id } });
  assert.match(first.passwordHash, /^\$argon2id\$/);
  assert.ok(await verifyPassword(first.passwordHash, 'first-test-password'));
  assert.ok(!JSON.stringify(first).includes('first-test-password'));
  await operator.setPassword('philip', 'second-test-password');
  const second = await client.user.findUniqueOrThrow({ where: { id: user.id } });
  assert.notEqual(second.passwordHash, first.passwordHash);
  assert.ok(await verifyPassword(second.passwordHash, 'second-test-password'));
  assert.equal(await verifyPassword(second.passwordHash, 'first-test-password'), false);
});

test('operator lists and revokes devices', async (t) => {
  const { client, operator } = await fixture(t);
  const user = await operator.createUser('Philip');
  const device = await client.device.create({
    data: { userId: user.id, name: 'Lost phone', tokenHash: 'a'.repeat(64) },
  });
  assert.deepEqual((await operator.listDevices('PHILIP')).map((item) => item.id), [device.id]);
  await operator.revokeDevice(device.id);
  const revoked = await client.device.findUniqueOrThrow({ where: { id: device.id } });
  assert.equal(revoked.tokenHash, null);
  assert.ok(revoked.revokedAt);
  await assert.rejects(operator.revokeDevice('00000000-0000-4000-8000-000000000000'),
    (error) => error instanceof OperatorCommandError && error.code === 'DEVICE_NOT_FOUND');
});
