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
  assert.equal(loadConfig(env).derivativeWorkerEnabled, true);
  assert.equal(loadConfig({ ...env, DERIVATIVE_WORKER_ENABLED: 'false' }).derivativeWorkerEnabled, false);
  assert.ok(Object.isFrozen(loadConfig(env)));
});
for (const [key, value] of [
  ['MAX_UPLOAD_BYTES', '0'], ['MAX_UPLOAD_BYTES', '2147483648'],
  ['DERIVATIVE_WORKER_ENABLED', 'yes'], ['DERIVATIVE_POLL_INTERVAL_MS', '99'],
  ['DERIVATIVE_MAX_ATTEMPTS', '0'], ['DERIVATIVE_TOOL_TIMEOUT_MS', '999'],
  ['SETUP_TOKEN_HASH', 'plaintext'], ['PAIRING_CODE_TTL_SECONDS', '0'], ['PAIRING_CODE_TTL_SECONDS', '3601'], ['AUTH_RATE_LIMIT_MAX', '0'],
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
test('missing required variables are reported without secret values', () => {
  assert.throws(() => loadConfig({ DATABASE_URL: 'sensitive-secret' }), (error) => {
    assert.match(error.message, /DATABASE_URL/);
    assert.match(error.message, /MEDIA_DEV_ROOT/);
    assert.match(error.message, /MEDIA_PROD_ROOT/);
    assert.doesNotMatch(error.message, /sensitive-secret/);
    return true;
  });
});
