"""
공용 헬퍼 — auth-service / upload-receiver 에 똑같이 있던 것을 한곳으로.
"""

import ipaddress
import json
from typing import Optional

import asyncpg
from fastapi import Request


def normalize_ip(value: Optional[str]) -> Optional[str]:
    """문자열이 올바른 IPv4/IPv6 주소면 정규화해서 돌려주고, 아니면 None.
    (디비 inet 컬럼에 엉뚱한 값이 들어가 500 나는 것 방지)"""
    if not value:
        return None
    v = value.strip()
    # "[::1]:1234" / "1.2.3.4:5678" 같은 포트 붙은 형태도 일부 프록시가 보냄
    if v.startswith("[") and "]" in v:
        v = v[1:v.index("]")]
    elif v.count(":") == 1 and "." in v:
        v = v.split(":", 1)[0]
    if "%" in v:  # IPv6 zone id(fe80::1%eth0)는 inet 이 못 받음
        v = v.split("%", 1)[0]
    try:
        ip = ipaddress.ip_address(v)
    except ValueError:
        return None
    # IPv4 를 IPv6 로 감싼 형태(::ffff:1.2.3.4)는 IPv4 로 — 같은 사람이 두 주소로 세어지지 않게
    if isinstance(ip, ipaddress.IPv6Address) and ip.ipv4_mapped is not None:
        ip = ip.ipv4_mapped
    return str(ip)


def parse_client_ip(cf_connecting_ip: Optional[str], x_forwarded_for: Optional[str],
                    peer: Optional[str]) -> Optional[str]:
    """요청자 IP 결정(순수 함수, 테스트용으로 분리).

    1) CF-Connecting-IP — 클라우드플레어가 직접 넣는 값(앞단이 클라우드플레어 터널)
    2) X-Forwarded-For 의 첫 번째 '올바른' 주소 — 터널 없이 들어온 경우 호환
    3) 소켓 상대 주소
    어느 것도 올바른 IP 가 아니면 None(디비엔 NULL).
    """
    ip = normalize_ip(cf_connecting_ip)
    if ip:
        return ip
    if x_forwarded_for:
        for part in x_forwarded_for.split(","):
            ip = normalize_ip(part)
            if ip:
                return ip
    return normalize_ip(peer)


def client_ip(request: Request) -> Optional[str]:
    return parse_client_ip(
        request.headers.get("cf-connecting-ip"),
        request.headers.get("x-forwarded-for"),
        request.client.host if request.client else None,
    )


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
