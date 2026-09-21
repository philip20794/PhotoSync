ALTER TABLE "pairing_codes"
  ALTER COLUMN "createdByDeviceId" DROP NOT NULL,
  ADD COLUMN "createdByOperator" BOOLEAN NOT NULL DEFAULT false;

ALTER TABLE "pairing_codes"
  ADD CONSTRAINT "pairing_codes_creator_check" CHECK (
    ("createdByOperator" = false AND "createdByDeviceId" IS NOT NULL)
    OR ("createdByOperator" = true AND "createdByDeviceId" IS NULL AND "purpose" = 'device')
  );

UPDATE "service_metadata" SET "value" = '12' WHERE "key" = 'schema_version';
