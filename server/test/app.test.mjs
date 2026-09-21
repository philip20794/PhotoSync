import assert from 'node:assert/strict';
import { mkdtemp, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import { buildApp } from '../dist/app.js';
import { loadConfig } from '../dist/config.js';
import { createLogger } from '../dist/logger.js';

async function fixture(t, check = async () => {}) {
  const root = await mkdtemp(join(tmpdir(), 'photosync-unit-'));
  const config = loadConfig({
    NODE_ENV: 'test', LOG_LEVEL: 'silent', DATABASE_URL: 'postgresql://user:secret@localhost:5432/test',
    MEDIA_DEV_ROOT: root, MEDIA_PROD_ROOT: '/unused-production', DERIVATIVE_WORKER_ENABLED: 'false',
  });
  let closed = false;
  let output = '';
  const logger = createLogger('info', { write(chunk) { output += chunk; } });
  const app = buildApp(config, { check, async close() { closed = true; } }, logger);
  t.after(async () => { await app.close(); await rm(root, { recursive: true, force: true }); });
  return { app, root, logs: () => output, closed: () => closed };
}

test('health is ready with dependencies, adds request ID and forbids caching', async (t) => {
  const { app } = await fixture(t);
  const response = await app.inject({ url: '/health', headers: { 'x-request-id': 'untrusted' } });
  assert.equal(response.statusCode, 200);
  assert.deepEqual(response.json(), {
    status: 'ok',
    checks: { database: 'ok', media: 'ok', derivatives: 'disabled', originals: 'disabled' },
    derivatives: { status: 'disabled', worker: 'disabled' },
    originals: { status: 'disabled', errors: 0, checkedInCycle: 0, lastCycleAt: null },
  });
  assert.equal(response.headers['cache-control'], 'no-store');
  assert.match(response.headers['x-request-id'], /^[0-9a-f-]{36}$/);
  assert.notEqual(response.headers['x-request-id'], 'untrusted');
});

test('liveness stays independent from readiness dependencies', async (t) => {
  const { app, root } = await fixture(t, async () => { throw new Error('database down'); });
  await rm(root, { recursive: true });
  const response = await app.inject('/health/live');
  assert.equal(response.statusCode, 200);
  assert.deepEqual(response.json(), { status: 'ok' });
});

test('health reports database failure without exposing underlying secrets', async (t) => {
  const { app, logs } = await fixture(t, async () => { throw new Error('postgresql://user:secret@db/private'); });
  const response = await app.inject('/health');
  assert.equal(response.statusCode, 503);
  assert.equal(response.json().checks.database, 'error');
  assert.doesNotMatch(response.body + logs(), /secret|postgresql/);
});

test('health detects removed media directory and recovers after recreation', async (t) => {
  const { app, root } = await fixture(t);
  await rm(root, { recursive: true });
  const response = await app.inject('/health');
  assert.equal(response.statusCode, 503);
  assert.equal(response.json().checks.media, 'error');
  const { mkdir } = await import('node:fs/promises');
  await mkdir(root);
  assert.equal((await app.inject('/health')).statusCode, 200);
});

test('regular file cannot serve as media directory', async (t) => {
  const { app, root } = await fixture(t);
  await rm(root, { recursive: true });
  await writeFile(root, 'not a directory');
  assert.equal((await app.inject('/health')).statusCode, 503);
});

test('unknown route has central envelope and no raw URL in logs', async (t) => {
  const { app, logs } = await fixture(t);
  const response = await app.inject('/private-secret?token=query-secret');
  assert.equal(response.statusCode, 401);
  assert.equal(response.json().error.code, 'UNAUTHORIZED');
  assert.equal(response.json().error.requestId, response.headers['x-request-id']);
  assert.doesNotMatch(logs(), /private-secret|query-secret/);
});

test('unexpected errors are centrally handled and logging omits request secrets', async (t) => {
  const { app, logs } = await fixture(t);
  app.get('/explode', { config: { public: true } }, async () => { throw new Error('sensitive-database-secret'); });
  const response = await app.inject({ url: '/explode?token=query-secret', headers: {
    authorization: 'Bearer header-secret', cookie: 'session=cookie-secret',
  } });
  assert.equal(response.statusCode, 500);
  assert.equal(response.json().error.message, 'Internal server error');
  assert.doesNotMatch(response.body + logs(), /database-secret|query-secret|header-secret|cookie-secret/);
  const entries = logs().trim().split('\n').map(JSON.parse);
  assert.ok(entries.some((entry) => entry.event === 'request_failed' && entry.reqId));
});

test('malformed JSON and schema errors retain 400 with safe envelope', async (t) => {
  const { app } = await fixture(t);
  app.post('/validate', { config: { public: true }, schema: { body: {
    type: 'object', required: ['name'], properties: { name: { type: 'string' } },
  } } }, async () => ({}));
  for (const payload of ['{bad', '{}']) {
    const response = await app.inject({ method: 'POST', url: '/validate',
      headers: { 'content-type': 'application/json' }, payload });
    assert.equal(response.statusCode, 400);
    assert.equal(response.json().error.code, 'INVALID_REQUEST');
  }
});

test('closing the app closes the database pool', async (t) => {
  const { app, closed } = await fixture(t);
  await app.ready();
  await app.close();
  assert.equal(closed(), true);
});

test('new routes are protected by default before handler execution', async (t) => {
  const { app } = await fixture(t);
  let executed = false;
  app.get('/future-private-api', async () => { executed = true; return { private: 'data' }; });
  const response = await app.inject('/future-private-api');
  assert.equal(response.statusCode, 401);
  assert.equal(response.headers['www-authenticate'], 'Bearer');
  assert.equal(executed, false);
  assert.doesNotMatch(response.body, /private/);
});
