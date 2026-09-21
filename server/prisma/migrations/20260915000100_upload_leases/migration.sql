ALTER TABLE "assets"
  ADD COLUMN "uploadStartedAt" TIMESTAMPTZ(3),
  ADD COLUMN "uploadLeaseId" UUID,
  ADD COLUMN "uploadLeaseExpiresAt" TIMESTAMPTZ(3);

CREATE INDEX "assets_upload_recovery_idx"
  ON "assets" ("status", "uploadLeaseExpiresAt");

INSERT INTO "service_metadata" ("key", "value")
VALUES ('schema_version', '6')
ON CONFLICT ("key")
DO UPDATE SET "value" = EXCLUDED."value";
