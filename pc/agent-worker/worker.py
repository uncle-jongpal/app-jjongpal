"""
쫑팔이삼촌 — 에이전트 워커 (호스트 systemd 로 실행. 도커 아님.)

이유: 클로드 코드 (Claude Code, 명령줄 도구) 가 본인 PC 호스트에 로컬 로그인됨.
컨테이너 안에서 호출하려면 인증 마운트 필요. 호스트에 두는 게 깔끔.

흐름:
1. transcripts.summary_status='PENDING' 폴링
2. PROCESSING 마킹
3. 프롬프트 파일 (`prompts/call_summarize.md`) 의 sha256 해시 계산 →
   prompt_versions 에 없으면 INSERT.
4. `claude --print --output-format json` 호출 + 표준 입력으로 프롬프트 + transcript 전달
5. 응답에서 `result` 필드 (모델 본문) 추출 → JSON 파싱
6. summaries 행 INSERT + todos / appointments 분해 INSERT
7. transcripts.summary_status='DONE' + processed_at=NOW
8. transcript_processing_log OK 기록

실패 시 status='FAILED' + error_message + 로그 FAILED.
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
    # claude --print --output-format json 응답 형식:
    # {"type":"result","subtype":"success","is_error":false,"result":"<본문>", ...}
    if claude_out.get("is_error"):
        raise RuntimeError(f"claude error: {claude_out.get('subtype')}/{claude_out.get('result')}")
    result = claude_out.get("result")
    if not isinstance(result, str) or not result.strip():
        raise RuntimeError("claude response missing 'result'")
    return result.strip()


def parse_model_json(text: str) -> dict:
    # 마크다운 코드 펜스 들어와도 깎아내기
    t = text.strip()
    if t.startswith("```"):
        # 첫 줄 (```json 또는 ```) 제거 + 끝의 ``` 제거
        first_newline = t.find("\n")
        if first_newline > 0:
            t = t[first_newline + 1:]
        if t.endswith("```"):
            t = t[:-3]
        t = t.strip()
    return json.loads(t)


def parse_iso8601(s: Optional[str]) -> Optional[str]:
    # 그대로 통과시킴 — Postgres TIMESTAMPTZ 가 ISO 8601 받음. 빈 값은 NULL.
    if not s or not isinstance(s, str):
        return None
    return s.strip() or None


async def process_one(conn: asyncpg.Connection, row: asyncpg.Record, prompt_version_id: int, prompt_body: str) -> None:
    transcript_id = row["id"]
    text = row["text"]
    event_id = row["event_id"]
    user_id = row["user_id"]
    started = time.monotonic()

    await conn.execute(
        "UPDATE transcripts SET summary_status='PROCESSING' WHERE id=$1",
        transcript_id,
    )

    log.info(f"[{transcript_id}] start claude")

    try:
        full_prompt = prompt_body.replace("{{TRANSCRIPT}}", text)
        claude_out = await run_claude(full_prompt)
        model_text = extract_model_text(claude_out)
        parsed = parse_model_json(model_text)

        summary_text = parsed.get("summary") or ""
        todos = parsed.get("todos") or []
        appts = parsed.get("appointments") or []

        async with conn.transaction():
            summary_row = await conn.fetchrow(
                """
                INSERT INTO summaries (transcript_id, event_id, user_id, summary, raw_json, prompt_version_id)
                VALUES ($1, $2, $3, $4, $5, $6)
                RETURNING id
                """,
                transcript_id, event_id, user_id, summary_text, json.dumps(parsed, ensure_ascii=False), prompt_version_id,
            )

            for t in todos:
                if not isinstance(t, dict):
                    continue
                content = (t.get("content") or "").strip()
                if not content:
                    continue
                await conn.execute(
                    """
                    INSERT INTO todos (id, user_id, content, source, source_event_id, related_person, status)
                    VALUES ($1, $2, $3, 'call', $4, $5, 'open')
                    ON CONFLICT (id) DO NOTHING
                    """,
                    str(uuid4()), user_id, content[:500], event_id,
                    (t.get("person") or None),
                )

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
                await conn.execute(
                    """
                    INSERT INTO appointments (user_id, source_event_id, title, start_at, end_at, location, with_person, confidence)
                    VALUES ($1, $2, $3, $4::timestamptz, $5::timestamptz, $6, $7, $8)
                    """,
                    user_id, event_id, title[:200], start_at, parse_iso8601(a.get("end_at")),
                    (a.get("location") or None), (a.get("with") or None), max(0.0, min(1.0, confidence)),
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
            f"[{transcript_id}] done in {duration_ms} ms — "
            f"summary {len(summary_text)} chars, {len(todos)} todos, {len(appts)} appts"
        )

    except Exception as e:
        duration_ms = int((time.monotonic() - started) * 1000)
        err = str(e)[:1000]
        log.exception(f"[{transcript_id}] FAILED: {err}")
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


async def main() -> None:
    log.info("agent-worker 시작 (호스트 systemd)")
    while True:
        try:
            conn = await asyncpg.connect(DATABASE_URL)
        except Exception as e:
            log.warning(f"디비 연결 실패. 5초 후 재시도: {e}")
            await asyncio.sleep(5)
            continue

        try:
            prompt_body, _ = load_prompt("call_summarize")
            prompt_version_id = await ensure_prompt_version(conn, "call_summarize")

            while True:
                rows = await conn.fetch(
                    """
                    SELECT id, text, event_id, user_id
                    FROM transcripts
                    WHERE summary_status='PENDING'
                    ORDER BY created_at
                    LIMIT $1
                    """,
                    BATCH_SIZE,
                )
                if not rows:
                    await asyncio.sleep(POLL_INTERVAL_SEC)
                    # 프롬프트 파일 변경 감지
                    new_id = await ensure_prompt_version(conn, "call_summarize")
                    if new_id != prompt_version_id:
                        log.info(f"prompt version changed: {prompt_version_id} → {new_id}")
                        prompt_body, _ = load_prompt("call_summarize")
                        prompt_version_id = new_id
                    continue

                for row in rows:
                    await process_one(conn, row, prompt_version_id, prompt_body)
                    await asyncio.sleep(CALL_GAP_SEC)
        except (asyncpg.PostgresConnectionError, ConnectionResetError, OSError) as e:
            log.warning(f"디비 연결 끊김. 재연결: {e}")
            try:
                await conn.close()
            except Exception:
                pass
            await asyncio.sleep(3)


if __name__ == "__main__":
    asyncio.run(main())
