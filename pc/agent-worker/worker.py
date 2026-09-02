"""
쫑팔이삼촌 — 에이전트 워커 (호스트 systemd 로 실행. 도커 아님.)

이유: 클로드 코드 (Claude Code, 명령줄 도구) 가 본인 PC 호스트에 로컬 로그인됨.
컨테이너 안에서 호출하려면 인증 마운트 필요. 호스트에 두는 게 깔끔.

처리 흐름 (통화 요약 전용):
  통화 (transcripts.summary_status='PENDING') → call_summarize 프롬프트 → 요약

2026-09-02: 알림 자동분류 + 할 일·약속 추출 기능 제거(통화 요약 전용으로 가지치기).
              백업: worker.py.bak-20260902
"""

import asyncio
import hashlib
import json
import logging
import os
import time
from pathlib import Path
from typing import Tuple

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
    if claude_out.get("is_error"):
        raise RuntimeError(f"claude error: {claude_out.get('subtype')}/{claude_out.get('result')}")
    result = claude_out.get("result")
    if not isinstance(result, str) or not result.strip():
        raise RuntimeError("claude response missing 'result'")
    return result.strip()


def parse_model_json(text: str) -> dict:
    """모델 답에서 JSON 만 안전하게 꺼낸다.

    모델이 JSON 뒤에 설명을 덧붙이거나 코드블록으로 감싸는 경우가 있어
    (긴 통화일수록 잦음) 첫 번째 완결된 JSON 객체만 잘라 쓴다.
    """
    t = (text or "").strip()

    # 코드블록 안에 들어 있으면 벗겨낸다 (```json ... ```)
    if "```" in t:
        import re as _re
        m = _re.search(r"```(?:json)?\s*\n(.*?)```", t, _re.S)
        if m:
            t = m.group(1).strip()

    try:
        return json.loads(t)
    except json.JSONDecodeError:
        pass

    # 첫 '{' 부터 괄호 짝이 맞는 지점까지만 잘라서 다시 시도 (문자열 안 괄호는 건너뜀)
    start = t.find("{")
    if start < 0:
        raise ValueError("모델 답에 JSON 이 없음")
    depth, in_str, esc = 0, False, False
    for i in range(start, len(t)):
        ch = t[i]
        if in_str:
            if esc:
                esc = False
            elif ch == "\\":
                esc = True
            elif ch == '"':
                in_str = False
            continue
        if ch == '"':
            in_str = True
        elif ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                return json.loads(t[start:i + 1])
    raise ValueError("JSON 괄호가 닫히지 않음")


# ===== 통화 transcript 처리 =====

def depth_directive(text: str) -> str:
    """말한 양에 따라 요약 깊이를 다르게 — 짧은 통화에 장문 요약이 붙는 낭비를 막는다.

    길이(분)가 아니라 **실제 말한 양(글자 수)** 기준. 연결음만 6분인 통화와
    알찬 40초 통화를 구분하기 위함.
    """
    n = len(text or "")
    if n < 120:
        return ("\n\n## 이번 통화 요약 깊이 (필수)\n"
                "말이 거의 없는 짧은 통화입니다. **한 줄 제목 수준으로만** 요약하세요. "
                "억지로 늘리지 말고, 내용이 없으면 없다고 쓰세요. 할 일·약속은 명확한 것만.")
    if n < 1500:
        return ("\n\n## 이번 통화 요약 깊이 (필수)\n"
                "짧은 통화입니다. **핵심만 5~8문장 이내로** 간결하게 요약하세요.")
    if n < 8000:
        return ("\n\n## 이번 통화 요약 깊이 (필수)\n"
                "보통 길이 통화입니다. 주제별로 나눠 **충실히** 요약하세요.")
    return ("\n\n## 이번 통화 요약 깊이 (필수)\n"
            "아주 긴 통화입니다. 주제별 소제목을 나눠 **상세히** 요약하되, "
            "중복되는 잡담은 과감히 생략하고 실제로 중요한 대목 위주로 정리하세요.")


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
        full_prompt = prompt_body.replace("{{TRANSCRIPT}}", text) + depth_directive(text)
        claude_out = await run_claude(full_prompt)
        model_text = extract_model_text(claude_out)
        parsed = parse_model_json(model_text)

        summary_text = parsed.get("summary") or ""

        async with conn.transaction():
            await conn.fetchrow(
                """
                INSERT INTO summaries (transcript_id, event_id, user_id, summary, raw_json, prompt_version_id)
                VALUES ($1, $2, $3, $4, $5, $6)
                RETURNING id
                """,
                transcript_id, event_id, user_id, summary_text, json.dumps(parsed, ensure_ascii=False), prompt_version_id,
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
            f"summary {len(summary_text)} chars"
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


# ===== 메인 루프 =====

async def main() -> None:
    log.info("agent-worker 시작 (호스트 systemd, 통화 요약 전용)")

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

            while True:
                # 통화 처리 대기 폴링
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
                    continue

                # 대기 + 프롬프트 변경 감지
                await asyncio.sleep(POLL_INTERVAL_SEC)
                new_call_pv = await ensure_prompt_version(conn, "call_summarize")
                if new_call_pv != call_pv:
                    log.info(f"call prompt version changed: {call_pv} → {new_call_pv}")
                    call_body, _ = load_prompt("call_summarize")
                    call_pv = new_call_pv

        except (asyncpg.PostgresConnectionError, ConnectionResetError, OSError) as e:
            log.warning(f"디비 연결 끊김. 재연결: {e}")
            try:
                await conn.close()
            except Exception:
                pass
            await asyncio.sleep(3)


if __name__ == "__main__":
    asyncio.run(main())
