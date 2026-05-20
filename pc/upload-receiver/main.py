"""
쫑팔이삼촌 — 업로드 수신기
- POST /upload/audio  multipart(file, event_id, device_timestamp?)
  → 디스크 저장 + events INSERT (type='call') + audio_files INSERT (PENDING)

규칙:
- JWT 의 user_id (정수) + role 클레임으로 인증
- 파일은 ${STORAGE_ROOT}/audio/<user_id>/<YYYY-MM-DD>/<event_id>.m4a 로 저장
- 디비 트랜잭션 안에서 events + audio_files 같이 등록 (정합성)
"""

import os
from contextlib import asynccontextmanager
from datetime import date
from typing import Optional

import aiofiles
import asyncpg
import jwt
from fastapi import FastAPI, File, Form, Header, HTTPException, UploadFile

DATABASE_URL = os.environ["DATABASE_URL"]
JWT_SECRET = os.environ["JWT_SECRET"]
STORAGE_ROOT = os.environ["STORAGE_ROOT"]
JWT_ALG = "HS256"
ALLOWED_EXT = {".m4a", ".mp3", ".wav", ".ogg", ".amr"}
MAX_BYTES = 200 * 1024 * 1024  # 200MB

pool: Optional[asyncpg.Pool] = None


@asynccontextmanager
async def lifespan(app: FastAPI):
    global pool
    pool = await asyncpg.create_pool(DATABASE_URL, min_size=1, max_size=5)
    os.makedirs(STORAGE_ROOT, exist_ok=True)
    yield
    await pool.close()


app = FastAPI(title="jjongpal-upload", lifespan=lifespan)


def verify_access(authorization: Optional[str]) -> dict:
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(status_code=401, detail="no token")
    token = authorization[len("Bearer "):]
    try:
        payload = jwt.decode(token, JWT_SECRET, algorithms=[JWT_ALG])
    except jwt.ExpiredSignatureError:
        raise HTTPException(status_code=401, detail="token expired")
    except jwt.InvalidTokenError:
        raise HTTPException(status_code=401, detail="invalid token")
    if payload.get("type") != "access":
        raise HTTPException(status_code=401, detail="not access token")
    try:
        payload["user_id"] = int(payload["user_id"])
    except (KeyError, ValueError):
        raise HTTPException(status_code=401, detail="missing user_id")
    return payload


def safe_event_id(event_id: str) -> str:
    # 영숫자·_·-·.· 만 허용. 디렉토리 탈출 방지.
    bad = {c for c in event_id if not (c.isalnum() or c in "_-.")}
    if bad:
        raise HTTPException(status_code=400, detail=f"invalid event_id chars: {bad}")
    if not (1 <= len(event_id) <= 200):
        raise HTTPException(status_code=400, detail="invalid event_id length")
    return event_id


@app.get("/upload/health")
async def health() -> dict:
    return {"ok": True}


@app.post("/upload/audio")
async def upload_audio(
    file: UploadFile = File(...),
    event_id: str = Form(...),
    device_timestamp: Optional[str] = Form(None),  # ISO 8601
    device_id: Optional[str] = Form(None),         # UUID 문자열
    duration_sec: Optional[int] = Form(None),
    authorization: Optional[str] = Header(None),
):
    claims = verify_access(authorization)
    user_id = claims["user_id"]
    event_id = safe_event_id(event_id)

    ext = os.path.splitext(file.filename or "")[1].lower()
    if ext not in ALLOWED_EXT:
        raise HTTPException(status_code=400, detail=f"unsupported extension {ext!r}")

    today = date.today().isoformat()
    dest_dir = os.path.join(STORAGE_ROOT, "audio", str(user_id), today)
    os.makedirs(dest_dir, exist_ok=True)
    dest_path = os.path.join(dest_dir, f"{event_id}{ext}")

    # 스트리밍 디스크 저장 (메모리 폭발 방지)
    total = 0
    async with aiofiles.open(dest_path, "wb") as out:
        while True:
            chunk = await file.read(1 << 20)  # 1MB
            if not chunk:
                break
            total += len(chunk)
            if total > MAX_BYTES:
                await out.close()
                os.remove(dest_path)
                raise HTTPException(status_code=413, detail="file too large")
            await out.write(chunk)

    # 디비 등록 (트랜잭션)
    async with pool.acquire() as conn:
        async with conn.transaction():
            # events 가 이미 있으면 UPSERT 식으로
            await conn.execute(
                """
                INSERT INTO events (id, user_id, device_id, type, device_timestamp, timestamp)
                VALUES ($1, $2, $3, 'call', $4::timestamptz, NOW())
                ON CONFLICT (id) DO UPDATE SET
                    user_id = EXCLUDED.user_id,
                    device_id = COALESCE(events.device_id, EXCLUDED.device_id),
                    device_timestamp = COALESCE(events.device_timestamp, EXCLUDED.device_timestamp)
                """,
                event_id, user_id, device_id, device_timestamp,
            )

            audio_row = await conn.fetchrow(
                """
                INSERT INTO audio_files (event_id, user_id, file_path, size_bytes, duration_sec, transcript_status)
                VALUES ($1, $2, $3, $4, $5, 'PENDING')
                RETURNING id
                """,
                event_id, user_id, dest_path, total, duration_sec,
            )

    return {
        "ok": True,
        "audio_file_id": str(audio_row["id"]),
        "size_bytes": total,
    }
