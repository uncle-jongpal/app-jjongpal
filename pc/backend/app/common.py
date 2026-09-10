"""
공용 헬퍼 — auth-service / upload-receiver 에 똑같이 있던 것을 한곳으로.
"""

import json
from typing import Optional

import asyncpg
from fastapi import Request


def client_ip(request: Request) -> Optional[str]:
    fwd = request.headers.get("x-forwarded-for")
    if fwd:
        return fwd.split(",")[0].strip()
    if request.client:
        return request.client.host
    return None


async def log_activity(
    conn: asyncpg.Connection,
    user_id: Optional[int],
    device_id: Optional[str],
    action: str,
    target: Optional[str],
    ip: Optional[str],
    user_agent: Optional[str],
    metadata: Optional[dict] = None,
) -> None:
    """user_activity_log 기록. 로그 실패가 본 흐름을 깨지 않게 예외 무시(기존과 동일)."""
    try:
        await conn.execute(
            """
            INSERT INTO user_activity_log (user_id, device_id, action, target, ip_address, user_agent, metadata)
            VALUES ($1, $2, $3, $4, $5::inet, $6, $7::jsonb)
            """,
            user_id, device_id, action, target, ip, (user_agent or "")[:500],
            json.dumps(metadata) if metadata else None,
        )
    except Exception:
        pass
