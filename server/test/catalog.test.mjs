import assert from 'node:assert/strict';
import { mkdtemp, mkdir, readFile, readlink, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import { createMediaCatalogue } from '../dist/media/catalog.js';

const logger = { error() {} };

test('local catalogue projects active, private backup and trash originals as host-valid symlinks', async (t) => {
  const root = await mkdtemp(join(tmpdir(), 'photosync-catalogue-'));
  const mediaRoot = join(root, 'production', 'media');
  const catalogueRoot = join(root, 'production', 'catalog');
  const activePath = 'originals/owner-1/active/original';
  const deletedPath = 'originals/owner-1/deleted/original';
  await mkdir(join(mediaRoot, 'originals/owner-1/active'), { recursive: true });
  await mkdir(join(mediaRoot, 'originals/owner-1/deleted'), { recursive: true });
  await writeFile(join(mediaRoot, activePath), 'active');
  await writeFile(join(mediaRoot, deletedPath), 'deleted');
  let albums = [{
    id: 'album-1', title: 'Kamera', backedUpAt: new Date('2026-01-01T00:00:00Z'),
    owner: { id: 'owner-1', displayName: 'Philip' },
    assets: [
      { id: 'active-1', originalFileName: 'bild.jpg', storagePath: activePath, fileSize: 6n,
        status: 'ready', integrityStatus: 'healthy', capturedAt: new Date('2026-02-03T00:00:00Z'), createdAt: new Date() },
      { id: 'deleted-1', originalFileName: 'weg.jpg', storagePath: deletedPath, fileSize: 7n,
        status: 'deleted', integrityStatus: 'healthy', capturedAt: null, createdAt: new Date('2026-03-04T00:00:00Z') },
    ],
  }];
  const client = { album: { findMany: async () => albums } };
  const catalogue = createMediaCatalogue(client, { catalogRoot: catalogueRoot, mediaRoot }, logger);
  t.after(async () => { await catalogue.stop(); await rm(root, { recursive: true, force: true }); });
  await catalogue.runOnce();

  const activeLink = join(catalogueRoot, 'Philip', 'Alben', 'Kamera', 'bild.jpg');
  const backupLink = join(catalogueRoot, 'Philip', 'Auto-Backup', '2026', '02', 'bild.jpg');
  const trashLink = join(catalogueRoot, 'Philip', 'Papierkorb', 'Kamera', 'weg.jpg');
  assert.match(await readlink(activeLink), /\.media/);
  assert.equal(await readFile(activeLink, 'utf8'), 'active');
  assert.equal(await readFile(backupLink, 'utf8'), 'active');
  assert.equal(await readFile(trashLink, 'utf8'), 'deleted');
  assert.equal(await readlink(join(catalogueRoot, '.media')), '../media');

  albums = [];
  await catalogue.runOnce();
  await assert.rejects(readFile(activeLink));
});
