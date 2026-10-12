"""
업로드 라우터 — upload-receiver/main.py 에서 동작 그대로 포팅.

- GET  /upload/health
- POST /upload/audio  multipart(file, event_id, device_timestamp?, device_id?, duration_sec?)

규칙(유지):
- Authorization: Bearer 수동 파싱 (access JWT)
- 확장자 화이트리스트, 200MB 최대 / 4KB 최소
- mp4/m4a 는 moov 박스 존재 검증(잘린 녹음 거부 → 앱이 원본 오삭제하는 사고 방지)
- 저장 경로: ${STORAGE_ROOT}/audio/<user_id>/<YYYY-MM-DD>/<event_id><ext>
  (같은 폴더 임시 파일 .<event_id><ext>.<랜덤>.part 에 받은 뒤 os.replace 로 교체)
- 이미 등록된 통화 재업로드: 받아쓰기 끝남/파일 소모됨/처리 중이면 새 파일은 버림(응답은 동일)
- 트랜잭션 안에서 events upsert(ON CONFLICT id) + audio_files 멱등 단일 행(event_id 당 1개)
- user_activity_log 기록
- 디비는 소유자 커넥션 + user_id 수동(RLS 미사용, 기존과 동일)
"""

import logging
import os
import uuid
from datetime import date, datetime, timezone
from typing import Optional

import aiofiles
from fastapi import APIRouter, File, Form, Header, HTTPException, Request, UploadFile

from . import config, db
from .common import client_ip, log_activity
from .jwt_utils import verify_access_from_header

router = APIRouter()
log = logging.getLogger(__name__)


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


def safe_event_id(event_id: str) -> str:
    bad = {c for c in event_id if not (c.isalnum() or c in "_-.")}
    if bad:
        raise HTTPException(status_code=400, detail=f"invalid event_id chars: {bad}")
    if not (1 <= len(event_id) <= 200):
        raise HTTPException(status_code=400, detail="invalid event_id length")
    return event_id


@router.get("/upload/health")
async def health() -> dict:
    return {"ok": True}


@router.post("/upload/audio")
async def upload_audio(
    request: Request,
    file: UploadFile = File(...),
    event_id: str = Form(...),
    device_timestamp: Optional[str] = Form(None),
    device_id: Optional[str] = Form(None),
    duration_sec: Optional[int] = Form(None),
    authorization: Optional[str] = Header(None),
):
    claims = verify_access_from_header(authorization)
    user_id = claims["user_id"]
    event_id = safe_event_id(event_id)
    ip = client_ip(request)
    ua = request.headers.get("user-agent")

    ext = os.path.splitext(file.filename or "")[1].lower()
    if ext not in config.ALLOWED_EXT:
        async with db.pool.acquire() as conn:
            await log_activity(
                conn, user_id, device_id, "upload.fail", event_id, ip, ua,
                {"reason": "unsupported_ext", "ext": ext},
            )
        raise HTTPException(status_code=400, detail=f"unsupported extension {ext!r}")

    today = date.today().isoformat()
    dest_dir = os.path.join(config.STORAGE_ROOT, "audio", str(user_id), today)
    os.makedirs(dest_dir, exist_ok=True)
    dest_path = os.path.join(dest_dir, f"{event_id}{ext}")
    # 2026-10-13: 최종 경로에 바로 쓰지 않고 같은 폴더 임시 파일에 받은 뒤 os.replace(원자적 교체).
    # 받는 도중 끊기거나 같은 통화가 동시에 올라와도 받아쓰기 워커가 반쯤 쓴 파일을 읽지 않게.
    tmp_path = os.path.join(dest_dir, f".{event_id}{ext}.{uuid.uuid4().hex}.part")

    try:
        return await _receive_and_register(
            file, tmp_path, dest_path, ext, event_id, user_id, device_id,
            device_timestamp, duration_sec, ip, ua,
        )
    finally:
        # 어떤 경로로 끝나든(성공·거부·예외) 임시 파일은 남기지 않는다
        _remove_quietly(tmp_path)


def _remove_quietly(path: Optional[str]) -> None:
    if not path:
        return
    try:
        os.remove(path)
    except FileNotFoundError:
        pass
    except OSError as e:
        log.warning("임시/중복 파일 삭제 실패 %s: %s", path, e)


def decide_duplicate_action(existing_status: Optional[str], existing_path: Optional[str],
                            new_path: str, existing_file_present: bool) -> str:
    """이미 등록된 통화(event_id)가 다시 올라왔을 때 새로 받은 파일을 어떻게 할지(순수 함수).

    'discard'      — 새 파일 버림 (이미 받아쓰기 끝남/파일 소모됨/처리 중/기존 파일 멀쩡함)
    'replace'      — 같은 경로의 기존 파일을 새 파일로 원자적 교체(같은 날 재업로드, 처리 전)
    'restore'      — 기존 행이 가리키는 파일이 디스크에 없음 → 그 경로에 새 파일을 채워 넣음
    """
    if existing_path is None or existing_status in ("DONE", "SKIPPED", "PROCESSING"):
        return "discard"
    if existing_path == new_path:
        return "replace"
    if existing_file_present:
        return "discard"
    return "restore"


async def _receive_and_register(file, tmp_path, dest_path, ext, event_id, user_id, device_id,
                                device_timestamp, duration_sec, ip, ua):
    total = 0
    async with aiofiles.open(tmp_path, "wb") as out:
        while True:
            chunk = await file.read(1 << 20)
            if not chunk:
                break
            total += len(chunk)
            if total > config.MAX_BYTES:
                await out.close()
                _remove_quietly(tmp_path)
                async with db.pool.acquire() as conn:
                    await log_activity(
                        conn, user_id, device_id, "upload.fail", event_id, ip, ua,
                        {"reason": "too_large", "bytes": total},
                    )
                raise HTTPException(status_code=413, detail="file too large")
            await out.write(chunk)

    # 무결성 검증 — 너무 작거나(빈 토막) mp4 계열인데 moov(최종화) 없는 깨진 파일은 거부.
    # 이렇게 막아야 클라이언트가 "업로드 성공" 으로 잘못 알고 폰 원본을 지우는 사고를 방지한다.
    ext_is_mp4 = ext in config.MP4_EXT
    moov_ok = (not ext_is_mp4) or mp4_has_moov(tmp_path)
    if total < config.MIN_BYTES or not moov_ok:
        _remove_quietly(tmp_path)
        async with db.pool.acquire() as conn:
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
                log.warning(
                    "device_timestamp naive (no tz), assuming UTC: %s -> %s", device_timestamp, parsed_ts.isoformat()
                )
        except ValueError as e:
            log.warning("device_timestamp parse failed: %s (%s)", device_timestamp, e)
            parsed_ts = None

    dup_action = None
    async with db.pool.acquire() as conn:
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
            if audio_row is not None:
                # 새 통화 → 임시 파일을 최종 경로로(커밋 전에 옮겨 두어야 워커가 빈 경로를 안 봄.
                # 옮기기 실패 시 예외 → 트랜잭션 롤백)
                os.replace(tmp_path, dest_path)
            else:
                # 이미 등록된 통화 — 기존 행을 그대로 사용(중복 생성 안 함).
                # 행 잠금: 받아쓰기 워커가 동시에 PROCESSING 으로 바꾸는 것과 겹치지 않게.
                audio_row = await conn.fetchrow(
                    """
                    SELECT id, transcript_status, file_path FROM audio_files
                    WHERE event_id = $1 ORDER BY id LIMIT 1 FOR UPDATE
                    """,
                    event_id,
                )
                existing_path = audio_row["file_path"]
                dup_action = decide_duplicate_action(
                    audio_row["transcript_status"], existing_path, dest_path,
                    bool(existing_path) and os.path.exists(existing_path),
                )
                if dup_action == "replace":
                    os.replace(tmp_path, dest_path)
                    await conn.execute(
                        "UPDATE audio_files SET size_bytes = $2 WHERE id = $1", audio_row["id"], total,
                    )
                elif dup_action == "restore":
                    os.makedirs(os.path.dirname(existing_path), exist_ok=True)
                    os.replace(tmp_path, existing_path)
                    await conn.execute(
                        "UPDATE audio_files SET size_bytes = $2 WHERE id = $1", audio_row["id"], total,
                    )
                # 'discard' → 아무것도 안 함. 임시 파일은 바깥 finally 에서 지움
                # (이미 받아쓰기 끝나 원본이 지워진 통화에 고아 파일이 쌓이던 문제 방지)

        meta = {"size_bytes": total, "duration_sec": duration_sec, "audio_file_id": str(audio_row["id"])}
        if dup_action:
            meta["duplicate"] = dup_action
        await log_activity(conn, user_id, device_id, "upload.audio", event_id, ip, ua, meta)

    return {
        "ok": True,
        "audio_file_id": str(audio_row["id"]),
        "size_bytes": total,
    }
