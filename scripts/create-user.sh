#!/usr/bin/env bash
# 쫑팔이삼촌 — 사용자 1명 생성 (어드민 / 일반 사용자)
#
# 사용:
#   scripts/create-user.sh <email> "<name>" [admin|user] [password]
#
# 비밀번호 미입력 시 랜덤 16자 생성.
# bcrypt 해시 + users INSERT.

set -euo pipefail

cd "$(dirname "$0")/.."

if [[ $# -lt 2 ]]; then
    echo "usage: $0 <email> \"<name>\" [admin|user] [password]" >&2
    exit 1
fi

EMAIL="$1"
NAME="$2"
ROLE="${3:-user}"
PASSWORD="${4:-$(openssl rand -base64 12 | tr -d '/+=' | head -c 16)}"

if [[ "$ROLE" != "admin" && "$ROLE" != "user" ]]; then
    echo "role must be 'admin' or 'user'" >&2
    exit 1
fi

# bcrypt 해시 — 파이썬으로 (도커 컴포즈가 떠있어도 / 안 떠있어도 호스트 파이썬)
PASSWORD_HASH=$(python3 -c "
import bcrypt, sys
pw = sys.argv[1].encode()
print(bcrypt.hashpw(pw, bcrypt.gensalt(rounds=12)).decode())
" "$PASSWORD")

# 컴포즈 안의 postgres 컨테이너에 직접 INSERT
COMPOSE_FILE="pc/docker-compose.yml"
PG_USER="${POSTGRES_USER:-jjongpal}"
PG_DB="${POSTGRES_DB:-jjongpal}"

docker compose -f "$COMPOSE_FILE" exec -T postgres \
    psql -U "$PG_USER" -d "$PG_DB" -v ON_ERROR_STOP=1 \
        -c "INSERT INTO users (email, password_hash, name, role) VALUES ('$EMAIL', '$PASSWORD_HASH', \$\$$NAME\$\$, '$ROLE');"

echo ""
echo "사용자 생성 완료."
echo "  이메일:   $EMAIL"
echo "  이름:     $NAME"
echo "  역할:     $ROLE"
echo "  비밀번호: $PASSWORD"
echo ""
echo "이 정보를 안전한 통로로 본인에게 전달."
