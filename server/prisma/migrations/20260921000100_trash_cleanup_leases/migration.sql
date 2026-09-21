ALTER TABLE assets
  ADD COLUMN "cleanupLeaseId" uuid,
  ADD COLUMN "cleanupLeaseExpiresAt" timestamptz;

CREATE INDEX assets_trash_recovery_idx
  ON assets(status, "cleanupLeaseExpiresAt");

UPDATE service_metadata SET value = '9' WHERE key = 'schema_version';
