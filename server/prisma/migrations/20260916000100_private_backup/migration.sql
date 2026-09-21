ALTER TABLE "albums" ADD COLUMN "backedUpAt" TIMESTAMPTZ(3);

COMMENT ON COLUMN "albums"."backedUpAt" IS
  'Durable private-backup retention marker, independent from partner sharing';
