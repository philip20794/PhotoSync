BEGIN;

-- Product decision: legacy PhotoSync application data is intentionally not
-- migrated. Infrastructure, the singleton instance row and migration history
-- remain intact.
TRUNCATE TABLE
  "asset_derivatives",
  "sync_push_devices",
  "sync_changes",
  "assets",
  "albums",
  "pairing_codes",
  "devices",
  "users"
RESTART IDENTITY CASCADE;

UPDATE "sync_head"
SET "epoch" = gen_random_uuid(), "revision" = 0, "minRevision" = 0
WHERE "id" = 1;

ALTER TABLE "users"
  ADD COLUMN "username" VARCHAR(80) NOT NULL,
  ADD COLUMN "passwordHash" VARCHAR(255);
ALTER TABLE "users"
  ADD CONSTRAINT "users_username_key" UNIQUE ("username"),
  ADD CONSTRAINT "users_username_normalized_check"
    CHECK ("username" = lower(trim("username")) AND length("username") > 0);

DROP TABLE "pairing_codes";

ALTER TABLE "albums"
  ADD COLUMN "sourceVolume" VARCHAR(80) NOT NULL,
  ADD COLUMN "sourceRelativePath" VARCHAR(512) NOT NULL;
ALTER TABLE "albums" DROP CONSTRAINT "albums_sourceDeviceId_clientAlbumId_key";
ALTER TABLE "albums"
  ADD CONSTRAINT "albums_ownerId_sourceVolume_sourceRelativePath_key"
  UNIQUE ("ownerId", "sourceVolume", "sourceRelativePath");

UPDATE "pairs" SET "setupCompletedAt" = NULL WHERE "singleton" = 1;
UPDATE "service_metadata" SET "value" = '13' WHERE "key" = 'schema_version';
COMMIT;
