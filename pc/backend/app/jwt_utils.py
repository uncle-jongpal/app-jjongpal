"""
JWT 유틸 — auth-service 에서 그대로 포팅.

클레임 이름은 안드로이드 APK 가 의존하므로 절대 바꾸지 않는다:
  access:  iss, user_id(문자열), role(PG 역할명), app_role(admin/user), type='access', iat, exp
  refresh: iss, user_id(문자열), device_id, type='refresh', nonce, iat, exp
"""

import hashlib
import secrets
from datetime import datetime, timedelta, timezone

import jwt
from fastapi import HTTPException

from . import config


def now_utc() -> datetime:
    return datetime.now(timezone.utc)


def hash_refresh(token: str) -> str:
    return hashlib.sha256(token.encode()).hexdigest()


def make_access(user_id: int, role: str) -> str:
    pg_role = config.APP_ROLE_TO_PG_ROLE.get(role, "jjongpal_user")
    payload = {
        "iss": "jjongpal-auth",
        "user_id": str(user_id),
        # PostgREST(이제 rest 라우터) 가 'role' 클레임으로 SET ROLE 함. PG 역할명 사용.
        "role": pg_role,
        # 애플리케이션 측 사용자 역할 (admin / user) — RLS 정책 헬퍼가 읽음
        "app_role": role,
        "type": "access",
        "iat": int(now_utc().timestamp()),
        "exp": int((now_utc() + timedelta(seconds=config.ACCESS_TTL)).timestamp()),
    }
    return jwt.encode(payload, config.JWT_SECRET, algorithm=config.JWT_ALG)


def make_refresh(user_id: int, device_id: str) -> str:
    payload = {
        "iss": "jjongpal-auth",
        "user_id": str(user_id),
        "device_id": device_id,
        "type": "refresh",
        "nonce": secrets.token_hex(8),
        "iat": int(now_utc().timestamp()),
        "exp": int((now_utc() + timedelta(seconds=config.REFRESH_TTL)).timestamp()),
    }
    return jwt.encode(payload, config.JWT_SECRET, algorithm=config.JWT_ALG)


def verify_jwt(token: str, expected_type: str) -> dict:
    try:
        payload = jwt.decode(token, config.JWT_SECRET, algorithms=[config.JWT_ALG])
    except jwt.ExpiredSignatureError:
        raise HTTPException(status_code=401, detail="token expired")
    except jwt.InvalidTokenError:
        raise HTTPException(status_code=401, detail="invalid token")
    if payload.get("type") != expected_type:
        raise HTTPException(status_code=401, detail="token type mismatch")
    return payload


def verify_access_from_header(authorization: str | None) -> dict:
    """Authorization: Bearer <access> 수동 파싱 — upload-receiver 방식 그대로.
    user_id 를 int 로 정규화해 반환."""
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(status_code=401, detail="no token")
    token = authorization[len("Bearer "):]
    payload = verify_jwt(token, "access")
    try:
        payload["user_id"] = int(payload["user_id"])
    except (KeyError, ValueError):
        raise HTTPException(status_code=401, detail="missing user_id")
    return payload
