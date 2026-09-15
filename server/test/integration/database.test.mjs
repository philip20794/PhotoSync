import assert from 'node:assert/strict';
import test from 'node:test';
import { loadConfig } from '../../dist/config.js';
import { createDatabase } from '../../dist/database.js';
import { buildApp } from '../../dist/app.js';

// This suite may change the technical baseline only in the isolated test DB.
if (process.env.PHOTOSYNC_INTEGRATION_TEST !== '1' ||
    new URL(process.env.DATABASE_URL).pathname !== '/photosync_test') {
  throw new Error('Integration tests require the explicit isolated photosync_test database');
}

test('real PostgreSQL: applied migration, ready health, missing baseline and recovery', async (t) => {
  const config = loadConfig();
  const database = createDatabase(config);
  const app = buildApp(config, database);
  t.after(async () => { await app.close(); });
  const migrations = await database.client.$queryRaw`
    SELECT migration_name FROM "_prisma_migrations" WHERE finished_at IS NOT NULL AND rolled_back_at IS NULL`;
  assert.ok(migrations.some((row) => row.migration_name === '20260914000500_media_derivatives'));
  assert.equal((await app.inject('/health')).statusCode, 200);
  await database.client.serviceMetadata.delete({ where: { key: 'schema_version' } });
  try {
    const response = await app.inject('/health');
    assert.equal(response.statusCode, 503);
    assert.equal(response.json().checks.database, 'error');
  } finally {
    await database.client.serviceMetadata.create({ data: { key: 'schema_version', value: '5' } });
  }
  assert.equal((await app.inject('/health')).statusCode, 200);
});

test('unreachable PostgreSQL returns 503 within the configured timeout budget', async (t) => {
  const config = loadConfig({ ...process.env,
    DATABASE_URL: 'postgresql://user:secret@127.0.0.1:1/photosync_test', DATABASE_TIMEOUT_MS: '500',
  });
  const app = buildApp(config, createDatabase(config));
  t.after(async () => { await app.close(); });
  const start = Date.now();
  const response = await app.inject('/health');
  assert.equal(response.statusCode, 503);
  assert.equal(response.json().checks.database, 'error');
  assert.ok(Date.now() - start < 4000);
});
