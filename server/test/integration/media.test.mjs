import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { access, mkdir, readdir, rm, stat } from 'node:fs/promises';
import { resolve } from 'node:path';
import { Readable } from 'node:stream';
import test from 'node:test';
import { buildApp } from '../../dist/app.js';
import { createAuthService } from '../../dist/auth/service.js';
import { hashSecret, newSetupToken } from '../../dist/auth/secrets.js';
import { loadConfig } from '../../dist/config.js';
import { createDatabase } from '../../dist/database.js';
import { createMediaService } from '../../dist/media/service.js';
import { createDerivativeWorker } from '../../dist/media/derivatives.js';
import { createLogger } from '../../dist/logger.js';
import sharp from 'sharp';

if (process.env.PHOTOSYNC_INTEGRATION_TEST !== '1' ||
    new URL(process.env.DATABASE_URL).pathname !== '/photosync_test') {
  throw new Error('Media integration tests require the isolated photosync_test database');
}
const bearer = (token) => ({ authorization: 'Bearer ' + token });
const call = (app, token, method, url, payload, headers = {}) => app.inject({
  method, url, headers: { ...bearer(token), ...headers },
  ...(payload === undefined ? {} : { payload }),
});

async function fixture(t) {
  const setupToken = newSetupToken();
  const config = loadConfig({
    ...process.env,
    SETUP_TOKEN_HASH: hashSecret(setupToken, 'setup'),
    AUTH_RATE_LIMIT_MAX: '1000',
    MAX_UPLOAD_BYTES: '1048576',
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
  await rm(config.mediaRoot, { recursive: true, force: true });
  await mkdir(config.mediaRoot, { recursive: true });
  const app = buildApp(config, database);
  t.after(async () => {
    await clear();
    await app.close();
    await rm(config.mediaRoot, { recursive: true, force: true });
    await mkdir(config.mediaRoot, { recursive: true });
  });
  const setup = await call(app, setupToken, 'POST', '/v1/auth/setup',
    { displayName: 'Alice', deviceName: 'Alice phone' });
  assert.equal(setup.statusCode, 201, setup.body);
  const alice = setup.json();
  const invitation = await call(app, alice.accessToken, 'POST', '/v1/auth/pairing-codes',
    { purpose: 'partner' });
  assert.equal(invitation.statusCode, 201, invitation.body);
  const paired = await app.inject({ method: 'POST', url: '/v1/auth/pair', payload: {
    code: invitation.json().code, displayName: 'Bob', deviceName: 'Bob phone',
  } });
  assert.equal(paired.statusCode, 201, paired.body);
  return { app, client, config, alice, bob: paired.json() };
}

async function createAlbum(app, token, clientAlbumId = 'camera') {
  const response = await call(app, token, 'POST', '/v1/albums',
    { clientAlbumId, title: 'Camera' });
  assert.equal(response.statusCode, 201, response.body);
  return response.json();
}

async function createAsset(app, token, albumId, bytes, metadata = {}) {
  const response = await call(app, token, 'POST', '/v1/albums/' + albumId + '/assets', {
    originalFileName: 'Urlaub ü 2026.jpg',
    mimeType: 'image/jpeg',
    capturedAt: '2026-08-04T12:34:56.789+02:00',
    fileSize: String(bytes.length),
    width: 4032,
    height: 3024,
    ...metadata,
  });
  assert.equal(response.statusCode, 201, response.body);
  return response.json();
}

test('authenticated upload is atomic and download preserves bytes and SHA-256', async (t) => {
  const { app, client, config, alice, bob } = await fixture(t);
  const album = await createAlbum(app, alice.accessToken);
  assert.equal(album.owner.id, alice.user.id);
  assert.equal(album.sourceDeviceId, alice.device.id);

  const partnerAlbums = await call(app, bob.accessToken, 'GET', '/v1/albums');
  assert.equal(partnerAlbums.statusCode, 200);
  assert.equal(partnerAlbums.json().albums[0].id, album.id);
  assert.equal(partnerAlbums.json().albums[0].ownedByMe, false);
  assert.equal(partnerAlbums.json().albums[0].sourceDeviceId, undefined);
  assert.equal(partnerAlbums.json().albums[0].clientAlbumId, undefined);

  const bytes = Buffer.from([80, 104, 111, 116, 111, 83, 121, 110, 99, 0, 1, 255]);
  const asset = await createAsset(app, alice.accessToken, album.id, bytes);
  assert.equal(asset.status, 'pending');
  assert.equal(asset.sha256, null);
  assert.equal(asset.storagePath, undefined);
  assert.equal((await call(app, bob.accessToken, 'GET', '/v1/assets/' + asset.id)).statusCode, 404);
  assert.deepEqual((await call(app, bob.accessToken, 'GET', '/v1/albums/' + album.id + '/assets')).json().assets, []);
  assert.equal((await call(app, bob.accessToken, 'GET', '/v1/assets/' + asset.id + '/original')).statusCode, 404);

  const upload = await call(app, alice.accessToken, 'PUT', '/v1/assets/' + asset.id + '/original',
    bytes, { 'content-type': 'application/octet-stream', 'content-length': String(bytes.length) });
  assert.equal(upload.statusCode, 200, upload.body);
  const expectedHash = createHash('sha256').update(bytes).digest('hex');
  assert.equal(upload.json().status, 'ready');
  assert.equal(upload.json().sha256, expectedHash);

  const stored = await client.asset.findUniqueOrThrow({ where: { id: asset.id } });
  assert.equal(stored.sha256, expectedHash);
  assert.equal(stored.status, 'ready');
  assert.equal((await stat(resolve(config.mediaRoot, stored.storagePath))).size, bytes.length);
  assert.deepEqual(await readdir(resolve(config.mediaRoot, 'uploads')), []);

  for (const token of [alice.accessToken, bob.accessToken]) {
    const download = await call(app, token, 'GET', '/v1/assets/' + asset.id + '/original');
    assert.equal(download.statusCode, 200, download.body);
    assert.deepEqual(download.rawPayload, bytes);
    assert.equal(createHash('sha256').update(download.rawPayload).digest('hex'), expectedHash);
    assert.equal(download.headers.etag, '"' + expectedHash + '"');
    assert.equal(download.headers['content-type'], 'image/jpeg');
    assert.equal(download.headers['content-length'], String(bytes.length));
    assert.match(download.headers['content-disposition'], /filename\*=UTF-8''Urlaub%20%C3%BC%202026\.jpg/);
  }
  const partnerMetadata = await call(app, bob.accessToken, 'GET', '/v1/assets/' + asset.id);
  assert.equal(partnerMetadata.statusCode, 200);
  assert.equal(partnerMetadata.json().sha256, expectedHash);
  assert.equal(partnerMetadata.json().storagePath, undefined);
  assert.equal((await call(app, alice.accessToken, 'PUT', '/v1/assets/' + asset.id + '/original',
    bytes, { 'content-type': 'application/octet-stream', 'content-length': String(bytes.length) })).statusCode, 409);
});

test('partial upload is failed, cleaned and never downloadable', async (t) => {
  const { app, client, config, alice, bob } = await fixture(t);
  const album = await createAlbum(app, alice.accessToken, 'partial');
  const bytes = Buffer.from('partial original');
  const asset = await createAsset(app, alice.accessToken, album.id, bytes);
  const principal = await createAuthService(client, config).authenticate('Bearer ' + alice.accessToken);
  const media = createMediaService(client, config);
  await assert.rejects(
    media.uploadOriginal(principal, asset.id, Readable.from([bytes.subarray(0, bytes.length - 2)]), bytes.length),
    (error) => error.statusCode === 422 && error.code === 'UPLOAD_SIZE_MISMATCH',
  );
  const failed = await client.asset.findUniqueOrThrow({ where: { id: asset.id } });
  assert.equal(failed.status, 'failed');
  assert.equal(failed.sha256, null);
  await assert.rejects(access(resolve(config.mediaRoot, failed.storagePath)));
  assert.deepEqual(await readdir(resolve(config.mediaRoot, 'uploads')), []);
  assert.equal((await call(app, alice.accessToken, 'GET', '/v1/assets/' + asset.id + '/original')).statusCode, 404);
  assert.equal((await call(app, bob.accessToken, 'GET', '/v1/assets/' + asset.id)).statusCode, 404);
});

test('ownership, authentication and media metadata validation are enforced', async (t) => {
  const { app, alice, bob } = await fixture(t);
  const album = await createAlbum(app, alice.accessToken, 'authorization');
  const bytes = Buffer.from('private original');
  const metadata = {
    originalFileName: 'private.jpg', mimeType: 'image/jpeg',
    fileSize: String(bytes.length), width: 1, height: 1,
  };
  assert.equal((await call(app, bob.accessToken, 'POST', '/v1/albums/' + album.id + '/assets', metadata)).statusCode, 404);
  assert.equal((await app.inject({ method: 'POST', url: '/v1/albums/' + album.id + '/assets', payload: metadata })).statusCode, 401);
  assert.equal((await app.inject({ method: 'GET', url: '/v1/albums' })).statusCode, 401);

  const asset = await createAsset(app, alice.accessToken, album.id, bytes, metadata);
  assert.equal((await call(app, bob.accessToken, 'PUT', '/v1/assets/' + asset.id + '/original',
    bytes, { 'content-type': 'application/octet-stream', 'content-length': String(bytes.length) })).statusCode, 404);
  assert.equal((await call(app, alice.accessToken, 'PUT', '/v1/assets/' + asset.id + '/original',
    bytes, { 'content-type': 'image/jpeg', 'content-length': String(bytes.length) })).statusCode, 415);
  assert.equal((await call(app, alice.accessToken, 'PUT', '/v1/assets/' + asset.id + '/original',
    bytes, { 'content-type': 'application/octet-stream', 'content-length': String(bytes.length - 1) })).statusCode, 422);
  assert.equal((await call(app, alice.accessToken, 'GET', '/v1/assets/' + asset.id)).json().status, 'pending');

  const video = { originalFileName: 'clip.mp4', mimeType: 'video/mp4',
    fileSize: '10', width: 1920, height: 1080 };
  assert.equal((await call(app, alice.accessToken, 'POST', '/v1/albums/' + album.id + '/assets', video)).statusCode, 400);
  assert.equal((await call(app, alice.accessToken, 'POST', '/v1/albums/' + album.id + '/assets',
    { ...video, durationMillis: '1234' })).statusCode, 201);
  assert.equal((await call(app, alice.accessToken, 'POST', '/v1/albums/' + album.id + '/assets',
    { ...video, mimeType: 'image/jpeg', durationMillis: '1234' })).statusCode, 400);
  assert.equal((await call(app, alice.accessToken, 'POST', '/v1/albums/' + album.id + '/assets',
    { ...video, durationMillis: '1234', fileSize: '1048577' })).statusCode, 413);
  assert.equal((await call(app, alice.accessToken, 'POST', '/v1/albums/' + album.id + '/assets',
    { ...video, durationMillis: '1234', originalFileName: '../bad' + String.fromCharCode(10) + 'name' })).statusCode, 400);
});

test('sharing is reversible and partner can only access shared albums', async (t) => {
  const { app, alice, bob } = await fixture(t);
  const album = await createAlbum(app, alice.accessToken, 'private-switch');
  assert.equal(album.shared, true);

  const hidden = await call(app, alice.accessToken, 'PATCH', '/v1/albums/' + album.id, { shared: false });
  assert.equal(hidden.statusCode, 200, hidden.body);
  assert.equal(hidden.json().shared, false);
  assert.equal((await call(app, bob.accessToken, 'GET', '/v1/albums/' + album.id)).statusCode, 404);
  assert.deepEqual((await call(app, bob.accessToken, 'GET', '/v1/albums')).json().albums, []);
  assert.equal((await call(app, alice.accessToken, 'GET', '/v1/albums/' + album.id)).json().shared, false);

  const relinked = await call(app, alice.accessToken, 'POST', '/v1/albums', {
    clientAlbumId: 'private-switch', title: 'Camera renamed',
  });
  assert.equal(relinked.statusCode, 201, relinked.body);
  assert.equal(relinked.json().id, album.id);
  assert.equal(relinked.json().shared, true);
  assert.equal((await call(app, bob.accessToken, 'GET', '/v1/albums/' + album.id)).statusCode, 200);
});

test('stable client identity and SHA make upload retry idempotent', async (t) => {
  const { app, client, config, alice } = await fixture(t);
  const album = await createAlbum(app, alice.accessToken, 'idempotent');
  const bytes = Buffer.from('stable original bytes');
  const expectedSha256 = createHash('sha256').update(bytes).digest('hex');
  const metadata = { clientAssetId: 'external_primary:42:7', expectedSha256 };

  const first = await createAsset(app, alice.accessToken, album.id, bytes, metadata);
  const repeated = await createAsset(app, alice.accessToken, album.id, bytes, metadata);
  assert.equal(repeated.id, first.id);
  assert.equal(repeated.status, 'pending');

  const principal = await createAuthService(client, config).authenticate('Bearer ' + alice.accessToken);
  const media = createMediaService(client, config);
  await assert.rejects(
    media.uploadOriginal(principal, first.id, Readable.from([bytes.subarray(0, bytes.length - 1)]), bytes.length),
    (error) => error.statusCode === 422 && error.code === 'UPLOAD_SIZE_MISMATCH',
  );
  assert.equal((await client.asset.findUniqueOrThrow({ where: { id: first.id } })).status, 'failed');

  const reset = await createAsset(app, alice.accessToken, album.id, bytes, metadata);
  assert.equal(reset.id, first.id);
  assert.equal(reset.status, 'pending');
  const uploaded = await call(app, alice.accessToken, 'PUT', '/v1/assets/' + first.id + '/original',
    bytes, { 'content-type': 'application/octet-stream', 'content-length': String(bytes.length) });
  assert.equal(uploaded.statusCode, 200, uploaded.body);
  assert.equal(uploaded.json().sha256, expectedSha256);

  const afterLostResponse = await createAsset(app, alice.accessToken, album.id, bytes, metadata);
  assert.equal(afterLostResponse.id, first.id);
  assert.equal(afterLostResponse.status, 'ready');
  assert.equal((await call(app, alice.accessToken, 'PUT', '/v1/assets/' + first.id + '/original',
    bytes, { 'content-type': 'application/octet-stream', 'content-length': String(bytes.length) })).statusCode, 409);

  const wrongHash = '0'.repeat(64);
  const conflictResponse = await call(app, alice.accessToken, 'POST', '/v1/albums/' + album.id + '/assets', {
    originalFileName: 'same-id.jpg', mimeType: 'image/jpeg', fileSize: String(bytes.length),
    width: 1, height: 1, clientAssetId: metadata.clientAssetId, expectedSha256: wrongHash,
  });
  assert.equal(conflictResponse.statusCode, 409);

  const wrongHashAsset = await createAsset(app, alice.accessToken, album.id, bytes, {
    clientAssetId: 'external_primary:99:1', expectedSha256: wrongHash,
  });
  const rejectedBytes = await call(app, alice.accessToken, 'PUT', '/v1/assets/' + wrongHashAsset.id + '/original',
    bytes, { 'content-type': 'application/octet-stream', 'content-length': String(bytes.length) });
  assert.equal(rejectedBytes.statusCode, 422);
  assert.equal(rejectedBytes.json().error.code, 'UPLOAD_HASH_MISMATCH');
  assert.equal((await client.asset.findUniqueOrThrow({ where: { id: wrongHashAsset.id } })).status, 'failed');
});


test('image derivatives are asynchronous, authorized and support byte ranges', async (t) => {
  const { app, client, config, alice, bob } = await fixture(t);
  const album = await createAlbum(app, alice.accessToken, 'derivative-image');
  const source = await sharp({ create: { width: 1600, height: 1000, channels: 3,
    background: { r: 28, g: 112, b: 188 } } })
    .composite([{ input: Buffer.from('<svg width="1600" height="1000"><defs><linearGradient id="g"><stop stop-color="#f80"/><stop offset="1" stop-color="#08f"/></linearGradient></defs><rect width="1600" height="1000" fill="url(#g)"/><circle cx="800" cy="500" r="320" fill="#fff" opacity=".65"/></svg>') }])
    .jpeg({ quality: 96 }).toBuffer();
  const asset = await createAsset(app, alice.accessToken, album.id, source, {
    originalFileName: 'valid-photo.jpg', width: 1600, height: 1000,
  });
  const uploaded = await call(app, alice.accessToken, 'PUT', '/v1/assets/' + asset.id + '/original',
    source, { 'content-type': 'application/octet-stream', 'content-length': String(source.length) });
  assert.equal(uploaded.statusCode, 200, uploaded.body);
  assert.deepEqual(uploaded.json().derivatives.map((item) => item.status), ['pending', 'pending']);

  const worker = createDerivativeWorker(client, config, createLogger('silent'));
  assert.equal(await worker.runOnce(), 2);
  const metadata = await call(app, alice.accessToken, 'GET', '/v1/assets/' + asset.id);
  assert.equal(metadata.statusCode, 200, metadata.body);
  assert.deepEqual(metadata.json().derivatives.map((item) => item.status), ['ready', 'ready']);
  const optimized = metadata.json().derivatives.find((item) => item.kind === 'optimized');
  const thumbnail = metadata.json().derivatives.find((item) => item.kind === 'thumbnail');
  assert.equal(optimized.mimeType, 'image/webp');
  assert.ok(optimized.width <= 2560 && optimized.height <= 2560);
  assert.ok(thumbnail.width <= 512 && thumbnail.height <= 512);

  for (const variant of ['thumbnail', 'optimized']) {
    const full = await call(app, bob.accessToken, 'GET', '/v1/assets/' + asset.id + '/' + variant);
    assert.equal(full.statusCode, 200, full.body);
    assert.ok(full.rawPayload.length > 100);
    assert.equal(full.headers['accept-ranges'], 'bytes');
    const partial = await call(app, bob.accessToken, 'GET', '/v1/assets/' + asset.id + '/' + variant,
      undefined, { range: 'bytes=2-11' });
    assert.equal(partial.statusCode, 206, partial.body);
    assert.equal(partial.rawPayload.length, 10);
    assert.deepEqual(partial.rawPayload, full.rawPayload.subarray(2, 12));
    assert.equal(partial.headers['content-range'], 'bytes 2-11/' + full.rawPayload.length);
  }
  const originalRange = await call(app, alice.accessToken, 'GET', '/v1/assets/' + asset.id + '/original',
    undefined, { range: 'bytes=-7' });
  assert.equal(originalRange.statusCode, 206, originalRange.body);
  assert.deepEqual(originalRange.rawPayload, source.subarray(-7));
  const invalidRange = await call(app, alice.accessToken, 'GET', '/v1/assets/' + asset.id + '/optimized',
    undefined, { range: 'bytes=999999999-' });
  assert.equal(invalidRange.statusCode, 416, invalidRange.body);
  assert.match(invalidRange.headers['content-range'], /^bytes \*\/[0-9]+$/);
});

test('derivative failures preserve originals and can be explicitly retried', async (t) => {
  const { app, client, config, alice } = await fixture(t);
  const album = await createAlbum(app, alice.accessToken, 'derivative-failure');
  const bytes = Buffer.from('not actually a jpeg but an immutable original');
  const asset = await createAsset(app, alice.accessToken, album.id, bytes);
  const upload = await call(app, alice.accessToken, 'PUT', '/v1/assets/' + asset.id + '/original',
    bytes, { 'content-type': 'application/octet-stream', 'content-length': String(bytes.length) });
  assert.equal(upload.statusCode, 200, upload.body);
  const originalHash = upload.json().sha256;

  const worker = createDerivativeWorker(client, config, createLogger('silent'));
  assert.equal(await worker.runOnce(), 2);
  const failed = await client.assetDerivative.findMany({ where: { assetId: asset.id }, orderBy: { kind: 'asc' } });
  assert.deepEqual(failed.map((item) => item.status), ['failed', 'failed']);
  assert.deepEqual(failed.map((item) => item.attempts), [1, 1]);
  assert.ok(failed.every((item) => item.nextAttemptAt > new Date()));
  assert.equal((await call(app, alice.accessToken, 'GET', '/v1/assets/' + asset.id + '/thumbnail')).statusCode, 409);
  const original = await call(app, alice.accessToken, 'GET', '/v1/assets/' + asset.id + '/original');
  assert.equal(original.statusCode, 200, original.body);
  assert.deepEqual(original.rawPayload, bytes);
  assert.equal(createHash('sha256').update(original.rawPayload).digest('hex'), originalHash);

  const retried = await call(app, alice.accessToken, 'POST', '/v1/assets/' + asset.id + '/derivatives/retry');
  assert.equal(retried.statusCode, 202, retried.body);
  assert.deepEqual(retried.json().derivatives.map((item) => item.status), ['pending', 'pending']);
  assert.deepEqual(retried.json().derivatives.map((item) => item.attempts), [0, 0]);
});
