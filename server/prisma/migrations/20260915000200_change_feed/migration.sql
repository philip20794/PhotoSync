-- A transactional counter, NOT a sequence: no later revision can commit first.
CREATE TABLE sync_head (id integer PRIMARY KEY CHECK (id = 1), epoch uuid NOT NULL DEFAULT gen_random_uuid(), revision bigint NOT NULL DEFAULT 0, "minRevision" bigint NOT NULL DEFAULT 0);
INSERT INTO sync_head(id) VALUES (1);
CREATE TABLE sync_changes (
  revision bigint PRIMARY KEY, "pairId" uuid NOT NULL, "ownerId" uuid NOT NULL,
  "albumId" uuid NOT NULL, "assetId" uuid, kind text NOT NULL CHECK (kind IN ('ALBUM','ASSET')),
  operation text NOT NULL CHECK (operation IN ('UPSERT','DELETE','RESTORE')), shared boolean NOT NULL,
  "createdAt" timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX sync_changes_pair_revision ON sync_changes("pairId", revision);
CREATE TABLE sync_push_devices (
  "deviceId" uuid PRIMARY KEY REFERENCES devices(id) ON DELETE CASCADE,
  token text NOT NULL UNIQUE, revision bigint NOT NULL DEFAULT 0,
  "nextAttemptAt" timestamptz NOT NULL DEFAULT now(), attempts integer NOT NULL DEFAULT 0
);
-- Acquire before any media row locks, including bulk statements/derivative jobs.
CREATE FUNCTION sync_lock() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  PERFORM 1 FROM sync_head WHERE id = 1 FOR UPDATE;
  RETURN NULL;
END $$;
CREATE FUNCTION sync_record() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE a record; item jsonb; old_item jsonb; new_item jsonb; asset_id uuid; next_revision bigint; pair_id uuid; was_shared boolean;
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
    IF TG_OP = 'DELETE' AND old_item->>'status' <> 'ready' THEN RETURN NULL; END IF;
    IF TG_OP = 'UPDATE' THEN
      IF old_item->>'status' <> 'ready' AND new_item->>'status' <> 'ready' THEN RETURN NULL; END IF;
      IF old_item->>'status' = 'ready' AND new_item->>'status' = 'ready'
        AND (old_item - ARRAY['updatedAt','uploadStartedAt','uploadLeaseId','uploadLeaseExpiresAt']::text[])
          IS NOT DISTINCT FROM
            (new_item - ARRAY['updatedAt','uploadStartedAt','uploadLeaseId','uploadLeaseExpiresAt']::text[])
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
  UPDATE sync_head SET revision = revision + 1 WHERE id = 1 RETURNING revision INTO next_revision;
  INSERT INTO sync_changes(revision, "pairId", "ownerId", "albumId", "assetId", kind, operation, shared)
    VALUES(next_revision, pair_id, a."ownerId", a.id, asset_id,
    CASE WHEN asset_id IS NULL THEN 'ALBUM' ELSE 'ASSET' END,
    CASE WHEN TG_OP = 'DELETE' AND TG_TABLE_NAME <> 'asset_derivatives' THEN 'DELETE' ELSE 'UPSERT' END, was_shared);
  RETURN NULL;
END $$;
CREATE TRIGGER albums_sync_lock BEFORE INSERT OR UPDATE OR DELETE ON albums FOR EACH STATEMENT EXECUTE FUNCTION sync_lock();
CREATE TRIGGER assets_sync_lock BEFORE INSERT OR UPDATE OR DELETE ON assets FOR EACH STATEMENT EXECUTE FUNCTION sync_lock();
CREATE TRIGGER derivatives_sync_lock BEFORE INSERT OR UPDATE OR DELETE ON asset_derivatives FOR EACH STATEMENT EXECUTE FUNCTION sync_lock();
CREATE TRIGGER albums_sync_record AFTER INSERT OR UPDATE OR DELETE ON albums FOR EACH ROW EXECUTE FUNCTION sync_record();
CREATE TRIGGER assets_sync_record AFTER INSERT OR UPDATE OR DELETE ON assets FOR EACH ROW EXECUTE FUNCTION sync_record();
CREATE TRIGGER derivatives_sync_record AFTER INSERT OR UPDATE OR DELETE ON asset_derivatives FOR EACH ROW EXECUTE FUNCTION sync_record();
-- Baseline existing albums, including albums with no assets. Album invalidations
-- cause paginated metadata reconciliation, so no separate asset backfill is needed.
DO $$ DECLARE a record; r bigint; BEGIN
  FOR a IN SELECT albums.*, users."pairId" FROM albums JOIN users ON users.id = albums."ownerId" ORDER BY albums.id LOOP
    UPDATE sync_head SET revision = revision + 1 WHERE id = 1 RETURNING revision INTO r;
    INSERT INTO sync_changes(revision, "pairId", "ownerId", "albumId", "assetId", kind, operation, shared)
      VALUES(r, a."pairId", a."ownerId", a.id, NULL, 'ALBUM', 'UPSERT', a."sharedAt" IS NOT NULL);
  END LOOP;
END $$;
UPDATE service_metadata SET value = '7' WHERE key = 'schema_version';
