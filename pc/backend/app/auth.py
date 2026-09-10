"""
인증 라우터 — auth-service/main.py 에서 동작 그대로 포팅.

- GET  /auth/health
- POST /auth/login    {email, password, device_name, fcm_token?} → access + refresh  (비인증)
- POST /auth/refresh  {refresh_token} → 새 access                                   (비인증)
- POST /auth/logout   {refresh_token} → 디바이스 폐기

규칙(유지): bcrypt 해시, 무차별 대입 스로틀(이메일 5회/IP 15회, 15분), JWT HS256,
refresh_token 해시를 devices 에 저장, 모든 활동을 user_activity_log 에 기록.
디비는 소유자(jjongpal) 커넥션 + 코드에서 user_id 수동 처리(기존과 동일, RLS 미사용).
"""

from typing import Optional

import asyncpg
import bcrypt
from fastapi import APIRouter, HTTPException, Request
from pydantic import BaseModel, EmailStr, Field

from . import db
from .common import client_ip, log_activity
from .jwt_utils import hash_refresh, make_access, make_refresh, verify_jwt

router = APIRouter()


# ===== 모델 =====
class LoginReq(BaseModel):
    email: str = Field(min_length=1, max_length=200)
    password: str = Field(min_length=1, max_length=200)
    device_name: str = Field(default="unknown", max_length=120)
    fcm_token: Optional[str] = None


class RefreshReq(BaseModel):
    refresh_token: str


class LogoutReq(BaseModel):
    refresh_token: str


class UserPublic(BaseModel):
    id: int
    name: str
    email: str
    role: str


class LoginResp(BaseModel):
    access_token: str
    refresh_token: str
    user: UserPublic
    device_id: str


class RefreshResp(BaseModel):
    access_token: str


# ===== 엔드포인트 =====
@router.get("/auth/health")
async def health() -> dict:
    return {"ok": True}


@router.post("/auth/login", response_model=LoginResp)
async def login(req: LoginReq, request: Request) -> LoginResp:
    ip = client_ip(request)
    ua = request.headers.get("user-agent")

    async with db.pool.acquire() as conn:
        # 무차별 대입 방어: 최근 15분 실패 누적이 임계치를 넘으면 즉시 차단
        # (같은 이메일 5회 또는 같은 IP 15회). 실패 로그를 그대로 활용.
        throttle = await conn.fetchrow(
            """
            SELECT
                COUNT(*) FILTER (WHERE target = $1)        AS email_fails,
                COUNT(*) FILTER (WHERE ip_address = $2::inet) AS ip_fails
            FROM user_activity_log
            WHERE action = 'login.fail'
              AND created_at > now() - interval '15 minutes'
            """,
            req.email, ip,
        )
        if throttle is not None and (
            throttle["email_fails"] >= 5 or (ip is not None and throttle["ip_fails"] >= 15)
        ):
            await log_activity(
                conn, None, None, "login.blocked", req.email, ip, ua,
                {
                    "email_fails": throttle["email_fails"],
                    "ip_fails": throttle["ip_fails"],
                    "device_name": req.device_name,
                },
            )
            raise HTTPException(
                status_code=429,
                detail="too many failed attempts, try again in a few minutes",
            )

        row = await conn.fetchrow(
            "SELECT id, email, name, role, password_hash, active FROM users WHERE email = $1",
            req.email,
        )

        # 이메일이 없거나 비활성
        if row is None or not row["active"]:
            await log_activity(
                conn, None, None, "login.fail", req.email, ip, ua,
                {"reason": "unknown_or_inactive", "device_name": req.device_name},
            )
            raise HTTPException(status_code=401, detail="invalid credentials")

        # 비밀번호 불일치
        if not bcrypt.checkpw(req.password.encode(), row["password_hash"].encode()):
            await log_activity(
                conn, row["id"], None, "login.fail", req.email, ip, ua,
                {"reason": "bad_password", "device_name": req.device_name},
            )
            raise HTTPException(status_code=401, detail="invalid credentials")

        user_id = row["id"]
        role = row["role"]

        # 디바이스 등록 (매 로그인마다 새 디바이스 행 — 디바이스 단위 폐기 가능)
        device_row = await conn.fetchrow(
            """
            INSERT INTO devices (user_id, name, fcm_token, refresh_token_hash, last_seen, revoked_at)
            VALUES ($1, $2, $3, NULL, NOW(), NULL)
            RETURNING id
            """,
            user_id, req.device_name, req.fcm_token,
        )
        device_id = str(device_row["id"])

        refresh_token = make_refresh(user_id, device_id)
        await conn.execute(
            "UPDATE devices SET refresh_token_hash = $1 WHERE id = $2",
            hash_refresh(refresh_token), device_id,
        )

        await log_activity(
            conn, user_id, device_id, "login.success", None, ip, ua,
            {"device_name": req.device_name, "has_fcm_token": bool(req.fcm_token)},
        )

        return LoginResp(
            access_token=make_access(user_id, role),
            refresh_token=refresh_token,
            user=UserPublic(
                id=user_id,
                name=row["name"],
                email=row["email"],
                role=role,
            ),
            device_id=device_id,
        )


@router.post("/auth/refresh", response_model=RefreshResp)
async def refresh(req: RefreshReq, request: Request) -> RefreshResp:
    ip = client_ip(request)
    ua = request.headers.get("user-agent")

    payload = verify_jwt(req.refresh_token, "refresh")
    user_id = int(payload["user_id"])
    device_id = payload["device_id"]
    expected_hash = hash_refresh(req.refresh_token)

    async with db.pool.acquire() as conn:
        row = await conn.fetchrow(
            """
            SELECT d.refresh_token_hash, d.revoked_at, u.role, u.active
            FROM devices d
            JOIN users u ON u.id = d.user_id
            WHERE d.id = $1 AND d.user_id = $2
            """,
            device_id, user_id,
        )
        if row is None or row["revoked_at"] is not None or not row["active"]:
            await log_activity(
                conn, user_id, device_id, "refresh.fail", None, ip, ua,
                {"reason": "device_revoked_or_user_inactive"},
            )
            raise HTTPException(status_code=401, detail="device revoked")
        if row["refresh_token_hash"] != expected_hash:
            await log_activity(
                conn, user_id, device_id, "refresh.fail", None, ip, ua,
                {"reason": "token_rotated"},
            )
            raise HTTPException(status_code=401, detail="refresh token rotated")

        await conn.execute("UPDATE devices SET last_seen = NOW() WHERE id = $1", device_id)
        await log_activity(conn, user_id, device_id, "refresh.success", None, ip, ua, None)
        return RefreshResp(access_token=make_access(user_id, row["role"]))


@router.post("/auth/logout")
async def logout(req: LogoutReq, request: Request) -> dict:
    ip = client_ip(request)
    ua = request.headers.get("user-agent")

    try:
        payload = verify_jwt(req.refresh_token, "refresh")
        device_id = payload.get("device_id")
        user_id = int(payload.get("user_id", 0)) or None
    except HTTPException:
        device_id = None
        user_id = None

    expected_hash = hash_refresh(req.refresh_token)
    async with db.pool.acquire() as conn:
        if device_id:
            await conn.execute(
                "UPDATE devices SET refresh_token_hash = NULL, revoked_at = NOW() WHERE id = $1 AND refresh_token_hash = $2",
                device_id, expected_hash,
            )
        else:
            await conn.execute(
                "UPDATE devices SET refresh_token_hash = NULL, revoked_at = NOW() WHERE refresh_token_hash = $1",
                expected_hash,
            )
        await log_activity(conn, user_id, device_id, "logout", None, ip, ua, None)
    return {"ok": True}
