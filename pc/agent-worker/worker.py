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
# 디비 문장 하나의 최대 대기(초). 소리 없이 끊긴(반쯤 열린) 연결에서 무한 대기 방지.
# 2026-10-13: 백엔드 받아쓰기 워커(650b74d)와 같은 방식으로 맞춤.
DB_COMMAND_TIMEOUT_SEC = float(os.environ.get("AGENT_DB_COMMAND_TIMEOUT_SEC", "60"))
# 디비 장애 시 재시도 대기(초): 3 → 6 → 12 … 최대 60
RECONNECT_BACKOFF_MIN_SEC = 3.0
RECONNECT_BACKOFF_MAX_SEC = 60.0

# 디비 연결 자체가 문제인 오류. 요약 한 건 처리 중 이게 나면 그 건을 FAILED 로 찍지 않고
# 상위 루프로 올려 풀을 버리고 새로 만든다. (ConnectionDoesNotExistError 는 InterfaceError 하위)
DB_CONN_ERRORS = (
    asyncpg.PostgresConnectionError,
    asyncpg.InterfaceError,
    asyncio.TimeoutError,  # command_timeout 초과
)
# 상위 루프에서 재연결로 다룰 오류 = 위 + 풀 생성 때 나는 소켓 오류(연결 거부 등)
LOOP_CONN_ERRORS = DB_CONN_ERRORS + (ConnectionError, OSError)


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


async def process_transcript(pool: asyncpg.Pool, row: asyncpg.Record, prompt_version_id: int, prompt_body: str) -> None:
    """한 건 요약. 클로드 호출(최대 수 분) 동안에는 디비 연결을 붙잡지 않는다
    (그 사이 연결이 끊겨도 결과 저장 때 새 연결을 빌림)."""
    transcript_id = row["id"]
    text = row["text"]
    event_id = row["event_id"]
    user_id = row["user_id"]
    started = time.monotonic()

    async with pool.acquire() as conn:
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

        async with pool.acquire() as conn:
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

    except DB_CONN_ERRORS:
        # 디비 연결 문제는 이 건의 실패가 아님 → FAILED 로 찍지 않고 상위 루프에서 재연결.
        # 행은 PROCESSING 으로 남지만 회수 함수(reap_stuck_processing)가 15분 뒤 PENDING 으로 되돌린다.
        raise

    except Exception as e:
        duration_ms = int((time.monotonic() - started) * 1000)
        err = str(e)[:1000]
        log.exception(f"[transcript {transcript_id}] FAILED: {err}")
        async with pool.acquire() as conn:
            await conn.execute(
                # processed_at 기록 필수: 회수 함수(reap_stuck_processing)는 FAILED 를
                # processed_at < now()-30분 일 때만 재시도한다. NULL 이면 영영 재시도 안 됨.
                "UPDATE transcripts SET summary_status='FAILED', error_message=$1, processed_at=NOW() WHERE id=$2",
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

async def create_pool() -> asyncpg.Pool:
    return await asyncpg.create_pool(
        DATABASE_URL,
        min_size=1,
        max_size=2,
        command_timeout=DB_COMMAND_TIMEOUT_SEC,
        # 오래 놀던 연결은 버리고 새로 맺음(끊긴 연결 재사용 방지)
        max_inactive_connection_lifetime=300,
    )


async def close_pool(pool) -> None:
    if pool is None:
        return
    try:
        await asyncio.wait_for(pool.close(), timeout=10)
    except Exception:
        pool.terminate()


async def run_cycle(pool: asyncpg.Pool, prompt: dict) -> bool:
    """대기 중 요약 한 묶음 처리. 처리한 게 있으면 True.

    2026-10-13: 연결 하나를 영원히 붙잡던 구조 → 회차마다 풀에서 빌림.
    (백엔드 받아쓰기 워커가 10/09 디비 리셋 후 끊긴 연결에 묶여 19시간 멈춘 사고와 같은 구멍)
    """
    async with pool.acquire() as conn:
        new_pv = await ensure_prompt_version(conn, "call_summarize")
        if new_pv != prompt["pv"]:
            if prompt["pv"] is not None:
                log.info(f"call prompt version changed: {prompt['pv']} → {new_pv}")
            prompt["body"], _ = load_prompt("call_summarize")
            prompt["pv"] = new_pv

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

    for row in transcripts:
        await process_transcript(pool, row, prompt["pv"], prompt["body"])
        await asyncio.sleep(CALL_GAP_SEC)
    return bool(transcripts)


async def main() -> None:
    log.info("agent-worker 시작 (호스트 systemd, 통화 요약 전용)")

    pool = None
    prompt = {"pv": None, "body": ""}
    backoff = RECONNECT_BACKOFF_MIN_SEC

    while True:
        try:
            if pool is None:
                pool = await create_pool()
                log.info("디비 풀 연결됨")
            busy = await run_cycle(pool, prompt)
            backoff = RECONNECT_BACKOFF_MIN_SEC
            if not busy:
                await asyncio.sleep(POLL_INTERVAL_SEC)
        except asyncio.CancelledError:
            raise
        except LOOP_CONN_ERRORS as e:
            log.warning(f"디비 연결 문제. {backoff:.0f}초 후 재연결: {type(e).__name__}: {e}")
            await close_pool(pool)
            pool = None
            await asyncio.sleep(backoff)
            backoff = min(backoff * 2, RECONNECT_BACKOFF_MAX_SEC)
        except Exception as e:
            # 프롬프트 파일 오류 등 예상 밖 오류 — 죽지 않고 대기 후 재시도
            log.exception(f"루프 오류. {backoff:.0f}초 후 재시도: {e}")
            await asyncio.sleep(backoff)
            backoff = min(backoff * 2, RECONNECT_BACKOFF_MAX_SEC)


if __name__ == "__main__":
    asyncio.run(main())
