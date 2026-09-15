BEGIN;
CREATE TABLE "asset_derivatives" (
  "id" UUID NOT NULL,
  "assetId" UUID NOT NULL,
  "kind" VARCHAR(16) NOT NULL,
  "status" VARCHAR(16) NOT NULL DEFAULT 'pending',
  "mimeType" VARCHAR(127),
  "storagePath" VARCHAR(512),
  "fileSize" BIGINT,
  "width" INTEGER,
  "height" INTEGER,
  "durationMillis" BIGINT,
  "sha256" CHAR(64),
  "attempts" INTEGER NOT NULL DEFAULT 0,
  "lastError" VARCHAR(500),
  "nextAttemptAt" TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "createdAt" TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "updatedAt" TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT "asset_derivatives_pkey" PRIMARY KEY ("id"),
  CONSTRAINT "asset_derivatives_kind_check" CHECK ("kind" IN ('thumbnail', 'optimized')),
  CONSTRAINT "asset_derivatives_status_check" CHECK ("status" IN ('pending', 'processing', 'ready', 'failed')),
  CONSTRAINT "asset_derivatives_attempts_check" CHECK ("attempts" >= 0),
  CONSTRAINT "asset_derivatives_dimensions_check" CHECK (
    ("width" IS NULL AND "height" IS NULL) OR ("width" > 0 AND "height" > 0)
  ),
  CONSTRAINT "asset_derivatives_fileSize_check" CHECK ("fileSize" IS NULL OR "fileSize" > 0),
  CONSTRAINT "asset_derivatives_duration_check" CHECK ("durationMillis" IS NULL OR "durationMillis" >= 0),
  CONSTRAINT "asset_derivatives_sha256_check" CHECK ("sha256" IS NULL OR "sha256" ~ '^[a-f0-9]{64}$'),
  CONSTRAINT "asset_derivatives_ready_check" CHECK (
    ("status" = 'ready' AND "mimeType" IS NOT NULL AND "storagePath" IS NOT NULL AND
     "fileSize" IS NOT NULL AND "width" IS NOT NULL AND "height" IS NOT NULL AND "sha256" IS NOT NULL)
    OR
    ("status" <> 'ready' AND "mimeType" IS NULL AND "storagePath" IS NULL AND
     "fileSize" IS NULL AND "width" IS NULL AND "height" IS NULL AND "durationMillis" IS NULL AND "sha256" IS NULL)
  ),
  CONSTRAINT "asset_derivatives_assetId_fkey" FOREIGN KEY ("assetId") REFERENCES "assets"("id") ON DELETE CASCADE ON UPDATE CASCADE
);
CREATE UNIQUE INDEX "asset_derivatives_assetId_kind_key" ON "asset_derivatives"("assetId", "kind");
CREATE UNIQUE INDEX "asset_derivatives_storagePath_key" ON "asset_derivatives"("storagePath");
CREATE INDEX "asset_derivatives_status_nextAttemptAt_idx" ON "asset_derivatives"("status", "nextAttemptAt");
UPDATE "service_metadata" SET "value" = '5' WHERE "key" = 'schema_version';
COMMIT;
