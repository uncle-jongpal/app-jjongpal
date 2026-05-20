#!/usr/bin/env bash
# 쫑팔이삼촌 — 비밀번호 재설정
# 사용: scripts/reset-password.sh <email> [new_password]
# 비밀번호 미입력 시 랜덤 16자 생성.

set -euo pipefail
cd "$(dirname "$0")/.."

if [[ $# -lt 1 ]]; then
    echo "usage: $0 <email> [new_password]" >&2
    exit 1
fi

EMAIL="$1"
NEW_PASSWORD="${2:-$(openssl rand -base64 12 | tr -d '/+=' | head -c 16)}"

PASSWORD_HASH=$(python3 -c "
import bcrypt, sys
pw = sys.argv[1].encode()
print(bcrypt.hashpw(pw, bcrypt.gensalt(rounds=12)).decode())
" "$NEW_PASSWORD")

PG_USER="${POSTGRES_USER:-jjongpal}"
PG_DB="${POSTGRES_DB:-jjongpal}"

docker compose -f pc/docker-compose.yml exec -T postgres \
    psql -U "$PG_USER" -d "$PG_DB" -v ON_ERROR_STOP=1 \
        -c "UPDATE users SET password_hash = '$PASSWORD_HASH' WHERE email = '$EMAIL';"

# 해당 사용자의 모든 디바이스 폐기 (강제 재로그인)
docker compose -f pc/docker-compose.yml exec -T postgres \
    psql -U "$PG_USER" -d "$PG_DB" -v ON_ERROR_STOP=1 \
        -c "UPDATE devices SET refresh_token_hash = NULL, revoked_at = NOW() WHERE user_id = (SELECT id FROM users WHERE email = '$EMAIL');"

echo "비밀번호 재설정 완료."
echo "  이메일:       $EMAIL"
echo "  새 비밀번호:  $NEW_PASSWORD"
echo "  → 해당 사용자의 모든 디바이스 폐기됨. 새 로그인 필요."
