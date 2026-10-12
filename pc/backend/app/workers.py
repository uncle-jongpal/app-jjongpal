"""
백그라운드 워커 — whisper 전달 워커 + FCM 푸시 워커.
whisper-worker/worker.py, fcm-pusher/pusher.py 의 동작을 그대로 포팅하되,
각자 asyncpg.connect 하던 것을 공유 풀(db.pool)로 바꾸고 FastAPI lifespan 에서 태스크로 띄운다.

중요(불변식 유지):
- 단일 인스턴스 전제. uvicorn --workers 1 이어야 이 루프가 대기열을 이중 소비하지 않는다.
- 블로킹 호출(requests, google-auth 토큰 갱신)은 run_in_executor 로 이벤트 루프를 막지 않는다.
- whisper 오류 구분(2026-10-13 개편, 대기열 맨 앞 막힘 방지):
    · 서버 다운(연결 거부·연결 시간초과·502/503/504) = PENDING 복귀+백오프, 시도 횟수 안 셈
    · 파일 단위 일시 오류(응답 시간초과·500 등)       = attempt_count+1, N회 넘으면 FAILED,
      아니면 PENDING 으로 두되 WHISPER_RETRY_DELAY_SEC 동안은 다시 안 집음
    · 진짜 오류(4xx/깨짐)                              = FAILED
  집는 순서는 attempt_count 적은 것 먼저 → 독 파일 하나가 뒤 통화를 영원히 막지 않는다.
- fcm: 한 대라도 성공하면 pushed=TRUE, UNREGISTERED/INVALID_ARGUMENT 면 해당 토큰 NULL.
- 워커는 RUN_WORKERS 플래그로 끌 수 있다(스테이징 이중 소비 방지).
"""

import asyncio
import json
import logging
import os
import time
from typing import Optional

import asyncpg
import httpx
import requests
from google.auth.transport.requests import Request as GoogleAuthRequest
from google.oauth2 import service_account

from . import config, db

log = logging.getLogger("workers")


# ============================================================
# whisper 전달 워커
# ============================================================
class TransientBackendError(Exception):
    """받아쓰기 서버 쪽 일시 오류 공통 부모. FAILED 바로 확정하지 않는다."""
    pass


class WhisperBackendDown(TransientBackendError):
    """받아쓰기 서버 자체가 안 닿음(꺼짐·연결 거부·게이트웨이 오류).
    파일 탓이 아니므로 시도 횟수를 안 세고 대기열에 둔 채 백오프."""
    pass


class WhisperFileTransient(TransientBackendError):
    """서버엔 닿았지만 이 파일 처리 중 일시 오류(응답 시간초과·500 등).
    파일 탓일 수 있으므로 시도 횟수를 세고, 상한을 넘으면 FAILED."""
    pass


# 서버가 통째로 못 받는 상태를 뜻하는 HTTP 코드(게이트웨이/점검/모델 로딩 중)
WHISPER_DOWN_STATUSES = frozenset({502, 503, 504})


def classify_http_status(status_code: int) -> str:
    """받아쓰기 서버 응답 코드 분류: 'ok' | 'down' | 'file' | 'permanent'."""
    if status_code < 400:
        return "ok"
    if status_code in WHISPER_DOWN_STATUSES:
        return "down"
    if status_code >= 500:
        return "file"
    return "permanent"  # 4xx


class FileErrorJudge:
    """파일 단위 오류를 '파일 탓'으로 셀지, '서버 전체 문제'로 볼지 판정.

    작업PC 받아쓰기 서버는 헬스 체크는 정상인데 모든 파일에 500 을 내는 상태가 있다
    (2026-10-07 NVIDIA 드라이버 갱신 후 실제 사례, GPU 메모리 부족도 같은 모양).
    그때 파일마다 시도 횟수를 세면 대기열 전체가 몇 분 만에 FAILED 로 굳는다.

    규칙: 직전에 처리한 파일이 성공했을 때(=서버가 멀쩡함이 입증됨)의 오류만 파일 탓으로 센다.
    오류가 연달아 나면 두 번째부터는 서버 문제로 보고 횟수를 안 세며 백오프한다.
    → 서버 전체 장애 중엔 첫 파일만 1회 세고 끝. 독 파일 하나는 다른 파일이 성공할 때마다 1회씩 쌓여 결국 FAILED.
    """

    def __init__(self) -> None:
        self._last_ok = True  # 기동 직후 첫 오류는 파일 탓으로 셈

    def on_success(self) -> None:
        self._last_ok = True

    def blame_file(self) -> bool:
        """이번 오류를 파일 탓으로 셀지. 호출하면 '직전 결과=실패'로 기록된다."""
        blame = self._last_ok
        self._last_ok = False
        return blame


_file_error_judge = FileErrorJudge()


def _iter_causes(exc: BaseException):
    """예외와 그 원인 사슬(args 안 예외, urllib3 .reason, __cause__, __context__)을 훑는다."""
    seen = set()
    stack = [exc]
    while stack:
        e = stack.pop()
        if e is None or id(e) in seen or not isinstance(e, BaseException):
            continue
        seen.add(id(e))
        yield e
        stack.append(getattr(e, "reason", None))
        stack.append(e.__cause__)
        stack.append(e.__context__)
        for a in getattr(e, "args", ()):
            if isinstance(a, BaseException):
                stack.append(a)


def classify_request_error(exc: BaseException) -> str:
    """requests 예외 분류: 'down'(서버에 연결 자체를 못 맺음) | 'file'(연결 후 처리 중 문제).

    - 연결 거부·이름 해석 실패·연결 시간초과·잘못된 주소 → 'down' (시도 횟수 안 셈)
    - 응답 대기 시간초과(ReadTimeout)·처리 중 연결 끊김 등 → 'file' (시도 횟수 셈)
      (특정 파일이 서버를 죽이는 경우도 '처리 중 끊김'으로 나타나므로 셈에 넣는다)
    """
    import urllib3.exceptions as u3

    if isinstance(exc, (requests.exceptions.ConnectTimeout,
                        requests.exceptions.InvalidURL,
                        requests.exceptions.MissingSchema,
                        requests.exceptions.InvalidSchema)):
        return "down"
    for e in _iter_causes(exc):
        # NameResolutionError 는 NewConnectionError 의 하위(urllib3 2.x)
        if isinstance(e, (u3.NewConnectionError, u3.ConnectTimeoutError, ConnectionRefusedError)):
            return "down"
    return "file"


def _transcribe_via_api(file_path: str):
    """원격 GPU 서버로 음성 파일을 보내 (전체 텍스트, 구간목록) 을 받는다.
    서버 다운 → WhisperBackendDown, 파일 단위 일시 오류 → WhisperFileTransient,
    4xx·파싱오류 → 그 밖의 예외(영구 오류)."""
    try:
        with open(file_path, "rb") as f:
            resp = requests.post(
                f"{config.WHISPER_BACKEND_URL}/transcribe",
                files={"file": (os.path.basename(file_path), f, "application/octet-stream")},
                data={"language": config.WHISPER_LANGUAGE},
                timeout=config.WHISPER_TIMEOUT_SEC,
            )
    except requests.exceptions.RequestException as e:
        if classify_request_error(e) == "down":
            raise WhisperBackendDown(f"backend unreachable: {e}") from e
        raise WhisperFileTransient(f"request failed: {e}") from e

    kind = classify_http_status(resp.status_code)
    if kind == "down":
        raise WhisperBackendDown(f"backend {resp.status_code}: {resp.text[:200]}")
    if kind == "file":
        raise WhisperFileTransient(f"backend {resp.status_code}: {resp.text[:200]}")
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
    """블로킹 requests 호출을 executor 로 넘겨 이벤트 루프를 막지 않음."""
    loop = asyncio.get_running_loop()
    return await loop.run_in_executor(None, _transcribe_via_api, file_path)


async def _count_file_transient(conn: asyncpg.Connection, audio_id, started: float, e: Exception) -> None:
    """파일 단위 일시 오류 → 시도 횟수 +1. 상한 도달 시 FAILED 확정(processed_at 기록),
    아니면 PENDING 으로 두고 processing_at=NOW() 로 재시도 대기 시작점을 찍는다."""
    duration_ms = int((time.monotonic() - started) * 1000)
    err = str(e)[:1000]
    upd = await conn.fetchrow(
        """
        UPDATE audio_files
        SET attempt_count = COALESCE(attempt_count, 0) + 1,
            transcript_status = CASE WHEN COALESCE(attempt_count, 0) + 1 >= $2
                                     THEN 'FAILED' ELSE 'PENDING' END,
            processed_at = CASE WHEN COALESCE(attempt_count, 0) + 1 >= $2
                                THEN NOW() ELSE processed_at END,
            processing_at = NOW(),
            error_message = $3
        WHERE id = $1
        RETURNING attempt_count, transcript_status
        """,
        audio_id, config.WHISPER_MAX_TRANSIENT_ATTEMPTS, err,
    )
    attempts = upd["attempt_count"] if upd else None
    status = upd["transcript_status"] if upd else "?"
    await conn.execute(
        """
        INSERT INTO audio_processing_log (audio_file_id, stage, status, message, duration_ms)
        VALUES ($1, 'whisper', 'FAILED', $2, $3)
        """,
        audio_id,
        f"[일시 오류 {attempts}/{config.WHISPER_MAX_TRANSIENT_ATTEMPTS} → {status}] {err}"[:1000],
        duration_ms,
    )
    log.warning(
        f"[{audio_id}] 파일 단위 일시 오류 ({attempts}/{config.WHISPER_MAX_TRANSIENT_ATTEMPTS}) → {status}: {err}"
    )



async def whisper_process_one(conn: asyncpg.Connection, row: asyncpg.Record) -> None:
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
            if segments[i]["t"] == segments[i + 1]["t"] == segments[i + 2]["t"] == segments[i + 3]["t"]:
                for j in range(i, min(i + 4, len(segments))):
                    segments[j]["low"] = True

        duration_ms = int((time.monotonic() - started) * 1000)

        async with conn.transaction():
            await conn.execute(
                """
                INSERT INTO transcripts (audio_file_id, event_id, user_id, text, language, summary_status, segments_json)
                VALUES ($1, $2, $3, $4, $5, 'PENDING', $6::jsonb)
                """,
                audio_id, event_id, user_id, text, config.WHISPER_LANGUAGE,
                json.dumps(segments, ensure_ascii=False),
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

        # 통화 음성 파일 즉시 삭제
        try:
            os.remove(file_path)
        except OSError as e:
            log.warning(f"[{audio_id}] file remove warn: {e}")

        _file_error_judge.on_success()
        log.info(f"[{audio_id}] done in {duration_ms} ms, {len(text)} chars")

    except WhisperFileTransient as e:
        if not _file_error_judge.blame_file():
            # 연달아 실패 → 파일보다 서버 쪽 문제일 가능성이 큼. 횟수 안 세고 서버 다운처럼 백오프.
            await conn.execute(
                "UPDATE audio_files SET transcript_status = 'PENDING' WHERE id = $1",
                audio_id,
            )
            log.warning(f"[{audio_id}] 연속 파일 오류 — 서버 쪽 문제로 보고 횟수 없이 백오프: {e}")
            raise WhisperBackendDown(f"consecutive file errors: {e}") from e
        await _count_file_transient(conn, audio_id, started, e)

    except WhisperBackendDown as e:
        # 서버 자체가 다운 → 파일 탓 아님. 횟수 안 세고 PENDING 복귀, 상위로 전파해 백오프.
        await conn.execute(
            "UPDATE audio_files SET transcript_status = 'PENDING' WHERE id = $1",
            audio_id,
        )
        log.warning(f"[{audio_id}] 받아쓰기 서버 다운 — 대기열 유지 후 백오프: {e}")
        raise

    except Exception as e:
        duration_ms = int((time.monotonic() - started) * 1000)
        err = str(e)[:1000]
        log.exception(f"[{audio_id}] FAILED: {err}")
        await conn.execute(
            # processed_at 기록 필수: 회수 함수(reap_stuck_processing)는 FAILED 를
            # processed_at < now()-30분 일 때만 재시도한다. NULL 이면 영영 재시도 안 됨.
            "UPDATE audio_files SET transcript_status = 'FAILED', error_message = $1, processed_at = NOW() WHERE id = $2",
            err, audio_id,
        )
        await conn.execute(
            """
            INSERT INTO audio_processing_log (audio_file_id, stage, status, message, duration_ms)
            VALUES ($1, 'whisper', 'FAILED', $2, $3)
            """,
            audio_id, err, duration_ms,
        )


async def fetch_pending_audio(conn: asyncpg.Connection) -> list:
    """받아쓰기 대기열에서 이번 회차에 처리할 행들.
    시도 횟수 적은 것 먼저 → 독 파일이 대기열 맨 앞을 영원히 막지 않음.
    일시 오류로 되돌아온 건(attempt>0)은 WHISPER_RETRY_DELAY_SEC 지난 뒤에만 다시 집음."""
    return await conn.fetch(
        """
        SELECT id, file_path, event_id, user_id
        FROM audio_files
        WHERE transcript_status = 'PENDING' AND file_path IS NOT NULL
          AND (COALESCE(attempt_count, 0) = 0
               OR processing_at IS NULL
               OR processing_at < NOW() - make_interval(secs => $2))
        ORDER BY COALESCE(attempt_count, 0), uploaded_at
        LIMIT $1
        """,
        config.WHISPER_BATCH_SIZE,
        float(config.WHISPER_RETRY_DELAY_SEC),
    )


async def whisper_loop() -> None:
    if not config.WHISPER_BACKEND_URL:
        log.error("WHISPER_BACKEND_URL 미설정 — whisper 전달 워커 비활성(앱은 계속 뜸).")
        return
    log.info(f"whisper 워커(전달 전용): 원격 받아쓰기 서버 {config.WHISPER_BACKEND_URL} 사용")

    while True:
        try:
            # 2026-10-10: 연결을 영원히 붙잡지 않고 매 회차 새로 빌림. 끊긴 연결에 묶여
            # 로그 없이 멈추던 문제(10/09 리셋 후 19시간 정지) 방지. 문장 시간 제한은 풀 설정(command_timeout).
            backoff = 0.0
            async with db.pool.acquire() as conn:
                rows = await fetch_pending_audio(conn)
                for row in rows:
                    try:
                        await whisper_process_one(conn, row)
                    except WhisperBackendDown:
                        # 원격 서버 복귀 대기 후 재폴링(row 는 이미 PENDING 으로 되돌려짐)
                        backoff = config.WHISPER_BACKEND_BACKOFF_SEC
                        break
            if backoff:
                await asyncio.sleep(backoff)
            elif not rows:
                await asyncio.sleep(config.WHISPER_POLL_INTERVAL_SEC)
        except asyncio.CancelledError:
            raise
        except (asyncpg.PostgresConnectionError, ConnectionResetError, OSError) as e:
            log.warning(f"[whisper] 디비 연결 문제. 재시도: {e}")
            await asyncio.sleep(3)
        except Exception as e:
            log.exception(f"[whisper] 루프 오류. 재시도: {e}")
            await asyncio.sleep(5)


# ============================================================
# FCM 푸시 워커
# ============================================================
FCM_ENDPOINT = None
FCM_SCOPES = ["https://www.googleapis.com/auth/firebase.messaging"]
_creds: Optional[service_account.Credentials] = None


def _get_access_token() -> str:
    """서비스 계정으로 FCM v1 액세스 토큰 발급(블로킹 — executor 에서 호출)."""
    global _creds
    if _creds is None:
        _creds = service_account.Credentials.from_service_account_file(
            config.FIREBASE_CREDENTIALS, scopes=FCM_SCOPES
        )
    if not _creds.valid:
        _creds.refresh(GoogleAuthRequest())
    return _creds.token


async def get_access_token() -> str:
    # 블로킹 google-auth 갱신을 executor 로 넘김(이벤트 루프 보호).
    loop = asyncio.get_running_loop()
    return await loop.run_in_executor(None, _get_access_token)


async def push_to_device(client: httpx.AsyncClient, fcm_token: str, payload_data: dict) -> bool:
    token = await get_access_token()
    headers = {
        "Authorization": f"Bearer {token}",
        "Content-Type": "application/json",
    }
    body = {
        "message": {
            "token": fcm_token,
            "data": {k: str(v) for k, v in payload_data.items()},
            "android": {"priority": "HIGH"},
        }
    }
    r = await client.post(FCM_ENDPOINT, headers=headers, json=body, timeout=15.0)
    if r.status_code == 200:
        return True
    log.warning(f"FCM push failed status={r.status_code} body={r.text[:300]}")
    # 토큰이 폐기된 폰이면 404 / UNREGISTERED — 디바이스 토큰 정리 신호
    if r.status_code in (400, 404):
        try:
            error_detail = r.json().get("error", {}).get("status", "")
        except Exception:
            error_detail = ""
        if "UNREGISTERED" in error_detail or "INVALID_ARGUMENT" in error_detail:
            return False
    return False


async def fcm_process_summary(conn: asyncpg.Connection, client: httpx.AsyncClient, row: asyncpg.Record) -> None:
    summary_id = row["id"]
    user_id = row["user_id"]

    devices = await conn.fetch(
        """
        SELECT id, fcm_token FROM devices
        WHERE user_id = $1 AND revoked_at IS NULL AND fcm_token IS NOT NULL
        """,
        user_id,
    )
    if not devices:
        log.info(f"[{summary_id}] no active device for user {user_id}, marking pushed (no-op)")
        await conn.execute(
            "UPDATE summaries SET pushed = TRUE, pushed_at = NOW() WHERE id = $1",
            summary_id,
        )
        return

    payload = {"type": "summary_ready", "summary_id": str(summary_id)}
    success = 0
    for d in devices:
        ok = await push_to_device(client, d["fcm_token"], payload)
        if ok:
            success += 1
        else:
            # 토큰 정리 — fcm_token NULL 로 (디바이스는 살려둠, 다음 로그인에서 토큰 갱신)
            await conn.execute(
                "UPDATE devices SET fcm_token = NULL WHERE id = $1",
                d["id"],
            )

    # 한 대라도 성공이면 push 완료로 마킹. 모두 실패면 다음 폴링에서 재시도.
    if success > 0:
        await conn.execute(
            "UPDATE summaries SET pushed = TRUE, pushed_at = NOW() WHERE id = $1",
            summary_id,
        )
    log.info(f"[{summary_id}] pushed to {success}/{len(devices)} devices for user {user_id}")


async def fcm_loop() -> None:
    global FCM_ENDPOINT
    if not config.FIREBASE_PROJECT_ID:
        log.error("FIREBASE_PROJECT_ID 미설정 — FCM 워커 비활성(앱은 계속 뜸).")
        return
    FCM_ENDPOINT = f"https://fcm.googleapis.com/v1/projects/{config.FIREBASE_PROJECT_ID}/messages:send"
    log.info("fcm 워커 시작")

    while True:
        try:
            async with httpx.AsyncClient() as client:
                async with db.pool.acquire() as conn:
                    while True:
                        rows = await conn.fetch(
                            """
                            SELECT id, user_id
                            FROM summaries
                            WHERE pushed = FALSE
                            ORDER BY created_at
                            LIMIT $1
                            """,
                            config.FCM_BATCH_SIZE,
                        )
                        if not rows:
                            await asyncio.sleep(config.FCM_POLL_INTERVAL_SEC)
                            continue
                        for row in rows:
                            try:
                                await fcm_process_summary(conn, client, row)
                            except asyncio.CancelledError:
                                raise
                            except Exception as e:
                                log.exception(f"push error for summary {row['id']}: {e}")
                                await asyncio.sleep(1)
        except asyncio.CancelledError:
            raise
        except (asyncpg.PostgresConnectionError, ConnectionResetError, OSError) as e:
            log.warning(f"[fcm] 디비 연결 문제. 재시도: {e}")
            await asyncio.sleep(3)
        except Exception as e:
            log.exception(f"[fcm] 루프 오류. 재시도: {e}")
            await asyncio.sleep(5)
