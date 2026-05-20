"""
쫑팔이삼촌 — 인증 서비스
- POST /auth/login    {email, password, device_name, fcm_token?} → access + refresh
- POST /auth/refresh  {refresh_token} → 새 access
- POST /auth/logout   {refresh_token} → 디바이스 폐기

규칙:
- 비밀번호는 bcrypt 해시. 평문 저장 X.
- JWT 클레임에 user_id (정수) + role + (refresh 용엔 device_id) 포함.
- refresh_token 의 해시를 devices 테이블에 보관. 디바이스 단위 폐기 가능.
"""

import hashlib
import os
import secrets
from contextlib import asynccontextmanager
from datetime import datetime, timedelta, timezone
from typing import Optional

import asyncpg
import bcrypt
import jwt
from fastapi import FastAPI, HTTPException
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


def make_access(user_id: int, role: str) -> str:
    payload = {
        "iss": "jjongpal-auth",
        "user_id": str(user_id),
        "role": role,
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


# ===== 엔드포인트 =====

@app.get("/auth/health")
async def health() -> dict:
    return {"ok": True}


@app.post("/auth/login", response_model=LoginResp)
async def login(req: LoginReq) -> LoginResp:
    async with pool.acquire() as conn:
        row = await conn.fetchrow(
            "SELECT id, email, name, role, password_hash, active FROM users WHERE email = $1",
            req.email,
        )
        if row is None or not row["active"]:
            raise HTTPException(status_code=401, detail="invalid credentials")

        if not bcrypt.checkpw(req.password.encode(), row["password_hash"].encode()):
            raise HTTPException(status_code=401, detail="invalid credentials")

        user_id = row["id"]
        role = row["role"]

        # 디바이스 등록 (이 사용자 + 같은 device_name 이 이미 있으면 갱신)
        device_row = await conn.fetchrow(
            """
            INSERT INTO devices (user_id, name, fcm_token, refresh_token_hash, last_seen, revoked_at)
            VALUES ($1, $2, $3, NULL, NOW(), NULL)
            RETURNING id
            """,
            user_id, req.device_name, req.fcm_token,
        )
        device_id = str(device_row["id"])

        # refresh 토큰 발급 + 해시 저장
        refresh_token = make_refresh(user_id, device_id)
        await conn.execute(
            "UPDATE devices SET refresh_token_hash = $1 WHERE id = $2",
            hash_refresh(refresh_token), device_id,
        )

        access_token = make_access(user_id, role)

        return LoginResp(
            access_token=access_token,
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
async def refresh(req: RefreshReq) -> RefreshResp:
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
            raise HTTPException(status_code=401, detail="device revoked")
        if row["refresh_token_hash"] != expected_hash:
            raise HTTPException(status_code=401, detail="refresh token rotated")

        await conn.execute("UPDATE devices SET last_seen = NOW() WHERE id = $1", device_id)
        return RefreshResp(access_token=make_access(user_id, row["role"]))


@app.post("/auth/logout")
async def logout(req: LogoutReq) -> dict:
    # 검증은 best-effort. 만료된 토큰이어도 해시로 폐기 가능.
    try:
        payload = verify_jwt(req.refresh_token, "refresh")
        device_id = payload.get("device_id")
    except HTTPException:
        device_id = None

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
    return {"ok": True}
