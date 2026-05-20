"""
쫑팔이삼촌 — 파이어베이스 푸시 송신 (트리거만, 본문 X)

흐름:
1. summaries.pushed=FALSE 폴링
2. 해당 user_id 의 활성 디바이스 (fcm_token IS NOT NULL AND revoked_at IS NULL) 조회
3. 각 디바이스에 FCM (파이어베이스 클라우드 푸시) HTTP v1 호출
   - 페이로드 데이터 전용 (data-only): {"type":"summary_ready","summary_id":"<UUID>"}
   - notification 필드 X. 본문은 폰이 PC 의 자동 REST 입구에서 다시 가져옴.
4. summaries.pushed=TRUE + pushed_at=NOW

본문이 구글 인프라에 안 흘러감 (프라이버시 원칙).
"""

import asyncio
import json
import logging
import os
import time
from typing import Optional

import asyncpg
import httpx
from google.oauth2 import service_account
from google.auth.transport.requests import Request as GoogleAuthRequest

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("fcm-pusher")

DATABASE_URL = os.environ["DATABASE_URL"]
FIREBASE_PROJECT_ID = os.environ["FIREBASE_PROJECT_ID"]
FIREBASE_CREDENTIALS = os.environ.get("FIREBASE_CREDENTIALS", "/secrets/service-account.json")
POLL_INTERVAL_SEC = int(os.environ.get("FCM_POLL_INTERVAL_SEC", "5"))
BATCH_SIZE = int(os.environ.get("FCM_BATCH_SIZE", "10"))

FCM_ENDPOINT = f"https://fcm.googleapis.com/v1/projects/{FIREBASE_PROJECT_ID}/messages:send"
SCOPES = ["https://www.googleapis.com/auth/firebase.messaging"]

# 액세스 토큰 캐시
_creds: Optional[service_account.Credentials] = None


def get_access_token() -> str:
    global _creds
    if _creds is None:
        _creds = service_account.Credentials.from_service_account_file(
            FIREBASE_CREDENTIALS, scopes=SCOPES
        )
    if not _creds.valid:
        _creds.refresh(GoogleAuthRequest())
    return _creds.token


async def push_to_device(client: httpx.AsyncClient, fcm_token: str, payload_data: dict) -> bool:
    token = get_access_token()
    headers = {
        "Authorization": f"Bearer {token}",
        "Content-Type": "application/json",
    }
    body = {
        "message": {
            "token": fcm_token,
            "data": {k: str(v) for k, v in payload_data.items()},
            "android": {
                "priority": "HIGH",
            },
        }
    }
    r = await client.post(FCM_ENDPOINT, headers=headers, json=body, timeout=15.0)
    if r.status_code == 200:
        return True
    log.warning(f"FCM push failed status={r.status_code} body={r.text[:300]}")
    # 토큰이 폐기된 폰이면 404 / UNREGISTERED — 디바이스 폐기 처리
    if r.status_code in (400, 404):
        try:
            error_detail = r.json().get("error", {}).get("status", "")
        except Exception:
            error_detail = ""
        if "UNREGISTERED" in error_detail or "INVALID_ARGUMENT" in error_detail:
            return False  # 호출자에게 정리 신호
    return False


async def process_summary(conn: asyncpg.Connection, client: httpx.AsyncClient, row: asyncpg.Record) -> None:
    summary_id = row["id"]
    user_id = row["user_id"]

    devices = await conn.fetch(
        """
        SELECT id, fcm_token FROM devices
        WHERE user_id = $1 AND revoked_at IS NULL AND fcm_token IS NOT NULL
        """,
        user_id,
    )
    if not devices:
        log.info(f"[{summary_id}] no active device for user {user_id}, marking pushed (no-op)")
        await conn.execute(
            "UPDATE summaries SET pushed = TRUE, pushed_at = NOW() WHERE id = $1",
            summary_id,
        )
        return

    payload = {"type": "summary_ready", "summary_id": str(summary_id)}
    success = 0
    for d in devices:
        ok = await push_to_device(client, d["fcm_token"], payload)
        if ok:
            success += 1
        else:
            # 토큰 정리 — fcm_token NULL 로 (디바이스는 살려둠, 다음 로그인에서 토큰 갱신)
            await conn.execute(
                "UPDATE devices SET fcm_token = NULL WHERE id = $1",
                d["id"],
            )

    # 한 대라도 성공이면 push 완료로 마킹. 모두 실패면 다음 폴링에서 재시도.
    if success > 0:
        await conn.execute(
            "UPDATE summaries SET pushed = TRUE, pushed_at = NOW() WHERE id = $1",
            summary_id,
        )
    log.info(f"[{summary_id}] pushed to {success}/{len(devices)} devices for user {user_id}")


async def main() -> None:
    log.info("fcm-pusher 시작")
    while True:
        try:
            conn = await asyncpg.connect(DATABASE_URL)
        except Exception as e:
            log.warning(f"디비 연결 실패. 5초 후 재시도: {e}")
            await asyncio.sleep(5)
            continue

        try:
            async with httpx.AsyncClient() as client:
                while True:
                    rows = await conn.fetch(
                        """
                        SELECT id, user_id
                        FROM summaries
                        WHERE pushed = FALSE
                        ORDER BY created_at
                        LIMIT $1
                        """,
                        BATCH_SIZE,
                    )
                    if not rows:
                        await asyncio.sleep(POLL_INTERVAL_SEC)
                        continue
                    for row in rows:
                        try:
                            await process_summary(conn, client, row)
                        except Exception as e:
                            log.exception(f"push error for summary {row['id']}: {e}")
                            await asyncio.sleep(1)
        except (asyncpg.PostgresConnectionError, ConnectionResetError, OSError) as e:
            log.warning(f"디비 연결 끊김. 재연결: {e}")
            try:
                await conn.close()
            except Exception:
                pass
            await asyncio.sleep(3)


if __name__ == "__main__":
    asyncio.run(main())
