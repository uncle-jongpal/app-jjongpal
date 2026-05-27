"""
쫑팔이삼촌 — 인증 서비스
- POST /auth/login    {email, password, device_name, fcm_token?} → access + refresh
- POST /auth/refresh  {refresh_token} → 새 access
- POST /auth/logout   {refresh_token} → 디바이스 폐기

규칙:
- 비밀번호는 bcrypt 해시. 평문 저장 X.
- JWT 클레임에 user_id (정수) + role + (refresh 용엔 device_id) 포함.
- refresh_token 의 해시를 devices 테이블에 보관. 디바이스 단위 폐기 가능.
- 모든 활동 (성공/실패) 을 user_activity_log 에 영구 기록.
"""

import hashlib
import json
import os
import secrets
from contextlib import asynccontextmanager
from datetime import datetime, timedelta, timezone
from typing import Optional

import asyncpg
import bcrypt
import jwt
from fastapi import FastAPI, HTTPException, Request
from pydantic import BaseModel, EmailStr, Field

DATABASE_URL = os.environ["DATABASE_URL"]
JWT_SECRET = os.environ["JWT_SECRET"]
ACCESS_TTL = int(os.environ.get("ACCESS_TOKEN_TTL_SEC", 3600))
REFRESH_TTL = int(os.environ.get("REFRESH_TOKEN_TTL_SEC", 60 * 60 * 24 * 90))
JWT_ALG = "HS256"


pool: Optional[asyncpg.Pool] = None


@asynccontextmanager
async def lifespan(app: FastAPI):
    global pool
    pool = await asyncpg.create_pool(DATABASE_URL, min_size=1, max_size=5)
    yield
    await pool.close()


app = FastAPI(title="jjongpal-auth", lifespan=lifespan)


# ===== 모델 =====

class LoginReq(BaseModel):
    email: EmailStr
    password: str = Field(min_length=1, max_length=200)
    device_name: str = Field(min_length=1, max_length=120)
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


# ===== 유틸 =====

def now_utc() -> datetime:
    return datetime.now(timezone.utc)


def hash_refresh(token: str) -> str:
    return hashlib.sha256(token.encode()).hexdigest()


APP_ROLE_TO_PG_ROLE = {
    "admin": "jjongpal_admin",
    "user": "jjongpal_user",
}


def make_access(user_id: int, role: str) -> str:
    pg_role = APP_ROLE_TO_PG_ROLE.get(role, "jjongpal_user")
    payload = {
        "iss": "jjongpal-auth",
        "user_id": str(user_id),
        # PostgREST 가 'role' 클레임으로 SET ROLE 함. 포스트그레큐엘 역할명 사용.
        "role": pg_role,
        # 애플리케이션 측 사용자 역할 (admin / user) — RLS 정책 헬퍼가 읽음
        "app_role": role,
        "type": "access",
        "iat": int(now_utc().timestamp()),
        "exp": int((now_utc() + timedelta(seconds=ACCESS_TTL)).timestamp()),
    }
    return jwt.encode(payload, JWT_SECRET, algorithm=JWT_ALG)


def make_refresh(user_id: int, device_id: str) -> str:
    payload = {
        "iss": "jjongpal-auth",
        "user_id": str(user_id),
        "device_id": device_id,
        "type": "refresh",
        "nonce": secrets.token_hex(8),
        "iat": int(now_utc().timestamp()),
        "exp": int((now_utc() + timedelta(seconds=REFRESH_TTL)).timestamp()),
    }
    return jwt.encode(payload, JWT_SECRET, algorithm=JWT_ALG)


def verify_jwt(token: str, expected_type: str) -> dict:
    try:
        payload = jwt.decode(token, JWT_SECRET, algorithms=[JWT_ALG])
    except jwt.ExpiredSignatureError:
        raise HTTPException(status_code=401, detail="token expired")
    except jwt.InvalidTokenError:
        raise HTTPException(status_code=401, detail="invalid token")
    if payload.get("type") != expected_type:
        raise HTTPException(status_code=401, detail="token type mismatch")
    return payload


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
        # 로그 실패는 본 흐름 깨지 않게 (어차피 로그)
        pass


# ===== 엔드포인트 =====

@app.get("/auth/health")
async def health() -> dict:
    return {"ok": True}


@app.post("/auth/login", response_model=LoginResp)
async def login(req: LoginReq, request: Request) -> LoginResp:
    ip = client_ip(request)
    ua = request.headers.get("user-agent")

    async with pool.acquire() as conn:
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


@app.post("/auth/refresh", response_model=RefreshResp)
async def refresh(req: RefreshReq, request: Request) -> RefreshResp:
    ip = client_ip(request)
    ua = request.headers.get("user-agent")

    payload = verify_jwt(req.refresh_token, "refresh")
    user_id = int(payload["user_id"])
    device_id = payload["device_id"]
    expected_hash = hash_refresh(req.refresh_token)

    async with pool.acquire() as conn:
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


@app.post("/auth/logout")
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
    async with pool.acquire() as conn:
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
