#!/usr/bin/env bash
# 쫑팔이삼촌 — 디비 백업
# 사용: scripts/backup-db.sh [백업저장디렉토리]

set -euo pipefail
cd "$(dirname "$0")/.."

BACKUP_DIR="${1:-$HOME/backups/jjongpal}"
mkdir -p "$BACKUP_DIR"

DATE=$(date +%Y%m%d-%H%M%S)
OUT="$BACKUP_DIR/jjongpal-$DATE.sql.gz"

PG_USER="${POSTGRES_USER:-jjongpal}"
PG_DB="${POSTGRES_DB:-jjongpal}"

docker compose -f pc/docker-compose.yml exec -T postgres \
    pg_dump -U "$PG_USER" "$PG_DB" | gzip > "$OUT"

# 30일 넘은 백업 정리
find "$BACKUP_DIR" -name "jjongpal-*.sql.gz" -mtime +30 -delete

echo "백업 완료: $OUT"
ls -lh "$OUT"
