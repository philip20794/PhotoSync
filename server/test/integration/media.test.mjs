import assert from 'node:assert/strict';
import { createHash, randomUUID } from 'node:crypto';
import { access, mkdir, readFile, readdir, rm, stat, unlink, utimes, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { execFile } from 'node:child_process';
import { request as httpRequest } from 'node:http';
import { promisify } from 'node:util';
import { Readable } from 'node:stream';
import test from 'node:test';
import { buildApp } from '../../dist/app.js';
import { createAuthService } from '../../dist/auth/service.js';
import { hashSecret, newSetupToken } from '../../dist/auth/secrets.js';
import { loadConfig } from '../../dist/config.js';
import { createDatabase } from '../../dist/database.js';
import { createMediaService } from '../../dist/media/service.js';
import { createDerivativeWorker } from '../../dist/media/derivatives.js';
import { createOriginalIntegrityWorker } from '../../dist/media/originals.js';
import { createLogger } from '../../dist/logger.js';
import sharp from 'sharp';
import { dispatchWakeups, wakeMessage } from '../../dist/sync/push.js';
import { compactChangeJournal } from '../../dist/sync/retention.js';

async function drainChanges(app, token, cursor) {
  const changes = [];
  for (let i = 0; i < 1000; i++) {
    const response = await call(app, token, 'GET', '/v1/sync/changes' + (cursor ? '?cursor=' + cursor : ''));
    assert.equal(response.statusCode, 200, response.body);
    const page = response.json();
    changes.push(...page.changes);
    cursor = page.nextCursor;
    if (!page.hasMore) return { changes, cursor };
  }
  throw new Error('Feed did not converge');
}

test('change feed replays idempotently, includes derivative changes and revocation, rejects foreign cursors', async (t) => {
  const { app, client, alice, bob } = await fixture(t);
  const baseline = await drainChanges(app, bob.accessToken);
  const album = await createAlbum(app, alice.accessToken, 'feed');
  const asset = await createAsset(app, alice.accessToken, album.id, Buffer.from('bytes'));
  await client.asset.update({ where: { id: asset.id }, data: { status: 'ready', sha256: 'a'.repeat(64) } });
  await client.asset.update({ where: { id: asset.id }, data: { width: 100 } });
  await client.assetDerivative.create({ data: {
    assetId: asset.id, kind: 'thumbnail', status: 'ready', mimeType: 'image/jpeg',
    storagePath: 'derivatives/feed-thumbnail.jpg', fileSize: 1n, width: 1, height: 1, sha256: 'b'.repeat(64),
  } });
  const first = await drainChanges(app, bob.accessToken, baseline.cursor);
  const replay = await drainChanges(app, bob.accessToken, baseline.cursor);
  assert.deepEqual(first, replay);
  assert.ok(first.changes.some((change) => change.kind === 'ALBUM' && change.albumId === album.id));
  assert.ok(first.changes.filter((change) => change.assetId === asset.id).length >= 3);
  assert.ok(first.changes.every((change, index) => index === 0 || BigInt(change.revision) > BigInt(first.changes[index - 1].revision)));
  assert.equal((await call(app, alice.accessToken, 'GET', '/v1/sync/changes?cursor=' + first.cursor)).statusCode, 410);
  assert.equal((await call(app, bob.accessToken, 'GET', '/v1/sync/changes?cursor=broken')).statusCode, 410);
  await call(app, alice.accessToken, 'PATCH', '/v1/albums/' + album.id, { shared: false });
  const revoked = await drainChanges(app, bob.accessToken, first.cursor);
  assert.ok(revoked.changes.some((change) => change.albumId === album.id));
  assert.equal((await call(app, bob.accessToken, 'GET', '/v1/assets/' + asset.id)).statusCode, 404);
  await client.asset.update({ where: { id: asset.id }, data: { width: 101 } });
  const privateChanges = await drainChanges(app, bob.accessToken, revoked.cursor);
  assert.equal(privateChanges.changes.length, 0);
  assert.notEqual(privateChanges.cursor, revoked.cursor);
});

test('transactional revision cannot skip a slow commit; rollback creates no event', async (t) => {
  const { app, client, alice, bob } = await fixture(t);
  const album = await createAlbum(app, alice.accessToken, 'commit-order');
  const baseline = await drainChanges(app, bob.accessToken);
  let unlock;
  const gate = new Promise((resolve) => { unlock = resolve; });
  let entered;
  const started = new Promise((resolve) => { entered = resolve; });
  const slow = client.$transaction(async (tx) => {
    await tx.album.update({ where: { id: album.id }, data: { title: 'slow' } });
    entered();
    await gate;
  });
  await started;
  const second = client.album.update({ where: { id: album.id }, data: { title: 'later' } });
  // Prisma promises are lazy: start execution now.
  const secondRunning = second.then((value) => value);
  try {
    const during = await drainChanges(app, bob.accessToken, baseline.cursor);
    assert.deepEqual(during.changes, []);
  } finally { unlock(); }
  await Promise.all([slow, secondRunning]);
  const committed = await drainChanges(app, bob.accessToken, baseline.cursor);
  assert.equal(committed.changes.length, 2);
  assert.ok(BigInt(committed.changes[0].revision) < BigInt(committed.changes[1].revision));
  await assert.rejects(client.$transaction(async (tx) => {
    await tx.album.update({ where: { id: album.id }, data: { title: 'rolled back' } });
    throw new Error('abort');
  }));
  assert.deepEqual((await drainChanges(app, bob.accessToken, committed.cursor)).changes, []);
});

test('push is a replay-safe hint; outage backoff persists and deleted assets produce tombstone invalidations', async (t) => {
  const { app, client, alice, bob } = await fixture(t);
  const token = 'synthetic-fcm-token-for-test';
  assert.equal((await call(app, bob.accessToken, 'PUT', '/v1/sync/push-token', { token })).statusCode, 204);
  assert.equal((await call(app, bob.accessToken, 'PUT', '/v1/sync/push-token', { token })).statusCode, 204);
  const album = await createAlbum(app, alice.accessToken, 'push');
  const asset = await createAsset(app, alice.accessToken, album.id, Buffer.from('data'));
  await client.asset.update({ where: { id: asset.id }, data: { status: 'ready', sha256: 'a'.repeat(64) } });
  const baseline = await drainChanges(app, bob.accessToken);
  await dispatchWakeups(client, async () => { throw new Error('FCM down'); });
  const [failed] = await client.$queryRaw`SELECT attempts, revision FROM sync_push_devices WHERE "deviceId" = ${bob.device.id}::uuid`;
  assert.equal(failed.attempts, 1);
  assert.equal(failed.revision, 0n);
  await client.$executeRaw`UPDATE sync_push_devices SET "nextAttemptAt" = now()`;
  const messages = [];
  await dispatchWakeups(client, async (value) => { messages.push(wakeMessage(value)); });
  await dispatchWakeups(client, async (value) => { messages.push(wakeMessage(value)); });
  assert.equal(messages.length, 1);
  assert.deepEqual(messages[0].data, { type: 'sync' });
  await client.asset.delete({ where: { id: asset.id } });
  const changes = await drainChanges(app, bob.accessToken, baseline.cursor);
  assert.ok(changes.changes.some((change) => change.assetId === asset.id && change.operation === 'DELETE'));
});

async function heartbeatDoesNotWake(t) {
  const fixtureData = await fixture(t);
  const app = fixtureData.app;
  const client = fixtureData.client;
  const alice = fixtureData.alice;
  const bob = fixtureData.bob;
  await call(app, bob.accessToken, 'PUT', '/v1/sync/push-token', { token: 'heartbeat-fcm-token-for-test' });
  const album = await createAlbum(app, alice.accessToken, 'heartbeat');
  const asset = await createAsset(app, alice.accessToken, album.id, Buffer.from('heartbeat'));
  await dispatchWakeups(client, async () => undefined);
  const beforeRows = await client.$queryRaw`SELECT revision FROM sync_head WHERE id = 1`;
  await client.asset.update({ where: { id: asset.id }, data: { uploadLeaseId: randomUUID(), uploadStartedAt: new Date() } });
  const derivative = await client.assetDerivative.create({ data: { assetId: asset.id, kind: 'optimized' } });
  await client.assetDerivative.update({ where: { id: derivative.id }, data: {
    status: 'processing', attempts: 1, processingLeaseId: randomUUID(),
    processingLeaseExpiresAt: new Date(Date.now() + 60_000),
  } });
  const afterRows = await client.$queryRaw`SELECT revision FROM sync_head WHERE id = 1`;
  assert.equal(afterRows[0].revision, beforeRows[0].revision);
  const messages = [];
  await dispatchWakeups(client, async (value) => messages.push(value));
  assert.deepEqual(messages, []);
}
test('technical heartbeat creates no revision and no push', heartbeatDoesNotWake);

async function compactionResetsEpoch(t) {
  const fixtureData = await fixture(t);
  const app = fixtureData.app;
  const client = fixtureData.client;
  const alice = fixtureData.alice;
  const bob = fixtureData.bob;
  await createAlbum(app, alice.accessToken, 'retention');
  const baseline = await drainChanges(app, bob.accessToken);
  await client.$executeRaw`UPDATE sync_changes SET "createdAt" = now() - interval '100 days'`;
  assert.equal(await compactChangeJournal(client, 90), true);
  const result = await call(app, bob.accessToken, 'GET', '/v1/sync/changes?cursor=' + baseline.cursor);
  assert.equal(result.statusCode, 410);
}
test('journal compaction invalidates old cursors with a safe epoch reset', compactionResetsEpoch);
if (process.env.PHOTOSYNC_INTEGRATION_TEST !== '1' ||
    new URL(process.env.DATABASE_URL).pathname !== '/photosync_test') {
  throw new Error('Media integration tests require the isolated photosync_test database');
}
const execFileAsync = promisify(execFile);
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
    UPLOAD_LEASE_MS: '5000',
    UPLOAD_RECOVERY_INTERVAL_MS: '1000',
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

test('an interrupted chunk rolls back to its committed offset and resumes after restart', async (t) => {
  const { app, client, config, alice, bob } = await fixture(t);
  const album = await createAlbum(app, alice.accessToken, 'partial');
  const bytes = Buffer.from('partial original');
  const expectedSha256 = createHash('sha256').update(bytes).digest('hex');
  const asset = await createAsset(app, alice.accessToken, album.id, bytes, {
    clientAssetId: 'partial-resume-1', expectedSha256,
  });
  const principal = await createAuthService(client, config).authenticate('Bearer ' + alice.accessToken);
  const media = createMediaService(client, config);
  const session = await media.createUploadSession(principal, asset.id);
  await assert.rejects(
    media.uploadChunk(principal, session.id, 0n,
      Readable.from([bytes.subarray(0, bytes.length - 2)]), bytes.length),
    (error) => error.statusCode === 422 && error.code === 'UPLOAD_SIZE_MISMATCH',
  );
  const interrupted = await client.asset.findUniqueOrThrow({ where: { id: asset.id } });
  assert.equal(interrupted.status, 'uploading');
  assert.equal(interrupted.uploadOffset, 0n);
  assert.equal(interrupted.uploadSessionId, session.id);
  assert.equal((await stat(resolve(config.mediaRoot, interrupted.uploadPartPath))).size, 0);
  assert.equal((await call(app, alice.accessToken, 'GET', '/v1/assets/' + asset.id + '/original')).statusCode, 404);
  assert.equal((await call(app, bob.accessToken, 'GET', '/v1/assets/' + asset.id)).statusCode, 404);

  const restarted = createMediaService(client, config);
  const resumed = await restarted.createUploadSession(principal, asset.id);
  assert.equal(resumed.id, session.id);
  assert.equal(resumed.offset, '0');
  const completed = await restarted.uploadChunk(
    principal, session.id, 0n, Readable.from([bytes]), bytes.length,
  );
  assert.equal(completed.completed, true);
  assert.equal(completed.asset.sha256, expectedSha256);
  assert.deepEqual(await readdir(resolve(config.mediaRoot, 'uploads')), []);
});

test('resumable upload validates offsets and serializes parallel workers', async (t) => {
  const { app, client, alice } = await fixture(t);
  const album = await createAlbum(app, alice.accessToken, 'chunked');
  const bytes = Buffer.from('two independently retryable upload chunks');
  const split = 13;
  const expectedSha256 = createHash('sha256').update(bytes).digest('hex');
  const asset = await createAsset(app, alice.accessToken, album.id, bytes, {
    clientAssetId: 'parallel-chunks-1', expectedSha256,
  });
  const sessionResponse = await call(app, alice.accessToken, 'POST', '/v1/assets/' + asset.id + '/upload-session');
  assert.equal(sessionResponse.statusCode, 200, sessionResponse.body);
  const session = sessionResponse.json();

  const headers = { 'content-type': 'application/octet-stream', 'content-length': String(split), 'upload-offset': '0' };
  const parallel = await Promise.all([
    call(app, alice.accessToken, 'PATCH', '/v1/upload-sessions/' + session.id, bytes.subarray(0, split), headers),
    call(app, alice.accessToken, 'PATCH', '/v1/upload-sessions/' + session.id, bytes.subarray(0, split), headers),
  ]);
  assert.deepEqual(parallel.map((item) => item.statusCode).sort(), [200, 409]);
  assert.equal(parallel.find((item) => item.statusCode === 200).json().offset, String(split));

  const repeatedSession = await call(app, alice.accessToken, 'POST', '/v1/assets/' + asset.id + '/upload-session');
  assert.equal(repeatedSession.json().id, session.id);
  assert.equal(repeatedSession.json().offset, String(split));
  const wrongOffset = await call(app, alice.accessToken, 'PATCH', '/v1/upload-sessions/' + session.id,
    bytes.subarray(split), { 'content-type': 'application/octet-stream',
      'content-length': String(bytes.length - split), 'upload-offset': '0' });
  assert.equal(wrongOffset.statusCode, 409, wrongOffset.body);

  const final = await call(app, alice.accessToken, 'PATCH', '/v1/upload-sessions/' + session.id,
    bytes.subarray(split), { 'content-type': 'application/octet-stream',
      'content-length': String(bytes.length - split), 'upload-offset': String(split) });
  assert.equal(final.statusCode, 200, final.body);
  assert.equal(final.json().completed, true);
  assert.equal(final.json().asset.sha256, expectedSha256);
  const stored = await client.asset.findUniqueOrThrow({ where: { id: asset.id } });
  assert.equal(stored.status, 'ready');
  assert.equal(stored.uploadSessionId, null);
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

test('private backup and later sharing reuse one album and one asset without duplicate upload', async (t) => {
  const { app, client, alice, bob } = await fixture(t);
  const create = await call(app, alice.accessToken, 'POST', '/v1/albums', {
    clientAlbumId: 'private-camera',
    title: 'Camera',
    shared: false,
    backedUp: true,
  });
  assert.equal(create.statusCode, 201, create.body);
  const album = create.json();
  assert.equal(album.shared, false);
  assert.equal(album.backedUp, true);
  assert.equal((await call(app, bob.accessToken, 'GET', '/v1/partner/albums')).json().albums.length, 0);
  const expectedSha256 = 'a'.repeat(64);
  const metadata = {
    originalFileName: 'private.jpg',
    mimeType: 'image/jpeg',
    fileSize: '4',
    width: 2,
    height: 2,
    clientAssetId: 'private-camera-1',
    expectedSha256,
  };
  const first = await call(app, alice.accessToken, 'POST', '/v1/albums/' + album.id + '/assets', metadata);
  assert.equal(first.statusCode, 201, first.body);
  await client.asset.update({ where: { id: first.json().id }, data: { status: 'ready', sha256: expectedSha256 } });
  const shared = await call(app, alice.accessToken, 'PATCH', '/v1/albums/' + album.id, { shared: true });
  assert.equal(shared.statusCode, 200, shared.body);
  assert.equal(shared.json().id, album.id);
  assert.equal(shared.json().shared, true);
  const reused = await call(app, alice.accessToken, 'POST', '/v1/albums/' + album.id + '/assets', metadata);
  assert.equal(reused.statusCode, 201, reused.body);
  assert.equal(reused.json().id, first.json().id);
  assert.equal(await client.asset.count({ where: { albumId: album.id } }), 1);
  assert.equal((await call(app, bob.accessToken, 'GET', '/v1/partner/albums')).json().albums[0].id, album.id);
  const unshared = await call(app, alice.accessToken, 'PATCH', '/v1/albums/' + album.id, { shared: false });
  assert.equal(unshared.statusCode, 200);
  assert.equal(unshared.json().backedUp, true);
  assert.equal((await call(app, bob.accessToken, 'GET', '/v1/partner/albums')).json().albums.length, 0);
  assert.equal((await call(app, alice.accessToken, 'GET', '/v1/assets/' + first.json().id)).statusCode, 200);
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
  assert.equal((await client.asset.findUniqueOrThrow({ where: { id: first.id } })).status, 'uploading');

  const reset = await createAsset(app, alice.accessToken, album.id, bytes, metadata);
  assert.equal(reset.id, first.id);
  assert.equal(reset.status, 'uploading');
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

  const orphanPart = resolve(config.mediaRoot, 'derivatives', alice.user.id, asset.id,
    'thumbnail.webp.abandoned.part');
  await mkdir(resolve(orphanPart, '..'), { recursive: true });
  await writeFile(orphanPart, 'interrupted output');
  const worker = createDerivativeWorker(client, config, createLogger('silent'));
  assert.equal(await worker.runOnce(), 2);
  await assert.rejects(access(orphanPart));
  const metadata = await call(app, alice.accessToken, 'GET', '/v1/assets/' + asset.id);
  assert.equal(metadata.statusCode, 200, metadata.body);
  assert.deepEqual(metadata.json().derivatives.map((item) => item.status), ['ready', 'ready']);
  const optimized = metadata.json().derivatives.find((item) => item.kind === 'optimized');
  const thumbnail = metadata.json().derivatives.find((item) => item.kind === 'thumbnail');
  assert.equal(optimized.mimeType, 'image/webp');
  assert.ok(optimized.width <= 2560 && optimized.height <= 2560);
  assert.ok(thumbnail.width <= 512 && thumbnail.height <= 512);

  const missing = await client.assetDerivative.findFirstOrThrow({
    where: { assetId: asset.id, kind: 'thumbnail' },
  });
  await rm(resolve(config.mediaRoot, missing.storagePath));
  assert.equal(await worker.reconcileReadyDerivatives(), 1);
  assert.equal((await client.assetDerivative.findUniqueOrThrow({ where: { id: missing.id } })).status, 'pending');
  assert.equal(await worker.runOnce(), 1);
  assert.equal((await client.assetDerivative.findUniqueOrThrow({ where: { id: missing.id } })).status, 'ready');

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

test('derivative recovery reclaims only expired processing leases', async (t) => {
  const { app, client, config, alice } = await fixture(t);
  const album = await createAlbum(app, alice.accessToken, 'derivative-leases');
  const bytes = Buffer.from('lease metadata only');
  const asset = await createAsset(app, alice.accessToken, album.id, bytes);
  await client.asset.update({ where: { id: asset.id }, data: { status: 'ready', sha256: 'a'.repeat(64) } });
  const now = new Date();
  const active = await client.assetDerivative.create({ data: {
    assetId: asset.id, kind: 'thumbnail', status: 'processing', processingLeaseId: randomUUID(),
    processingLeaseExpiresAt: new Date(now.getTime() + 60_000),
  } });
  const stale = await client.assetDerivative.create({ data: {
    assetId: asset.id, kind: 'optimized', status: 'processing', processingLeaseId: randomUUID(),
    processingLeaseExpiresAt: new Date(now.getTime() - 1),
  } });
  const worker = createDerivativeWorker(client, config, createLogger('silent'));
  assert.equal(await worker.recoverStaleJobs(now), 1);
  assert.equal((await client.assetDerivative.findUniqueOrThrow({ where: { id: active.id } })).status, 'processing');
  assert.equal((await client.assetDerivative.findUniqueOrThrow({ where: { id: stale.id } })).status, 'pending');
  assert.equal(await worker.recoverStaleJobs(new Date(now.getTime() + 60_001)), 1);
  assert.equal((await client.assetDerivative.findUniqueOrThrow({ where: { id: active.id } })).status, 'pending');
});


test('stale upload leases are recovered idempotently without touching active uploads', async (t) => {
  const { app, client, config, alice } = await fixture(t);
  const album = await createAlbum(app, alice.accessToken, 'upload-recovery');
  const bytes = Buffer.from('recoverable original bytes');
  const expectedSha256 = createHash('sha256').update(bytes).digest('hex');
  const asset = await createAsset(app, alice.accessToken, album.id, bytes, {
    clientAssetId: 'recovery-version-1', expectedSha256,
  });
  const media = createMediaService(client, config);
  const principal = await createAuthService(client, config).authenticate('Bearer ' + alice.accessToken);
  const session = await media.createUploadSession(principal, asset.id);
  const now = new Date('2026-09-15T12:00:00.000Z');
  const activeLease = '00000000-0000-0000-0000-000000000091';
  await client.asset.update({
    where: { id: asset.id },
    data: {
      uploadStartedAt: new Date(now.getTime() - 1000),
      uploadLeaseId: activeLease,
      uploadLeaseExpiresAt: new Date(now.getTime() + 60_000),
      uploadExpiresAt: new Date(now.getTime() - 1),
      uploadOffset: 5n,
    },
  });
  const storedSession = await client.asset.findUniqueOrThrow({ where: { id: asset.id } });
  const partPath = resolve(config.mediaRoot, storedSession.uploadPartPath);
  await mkdir(resolve(partPath, '..'), { recursive: true });
  await writeFile(partPath, bytes.subarray(0, 5));

  assert.equal(await media.recoverStaleUploads(now), 0);
  assert.equal((await client.asset.findUniqueOrThrow({ where: { id: asset.id } })).status, 'uploading');
  assert.equal((await stat(partPath)).size, 5);

  // Bytes written after the last committed offset model a process kill before the DB commit.
  await writeFile(partPath, bytes.subarray(0, 9));
  await client.asset.update({
    where: { id: asset.id },
    data: {
      uploadLeaseExpiresAt: new Date(now.getTime() - 1),
      uploadExpiresAt: new Date(Date.now() + config.uploadSessionTtlMs),
    },
  });
  assert.equal(await media.recoverStaleUploads(now), 1);
  const recovered = await client.asset.findUniqueOrThrow({ where: { id: asset.id } });
  assert.equal(recovered.status, 'uploading');
  assert.equal(recovered.uploadSessionId, session.id);
  assert.equal(recovered.uploadOffset, 5n);
  assert.equal(recovered.uploadLeaseId, null);
  assert.equal((await stat(partPath)).size, 5);
  assert.equal(await media.recoverStaleUploads(now), 0);

  await media.uploadChunk(principal, session.id, 5n, Readable.from([bytes.subarray(5)]), bytes.length - 5);
  const completed = await client.asset.findUniqueOrThrow({
    where: { id: asset.id }, include: { derivatives: true },
  });
  assert.equal(completed.status, 'ready');
  assert.equal(completed.sha256, expectedSha256);
  assert.equal(completed.uploadLeaseId, null);
  assert.deepEqual(completed.derivatives.map((item) => item.kind).sort(), ['optimized', 'thumbnail']);
  assert.equal(await media.recoverStaleUploads(now), 0);
});

test('upload recovery completes the DB commit after a crash following atomic rename', async (t) => {
  const { app, client, config, alice } = await fixture(t);
  const album = await createAlbum(app, alice.accessToken, 'rename-crash');
  const bytes = Buffer.from('durable rename before database commit');
  const expectedSha256 = createHash('sha256').update(bytes).digest('hex');
  const asset = await createAsset(app, alice.accessToken, album.id, bytes, {
    clientAssetId: 'rename-crash-1', expectedSha256,
  });
  const principal = await createAuthService(client, config).authenticate('Bearer ' + alice.accessToken);
  const crashing = createMediaService(client, config, {
    afterUploadRenamed: async () => { throw new Error('simulated process crash'); },
  });
  const session = await crashing.createUploadSession(principal, asset.id);
  await assert.rejects(
    crashing.uploadChunk(principal, session.id, 0n, Readable.from([bytes]), bytes.length),
    /simulated process crash/,
  );
  const stranded = await client.asset.findUniqueOrThrow({ where: { id: asset.id } });
  assert.equal(stranded.status, 'uploading');
  assert.ok(stranded.uploadLeaseId);
  assert.equal((await stat(resolve(config.mediaRoot, stranded.storagePath))).size, bytes.length);

  const restarted = createMediaService(client, config);
  const afterLease = new Date(stranded.uploadLeaseExpiresAt.getTime() + 1);
  assert.equal(await restarted.recoverStaleUploads(afterLease), 1);
  const recovered = await client.asset.findUniqueOrThrow({ where: { id: asset.id } });
  assert.equal(recovered.status, 'ready');
  assert.equal(recovered.sha256, expectedSha256);
  assert.equal(recovered.uploadSessionId, null);
  assert.equal(await restarted.recoverStaleUploads(afterLease), 0);
});

test('simulated full server disk preserves a retryable upload session', async (t) => {
  const { app, client, config, alice } = await fixture(t);
  const album = await createAlbum(app, alice.accessToken, 'disk-full');
  const bytes = Buffer.from('retry after storage pressure');
  const asset = await createAsset(app, alice.accessToken, album.id, bytes);
  const principal = await createAuthService(client, config).authenticate('Bearer ' + alice.accessToken);
  const media = createMediaService(client, config);
  const session = await media.createUploadSession(principal, asset.id);
  const diskFull = new Readable({
    read() {
      const error = new Error('simulated ENOSPC');
      error.code = 'ENOSPC';
      this.destroy(error);
    },
  });
  await assert.rejects(
    media.uploadChunk(principal, session.id, 0n, diskFull, bytes.length),
    (error) => error.statusCode === 507 && error.code === 'STORAGE_FULL',
  );
  const retryable = await client.asset.findUniqueOrThrow({ where: { id: asset.id } });
  assert.equal(retryable.status, 'uploading');
  assert.equal(retryable.uploadOffset, 0n);
  assert.equal(retryable.uploadSessionId, session.id);
  assert.equal(retryable.uploadLeaseId, null);
});

test('an original upload taking longer than ten seconds completes successfully', async (t) => {
  const { app, config, alice } = await fixture(t);
  const album = await createAlbum(app, alice.accessToken, 'slow-upload');
  const bytes = Buffer.from('slow-upload!');
  const asset = await createAsset(app, alice.accessToken, album.id, bytes);
  await app.listen({ host: '127.0.0.1', port: 0 });
  const address = app.server.address();
  assert.equal(typeof address, 'object');
  const started = Date.now();

  const response = await new Promise((resolvePromise, reject) => {
    const request = httpRequest({
      host: '127.0.0.1',
      port: address.port,
      method: 'PUT',
      path: '/v1/assets/' + asset.id + '/original',
      headers: {
        authorization: 'Bearer ' + alice.accessToken,
        'content-type': 'application/octet-stream',
        'content-length': String(bytes.length),
      },
    }, (incoming) => {
      const chunks = [];
      incoming.on('data', (chunk) => chunks.push(chunk));
      incoming.on('end', () => resolvePromise({
        statusCode: incoming.statusCode,
        body: Buffer.concat(chunks).toString('utf8'),
      }));
    });
    request.on('error', reject);
    void (async () => {
      try {
        for (const byte of bytes) {
          request.write(Buffer.from([byte]));
          await new Promise((resolveDelay) => setTimeout(resolveDelay, 1000));
        }
        request.end();
      } catch (error) {
        request.destroy(error);
      }
    })();
  });

  assert.ok(Date.now() - started >= 10_000);
  assert.equal(response.statusCode, 200, response.body);
  assert.equal(JSON.parse(response.body).status, 'ready');
  assert.ok(config.uploadRequestTimeoutMs > 10_000);
  assert.equal(config.uploadLeaseMs, 5000);
});

test('video derivatives complete end to end and preserve the authorized original', async (t) => {
  const { app, client, config, alice, bob } = await fixture(t);
  const sourcePath = resolve(config.mediaRoot, 'video-source.mp4');
  await execFileAsync('ffmpeg', [
    '-y', '-nostdin', '-hide_banner', '-loglevel', 'error',
    '-f', 'lavfi', '-i', 'color=c=0x2455aa:size=640x360:rate=24',
    '-f', 'lavfi', '-i', 'sine=frequency=660:sample_rate=48000',
    '-t', '2.2', '-c:v', 'libx264', '-pix_fmt', 'yuv420p',
    '-c:a', 'aac', '-b:a', '96k', '-movflags', '+faststart', sourcePath,
  ]);
  const source = await readFile(sourcePath);
  const originalHash = createHash('sha256').update(source).digest('hex');
  const album = await createAlbum(app, alice.accessToken, 'derivative-video');
  const asset = await createAsset(app, alice.accessToken, album.id, source, {
    originalFileName: 'real-video.mp4',
    mimeType: 'video/mp4',
    width: 640,
    height: 360,
    durationMillis: '2200',
    clientAssetId: 'video-version-1',
    expectedSha256: originalHash,
  });
  const upload = await call(app, alice.accessToken, 'PUT', '/v1/assets/' + asset.id + '/original',
    source, { 'content-type': 'application/octet-stream', 'content-length': String(source.length) });
  assert.equal(upload.statusCode, 200, upload.body);
  assert.deepEqual(upload.json().derivatives.map((item) => item.status), ['pending', 'pending']);

  const worker = createDerivativeWorker(client, config, createLogger('silent'));
  const before = await worker.diagnostics();
  assert.equal(before.ffmpeg, 'ok');
  assert.equal(before.ffprobe, 'ok');
  assert.equal(await worker.runOnce(), 2);
  const metadata = await call(app, alice.accessToken, 'GET', '/v1/assets/' + asset.id);
  assert.deepEqual(metadata.json().derivatives.map((item) => item.status), ['ready', 'ready']);
  const thumbnail = metadata.json().derivatives.find((item) => item.kind === 'thumbnail');
  const optimized = metadata.json().derivatives.find((item) => item.kind === 'optimized');
  assert.equal(thumbnail.mimeType, 'image/jpeg');
  assert.ok(thumbnail.width <= 512 && thumbnail.height <= 512);
  assert.equal(optimized.mimeType, 'video/mp4');
  assert.ok(optimized.width <= 1920 && optimized.height <= 1920);
  assert.ok(Number(optimized.durationMillis) >= 2000);

  const thumbResponse = await call(app, bob.accessToken, 'GET', '/v1/assets/' + asset.id + '/thumbnail');
  assert.equal(thumbResponse.statusCode, 200, thumbResponse.body);
  assert.deepEqual([...thumbResponse.rawPayload.subarray(0, 2)], [0xff, 0xd8]);
  const optimizedResponse = await call(app, bob.accessToken, 'GET', '/v1/assets/' + asset.id + '/optimized');
  assert.equal(optimizedResponse.statusCode, 200, optimizedResponse.body);
  assert.equal(optimizedResponse.rawPayload.subarray(4, 8).toString('ascii'), 'ftyp');
  const range = await call(app, bob.accessToken, 'GET', '/v1/assets/' + asset.id + '/optimized',
    undefined, { range: 'bytes=4-15' });
  assert.equal(range.statusCode, 206, range.body);
  assert.deepEqual(range.rawPayload, optimizedResponse.rawPayload.subarray(4, 16));
  assert.equal((await app.inject('/v1/assets/' + asset.id + '/optimized')).statusCode, 401);

  const original = await call(app, alice.accessToken, 'GET', '/v1/assets/' + asset.id + '/original');
  assert.equal(createHash('sha256').update(original.rawPayload).digest('hex'), originalHash);
  assert.deepEqual(original.rawPayload, source);
});


test('partner catalogue exposes only shared albums and pages thousands of ready asset metadata', async (t) => {
  const { app, client, alice, bob } = await fixture(t);
  const shared = await createAlbum(app, alice.accessToken, 'partner-paged');
  const privateAlbum = await createAlbum(app, alice.accessToken, 'partner-private');
  await client.album.update({ where: { id: privateAlbum.id }, data: { sharedAt: null } });

  const assetIds = Array.from({ length: 2500 }, () => randomUUID());
  await client.asset.createMany({
    data: assetIds.map((id) => ({
      id,
      ownerId: alice.user.id,
      albumId: shared.id,
      originalFileName: id + '.jpg',
      mimeType: 'image/jpeg',
      fileSize: 1n,
      width: 1,
      height: 1,
      storagePath: 'originals/' + alice.user.id + '/' + id + '/original',
      sha256: 'b'.repeat(64),
      status: 'ready',
    })),
  });
  await client.assetDerivative.create({
    data: {
      id: randomUUID(),
      assetId: assetIds[0],
      kind: 'thumbnail',
      status: 'ready',
      mimeType: 'image/webp',
      storagePath: 'derivatives/' + assetIds[0] + '/thumbnail.webp',
      fileSize: 1n,
      width: 1,
      height: 1,
      sha256: 'a'.repeat(64),
    },
  });
  await client.assetDerivative.create({
    data: {
      id: randomUUID(), assetId: assetIds[0], kind: 'optimized', status: 'ready',
      mimeType: 'image/webp', storagePath: 'derivatives/' + assetIds[0] + '/optimized.webp',
      fileSize: 1n, width: 1, height: 1, sha256: 'c'.repeat(64),
    },
  });

  const albums = await call(app, bob.accessToken, 'GET', '/v1/partner/albums');
  assert.equal(albums.statusCode, 200, albums.body);
  assert.equal(albums.json().albums.length, 1);
  assert.equal(albums.json().albums[0].id, shared.id);
  assert.equal(albums.json().albums[0].assetCount, 2500);
  assert.equal(albums.json().albums[0].originalBytes, '2500');
  assert.equal(albums.json().albums[0].optimizedBytes, '1');
  assert.equal(albums.json().albums[0].cover.assetId, assetIds[0]);

  const first = await call(app, bob.accessToken, 'GET', '/v1/albums/' + shared.id + '/assets?limit=60');
  assert.equal(first.statusCode, 200, first.body);
  assert.equal(first.json().assets.length, 60);
  assert.ok(first.json().nextCursor);
  const second = await call(app, bob.accessToken, 'GET', '/v1/albums/' + shared.id + '/assets?limit=60&cursor=' + encodeURIComponent(first.json().nextCursor));
  assert.equal(second.statusCode, 200, second.body);
  assert.equal(second.json().assets.length, 60);
  assert.equal(new Set(first.json().assets.map((asset) => asset.id)).size, 60);
  assert.equal(second.json().assets.some((asset) => first.json().assets.some((prior) => prior.id === asset.id)), false);
  assert.equal((await call(app, bob.accessToken, 'GET', '/v1/albums/' + privateAlbum.id + '/assets?limit=60')).statusCode, 404);
  assert.equal((await call(app, bob.accessToken, 'GET', '/v1/albums/' + shared.id + '/assets?limit=101')).statusCode, 400);
});

test('server trash hides deleted assets and emits DELETE/RESTORE only for shared media', async (t) => {
  const { app, client, config, alice, bob } = await fixture(t);
  const privateAlbum = await createAlbum(app, alice.accessToken, 'private-trash');
  await client.album.update({ where: { id: privateAlbum.id }, data: { sharedAt: null } });
  const privateAsset = await createAsset(app, alice.accessToken, privateAlbum.id, Buffer.from('private'), {
    clientAssetId: 'private-client', expectedSha256: createHash('sha256').update('private').digest('hex'),
  });
  const privateHash = createHash('sha256').update('private').digest('hex');
  const privateRow = await client.asset.update({
    where: { id: privateAsset.id }, data: { status: 'ready', sha256: privateHash },
  });
  const privateOriginalPath = resolve(config.mediaRoot, privateRow.storagePath);
  await mkdir(resolve(privateOriginalPath, '..'), { recursive: true });
  await writeFile(privateOriginalPath, 'private');
  const privateThumbnailPath = resolve(config.mediaRoot, 'derivatives/' + privateAsset.id + '-thumbnail.webp');
  await mkdir(resolve(privateThumbnailPath, '..'), { recursive: true });
  await writeFile(privateThumbnailPath, 'thumb');
  await client.assetDerivative.create({ data: {
    assetId: privateAsset.id, kind: 'thumbnail', status: 'ready', mimeType: 'image/webp',
    storagePath: 'derivatives/' + privateAsset.id + '-thumbnail.webp', fileSize: 5n,
    width: 1, height: 1, sha256: createHash('sha256').update('thumb').digest('hex'),
  } });
  const privateBaseline = await drainChanges(app, bob.accessToken);
  const deletedPrivate = await call(app, alice.accessToken, 'DELETE', '/v1/assets/' + privateAsset.id);
  assert.equal(deletedPrivate.statusCode, 200, deletedPrivate.body);
  assert.equal((await call(app, alice.accessToken, 'GET', '/v1/albums/' + privateAlbum.id + '/assets')).json().assets.length, 0);
  const trash = await call(app, alice.accessToken, 'GET', '/v1/trash');
  assert.equal(trash.statusCode, 200);
  assert.equal(trash.json().assets[0].id, privateAsset.id);
  assert.ok(trash.json().assets[0].remainingRetentionSeconds > 0);
  assert.equal((await call(app, alice.accessToken, 'GET', '/v1/trash/assets/' + privateAsset.id + '/thumbnail')).statusCode, 200);
  assert.equal((await call(app, bob.accessToken, 'GET', '/v1/trash/assets/' + privateAsset.id + '/thumbnail')).statusCode, 404);
  assert.equal((await call(app, bob.accessToken, 'POST', '/v1/trash/assets/' + privateAsset.id + '/restore')).statusCode, 404);
  assert.equal((await call(app, bob.accessToken, 'DELETE', '/v1/trash/assets/' + privateAsset.id)).statusCode, 404);
  assert.deepEqual((await drainChanges(app, bob.accessToken, privateBaseline.cursor)).changes, []);

  const shared = await createAlbum(app, alice.accessToken, 'shared-trash');
  const sharedAsset = await createAsset(app, alice.accessToken, shared.id, Buffer.from('shared'));
  const sharedHash = createHash('sha256').update('shared').digest('hex');
  const sharedRow = await client.asset.update({
    where: { id: sharedAsset.id }, data: { status: 'ready', sha256: sharedHash },
  });
  const sharedOriginalPath = resolve(config.mediaRoot, sharedRow.storagePath);
  await mkdir(resolve(sharedOriginalPath, '..'), { recursive: true });
  await writeFile(sharedOriginalPath, 'shared');
  const baseline = await drainChanges(app, bob.accessToken);
  assert.equal((await call(app, alice.accessToken, 'DELETE', '/v1/assets/' + sharedAsset.id)).statusCode, 200);
  const removed = await drainChanges(app, bob.accessToken, baseline.cursor);
  assert.equal(removed.changes.filter((change) => change.assetId === sharedAsset.id && change.operation === 'DELETE').length, 1);
  assert.equal((await call(app, alice.accessToken, 'POST', '/v1/trash/assets/' + sharedAsset.id + '/restore')).statusCode, 200);
  const restored = await drainChanges(app, bob.accessToken, removed.cursor);
  assert.equal(restored.changes.filter((change) => change.assetId === sharedAsset.id && change.operation === 'RESTORE').length, 1);
  assert.equal((await call(app, bob.accessToken, 'GET', '/v1/assets/' + sharedAsset.id)).statusCode, 200);
});

test('trash cleanup is retryable, idempotent and leaves a tombstone', async (t) => {
  const { app, client, config, alice, bob } = await fixture(t);
  const album = await createAlbum(app, alice.accessToken, 'cleanup');
  const asset = await createAsset(app, alice.accessToken, album.id, Buffer.from('cleanup'), {
    clientAssetId: 'cleanup-client', expectedSha256: 'd'.repeat(64),
  });
  await client.asset.update({ where: { id: asset.id }, data: { status: 'ready', sha256: 'd'.repeat(64) } });
  const originalPath = resolve(config.mediaRoot, 'originals/' + alice.user.id + '/' + asset.id + '/original');
  const derivativePath = resolve(config.mediaRoot, 'derivatives/' + asset.id + '-thumbnail.webp');
  await mkdir(resolve(originalPath, '..'), { recursive: true });
  await mkdir(resolve(derivativePath, '..'), { recursive: true });
  await writeFile(originalPath, 'cleanup');
  await writeFile(derivativePath, 'thumb');
  await client.assetDerivative.create({ data: {
    assetId: asset.id, kind: 'thumbnail', status: 'ready', mimeType: 'image/webp',
    storagePath: 'derivatives/' + asset.id + '-thumbnail.webp', fileSize: 5n, width: 1, height: 1, sha256: 'e'.repeat(64),
  } });
  assert.equal((await call(app, alice.accessToken, 'DELETE', '/v1/assets/' + asset.id)).statusCode, 200);
  const afterDelete = await drainChanges(app, bob.accessToken);
  const media = createMediaService(client, config);
  assert.equal(await media.cleanupExpired(new Date(Date.now() + 89 * 24 * 60 * 60 * 1000)), 0);
  await access(originalPath); await access(derivativePath);
  assert.equal(await media.cleanupExpired(new Date(Date.now() + 91 * 24 * 60 * 60 * 1000)), 1);
  await assert.rejects(access(originalPath));
  await assert.rejects(access(derivativePath));
  assert.equal((await client.asset.findUnique({ where: { id: asset.id } })).status, 'purged');
  assert.equal(await client.assetDerivative.count({ where: { assetId: asset.id } }), 0);
  assert.deepEqual((await drainChanges(app, bob.accessToken, afterDelete.cursor)).changes, []);
  assert.equal(await media.cleanupExpired(new Date(Date.now() + 92 * 24 * 60 * 60 * 1000)), 0);
  assert.equal((await call(app, alice.accessToken, 'GET', '/v1/trash')).json().assets.length, 0);
  const reintroduced = await call(app, alice.accessToken, 'POST', '/v1/albums/' + album.id + '/assets', {
    originalFileName: 'retry.jpg', mimeType: 'image/jpeg', fileSize: '7', width: 1, height: 1,
    clientAssetId: 'cleanup-client', expectedSha256: 'd'.repeat(64),
  });
  assert.equal(reintroduced.statusCode, 409);
});

test('partial purge remains irreversible and resumes after derivative deletion failure', async (t) => {
  const { app, client, config, alice } = await fixture(t);
  const album = await createAlbum(app, alice.accessToken, 'partial-purge');
  const bytes = Buffer.from('irreversible original');
  const hash = createHash('sha256').update(bytes).digest('hex');
  const created = await createAsset(app, alice.accessToken, album.id, bytes, {
    clientAssetId: 'partial-purge-client', expectedSha256: hash,
  });
  const asset = await client.asset.update({
    where: { id: created.id }, data: { status: 'ready', sha256: hash },
  });
  const originalPath = resolve(config.mediaRoot, asset.storagePath);
  await mkdir(resolve(originalPath, '..'), { recursive: true });
  await writeFile(originalPath, bytes);
  const blockedPath = resolve(config.mediaRoot, 'derivatives/' + asset.id + '/blocked.webp');
  await mkdir(blockedPath, { recursive: true });
  await client.assetDerivative.create({ data: {
    assetId: asset.id, kind: 'thumbnail', status: 'ready', mimeType: 'image/webp',
    storagePath: 'derivatives/' + asset.id + '/blocked.webp', fileSize: 1n,
    width: 1, height: 1, sha256: 'f'.repeat(64),
  } });
  assert.equal((await call(app, alice.accessToken, 'DELETE', '/v1/assets/' + asset.id)).statusCode, 200);
  const failed = await call(app, alice.accessToken, 'DELETE', '/v1/trash/assets/' + asset.id);
  assert.equal(failed.statusCode, 503, failed.body);
  await assert.rejects(access(originalPath));
  const afterFailure = await client.asset.findUniqueOrThrow({ where: { id: asset.id } });
  assert.equal(afterFailure.status, 'purging');
  assert.equal(afterFailure.cleanupLeaseId, null);
  assert.equal((await call(app, alice.accessToken, 'POST', '/v1/trash/assets/' + asset.id + '/restore')).statusCode, 409);

  await rm(blockedPath, { recursive: true });
  const media = createMediaService(client, config);
  assert.equal(await media.cleanupExpired(), 1);
  assert.equal((await client.asset.findUniqueOrThrow({ where: { id: asset.id } })).status, 'purged');
  assert.equal(await client.assetDerivative.count({ where: { assetId: asset.id } }), 0);
  assert.equal(await media.cleanupExpired(), 0);
});

test('trash cleanup reclaims only expired leases after a simulated server crash', async (t) => {
  const { app, client, config, alice } = await fixture(t);
  const album = await createAlbum(app, alice.accessToken, 'purge-crash');
  const bytes = Buffer.from('crash window');
  const hash = createHash('sha256').update(bytes).digest('hex');
  const created = await createAsset(app, alice.accessToken, album.id, bytes, {
    clientAssetId: 'purge-crash-client', expectedSha256: hash,
  });
  const asset = await client.asset.update({
    where: { id: created.id }, data: { status: 'ready', sha256: hash },
  });
  const originalPath = resolve(config.mediaRoot, asset.storagePath);
  await mkdir(resolve(originalPath, '..'), { recursive: true });
  await writeFile(originalPath, bytes);
  assert.equal((await call(app, alice.accessToken, 'DELETE', '/v1/assets/' + asset.id)).statusCode, 200);
  const now = new Date();
  await rm(originalPath);
  assert.equal((await call(app, alice.accessToken, 'POST', '/v1/trash/assets/' + asset.id + '/restore')).statusCode, 409);
  await client.asset.update({ where: { id: asset.id }, data: {
    status: 'purging', cleanupLeaseId: randomUUID(),
    cleanupLeaseExpiresAt: new Date(now.getTime() + 60_000),
  } });
  const media = createMediaService(client, config);
  assert.equal(await media.cleanupExpired(now), 0);
  assert.equal((await client.asset.findUniqueOrThrow({ where: { id: asset.id } })).status, 'purging');
  assert.equal((await call(app, alice.accessToken, 'POST', '/v1/trash/assets/' + asset.id + '/restore')).statusCode, 409);
  assert.equal(await media.cleanupExpired(new Date(now.getTime() + 61_000)), 1);
  assert.equal((await client.asset.findUniqueOrThrow({ where: { id: asset.id } })).status, 'purged');
});

test('derivative finalization racing purge removes the unreferenced final file', async (t) => {
  const { app, client, config, alice } = await fixture(t);
  const album = await createAlbum(app, alice.accessToken, 'derivative-purge-race');
  const source = await sharp({ create: {
    width: 64, height: 64, channels: 3, background: { r: 20, g: 40, b: 60 },
  } }).jpeg().toBuffer();
  const asset = await createAsset(app, alice.accessToken, album.id, source);
  const uploaded = await call(app, alice.accessToken, 'PUT', '/v1/assets/' + asset.id + '/original',
    source, { 'content-type': 'application/octet-stream', 'content-length': String(source.length) });
  assert.equal(uploaded.statusCode, 200, uploaded.body);
  let finalizedPath;
  const worker = createDerivativeWorker(client, config, createLogger('silent'), {
    afterFileFinalized: async (assetId, storagePath) => {
      finalizedPath = resolve(config.mediaRoot, storagePath);
      const now = new Date();
      await client.asset.update({ where: { id: assetId }, data: {
        status: 'purging', deletedAt: now, purgeAfter: new Date(now.getTime() + 60_000),
        cleanupLeaseId: randomUUID(), cleanupLeaseExpiresAt: new Date(now.getTime() + 60_000),
      } });
    },
  });
  assert.equal(await worker.runOnce(), 1);
  assert.ok(finalizedPath);
  await assert.rejects(access(finalizedPath));
  assert.equal((await client.asset.findUniqueOrThrow({ where: { id: asset.id } })).status, 'purging');
  assert.equal(await client.assetDerivative.count({
    where: { assetId: asset.id, status: 'ready', storagePath: { not: null } },
  }), 0);
});

test('an active derivative worker renews its lease during long processing', async (t) => {
  const { app, client, config, alice } = await fixture(t);
  const album = await createAlbum(app, alice.accessToken, 'derivative-heartbeat');
  const source = await sharp({ create: {
    width: 64, height: 64, channels: 3, background: { r: 80, g: 40, b: 20 },
  } }).jpeg().toBuffer();
  const asset = await createAsset(app, alice.accessToken, album.id, source);
  assert.equal((await call(app, alice.accessToken, 'PUT', '/v1/assets/' + asset.id + '/original',
    source, { 'content-type': 'application/octet-stream', 'content-length': String(source.length) })).statusCode, 200);
  await client.assetDerivative.updateMany({
    where: { assetId: asset.id, kind: 'optimized' },
    data: {
      status: 'failed', attempts: config.derivativeMaxAttempts,
      lastError: 'permanent fixture error', nextAttemptAt: new Date(0),
    },
  });

  let enteredHook;
  const entered = new Promise((resolvePromise) => { enteredHook = resolvePromise; });
  let releaseHook;
  const release = new Promise((resolvePromise) => { releaseHook = resolvePromise; });
  const worker = createDerivativeWorker(client, config, createLogger('silent'), {
    processingLeaseMs: 150,
    beforeGenerate: async () => {
      enteredHook();
      await release;
    },
  });
  const running = worker.runOnce();
  await entered;
  const initial = await client.assetDerivative.findFirstOrThrow({
    where: { assetId: asset.id, kind: 'thumbnail' },
  });
  assert.ok(initial.processingLeaseExpiresAt);
  await new Promise((resolvePromise) => setTimeout(resolvePromise, 220));
  const renewed = await client.assetDerivative.findUniqueOrThrow({ where: { id: initial.id } });
  assert.equal(renewed.status, 'processing');
  assert.equal(renewed.processingLeaseId, initial.processingLeaseId);
  assert.ok(renewed.processingLeaseExpiresAt > initial.processingLeaseExpiresAt);
  assert.ok(renewed.processingLeaseExpiresAt > new Date());
  releaseHook();
  assert.equal(await running, 1);
  assert.equal((await client.assetDerivative.findUniqueOrThrow({ where: { id: initial.id } })).status, 'ready');
});

test('a derivative worker that loses its claim cannot remove the parallel winner output', async (t) => {
  const { app, client, config, alice } = await fixture(t);
  const album = await createAlbum(app, alice.accessToken, 'parallel-derivative-finalization');
  const source = await sharp({ create: {
    width: 128, height: 96, channels: 3, background: { r: 100, g: 20, b: 60 },
  } }).jpeg().toBuffer();
  const asset = await createAsset(app, alice.accessToken, album.id, source);
  assert.equal((await call(app, alice.accessToken, 'PUT', '/v1/assets/' + asset.id + '/original',
    source, { 'content-type': 'application/octet-stream', 'content-length': String(source.length) })).statusCode, 200);
  await client.assetDerivative.updateMany({
    where: { assetId: asset.id, kind: 'optimized' },
    data: {
      status: 'failed', attempts: config.derivativeMaxAttempts,
      lastError: 'permanent fixture error', nextAttemptAt: new Date(0),
    },
  });

  let releaseFirst;
  const release = new Promise((resolvePromise) => { releaseFirst = resolvePromise; });
  let firstFinalized;
  const finalized = new Promise((resolvePromise) => { firstFinalized = resolvePromise; });
  let firstStoragePath;
  const firstWorker = createDerivativeWorker(client, config, createLogger('silent'), {
    afterFileFinalized: async (_assetId, storagePath) => {
      firstStoragePath = storagePath;
      firstFinalized();
      await release;
    },
  });
  const firstRun = firstWorker.runOnce();
  await finalized;
  const processing = await client.assetDerivative.findFirstOrThrow({
    where: { assetId: asset.id, kind: 'thumbnail' },
  });
  assert.equal(processing.status, 'processing');
  assert.ok(processing.processingLeaseExpiresAt);

  const secondWorker = createDerivativeWorker(client, config, createLogger('silent'));
  assert.equal(await secondWorker.recoverStaleJobs(new Date(processing.processingLeaseExpiresAt.getTime() + 1)), 1);
  await client.assetDerivative.update({
    where: { id: processing.id },
    data: { nextAttemptAt: new Date(0) },
  });
  assert.equal(await secondWorker.runOnce(), 1);
  const winner = await client.assetDerivative.findUniqueOrThrow({ where: { id: processing.id } });
  assert.equal(winner.status, 'ready');
  assert.ok(winner.storagePath);
  assert.notEqual(winner.storagePath, firstStoragePath);
  await access(resolve(config.mediaRoot, winner.storagePath));

  releaseFirst();
  assert.equal(await firstRun, 1);
  const afterLoser = await client.assetDerivative.findUniqueOrThrow({ where: { id: processing.id } });
  assert.equal(afterLoser.status, 'ready');
  assert.equal(afterLoser.storagePath, winner.storagePath);
  await access(resolve(config.mediaRoot, winner.storagePath));
  await assert.rejects(access(resolve(config.mediaRoot, firstStoragePath)));

  const orphan = resolve(dirname(resolve(config.mediaRoot, winner.storagePath)),
    'thumbnail-' + randomUUID() + '.webp');
  await writeFile(orphan, 'stranded-final');
  await utimes(orphan, new Date(0), new Date(0));
  await secondWorker.reconcileReadyDerivatives();
  await assert.rejects(access(orphan));
});

test('temporary derivative failures remain automatically retryable beyond the attempt budget', async (t) => {
  const { app, client, config, alice } = await fixture(t);
  const album = await createAlbum(app, alice.accessToken, 'transient-derivative-recovery');
  const source = await sharp({ create: {
    width: 96, height: 96, channels: 3, background: { r: 30, g: 90, b: 140 },
  } }).jpeg().toBuffer();
  const asset = await createAsset(app, alice.accessToken, album.id, source);
  assert.equal((await call(app, alice.accessToken, 'PUT', '/v1/assets/' + asset.id + '/original',
    source, { 'content-type': 'application/octet-stream', 'content-length': String(source.length) })).statusCode, 200);
  await client.assetDerivative.updateMany({
    where: { assetId: asset.id, kind: 'optimized' },
    data: {
      status: 'failed', attempts: config.derivativeMaxAttempts,
      lastError: 'permanent fixture error', nextAttemptAt: new Date(0),
    },
  });
  let failuresRemaining = config.derivativeMaxAttempts + 1;
  const worker = createDerivativeWorker(client, config, createLogger('silent'), {
    beforeGenerate: async () => {
      if (failuresRemaining > 0) {
        failuresRemaining -= 1;
        const error = new Error('simulated full filesystem');
        error.code = 'ENOSPC';
        throw error;
      }
    },
  });
  const derivative = await client.assetDerivative.findFirstOrThrow({
    where: { assetId: asset.id, kind: 'thumbnail' },
  });
  for (let attempt = 0; attempt <= config.derivativeMaxAttempts; attempt += 1) {
    await client.assetDerivative.update({
      where: { id: derivative.id },
      data: { nextAttemptAt: new Date(0) },
    });
    assert.equal(await worker.runOnce(), 1);
    const failed = await client.assetDerivative.findUniqueOrThrow({ where: { id: derivative.id } });
    assert.equal(failed.status, 'failed');
    assert.match(failed.lastError, /^\[transient\]/);
  }
  const beyondBudget = await client.assetDerivative.findUniqueOrThrow({ where: { id: derivative.id } });
  assert.ok(beyondBudget.attempts > config.derivativeMaxAttempts);
  await client.assetDerivative.update({ where: { id: derivative.id }, data: { nextAttemptAt: new Date(0) } });
  assert.equal(await worker.runOnce(), 1);
  const healed = await client.assetDerivative.findUniqueOrThrow({ where: { id: derivative.id } });
  assert.equal(healed.status, 'ready');
  await access(resolve(config.mediaRoot, healed.storagePath));
});

test('original integrity worker detects same-size corruption and missing files and later heals', async (t) => {
  const { app, client, config, alice, bob } = await fixture(t);
  const album = await createAlbum(app, alice.accessToken, 'original-integrity');
  const source = await sharp({ create: {
    width: 80, height: 60, channels: 3, background: { r: 10, g: 120, b: 50 },
  } }).jpeg().toBuffer();
  const asset = await createAsset(app, alice.accessToken, album.id, source);
  assert.equal((await call(app, alice.accessToken, 'PUT', '/v1/assets/' + asset.id + '/original',
    source, { 'content-type': 'application/octet-stream', 'content-length': String(source.length) })).statusCode, 200);
  const stored = await client.asset.findUniqueOrThrow({ where: { id: asset.id } });
  const originalPath = resolve(config.mediaRoot, stored.storagePath);
  const worker = createOriginalIntegrityWorker(client, config, createLogger('silent'));

  await writeFile(originalPath, Buffer.alloc(source.length, 0x5a));
  assert.equal(await worker.runOnce(), 1);
  const corrupt = await client.asset.findUniqueOrThrow({ where: { id: asset.id } });
  assert.equal(corrupt.integrityStatus, 'error');
  assert.match(corrupt.integrityError, /SHA-256/);
  const ownerDownload = await call(app, alice.accessToken, 'GET', '/v1/assets/' + asset.id + '/original');
  assert.equal(ownerDownload.statusCode, 503);
  assert.equal(ownerDownload.json().error.code, 'ORIGINAL_UNAVAILABLE');
  assert.equal((await call(app, bob.accessToken, 'GET', '/v1/assets/' + asset.id)).statusCode, 404);
  const degraded = await app.inject('/health');
  assert.equal(degraded.statusCode, 200);
  assert.equal(degraded.json().checks.originals, 'degraded');
  assert.equal(degraded.json().originals.errors, 1);

  await writeFile(originalPath, source);
  assert.equal(await worker.runOnce(), 1);
  assert.equal((await client.asset.findUniqueOrThrow({ where: { id: asset.id } })).integrityStatus, 'healthy');
  assert.equal((await call(app, alice.accessToken, 'GET', '/v1/assets/' + asset.id + '/original')).statusCode, 200);

  await unlink(originalPath);
  assert.equal(await worker.runOnce(), 1);
  const missing = await client.asset.findUniqueOrThrow({ where: { id: asset.id } });
  assert.equal(missing.integrityStatus, 'error');
  assert.match(missing.integrityError, /missing/);
  assert.equal((await call(app, alice.accessToken, 'GET', '/v1/assets/' + asset.id + '/original')).statusCode, 503);
});

test('an owner can idempotently cancel and clean an interrupted upload session', async (t) => {
  const { app, client, config, alice } = await fixture(t);
  const album = await createAlbum(app, alice.accessToken, 'cancel-upload');
  const bytes = Buffer.from('content-uri disappeared before upload completion');
  const asset = await createAsset(app, alice.accessToken, album.id, bytes, {
    clientAssetId: 'missing-content-uri', expectedSha256: createHash('sha256').update(bytes).digest('hex'),
  });
  const session = await call(app, alice.accessToken, 'POST', '/v1/assets/' + asset.id + '/upload-session');
  assert.equal(session.statusCode, 200, session.body);
  const prefix = bytes.subarray(0, 7);
  const partial = await call(app, alice.accessToken, 'PATCH', '/v1/upload-sessions/' + session.json().id,
    prefix, {
      'content-type': 'application/octet-stream', 'content-length': String(prefix.length), 'upload-offset': '0',
    });
  assert.equal(partial.statusCode, 200, partial.body);
  const uploading = await client.asset.findUniqueOrThrow({ where: { id: asset.id } });
  const partPath = resolve(config.mediaRoot, uploading.uploadPartPath);
  await access(partPath);

  const cancelled = await call(app, alice.accessToken, 'DELETE', '/v1/assets/' + asset.id + '/upload');
  assert.equal(cancelled.statusCode, 200, cancelled.body);
  assert.deepEqual(cancelled.json(), { id: asset.id, cancelled: true });
  assert.equal(await client.asset.findUnique({ where: { id: asset.id } }), null);
  await assert.rejects(access(partPath));
  assert.equal((await call(app, alice.accessToken, 'DELETE', '/v1/assets/' + asset.id + '/upload')).statusCode, 404);
});
