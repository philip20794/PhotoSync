BEGIN;
CREATE TABLE "service_metadata" (
    "key" VARCHAR(64) NOT NULL,
    "value" VARCHAR(255) NOT NULL,
    CONSTRAINT "service_metadata_pkey" PRIMARY KEY ("key")
);
INSERT INTO "service_metadata" ("key", "value") VALUES ('schema_version', '1');
COMMIT;
