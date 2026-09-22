import assert from 'node:assert/strict';
import test from 'node:test';
import { loadConfig, ConfigurationError } from '../dist/config.js';

const env = {
  DATABASE_URL: 'postgresql://user:secret@localhost:5432/photosync',
  MEDIA_DEV_ROOT: '/data/dev', MEDIA_PROD_ROOT: '/data/prod',
};
test('configuration defaults and environment select distinct media roots', () => {
  assert.equal(loadConfig(env).mediaRoot, '/data/dev');
  assert.equal(loadConfig({ ...env, NODE_ENV: 'production' }).mediaRoot, '/data/prod');
  assert.equal(loadConfig({ ...env, NODE_ENV: 'test' }).mediaRoot, '/data/dev');
  assert.equal(loadConfig(env).port, 3000);
  assert.equal(loadConfig(env).apiRequestTimeoutMs, 10000);
  assert.equal(loadConfig(env).uploadRequestTimeoutMs, 3600000);
  assert.equal(loadConfig(env).uploadLeaseMs, 120000);
  assert.equal(loadConfig(env).uploadSessionTtlMs, 604800000);
  assert.equal(loadConfig(env).maxUploadChunkBytes, 8388608);
  assert.equal(loadConfig(env).derivativeWorkerEnabled, true);
  assert.equal(loadConfig(env).fcmEnabled, false);
  assert.equal(loadConfig(env).syncChangeRetentionDays, 90);
  assert.equal(loadConfig({ ...env, DERIVATIVE_WORKER_ENABLED: 'false' }).derivativeWorkerEnabled, false);
  assert.equal(loadConfig({ ...env, FCM_ENABLED: 'true' }).fcmEnabled, true);
  assert.ok(Object.isFrozen(loadConfig(env)));
});
for (const [key, value] of [
  ['MAX_UPLOAD_BYTES', '0'], ['MAX_UPLOAD_BYTES', '2147483648'],
  ['API_REQUEST_TIMEOUT_MS', '999'], ['UPLOAD_REQUEST_TIMEOUT_MS', '9999'],
  ['UPLOAD_LEASE_MS', '4999'], ['UPLOAD_RECOVERY_INTERVAL_MS', '999'],
  ['UPLOAD_SESSION_TTL_MS', '59999'], ['MAX_UPLOAD_CHUNK_BYTES', '65535'],
  ['DERIVATIVE_WORKER_ENABLED', 'yes'], ['FCM_ENABLED', 'yes'], ['SYNC_CHANGE_RETENTION_DAYS', '6'], ['DERIVATIVE_POLL_INTERVAL_MS', '99'],
  ['DERIVATIVE_MAX_ATTEMPTS', '0'], ['DERIVATIVE_TOOL_TIMEOUT_MS', '999'], ['DERIVATIVE_BACKLOG_WARNING', '0'],
  ['AUTH_RATE_LIMIT_MAX', '0'],
  ['PORT', ''], ['PORT', '0'], ['PORT', '65536'], ['PORT', '3.1'], ['PORT', 'abc'],
  ['LOG_LEVEL', 'verbose'], ['NODE_ENV', 'prod'], ['DATABASE_URL', 'https://secret.invalid'],
  ['DATABASE_URL', 'postgresql://localhost'], ['MEDIA_DEV_ROOT', 'relative'],
  ['MEDIA_PROD_ROOT', '/data/dev'], ['MEDIA_PROD_ROOT', '/data/dev/originals'],
  ['MEDIA_PROD_ROOT', '/data'], ['DATABASE_TIMEOUT_MS', '0'],
]) {
  test(`invalid ${key}=${value} fails before startup`, () => {
    assert.throws(() => loadConfig({ ...env, [key]: value }), ConfigurationError);
  });
}
test('timeout and recovery relationships are validated', () => {
  assert.throws(() => loadConfig({ ...env, API_REQUEST_TIMEOUT_MS: '20000', UPLOAD_REQUEST_TIMEOUT_MS: '10000' }), ConfigurationError);
  assert.throws(() => loadConfig({ ...env, UPLOAD_LEASE_MS: '5000', UPLOAD_RECOVERY_INTERVAL_MS: '5000' }), ConfigurationError);
  assert.throws(() => loadConfig({ ...env, UPLOAD_LEASE_MS: '60000', UPLOAD_SESSION_TTL_MS: '60000' }), ConfigurationError);
});

test('missing required variables are reported without secret values', () => {
  assert.throws(() => loadConfig({ DATABASE_URL: 'sensitive-secret' }), (error) => {
    assert.match(error.message, /DATABASE_URL/);
    assert.match(error.message, /MEDIA_DEV_ROOT/);
    assert.match(error.message, /MEDIA_PROD_ROOT/);
    assert.doesNotMatch(error.message, /sensitive-secret/);
    return true;
  });
});

test('optional catalogue root stays disabled by default and must be separate from media roots', () => {
  assert.equal(loadConfig(env).catalogRoot, undefined);
  assert.equal(loadConfig({ ...env, CATALOG_ROOT: '/data/catalogue' }).catalogRoot, '/data/catalogue');
  assert.throws(() => loadConfig({ ...env, CATALOG_ROOT: '/data/dev/catalogue' }), ConfigurationError);
});
