#!/usr/bin/env bash
# Refuse a start if the dedicated filesystem is absent; never use the root SSD.
set -euo pipefail
: "${PHOTOSYNC_STORAGE_ROOT:?PHOTOSYNC_STORAGE_ROOT is required}"
: "${PHOTOSYNC_STORAGE_UUID:?PHOTOSYNC_STORAGE_UUID is required}"
: "${PHOTOSYNC_PROD_POSTGRES_PATH:?PHOTOSYNC_PROD_POSTGRES_PATH is required}"
: "${PHOTOSYNC_PROD_MEDIA_PATH:?PHOTOSYNC_PROD_MEDIA_PATH is required}"
: "${PHOTOSYNC_PROD_CATALOG_PATH:?PHOTOSYNC_PROD_CATALOG_PATH is required}"
: "${PHOTOSYNC_PROD_BACKUP_PATH:?PHOTOSYNC_PROD_BACKUP_PATH is required}"
actual_uuid="$(findmnt --target "$PHOTOSYNC_STORAGE_ROOT" --noheadings --output UUID | xargs)"
if [[ "$actual_uuid" != "$PHOTOSYNC_STORAGE_UUID" ]]; then
  echo "PhotoSync production storage is not mounted with the expected UUID; refusing startup." >&2
  exit 1
fi
for path in "$PHOTOSYNC_PROD_POSTGRES_PATH" "$PHOTOSYNC_PROD_MEDIA_PATH" "$PHOTOSYNC_PROD_CATALOG_PATH" "$PHOTOSYNC_PROD_BACKUP_PATH"; do
  [[ -d "$path" && ! -L "$path" ]] || { echo "Required production storage directory is missing or a symlink: $path" >&2; exit 1; }
  path_uuid="$(findmnt --target "$path" --noheadings --output UUID | xargs)"
  [[ "$path_uuid" == "$PHOTOSYNC_STORAGE_UUID" ]] || { echo "Production path is not on the dedicated filesystem: $path" >&2; exit 1; }
done
