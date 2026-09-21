#!/usr/bin/env bash
# Creates a portable PostgreSQL custom-format backup on the dedicated disk.
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
env_file="${PHOTOSYNC_ENV_FILE:-/srv/photosync-storage/photosync/production/config/production.env}"
[[ -r "$env_file" ]] || { echo "Missing production env file: $env_file" >&2; exit 1; }
set -a
# shellcheck disable=SC1090
source "$env_file"
set +a
"$repo_root/ops/verify-production-storage.sh"
umask 077
mkdir -p "$PHOTOSYNC_PROD_BACKUP_PATH"
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
target="$PHOTOSYNC_PROD_BACKUP_PATH/photosync-${stamp}.dump"
partial="${target}.part"
trap 'rm -f "$partial"' EXIT
docker compose --env-file "$env_file" -f "$repo_root/compose.yaml" -f "$repo_root/compose.production.yaml" exec -T db pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" --format=custom --no-owner --no-privileges >"$partial"
test -s "$partial"
mv -f "$partial" "$target"
trap - EXIT
echo "$target"
