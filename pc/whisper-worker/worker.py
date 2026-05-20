"""
쫑팔이삼촌 — whisper 워커

흐름:
1. audio_files.transcript_status='PENDING' 폴링
2. PROCESSING 마킹
3. faster-whisper (위스퍼, 오픈AI 음성-텍스트 모델 변종) 호출 — 한국어, large-v3
4. transcripts 행 INSERT (summary_status='PENDING')
5. audio_files.transcript_status='DONE' + processed_at=NOW + file_path=NULL
6. **통화 음성 파일 즉시 삭제** (디스크 + audio_files.file_path NULL)
7. audio_processing_log OK 기록

실패 시 status='FAILED' + error_message + 로그 FAILED 기록. 파일은 안 지움 (재시도 가능).
"""

import asyncio
import logging
import os
import time
from pathlib import Path
from typing import Optional

import asyncpg
from faster_whisper import WhisperModel

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("whisper-worker")

DATABASE_URL = os.environ["DATABASE_URL"]
STORAGE_ROOT = os.environ.get("STORAGE_ROOT", "/storage")
MODEL_NAME = os.environ.get("WHISPER_MODEL", "large-v3")
LANGUAGE = os.environ.get("WHISPER_LANGUAGE", "ko")
DEVICE = os.environ.get("WHISPER_DEVICE", "cpu")
COMPUTE_TYPE = os.environ.get("WHISPER_COMPUTE_TYPE", "int8")
POLL_INTERVAL_SEC = int(os.environ.get("WHISPER_POLL_INTERVAL_SEC", "5"))
BATCH_SIZE = int(os.environ.get("WHISPER_BATCH_SIZE", "3"))

model: Optional[WhisperModel] = None


def load_model() -> WhisperModel:
    log.info(f"모델 로드 중: {MODEL_NAME} device={DEVICE} compute={COMPUTE_TYPE}")
    m = WhisperModel(MODEL_NAME, device=DEVICE, compute_type=COMPUTE_TYPE, download_root="/models")
    log.info("모델 로드 완료")
    return m


async def transcribe_one(file_path: str) -> str:
    loop = asyncio.get_running_loop()

    def run() -> str:
        segments, info = model.transcribe(
            file_path,
            language=LANGUAGE,
            beam_size=5,
            vad_filter=True,        # 무음 구간 제거
            condition_on_previous_text=False,
        )
        parts = []
        for seg in segments:
            parts.append(seg.text)
        return "".join(parts).strip()

    return await loop.run_in_executor(None, run)


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

        text = await transcribe_one(file_path)
        if not text:
            text = "(음성이 감지되지 않음)"

        duration_ms = int((time.monotonic() - started) * 1000)

        async with conn.transaction():
            await conn.execute(
                """
                INSERT INTO transcripts (audio_file_id, event_id, user_id, text, language, summary_status)
                VALUES ($1, $2, $3, $4, $5, 'PENDING')
                """,
                audio_id, event_id, user_id, text, LANGUAGE,
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
    global model
    model = load_model()

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
                    await process_one(conn, row)
        except (asyncpg.PostgresConnectionError, ConnectionResetError, OSError) as e:
            log.warning(f"디비 연결 끊김. 재연결: {e}")
            try:
                await conn.close()
            except Exception:
                pass
            await asyncio.sleep(3)


if __name__ == "__main__":
    asyncio.run(main())
