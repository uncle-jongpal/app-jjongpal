"""
쫑팔이삼촌 — whisper 워커 (전달 전용 / API 모드)

이 워커는 위스퍼 모델을 직접 돌리지 않는다. 무거운 받아쓰기는 원격 GPU 서버(집 데탑)가 담당하고,
이 워커는 대기열을 폴링해서 음성 파일을 그 서버로 넘기고 결과를 받아 저장하는 "전달자" 역할만 한다.

흐름:
1. audio_files.transcript_status='PENDING' 폴링
2. PROCESSING 마킹
3. 원격 GPU 받아쓰기 서버로 음성 전달 → (전체 텍스트, 구간목록) 수신
4. transcripts 행 INSERT (summary_status='PENDING', segments_json)
5. audio_files.transcript_status='DONE' + processed_at=NOW + file_path=NULL
6. **통화 음성 파일 즉시 삭제** (디스크 + audio_files.file_path NULL)
7. audio_processing_log OK 기록

원격 서버가 일시적으로 안 닿으면(꺼짐/네트워크) FAILED 로 찍지 않고 대기열(PENDING)에 되돌려,
서버가 돌아오면 자동으로 이어받는다. 진짜 오류(깨진 파일 등)만 FAILED.
"""

import asyncio
import logging
import json
import os
import time

import asyncpg
import requests

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("whisper-worker")

DATABASE_URL = os.environ["DATABASE_URL"]
LANGUAGE = os.environ.get("WHISPER_LANGUAGE", "ko")
POLL_INTERVAL_SEC = int(os.environ.get("WHISPER_POLL_INTERVAL_SEC", "5"))
BATCH_SIZE = int(os.environ.get("WHISPER_BATCH_SIZE", "3"))

# 원격 GPU 받아쓰기 서버. 예: http://192.168.0.8:20410 (전달 전용이라 필수)
WHISPER_BACKEND_URL = os.environ.get("WHISPER_BACKEND_URL", "").strip().rstrip("/")
WHISPER_TIMEOUT_SEC = int(os.environ.get("WHISPER_TIMEOUT_SEC", "600"))
# 원격 서버가 안 닿을 때 대기열에 두고 재시도하기 전 대기 시간
BACKEND_RETRY_BACKOFF_SEC = int(os.environ.get("WHISPER_BACKEND_BACKOFF_SEC", "15"))


class TransientBackendError(Exception):
    """원격 받아쓰기 서버가 일시적으로 안 닿음. FAILED 처리 말고 대기열에 두고 재시도."""
    pass


def _transcribe_via_api(file_path: str):
    """원격 GPU 서버로 음성 파일을 보내 (전체 텍스트, 구간목록) 을 받는다.

    연결 실패/5xx 는 TransientBackendError (일시 오류 → 대기열 유지). 4xx·파싱오류는 영구 오류.
    """
    try:
        with open(file_path, "rb") as f:
            resp = requests.post(
                f"{WHISPER_BACKEND_URL}/transcribe",
                files={"file": (os.path.basename(file_path), f, "application/octet-stream")},
                data={"language": LANGUAGE},
                timeout=WHISPER_TIMEOUT_SEC,
            )
    except requests.exceptions.RequestException as e:
        raise TransientBackendError(f"backend unreachable: {e}") from e

    if resp.status_code >= 500:
        raise TransientBackendError(f"backend {resp.status_code}: {resp.text[:200]}")
    resp.raise_for_status()  # 4xx → 영구 오류(FAILED)

    data = resp.json()
    text = (data.get("text") or "").strip()
    segs = []
    for s in data.get("segments", []):
        t = (s.get("text") or "").strip()
        if not t:
            continue
        segs.append({"s": round(float(s["start"]), 2), "e": round(float(s["end"]), 2), "t": t})
    return text, segs


async def transcribe_one(file_path: str):
    """(전체 텍스트, 구간목록) 반환. 구간목록은 [{s: 시작초, e: 끝초, t: 말}] 형태.

    구간 시간정보가 있어야 앱에서 '이 문장이 나온 지점부터 재생'이 가능하다.
    """
    loop = asyncio.get_running_loop()
    return await loop.run_in_executor(None, _transcribe_via_api, file_path)


async def process_one(conn: asyncpg.Connection, row: asyncpg.Record) -> None:
    audio_id = row["id"]
    file_path = row["file_path"]
    event_id = row["event_id"]
    user_id = row["user_id"]
    started = time.monotonic()

    await conn.execute(
        "UPDATE audio_files SET transcript_status = 'PROCESSING' WHERE id = $1",
        audio_id,
    )

    log.info(f"[{audio_id}] start whisper file={file_path}")

    try:
        if not file_path or not os.path.exists(file_path):
            raise RuntimeError(f"audio file not found on disk: {file_path}")

        text, segments = await transcribe_one(file_path)
        if not text:
            text = "(음성이 감지되지 않음)"
            segments = []
        # 같은 말이 연속 4회 이상 반복되면 받아쓰기가 헛돈 구간으로 보고 표시해 둔다
        for i in range(len(segments) - 3):
            if segments[i]["t"] == segments[i+1]["t"] == segments[i+2]["t"] == segments[i+3]["t"]:
                for j in range(i, min(i + 4, len(segments))):
                    segments[j]["low"] = True

        duration_ms = int((time.monotonic() - started) * 1000)

        async with conn.transaction():
            await conn.execute(
                """
                INSERT INTO transcripts (audio_file_id, event_id, user_id, text, language, summary_status, segments_json)
                VALUES ($1, $2, $3, $4, $5, 'PENDING', $6::jsonb)
                """,
                audio_id, event_id, user_id, text, LANGUAGE, json.dumps(segments, ensure_ascii=False),
            )
            await conn.execute(
                """
                UPDATE audio_files
                SET transcript_status = 'DONE',
                    processed_at = NOW(),
                    file_path = NULL,
                    error_message = NULL
                WHERE id = $1
                """,
                audio_id,
            )
            await conn.execute(
                """
                INSERT INTO audio_processing_log (audio_file_id, stage, status, duration_ms)
                VALUES ($1, 'whisper', 'OK', $2)
                """,
                audio_id, duration_ms,
            )

        # 통화 음성 파일 즉시 삭제 (결정 11)
        try:
            os.remove(file_path)
        except OSError as e:
            log.warning(f"[{audio_id}] file remove warn: {e}")

        log.info(f"[{audio_id}] done in {duration_ms} ms, {len(text)} chars")

    except TransientBackendError as e:
        # 원격 서버 일시 오류 → FAILED 아님. 대기열(PENDING)로 되돌리고 상위로 전파해 백오프.
        await conn.execute(
            "UPDATE audio_files SET transcript_status = 'PENDING' WHERE id = $1",
            audio_id,
        )
        log.warning(f"[{audio_id}] 받아쓰기 서버 일시 오류 — 대기열 유지 후 재시도: {e}")
        raise

    except Exception as e:
        duration_ms = int((time.monotonic() - started) * 1000)
        err = str(e)[:1000]
        log.exception(f"[{audio_id}] FAILED: {err}")
        await conn.execute(
            "UPDATE audio_files SET transcript_status = 'FAILED', error_message = $1 WHERE id = $2",
            err, audio_id,
        )
        await conn.execute(
            """
            INSERT INTO audio_processing_log (audio_file_id, stage, status, message, duration_ms)
            VALUES ($1, 'whisper', 'FAILED', $2, $3)
            """,
            audio_id, err, duration_ms,
        )


async def main() -> None:
    if not WHISPER_BACKEND_URL:
        log.error("WHISPER_BACKEND_URL 미설정 — 전달 전용 워커는 원격 받아쓰기 서버 주소가 필수. 종료.")
        raise SystemExit(1)
    log.info(f"API 모드(전달 전용): 원격 받아쓰기 서버 {WHISPER_BACKEND_URL} 사용")

    while True:
        try:
            conn = await asyncpg.connect(DATABASE_URL)
        except Exception as e:
            log.warning(f"디비 연결 실패. 5초 후 재시도: {e}")
            await asyncio.sleep(5)
            continue

        try:
            while True:
                rows = await conn.fetch(
                    """
                    SELECT id, file_path, event_id, user_id
                    FROM audio_files
                    WHERE transcript_status = 'PENDING' AND file_path IS NOT NULL
                    ORDER BY uploaded_at
                    LIMIT $1
                    """,
                    BATCH_SIZE,
                )
                if not rows:
                    await asyncio.sleep(POLL_INTERVAL_SEC)
                    continue
                for row in rows:
                    try:
                        await process_one(conn, row)
                    except TransientBackendError:
                        # 원격 서버 복귀 대기 후 재폴링(row 는 이미 PENDING 으로 되돌려짐)
                        await asyncio.sleep(BACKEND_RETRY_BACKOFF_SEC)
                        break
        except (asyncpg.PostgresConnectionError, ConnectionResetError, OSError) as e:
            log.warning(f"디비 연결 끊김. 재연결: {e}")
            try:
                await conn.close()
            except Exception:
                pass
            await asyncio.sleep(3)


if __name__ == "__main__":
    asyncio.run(main())
