"""
쫑팔이삼촌 — 에이전트 워커 (호스트 systemd 로 실행. 도커 아님.)

이유: 클로드 코드 (Claude Code, 명령줄 도구) 가 본인 PC 호스트에 로컬 로그인됨.
컨테이너 안에서 호출하려면 인증 마운트 필요. 호스트에 두는 게 깔끔.

처리 흐름 2가지:

1. 통화 (transcripts.summary_status='PENDING') → call_summarize 프롬프트 → 요약 + 할 일 + 약속
2. 알림 (events.type='notification' AND processing_status='PENDING') → notification_classify 프롬프트 → 할 일 + 약속

둘 다 한 워커가 번갈아 처리. 실패 시 재시도 한도 안에서.
"""

import asyncio
import hashlib
import json
import logging
import os
import time
from pathlib import Path
from typing import Optional, Tuple
from uuid import uuid4

import asyncpg

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("agent-worker")

DATABASE_URL = os.environ["DATABASE_URL"]
PROMPT_DIR = Path(os.environ.get("PROMPT_DIR", Path(__file__).parent / "prompts"))
CLAUDE_BIN = os.environ.get("CLAUDE_BIN", "claude")
CLAUDE_TIMEOUT_SEC = int(os.environ.get("CLAUDE_TIMEOUT_SEC", "300"))
POLL_INTERVAL_SEC = int(os.environ.get("AGENT_POLL_INTERVAL_SEC", "10"))
BATCH_SIZE = int(os.environ.get("AGENT_BATCH_SIZE", "3"))
CALL_GAP_SEC = float(os.environ.get("AGENT_CALL_GAP_SEC", "2.0"))

# 시스템 / 잡음 패키지 — 분류 대상에서 제외 (SKIPPED 처리)
SKIP_PACKAGES = {
    "android",
    "com.android.systemui",
    "com.android.settings",
    "com.android.providers.media",
    "com.samsung.android.app.notes.sync",
    "com.samsung.android.bixby.agent",
    "com.google.android.gms",
    "com.google.android.apps.messaging.notifications",
    "app.jongpal.jjongpal",
}


def load_prompt(name: str) -> Tuple[str, str]:
    path = PROMPT_DIR / f"{name}.md"
    body = path.read_text(encoding="utf-8")
    h = hashlib.sha256(body.encode("utf-8")).hexdigest()
    return body, h


async def ensure_prompt_version(conn: asyncpg.Connection, name: str) -> int:
    body, h = load_prompt(name)
    row = await conn.fetchrow(
        "SELECT id FROM prompt_versions WHERE name=$1 AND hash=$2",
        name, h,
    )
    if row:
        return row["id"]
    row = await conn.fetchrow(
        """
        INSERT INTO prompt_versions (name, hash, body) VALUES ($1, $2, $3)
        ON CONFLICT (name, hash) DO UPDATE SET hash = EXCLUDED.hash
        RETURNING id
        """,
        name, h, body,
    )
    return row["id"]


async def run_claude(prompt: str) -> dict:
    proc = await asyncio.create_subprocess_exec(
        CLAUDE_BIN, "--print", "--output-format", "json",
        stdin=asyncio.subprocess.PIPE,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE,
    )
    try:
        stdout, stderr = await asyncio.wait_for(
            proc.communicate(prompt.encode("utf-8")),
            timeout=CLAUDE_TIMEOUT_SEC,
        )
    except asyncio.TimeoutError:
        try:
            proc.kill()
        except ProcessLookupError:
            pass
        raise RuntimeError(f"claude timeout after {CLAUDE_TIMEOUT_SEC}s")

    if proc.returncode != 0:
        raise RuntimeError(f"claude exit {proc.returncode}: {stderr.decode('utf-8', 'replace')[:500]}")
    raw = stdout.decode("utf-8", "replace")
    return json.loads(raw)


def extract_model_text(claude_out: dict) -> str:
    if claude_out.get("is_error"):
        raise RuntimeError(f"claude error: {claude_out.get('subtype')}/{claude_out.get('result')}")
    result = claude_out.get("result")
    if not isinstance(result, str) or not result.strip():
        raise RuntimeError("claude response missing 'result'")
    return result.strip()


def parse_model_json(text: str) -> dict:
    t = text.strip()
    if t.startswith("```"):
        first_newline = t.find("\n")
        if first_newline > 0:
            t = t[first_newline + 1:]
        if t.endswith("```"):
            t = t[:-3]
        t = t.strip()
    return json.loads(t)


def parse_iso8601(s: Optional[str]):
    """ISO 8601 문자열을 datetime 으로 변환. asyncpg 가 timestamptz 컬럼에 문자열 거부함."""
    if not s or not isinstance(s, str):
        return None
    s = s.strip()
    if not s:
        return None
    # 'Z' 접미사 → '+00:00' 변환 (datetime.fromisoformat 호환성)
    if s.endswith("Z"):
        s = s[:-1] + "+00:00"
    try:
        from datetime import datetime as _dt
        return _dt.fromisoformat(s)
    except (ValueError, TypeError):
        return None


async def insert_todos_and_appts(
    conn: asyncpg.Connection,
    user_id: int,
    source_event_id: Optional[str],
    source: str,                      # 'call' or 'notification'
    todos: list,
    appts: list,
) -> Tuple[int, int]:
    todo_count = 0
    appt_count = 0
    for t in todos:
        if not isinstance(t, dict):
            continue
        content = (t.get("content") or "").strip()
        if not content:
            continue
        content_safe = content[:500]
        excerpt = (t.get("quote") or "").strip()[:200] or None

        # 중요도 점수 — 0.3 미만(잡일·상대가 할 일)은 아예 제안 안 함. 누락 시 0.5 로 보수적 통과.
        try:
            importance = float(t.get("importance", 0.5))
        except (TypeError, ValueError):
            importance = 0.5
        importance = max(0.0, min(1.0, importance))
        if importance < 0.3:
            continue

        # 중복 방지: 같은 (user_id, content) 의 처리 대기 (open / suggested / parked) 할 일이 이미 있으면 INSERT 안 함.
        existing = await conn.fetchval(
            """
            SELECT id FROM todos
            WHERE user_id = $1 AND content = $2 AND status IN ('open', 'suggested', 'parked')
            LIMIT 1
            """,
            user_id, content_safe,
        )
        if existing:
            continue

        # 삼촌이 찾은 할 일은 'suggested' (확인 대기) 로 들어감 — 사용자가 확인해야 목록에 올라감. priority 높은 순으로 정렬됨.
        await conn.execute(
            """
            INSERT INTO todos (id, user_id, content, source, source_event_id, source_excerpt, related_person, status, priority)
            VALUES ($1, $2, $3, $4, $5, $6, $7, 'suggested', $8)
            ON CONFLICT (id) DO NOTHING
            """,
            str(uuid4()), user_id, content_safe, source, source_event_id, excerpt,
            (t.get("person") or None), importance,
        )
        todo_count += 1

    for a in appts:
        if not isinstance(a, dict):
            continue
        title = (a.get("title") or "").strip()
        start_at = parse_iso8601(a.get("start_at"))
        if not title or not start_at:
            continue
        try:
            confidence = float(a.get("confidence", 0.5))
        except (TypeError, ValueError):
            confidence = 0.5
        excerpt = (a.get("quote") or "").strip()[:200] or None
        await conn.execute(
            """
            INSERT INTO appointments (user_id, source_event_id, source_excerpt, title, start_at, end_at, location, with_person, confidence)
            VALUES ($1, $2, $3, $4, $5::timestamptz, $6::timestamptz, $7, $8, $9)
            """,
            user_id, source_event_id, excerpt, title[:200], start_at, parse_iso8601(a.get("end_at")),
            (a.get("location") or None), (a.get("with") or None), max(0.0, min(1.0, confidence)),
        )
        appt_count += 1
    return todo_count, appt_count


# ===== 통화 transcript 처리 =====

async def process_transcript(conn: asyncpg.Connection, row: asyncpg.Record, prompt_version_id: int, prompt_body: str) -> None:
    transcript_id = row["id"]
    text = row["text"]
    event_id = row["event_id"]
    user_id = row["user_id"]
    started = time.monotonic()

    await conn.execute(
        "UPDATE transcripts SET summary_status='PROCESSING' WHERE id=$1",
        transcript_id,
    )

    log.info(f"[transcript {transcript_id}] start claude")

    try:
        full_prompt = prompt_body.replace("{{TRANSCRIPT}}", text)
        claude_out = await run_claude(full_prompt)
        model_text = extract_model_text(claude_out)
        parsed = parse_model_json(model_text)

        summary_text = parsed.get("summary") or ""
        todos = parsed.get("todos") or []
        appts = parsed.get("appointments") or []

        async with conn.transaction():
            await conn.fetchrow(
                """
                INSERT INTO summaries (transcript_id, event_id, user_id, summary, raw_json, prompt_version_id)
                VALUES ($1, $2, $3, $4, $5, $6)
                RETURNING id
                """,
                transcript_id, event_id, user_id, summary_text, json.dumps(parsed, ensure_ascii=False), prompt_version_id,
            )
            todo_count, appt_count = await insert_todos_and_appts(
                conn, user_id, event_id, "call", todos, appts,
            )

            duration_ms = int((time.monotonic() - started) * 1000)
            await conn.execute(
                "UPDATE transcripts SET summary_status='DONE', processed_at=NOW(), error_message=NULL WHERE id=$1",
                transcript_id,
            )
            await conn.execute(
                """
                INSERT INTO transcript_processing_log (transcript_id, stage, status, duration_ms, prompt_version_id)
                VALUES ($1, 'claude', 'OK', $2, $3)
                """,
                transcript_id, duration_ms, prompt_version_id,
            )

        log.info(
            f"[transcript {transcript_id}] done in {duration_ms} ms — "
            f"summary {len(summary_text)} chars, {todo_count} todos, {appt_count} appts"
        )

    except Exception as e:
        duration_ms = int((time.monotonic() - started) * 1000)
        err = str(e)[:1000]
        log.exception(f"[transcript {transcript_id}] FAILED: {err}")
        await conn.execute(
            "UPDATE transcripts SET summary_status='FAILED', error_message=$1 WHERE id=$2",
            err, transcript_id,
        )
        await conn.execute(
            """
            INSERT INTO transcript_processing_log (transcript_id, stage, status, message, duration_ms, prompt_version_id)
            VALUES ($1, 'claude', 'FAILED', $2, $3, $4)
            """,
            transcript_id, err, duration_ms, prompt_version_id,
        )


# ===== 알림 이벤트 처리 =====

async def process_notification(conn: asyncpg.Connection, row: asyncpg.Record, prompt_version_id: int, prompt_body: str) -> None:
    event_id = row["id"]
    user_id = row["user_id"]
    source_pkg = row["source_package"] or ""
    title = row["title"] or ""
    content = row["content"] or ""
    timestamp = row["timestamp"]
    started = time.monotonic()

    # 빈 알림 / 시스템 알림 → SKIPPED
    if (source_pkg in SKIP_PACKAGES) or (not title and not content):
        await conn.execute(
            "UPDATE events SET processing_status='SKIPPED', processed_at=NOW() WHERE id=$1",
            event_id,
        )
        await conn.execute(
            """
            INSERT INTO event_processing_log (event_id, stage, status, message, duration_ms)
            VALUES ($1, 'claude_classify', 'SKIPPED', $2, 0)
            """,
            event_id, f"package={source_pkg}",
        )
        return

    await conn.execute(
        "UPDATE events SET processing_status='PROCESSING' WHERE id=$1",
        event_id,
    )
    log.info(f"[event {event_id}] start claude (pkg={source_pkg})")

    try:
        full_prompt = (
            prompt_body
            .replace("{{SOURCE_PACKAGE}}", source_pkg)
            .replace("{{TITLE}}", title)
            .replace("{{CONTENT}}", content[:1500])
            .replace("{{TIMESTAMP}}", timestamp.isoformat())
        )
        claude_out = await run_claude(full_prompt)
        model_text = extract_model_text(claude_out)
        parsed = parse_model_json(model_text)

        is_actionable = bool(parsed.get("is_actionable"))
        todos = parsed.get("todos") or []
        appts = parsed.get("appointments") or []

        async with conn.transaction():
            if is_actionable and (todos or appts):
                todo_count, appt_count = await insert_todos_and_appts(
                    conn, user_id, event_id, "notification", todos, appts,
                )
            else:
                todo_count = appt_count = 0

            duration_ms = int((time.monotonic() - started) * 1000)
            await conn.execute(
                "UPDATE events SET processing_status='DONE', processed_at=NOW(), processing_error=NULL WHERE id=$1",
                event_id,
            )
            await conn.execute(
                """
                INSERT INTO event_processing_log
                    (event_id, stage, status, duration_ms, prompt_version_id, todos_extracted, appointments_extracted)
                VALUES ($1, 'claude_classify', 'OK', $2, $3, $4, $5)
                """,
                event_id, duration_ms, prompt_version_id, todo_count, appt_count,
            )

        log.info(
            f"[event {event_id}] done in {duration_ms} ms — "
            f"actionable={is_actionable}, {todo_count} todos, {appt_count} appts"
        )

    except Exception as e:
        duration_ms = int((time.monotonic() - started) * 1000)
        err = str(e)[:1000]
        log.exception(f"[event {event_id}] FAILED: {err}")
        await conn.execute(
            "UPDATE events SET processing_status='FAILED', processing_error=$1 WHERE id=$2",
            err, event_id,
        )
        await conn.execute(
            """
            INSERT INTO event_processing_log (event_id, stage, status, message, duration_ms, prompt_version_id)
            VALUES ($1, 'claude_classify', 'FAILED', $2, $3, $4)
            """,
            event_id, err, duration_ms, prompt_version_id,
        )


# ===== 메인 루프 =====

async def main() -> None:
    log.info("agent-worker 시작 (호스트 systemd, 통화 + 알림 처리)")

    while True:
        try:
            conn = await asyncpg.connect(DATABASE_URL)
        except Exception as e:
            log.warning(f"디비 연결 실패. 5초 후 재시도: {e}")
            await asyncio.sleep(5)
            continue

        try:
            call_body, _ = load_prompt("call_summarize")
            call_pv = await ensure_prompt_version(conn, "call_summarize")
            notif_body, _ = load_prompt("notification_classify")
            notif_pv = await ensure_prompt_version(conn, "notification_classify")

            while True:
                # 1. 통화 처리 대기 폴링
                transcripts = await conn.fetch(
                    """
                    SELECT id, text, event_id, user_id
                    FROM transcripts
                    WHERE summary_status='PENDING'
                    ORDER BY created_at
                    LIMIT $1
                    """,
                    BATCH_SIZE,
                )
                if transcripts:
                    for row in transcripts:
                        await process_transcript(conn, row, call_pv, call_body)
                        await asyncio.sleep(CALL_GAP_SEC)
                    continue   # 한 종류 다 처리하고 다시 루프

                # 2. 알림 처리 대기 폴링
                notifs = await conn.fetch(
                    """
                    SELECT id, user_id, source_package, title, content, timestamp
                    FROM events
                    WHERE type='notification' AND processing_status='PENDING'
                    ORDER BY timestamp ASC
                    LIMIT $1
                    """,
                    BATCH_SIZE,
                )
                if notifs:
                    for row in notifs:
                        await process_notification(conn, row, notif_pv, notif_body)
                        await asyncio.sleep(CALL_GAP_SEC)
                    continue

                # 3. 둘 다 비어있으면 대기 + 프롬프트 변경 감지
                await asyncio.sleep(POLL_INTERVAL_SEC)
                new_call_pv = await ensure_prompt_version(conn, "call_summarize")
                if new_call_pv != call_pv:
                    log.info(f"call prompt version changed: {call_pv} → {new_call_pv}")
                    call_body, _ = load_prompt("call_summarize")
                    call_pv = new_call_pv
                new_notif_pv = await ensure_prompt_version(conn, "notification_classify")
                if new_notif_pv != notif_pv:
                    log.info(f"notification prompt version changed: {notif_pv} → {new_notif_pv}")
                    notif_body, _ = load_prompt("notification_classify")
                    notif_pv = new_notif_pv

        except (asyncpg.PostgresConnectionError, ConnectionResetError, OSError) as e:
            log.warning(f"디비 연결 끊김. 재연결: {e}")
            try:
                await conn.close()
            except Exception:
                pass
            await asyncio.sleep(3)


if __name__ == "__main__":
    asyncio.run(main())
