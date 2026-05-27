#!/usr/bin/env bash
# 쫑팔이삼촌 — 비밀번호 재설정
# 사용: scripts/reset-password.sh <email> [new_password]
# 비밀번호 미입력 시 랜덤 16자 생성.
#
# 인증 서비스 컨테이너 안의 파이썬으로 처리 (bcrypt + asyncpg).
# 재설정 후 해당 사용자의 모든 디바이스 폐기 (강제 재로그인).

set -euo pipefail
cd "$(dirname "$0")/.."

if [[ $# -lt 1 ]]; then
    echo "usage: $0 <email> [new_password]" >&2
    exit 1
fi

EMAIL="$1"
NEW_PASSWORD="${2:-$(openssl rand -base64 12 | tr -d '/+=' | head -c 16)}"

docker compose -f pc/docker-compose.yml exec -T auth-service python <<PYEOF
import asyncio, bcrypt, asyncpg, os

email = "$EMAIL"
password = "$NEW_PASSWORD"

async def main():
    pw_hash = bcrypt.hashpw(password.encode(), bcrypt.gensalt(rounds=12)).decode()
    conn = await asyncpg.connect(os.environ["DATABASE_URL"])
    try:
        affected = await conn.execute(
            "UPDATE users SET password_hash = \$1 WHERE email = \$2",
            pw_hash, email,
        )
        # 해당 사용자의 모든 디바이스 폐기
        await conn.execute(
            "UPDATE devices SET refresh_token_hash = NULL, revoked_at = NOW() WHERE user_id = (SELECT id FROM users WHERE email = \$1) AND revoked_at IS NULL",
            email,
        )
        print(f"updated: {affected}")
    finally:
        await conn.close()

asyncio.run(main())
PYEOF

echo ""
echo "비밀번호 재설정 완료."
echo "  이메일:       $EMAIL"
echo "  새 비밀번호:  $NEW_PASSWORD"
echo "  → 해당 사용자의 모든 디바이스 폐기됨. 새 로그인 필요."
