"""
쫑팔이삼촌 — 업로드 수신기
- POST /upload/audio  multipart(file, event_id, device_timestamp?)
  → 디스크 저장 + events INSERT (type='call') + audio_files INSERT (PENDING)
  → user_activity_log 에 upload.audio 기록

규칙:
- JWT 의 user_id (정수) + role 클레임으로 인증
- 파일은 ${STORAGE_ROOT}/audio/<user_id>/<YYYY-MM-DD>/<event_id>.m4a 로 저장
- 디비 트랜잭션 안에서 events + audio_files 같이 등록 (정합성)
"""

import json
import os
from contextlib import asynccontextmanager
import logging
from datetime import date, datetime, timezone
from typing import Optional

import aiofiles
import asyncpg
import jwt
from fastapi import FastAPI, File, Form, Header, HTTPException, Request, UploadFile

DATABASE_URL = os.environ["DATABASE_URL"]
JWT_SECRET = os.environ["JWT_SECRET"]
STORAGE_ROOT = os.environ["STORAGE_ROOT"]
JWT_ALG = "HS256"
ALLOWED_EXT = {".m4a", ".mp3", ".wav", ".ogg", ".amr"}
MAX_BYTES = 200 * 1024 * 1024  # 200MB
MIN_BYTES = 4 * 1024  # 4KB 미만은 빈/깨진 토막으로 간주하고 거부
MP4_EXT = {".m4a", ".mp4"}

pool: Optional[asyncpg.Pool] = None


def mp4_has_moov(path: str) -> bool:
    """mp4/m4a 최상위 박스를 훑어 'moov' 박스가 있으면 완성된 녹음으로 간주.
    녹음 중 잘린 파일은 moov 가 없음 → False. 박스 내용은 안 읽고 오프셋만 건너뜀."""
    try:
        size = os.path.getsize(path)
        if size < 8:
            return False
        with open(path, "rb") as f:
            offset = 0
            while offset + 8 <= size:
                f.seek(offset)
                header = f.read(8)
                if len(header) < 8:
                    break
                box_size = int.from_bytes(header[0:4], "big")
                box_type = header[4:8]
                if box_type == b"moov":
                    return True
                if box_size == 1:  # 64비트 확장 크기
                    ext = f.read(8)
                    if len(ext) < 8:
                        break
                    box_size = int.from_bytes(ext, "big")
                if box_size < 8:
                    break
                offset += box_size
    except Exception:
        return False
    return False


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
    bad = {c for c in event_id if not (c.isalnum() or c in "_-.")}
    if bad:
        raise HTTPException(status_code=400, detail=f"invalid event_id chars: {bad}")
    if not (1 <= len(event_id) <= 200):
        raise HTTPException(status_code=400, detail="invalid event_id length")
    return event_id


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
        pass


@app.get("/upload/health")
async def health() -> dict:
    return {"ok": True}


@app.post("/upload/audio")
async def upload_audio(
    request: Request,
    file: UploadFile = File(...),
    event_id: str = Form(...),
    device_timestamp: Optional[str] = Form(None),
    device_id: Optional[str] = Form(None),
    duration_sec: Optional[int] = Form(None),
    authorization: Optional[str] = Header(None),
):
    claims = verify_access(authorization)
    user_id = claims["user_id"]
    event_id = safe_event_id(event_id)
    ip = client_ip(request)
    ua = request.headers.get("user-agent")

    ext = os.path.splitext(file.filename or "")[1].lower()
    if ext not in ALLOWED_EXT:
        async with pool.acquire() as conn:
            await log_activity(
                conn, user_id, device_id, "upload.fail", event_id, ip, ua,
                {"reason": "unsupported_ext", "ext": ext},
            )
        raise HTTPException(status_code=400, detail=f"unsupported extension {ext!r}")

    today = date.today().isoformat()
    dest_dir = os.path.join(STORAGE_ROOT, "audio", str(user_id), today)
    os.makedirs(dest_dir, exist_ok=True)
    dest_path = os.path.join(dest_dir, f"{event_id}{ext}")

    total = 0
    async with aiofiles.open(dest_path, "wb") as out:
        while True:
            chunk = await file.read(1 << 20)
            if not chunk:
                break
            total += len(chunk)
            if total > MAX_BYTES:
                await out.close()
                os.remove(dest_path)
                async with pool.acquire() as conn:
                    await log_activity(
                        conn, user_id, device_id, "upload.fail", event_id, ip, ua,
                        {"reason": "too_large", "bytes": total},
                    )
                raise HTTPException(status_code=413, detail="file too large")
            await out.write(chunk)

    # 무결성 검증 — 너무 작거나(빈 토막) mp4 계열인데 moov(최종화) 없는 깨진 파일은 거부.
    # 이렇게 막아야 클라이언트가 "업로드 성공" 으로 잘못 알고 폰 원본을 지우는 사고를 방지한다.
    ext_is_mp4 = ext in MP4_EXT
    moov_ok = (not ext_is_mp4) or mp4_has_moov(dest_path)
    if total < MIN_BYTES or not moov_ok:
        try:
            os.remove(dest_path)
        except OSError:
            pass
        async with pool.acquire() as conn:
            await log_activity(
                conn, user_id, device_id, "upload.fail", event_id, ip, ua,
                {"reason": "incomplete_or_corrupt", "bytes": total, "moov_ok": moov_ok},
            )
        raise HTTPException(status_code=422, detail="incomplete or corrupt audio (no moov / too small)")

    # asyncpg 는 datetime 객체만 받음 — ISO 8601 문자열은 파이썬에서 미리 파싱
    parsed_ts = None
    if device_timestamp:
        try:
            ts_norm = device_timestamp.replace("Z", "+00:00") if device_timestamp.endswith("Z") else device_timestamp
            parsed_ts = datetime.fromisoformat(ts_norm)
            # 시간대 정보 없는 (naive) 경우 UTC 로 가정 — timestamptz 컬럼이 서버 컨테이너 시간대로 잘못 해석되지 않도록
            if parsed_ts.tzinfo is None:
                parsed_ts = parsed_ts.replace(tzinfo=timezone.utc)
                logging.getLogger(__name__).warning(
                    "device_timestamp naive (no tz), assuming UTC: %s -> %s", device_timestamp, parsed_ts.isoformat()
                )
        except ValueError as e:
            logging.getLogger(__name__).warning("device_timestamp parse failed: %s (%s)", device_timestamp, e)
            parsed_ts = None

    async with pool.acquire() as conn:
        async with conn.transaction():
            await conn.execute(
                """
                INSERT INTO events (id, user_id, device_id, type, device_timestamp, timestamp)
                VALUES ($1, $2, $3, 'call', $4, NOW())
                ON CONFLICT (id) DO UPDATE SET
                    user_id = EXCLUDED.user_id,
                    device_id = COALESCE(events.device_id, EXCLUDED.device_id),
                    device_timestamp = COALESCE(events.device_timestamp, EXCLUDED.device_timestamp)
                """,
                event_id, user_id, device_id, parsed_ts,
            )

            # 멱등: 같은 통화(event_id)를 재업로드해도 audio_files 행은 하나만.
            # (네트워크 불안으로 앱이 성공 응답을 못 받고 재업로드하는 경우 → 받아쓰기/요약/알림 중복 방지)
            audio_row = await conn.fetchrow(
                """
                INSERT INTO audio_files (event_id, user_id, file_path, size_bytes, duration_sec, transcript_status)
                SELECT $1, $2, $3, $4, $5, 'PENDING'
                WHERE NOT EXISTS (SELECT 1 FROM audio_files WHERE event_id = $1)
                RETURNING id
                """,
                event_id, user_id, dest_path, total, duration_sec,
            )
            if audio_row is None:
                # 이미 등록된 통화 — 기존 행을 그대로 사용(중복 생성 안 함)
                audio_row = await conn.fetchrow(
                    "SELECT id FROM audio_files WHERE event_id = $1 LIMIT 1",
                    event_id,
                )

        await log_activity(
            conn, user_id, device_id, "upload.audio", event_id, ip, ua,
            {"size_bytes": total, "duration_sec": duration_sec, "audio_file_id": str(audio_row["id"])},
        )

    return {
        "ok": True,
        "audio_file_id": str(audio_row["id"]),
        "size_bytes": total,
    }
