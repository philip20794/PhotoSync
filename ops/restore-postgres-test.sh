#!/usr/bin/env bash
# Restore one custom-format backup into an isolated, tmpfs-backed PostgreSQL.
set -euo pipefail
backup="${1:?Usage: ops/restore-postgres-test.sh /path/to/photosync-*.dump}"
[[ -s "$backup" ]] || { echo "Backup is missing or empty: $backup" >&2; exit 1; }
container="photosync-restore-check-$$"
password="$(openssl rand -hex 24)"
cleanup() { docker rm -f "$container" >/dev/null 2>&1 || true; }
trap cleanup EXIT
docker run -d --rm --name "$container" --network none --tmpfs /var/lib/postgresql/data:rw,noexec,nosuid,size=2g -e POSTGRES_DB=photosync_restore -e POSTGRES_USER=photosync -e POSTGRES_PASSWORD="$password" postgres:17-bookworm >/dev/null
for _ in $(seq 1 30); do
  if docker exec "$container" pg_isready -U photosync -d photosync_restore >/dev/null 2>&1; then break; fi
  sleep 1
done
docker exec "$container" pg_isready -U photosync -d photosync_restore >/dev/null
docker exec -i "$container" pg_restore -U photosync -d photosync_restore --no-owner --no-privileges <"$backup"
docker exec "$container" psql -U photosync -d photosync_restore -Atc "SELECT 'schema_migrations=' || count(*) FROM _prisma_migrations;"
echo "Restore check passed in isolated tmpfs container."
