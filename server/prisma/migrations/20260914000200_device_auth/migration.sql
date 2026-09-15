BEGIN;
CREATE TABLE "pairs" (
  "id" UUID NOT NULL PRIMARY KEY,
  "singleton" INTEGER NOT NULL DEFAULT 1 UNIQUE CHECK ("singleton" = 1),
  "setupCompletedAt" TIMESTAMPTZ(3),
  "createdAt" TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
INSERT INTO "pairs" ("id") VALUES (gen_random_uuid());
CREATE TABLE "users" (
  "id" UUID NOT NULL PRIMARY KEY,
  "pairId" UUID NOT NULL REFERENCES "pairs"("id") ON DELETE RESTRICT ON UPDATE CASCADE,
  "memberSlot" INTEGER NOT NULL CHECK ("memberSlot" IN (1, 2)),
  "displayName" VARCHAR(80) NOT NULL CHECK (length(trim("displayName")) > 0),
  "createdAt" TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT "users_pairId_memberSlot_key" UNIQUE ("pairId", "memberSlot")
);
CREATE TABLE "devices" (
  "id" UUID NOT NULL PRIMARY KEY,
  "userId" UUID NOT NULL REFERENCES "users"("id") ON DELETE RESTRICT ON UPDATE CASCADE,
  "name" VARCHAR(80) NOT NULL CHECK (length(trim("name")) > 0),
  "tokenHash" CHAR(64) UNIQUE CHECK ("tokenHash" ~ '^[a-f0-9]{64}$'),
  "createdAt" TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "revokedAt" TIMESTAMPTZ(3),
  CHECK (("tokenHash" IS NULL) = ("revokedAt" IS NOT NULL))
);
CREATE INDEX "devices_userId_idx" ON "devices"("userId");
CREATE TABLE "pairing_codes" (
  "id" UUID NOT NULL PRIMARY KEY,
  "pairId" UUID NOT NULL REFERENCES "pairs"("id") ON DELETE RESTRICT ON UPDATE CASCADE,
  "createdByDeviceId" UUID NOT NULL REFERENCES "devices"("id") ON DELETE RESTRICT ON UPDATE CASCADE,
  "targetUserId" UUID REFERENCES "users"("id") ON DELETE RESTRICT ON UPDATE CASCADE,
  "purpose" VARCHAR(16) NOT NULL CHECK ("purpose" IN ('partner', 'device')),
  "codeHash" CHAR(64) NOT NULL UNIQUE CHECK ("codeHash" ~ '^[a-f0-9]{64}$'),
  "createdAt" TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "expiresAt" TIMESTAMPTZ(3) NOT NULL,
  "consumedAt" TIMESTAMPTZ(3),
  "revokedAt" TIMESTAMPTZ(3),
  CHECK ("expiresAt" > "createdAt"),
  CHECK (("purpose" = 'partner' AND "targetUserId" IS NULL) OR
         ("purpose" = 'device' AND "targetUserId" IS NOT NULL))
);
CREATE INDEX "pairing_codes_createdByDeviceId_idx" ON "pairing_codes"("createdByDeviceId");
CREATE INDEX "pairing_codes_expiresAt_idx" ON "pairing_codes"("expiresAt");
UPDATE "service_metadata" SET "value" = '2' WHERE "key" = 'schema_version';
COMMIT;
