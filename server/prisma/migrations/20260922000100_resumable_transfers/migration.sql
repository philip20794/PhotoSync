ALTER TABLE assets
  ADD COLUMN "uploadSessionId" uuid,
  ADD COLUMN "uploadOffset" bigint NOT NULL DEFAULT 0,
  ADD COLUMN "uploadPartPath" varchar(512),
  ADD COLUMN "uploadExpiresAt" timestamptz;

CREATE UNIQUE INDEX assets_uploadSessionId_key ON assets("uploadSessionId");
CREATE UNIQUE INDEX assets_uploadPartPath_key ON assets("uploadPartPath");

UPDATE assets
SET status = 'pending',
    "uploadStartedAt" = NULL,
    "uploadLeaseId" = NULL,
    "uploadLeaseExpiresAt" = NULL
WHERE status = 'uploading';

ALTER TABLE assets
  ADD CONSTRAINT assets_upload_session_check CHECK (
    (
      status = 'uploading'
      AND "uploadSessionId" IS NOT NULL
      AND "uploadPartPath" IS NOT NULL
      AND "uploadExpiresAt" IS NOT NULL
      AND "uploadOffset" >= 0
      AND "uploadOffset" <= "fileSize"
    )
    OR (
      status <> 'uploading'
      AND "uploadSessionId" IS NULL
      AND "uploadPartPath" IS NULL
      AND "uploadExpiresAt" IS NULL
      AND "uploadOffset" = 0
    )
  );

ALTER TABLE asset_derivatives
  ADD COLUMN "processingLeaseId" uuid,
  ADD COLUMN "processingLeaseExpiresAt" timestamptz;

UPDATE asset_derivatives
SET status = 'pending'
WHERE status = 'processing';

CREATE INDEX asset_derivatives_recovery_idx
  ON asset_derivatives(status, "processingLeaseExpiresAt");

ALTER TABLE asset_derivatives
  ADD CONSTRAINT asset_derivatives_processing_lease_check CHECK (
    (
      status = 'processing'
      AND "processingLeaseId" IS NOT NULL
      AND "processingLeaseExpiresAt" IS NOT NULL
    )
    OR (
      status <> 'processing'
      AND "processingLeaseId" IS NULL
      AND "processingLeaseExpiresAt" IS NULL
    )
  );

UPDATE service_metadata SET value = '10' WHERE key = 'schema_version';
