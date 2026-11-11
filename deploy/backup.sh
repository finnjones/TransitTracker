#!/usr/bin/env bash
# Nightly backup of the tt-server database (and the settings needed to rebuild the server).
#
#   ./backup.sh                  # writes to ./backups
#   BACKUP_DIR=/mnt/usb/tt ./backup.sh
#
# Keeps 14 days of backups. Safe while the server runs (tt-admin uses SQLite's online backup).
# Timetable packs are not backed up: the server rebuilds them.
set -euo pipefail

cd "$(dirname "$0")"
BACKUP_DIR="${BACKUP_DIR:-$PWD/backups}"
KEEP_DAYS="${KEEP_DAYS:-14}"
stamp="$(date +%Y%m%d-%H%M%S)"
name="tt-server-${stamp}.db"

mkdir -p "$BACKUP_DIR"
chmod 700 "$BACKUP_DIR"

# 1. Consistent copy inside the data volume, then copy it out and remove the inner copy.
docker compose exec -T tt-server tt-admin backup "/data/backups/${name}"
docker compose cp "tt-server:/data/backups/${name}" "${BACKUP_DIR}/${name}"
docker compose exec -T tt-server rm -f "/data/backups/${name}"
chmod 600 "${BACKUP_DIR}/${name}"
gzip -f "${BACKUP_DIR}/${name}"

# 2. Settings and secrets (.env, Firebase key): small, but painful to recreate.
config_files=()
[ -f .env ] && config_files+=(.env)
[ -d secrets ] && config_files+=(secrets)
if [ "${#config_files[@]}" -gt 0 ]; then
  (umask 077 && tar -czf "${BACKUP_DIR}/tt-config-${stamp}.tar.gz" "${config_files[@]}")
fi

# 3. Keep the last KEEP_DAYS days.
find "$BACKUP_DIR" -maxdepth 1 -type f \( -name 'tt-server-*.db.gz' -o -name 'tt-config-*.tar.gz' \) \
  -mtime +"$((KEEP_DAYS - 1))" -delete

echo "Backup written to ${BACKUP_DIR}/${name}.gz"
