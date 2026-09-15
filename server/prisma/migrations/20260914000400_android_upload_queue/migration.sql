BEGIN;
ALTER TABLE "albums" ADD COLUMN "sharedAt" TIMESTAMPTZ(3);
UPDATE "albums" SET "sharedAt" = "createdAt";

ALTER TABLE "assets"
  ADD COLUMN "sourceDeviceId" UUID,
  ADD COLUMN "clientAssetId" VARCHAR(255),
  ADD COLUMN "expectedSha256" CHAR(64);
ALTER TABLE "assets" ADD CONSTRAINT "assets_source_identity_pair_check"
  CHECK (("sourceDeviceId" IS NULL AND "clientAssetId" IS NULL) OR
         ("sourceDeviceId" IS NOT NULL AND "clientAssetId" IS NOT NULL));
ALTER TABLE "assets" ADD CONSTRAINT "assets_expectedSha256_check"
  CHECK ("expectedSha256" IS NULL OR "expectedSha256" ~ '^[a-f0-9]{64}$');
ALTER TABLE "assets" ADD CONSTRAINT "assets_sourceDeviceId_ownerId_fkey"
  FOREIGN KEY ("sourceDeviceId", "ownerId") REFERENCES "devices"("id", "userId")
  ON DELETE RESTRICT ON UPDATE CASCADE;
ALTER TABLE "assets" ADD CONSTRAINT "assets_sourceDeviceId_clientAssetId_key"
  UNIQUE ("sourceDeviceId", "clientAssetId");
ALTER TABLE "assets" ADD CONSTRAINT "assets_albumId_expectedSha256_key"
  UNIQUE ("albumId", "expectedSha256");

UPDATE "service_metadata" SET "value" = '4' WHERE "key" = 'schema_version';
COMMIT;
