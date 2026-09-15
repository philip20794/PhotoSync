BEGIN;
ALTER TABLE "devices" ADD CONSTRAINT "devices_id_userId_key" UNIQUE ("id", "userId");
CREATE TABLE "albums" (
  "id" UUID NOT NULL PRIMARY KEY,
  "ownerId" UUID NOT NULL REFERENCES "users"("id") ON DELETE RESTRICT ON UPDATE CASCADE,
  "sourceDeviceId" UUID NOT NULL,
  "clientAlbumId" VARCHAR(255) NOT NULL CHECK (length(trim("clientAlbumId")) > 0),
  "title" VARCHAR(200) NOT NULL CHECK (length(trim("title")) > 0),
  "createdAt" TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "updatedAt" TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT "albums_sourceDeviceId_ownerId_fkey" FOREIGN KEY ("sourceDeviceId", "ownerId") REFERENCES "devices"("id", "userId") ON DELETE RESTRICT ON UPDATE CASCADE,
  CONSTRAINT "albums_sourceDeviceId_clientAlbumId_key" UNIQUE ("sourceDeviceId", "clientAlbumId"),
  CONSTRAINT "albums_id_ownerId_key" UNIQUE ("id", "ownerId")
);
CREATE INDEX "albums_ownerId_idx" ON "albums"("ownerId");
CREATE TABLE "assets" (
  "id" UUID NOT NULL PRIMARY KEY,
  "ownerId" UUID NOT NULL REFERENCES "users"("id") ON DELETE RESTRICT ON UPDATE CASCADE,
  "albumId" UUID NOT NULL,
  "originalFileName" VARCHAR(255) NOT NULL CHECK (length(trim("originalFileName")) > 0),
  "mimeType" VARCHAR(127) NOT NULL CHECK ("mimeType" ~ '^[a-z0-9][a-z0-9!#$&^_.+\\-]*/[a-z0-9][a-z0-9!#$&^_.+\\-]*$'),
  "capturedAt" TIMESTAMPTZ(3),
  "fileSize" BIGINT NOT NULL CHECK ("fileSize" > 0),
  "width" INTEGER NOT NULL CHECK ("width" > 0),
  "height" INTEGER NOT NULL CHECK ("height" > 0),
  "durationMillis" BIGINT CHECK ("durationMillis" >= 0),
  "sha256" CHAR(64) CHECK ("sha256" ~ '^[a-f0-9]{64}$'),
  "storagePath" VARCHAR(512) NOT NULL UNIQUE,
  "status" VARCHAR(16) NOT NULL DEFAULT 'pending' CHECK ("status" IN ('pending', 'uploading', 'ready', 'failed')),
  "createdAt" TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "updatedAt" TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT "assets_albumId_ownerId_fkey" FOREIGN KEY ("albumId", "ownerId") REFERENCES "albums"("id", "ownerId") ON DELETE RESTRICT ON UPDATE CASCADE,
  CHECK (("mimeType" LIKE 'video/%' AND "durationMillis" IS NOT NULL) OR ("mimeType" NOT LIKE 'video/%' AND "durationMillis" IS NULL)),
  CHECK (("status" = 'ready' AND "sha256" IS NOT NULL) OR ("status" <> 'ready' AND "sha256" IS NULL))
);
CREATE INDEX "assets_ownerId_idx" ON "assets"("ownerId");
CREATE INDEX "assets_albumId_createdAt_idx" ON "assets"("albumId", "createdAt");
UPDATE "service_metadata" SET "value" = '3' WHERE "key" = 'schema_version';
COMMIT;
