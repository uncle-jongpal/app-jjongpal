#!/usr/bin/env bash
# 쫑팔이삼촌 — 디바이스 폐기 (폰 분실 / 양도 시)
#
# 사용: scripts/revoke-device.sh <device_id_uuid>

set -euo pipefail
cd "$(dirname "$0")/.."

if [[ $# -lt 1 ]]; then
    echo "usage: $0 <device_id_uuid>" >&2
    exit 1
fi

DEVICE_ID="$1"
COMPOSE_FILE="pc/docker-compose.yml"
PG_USER="${POSTGRES_USER:-jjongpal}"
PG_DB="${POSTGRES_DB:-jjongpal}"

docker compose -f "$COMPOSE_FILE" exec -T postgres \
    psql -U "$PG_USER" -d "$PG_DB" -v ON_ERROR_STOP=1 \
        -c "UPDATE devices SET refresh_token_hash = NULL, revoked_at = NOW() WHERE id = '$DEVICE_ID';"

echo "디바이스 $DEVICE_ID 폐기 완료. 폰의 액세스 토큰도 최대 1시간 안에 만료됨."
