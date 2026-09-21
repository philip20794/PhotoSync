ALTER TABLE assets
  ADD COLUMN "deletedAt" timestamptz,
  ADD COLUMN "purgeAfter" timestamptz,
  ADD COLUMN "cleanupRequestedAt" timestamptz,
  ADD COLUMN "cleanupAttempts" integer NOT NULL DEFAULT 0,
  ADD COLUMN "cleanupLastError" varchar(500),
  ADD COLUMN "purgedAt" timestamptz;

DROP INDEX IF EXISTS assets_trash_purge_idx;
CREATE INDEX assets_trash_purge_idx ON assets(status, "purgeAfter");

ALTER TABLE assets DROP CONSTRAINT IF EXISTS assets_status_check;
ALTER TABLE assets DROP CONSTRAINT IF EXISTS assets_sha256_check;
ALTER TABLE assets DROP CONSTRAINT IF EXISTS assets_status_sha256_check;
ALTER TABLE assets DROP CONSTRAINT IF EXISTS assets_trash_dates_check;

ALTER TABLE assets
  ADD CONSTRAINT assets_status_check CHECK (status IN ('pending','uploading','ready','failed','deleted','purging','purged')),
  ADD CONSTRAINT assets_status_sha256_check CHECK (
    (status IN ('ready','deleted','purging') AND "sha256" IS NOT NULL)
    OR (status NOT IN ('ready','deleted','purging') AND "sha256" IS NULL)
  ),
  ADD CONSTRAINT assets_trash_dates_check CHECK (
    (status IN ('deleted','purging') AND "deletedAt" IS NOT NULL AND "purgeAfter" IS NOT NULL)
    OR (status NOT IN ('deleted','purging') AND "deletedAt" IS NULL AND "purgeAfter" IS NULL)
  );

CREATE OR REPLACE FUNCTION sync_record() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE a record; item jsonb; old_item jsonb; new_item jsonb; asset_id uuid; next_revision bigint; pair_id uuid; was_shared boolean; operation text;
BEGIN
  old_item := CASE WHEN TG_OP = 'INSERT' THEN NULL ELSE to_jsonb(OLD) END;
  new_item := CASE WHEN TG_OP = 'DELETE' THEN NULL ELSE to_jsonb(NEW) END;
  item := CASE WHEN TG_OP = 'DELETE' THEN to_jsonb(OLD) ELSE to_jsonb(NEW) END;
  IF TG_TABLE_NAME = 'albums' AND TG_OP = 'UPDATE'
    AND (old_item->'title') IS NOT DISTINCT FROM (new_item->'title')
    AND (old_item->'sharedAt') IS NOT DISTINCT FROM (new_item->'sharedAt') THEN RETURN NULL;
  END IF;
  IF TG_TABLE_NAME = 'assets' THEN
    IF TG_OP = 'INSERT' AND new_item->>'status' <> 'ready' THEN RETURN NULL; END IF;
    IF TG_OP = 'DELETE' AND old_item->>'status' NOT IN ('ready','deleted','purging') THEN RETURN NULL; END IF;
    IF TG_OP = 'UPDATE' THEN
      IF old_item->>'status' NOT IN ('ready','deleted') AND new_item->>'status' NOT IN ('ready','deleted') THEN RETURN NULL; END IF;
      IF old_item->>'status' = 'ready' AND new_item->>'status' = 'ready'
        AND (old_item - ARRAY['updatedAt','uploadStartedAt','uploadLeaseId','uploadLeaseExpiresAt','cleanupAttempts','cleanupLastError','cleanupRequestedAt','purgeAfter','purgedAt']::text[])
          IS NOT DISTINCT FROM
            (new_item - ARRAY['updatedAt','uploadStartedAt','uploadLeaseId','uploadLeaseExpiresAt','cleanupAttempts','cleanupLastError','cleanupRequestedAt','purgeAfter','purgedAt']::text[])
      THEN RETURN NULL; END IF;
      IF old_item->>'status' = 'deleted' AND new_item->>'status' = 'deleted'
        AND (old_item - ARRAY['updatedAt','cleanupAttempts','cleanupLastError','cleanupRequestedAt']::text[])
          IS NOT DISTINCT FROM
            (new_item - ARRAY['updatedAt','cleanupAttempts','cleanupLastError','cleanupRequestedAt']::text[])
      THEN RETURN NULL; END IF;
    END IF;
  END IF;
  IF TG_TABLE_NAME = 'asset_derivatives' THEN
    IF TG_OP = 'INSERT' AND new_item->>'status' <> 'ready' THEN RETURN NULL; END IF;
    IF TG_OP = 'DELETE' AND old_item->>'status' <> 'ready' THEN RETURN NULL; END IF;
    IF TG_OP = 'UPDATE' THEN
      IF old_item->>'status' <> 'ready' AND new_item->>'status' <> 'ready' THEN RETURN NULL; END IF;
      IF old_item->>'status' = 'ready' AND new_item->>'status' = 'ready'
        AND (old_item - ARRAY['updatedAt','attempts','lastError','nextAttemptAt']::text[])
          IS NOT DISTINCT FROM
            (new_item - ARRAY['updatedAt','attempts','lastError','nextAttemptAt']::text[])
      THEN RETURN NULL; END IF;
    END IF;
  END IF;
  IF TG_TABLE_NAME = 'albums' THEN
    SELECT (item->>'id')::uuid AS id, (item->>'ownerId')::uuid AS "ownerId", item->>'sharedAt' AS "sharedAt" INTO a;
    was_shared := a."sharedAt" IS NOT NULL OR (TG_OP = 'UPDATE' AND to_jsonb(OLD)->>'sharedAt' IS NOT NULL);
  ELSE
    IF TG_TABLE_NAME = 'assets' THEN
      asset_id := (item->>'id')::uuid;
      SELECT * INTO a FROM albums WHERE id = (item->>'albumId')::uuid;
    ELSE
      asset_id := (item->>'assetId')::uuid;
      SELECT albums.* INTO a FROM albums JOIN assets ON assets."albumId" = albums.id WHERE assets.id = asset_id;
    END IF;
    IF a.id IS NULL THEN RETURN NULL; END IF;
    was_shared := a."sharedAt" IS NOT NULL;
  END IF;
  SELECT "pairId" INTO pair_id FROM users WHERE id = a."ownerId";
  operation := CASE
    WHEN TG_TABLE_NAME = 'assets' AND TG_OP = 'UPDATE' AND old_item->>'status' = 'ready' AND new_item->>'status' = 'deleted' THEN 'DELETE'
    WHEN TG_TABLE_NAME = 'assets' AND TG_OP = 'UPDATE' AND old_item->>'status' = 'deleted' AND new_item->>'status' = 'ready' THEN 'RESTORE'
    WHEN TG_OP = 'DELETE' AND TG_TABLE_NAME <> 'asset_derivatives' THEN 'DELETE'
    ELSE 'UPSERT'
  END;
  UPDATE sync_head SET revision = revision + 1 WHERE id = 1 RETURNING revision INTO next_revision;
  INSERT INTO sync_changes(revision, "pairId", "ownerId", "albumId", "assetId", kind, operation, shared)
    VALUES(next_revision, pair_id, a."ownerId", a.id, asset_id,
    CASE WHEN asset_id IS NULL THEN 'ALBUM' ELSE 'ASSET' END, operation, was_shared);
  RETURN NULL;
END $$;

UPDATE service_metadata SET value = '8' WHERE key = 'schema_version';
