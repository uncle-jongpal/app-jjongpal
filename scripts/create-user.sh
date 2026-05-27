#!/usr/bin/env bash
# 쫑팔이삼촌 — 사용자 1명 생성 (어드민 / 일반 사용자)
#
# 사용:
#   scripts/create-user.sh <email> "<name>" [admin|user] [password]
#
# 비밀번호 미입력 시 랜덤 16자 생성.
# 인증 서비스 컨테이너 안의 파이썬으로 bcrypt 해시 + DATABASE_URL 통해 INSERT.

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

# 인증 서비스 컨테이너 안에서 처리 (bcrypt + asyncpg 다 있음)
docker compose -f pc/docker-compose.yml exec -T auth-service python <<PYEOF
import asyncio, bcrypt, asyncpg, os, sys

email = "$EMAIL"
name = "$NAME"
role = "$ROLE"
password = "$PASSWORD"

async def main():
    pw_hash = bcrypt.hashpw(password.encode(), bcrypt.gensalt(rounds=12)).decode()
    conn = await asyncpg.connect(os.environ["DATABASE_URL"])
    try:
        row = await conn.fetchrow(
            "INSERT INTO users (email, password_hash, name, role) VALUES (\$1, \$2, \$3, \$4) RETURNING id",
            email, pw_hash, name, role
        )
        print(f"created user id={row['id']}")
    finally:
        await conn.close()

asyncio.run(main())
PYEOF

echo ""
echo "사용자 생성 완료."
echo "  이메일:   $EMAIL"
echo "  이름:     $NAME"
echo "  역할:     $ROLE"
echo "  비밀번호: $PASSWORD"
echo ""
echo "이 정보를 안전한 통로로 본인에게 전달."
