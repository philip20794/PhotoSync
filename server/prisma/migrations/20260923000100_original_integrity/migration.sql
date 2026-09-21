ALTER TABLE assets
  ADD COLUMN "integrityStatus" varchar(16) NOT NULL DEFAULT 'healthy',
  ADD COLUMN "integrityError" varchar(500),
  ADD COLUMN "integrityCheckedAt" timestamptz(3);

ALTER TABLE assets
  ADD CONSTRAINT assets_integrity_status_check CHECK (
    ("integrityStatus" = 'healthy' AND "integrityError" IS NULL)
    OR ("integrityStatus" = 'error' AND "integrityError" IS NOT NULL)
  );

CREATE INDEX assets_integrity_status_idx ON assets(status, "integrityStatus");

UPDATE service_metadata SET value = '11' WHERE key = 'schema_version';
