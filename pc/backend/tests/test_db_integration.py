"""실제 Postgres 에 붙는 통합 테스트 — TEST_DATABASE_URL 이 있을 때만 돈다(평소엔 건너뜀).

운영/개발 디비에 절대 붙이지 말 것. 일회용 컨테이너에 pc/postgres/init/*.sql +
2026-08-12 마이그레이션(트리거·회수 함수)을 깐 빈 디비 전용. 예:
  docker run -d --rm --name jjongpal-pg-test -e POSTGRES_USER=jjongpal -e POSTGRES_PASSWORD=x \
    -e POSTGRES_DB=jjongpal -p 127.0.0.1:55432:5432 -v <init복사본>:/docker-entrypoint-initdb.d:ro postgres:16-alpine
  TEST_DATABASE_URL=postgresql://jjongpal:x@127.0.0.1:55432/jjongpal pytest tests/test_db_integration.py
"""

import asyncio
import importlib.util
import io
import os
import uuid

import pytest

URL = os.environ.get("TEST_DATABASE_URL")
pytestmark = pytest.mark.skipif(not URL, reason="TEST_DATABASE_URL 없음 — 통합 테스트 건너뜀")

if URL:
    import asyncpg
    from starlette.datastructures import UploadFile

    from app import config, db, upload, workers


def run(coro):
    return asyncio.run(coro)


async def _setup(conn, n_audio=1, tmp_path=None):
    uid = await conn.fetchval(
        "INSERT INTO users (email, password_hash, name) VALUES ($1, 'x', 't') RETURNING id",
        f"t-{uuid.uuid4().hex[:8]}@test",
    )
    ids = []
    for i in range(n_audio):
        ev = f"ev-{uuid.uuid4().hex[:10]}"
        await conn.execute("INSERT INTO events (id, user_id, type) VALUES ($1, $2, 'call')", ev, uid)
        path = None
        if tmp_path is not None:
            path = str(tmp_path / f"{ev}.m4a")
            with open(path, "wb") as f:
                f.write(b"\0" * 10)
        aid = await conn.fetchval(
            """INSERT INTO audio_files (event_id, user_id, file_path, transcript_status, uploaded_at)
               VALUES ($1, $2, $3, 'PENDING', NOW() - make_interval(mins => $4)) RETURNING id""",
            ev, uid, path or "/nonexistent", n_audio - i,
        )
        ids.append(aid)
    return uid, ids


def test_file_transient_counts_then_fails(tmp_path, monkeypatch):
    async def go():
        conn = await asyncpg.connect(URL)
        try:
            _, (aid,) = await _setup(conn, 1, tmp_path)
            row = await conn.fetchrow("SELECT id, file_path, event_id, user_id FROM audio_files WHERE id=$1", aid)

            async def boom(_p):
                raise workers.WhisperFileTransient("read timeout")
            monkeypatch.setattr(workers, "transcribe_one", boom)
            monkeypatch.setattr(config, "WHISPER_MAX_TRANSIENT_ATTEMPTS", 3)

            seen = []
            for _ in range(3):
                await workers.whisper_process_one(conn, row)
                r = await conn.fetchrow(
                    "SELECT transcript_status, attempt_count, processed_at, processing_at FROM audio_files WHERE id=$1", aid)
                seen.append((r["transcript_status"], r["attempt_count"], r["processed_at"] is not None))
            assert seen == [("PENDING", 1, False), ("PENDING", 2, False), ("FAILED", 3, True)]
            logs = await conn.fetchval(
                "SELECT count(*) FROM audio_processing_log WHERE audio_file_id=$1 AND status='FAILED'", aid)
            assert logs == 3
        finally:
            await conn.close()
    run(go())


def test_backend_down_does_not_count(tmp_path, monkeypatch):
    async def go():
        conn = await asyncpg.connect(URL)
        try:
            _, (aid,) = await _setup(conn, 1, tmp_path)
            row = await conn.fetchrow("SELECT id, file_path, event_id, user_id FROM audio_files WHERE id=$1", aid)

            async def down(_p):
                raise workers.WhisperBackendDown("refused")
            monkeypatch.setattr(workers, "transcribe_one", down)
            for _ in range(5):
                with pytest.raises(workers.WhisperBackendDown):
                    await workers.whisper_process_one(conn, row)
            r = await conn.fetchrow("SELECT transcript_status, attempt_count FROM audio_files WHERE id=$1", aid)
            assert (r["transcript_status"], r["attempt_count"]) == ("PENDING", 0)
        finally:
            await conn.close()
    run(go())


def test_permanent_failure_sets_processed_at_and_reaper_retries(tmp_path, monkeypatch):
    async def go():
        conn = await asyncpg.connect(URL)
        try:
            _, (aid,) = await _setup(conn, 1, tmp_path)
            row = await conn.fetchrow("SELECT id, file_path, event_id, user_id FROM audio_files WHERE id=$1", aid)

            async def bad(_p):
                raise ValueError("broken audio")
            monkeypatch.setattr(workers, "transcribe_one", bad)
            await workers.whisper_process_one(conn, row)
            r = await conn.fetchrow("SELECT transcript_status, processed_at FROM audio_files WHERE id=$1", aid)
            assert r["transcript_status"] == "FAILED" and r["processed_at"] is not None

            # 30분 지난 것으로 만들고 회수 함수 호출 → PENDING 으로 재시도돼야 함
            await conn.execute("UPDATE audio_files SET processed_at = NOW() - interval '31 minutes' WHERE id=$1", aid)
            await conn.fetch("SELECT * FROM public.reap_stuck_processing()")
            r = await conn.fetchrow("SELECT transcript_status, attempt_count FROM audio_files WHERE id=$1", aid)
            assert (r["transcript_status"], r["attempt_count"]) == ("PENDING", 1)
        finally:
            await conn.close()
    run(go())


def test_pick_order_and_retry_delay(tmp_path, monkeypatch):
    async def go():
        conn = await asyncpg.connect(URL)
        try:
            await conn.execute("UPDATE audio_files SET transcript_status='SKIPPED' WHERE transcript_status='PENDING'")
            _, ids = await _setup(conn, 3, tmp_path)  # ids[0] 가 가장 오래됨
            monkeypatch.setattr(config, "WHISPER_BATCH_SIZE", 10)
            monkeypatch.setattr(config, "WHISPER_RETRY_DELAY_SEC", 300)
            # 가장 오래된 건이 일시 오류 1회(방금) → 대기 중이라 안 집힘
            await conn.execute(
                "UPDATE audio_files SET attempt_count=1, processing_at=NOW() WHERE id=$1", ids[0])
            got = [r["id"] for r in await workers.fetch_pending_audio(conn)]
            assert got == [ids[1], ids[2]]
            # 대기 시간 지나면 집히되 시도 0회짜리들 뒤로
            await conn.execute(
                "UPDATE audio_files SET processing_at=NOW() - interval '10 minutes' WHERE id=$1", ids[0])
            got = [r["id"] for r in await workers.fetch_pending_audio(conn)]
            assert got == [ids[1], ids[2], ids[0]]
        finally:
            await conn.close()
    run(go())


def _m4a_bytes():
    def box(t, payload=b""):
        return (8 + len(payload)).to_bytes(4, "big") + t + payload
    return box(b"ftyp", b"M4A ") + box(b"mdat", b"\0" * 8000) + box(b"moov", b"\0" * 8)


def _call_upload(tmp_root, uid, ev):
    today_dir = os.path.join(tmp_root, "audio", str(uid), "d")
    os.makedirs(today_dir, exist_ok=True)
    dest = os.path.join(today_dir, f"{ev}.m4a")
    tmp = os.path.join(today_dir, f".{ev}.m4a.{uuid.uuid4().hex}.part")
    f = UploadFile(io.BytesIO(_m4a_bytes()), filename="a.m4a")

    async def go():
        try:
            return await upload._receive_and_register(
                f, tmp, dest, ".m4a", ev, uid, None, None, None, None, "pytest")
        finally:
            upload._remove_quietly(tmp)
    return dest, today_dir, go


def test_upload_new_then_duplicate_after_done(tmp_path):
    async def go():
        db.pool = await asyncpg.create_pool(URL, min_size=1, max_size=2)
        try:
            async with db.pool.acquire() as conn:
                uid = await conn.fetchval(
                    "INSERT INTO users (email, password_hash, name) VALUES ($1,'x','t') RETURNING id",
                    f"u-{uuid.uuid4().hex[:8]}@test")
            ev = f"call-{uuid.uuid4().hex[:8]}"

            dest, d, call = _call_upload(str(tmp_path), uid, ev)
            r1 = await call()
            assert os.path.exists(dest)
            assert [n for n in os.listdir(d) if n.endswith(".part")] == []

            # 받아쓰기 끝남 → 원본 삭제, file_path NULL
            os.remove(dest)
            async with db.pool.acquire() as conn:
                await conn.execute(
                    "UPDATE audio_files SET transcript_status='DONE', file_path=NULL WHERE event_id=$1", ev)

            dest2, d2, call2 = _call_upload(str(tmp_path), uid, ev)
            r2 = await call2()
            assert r2["audio_file_id"] == r1["audio_file_id"]
            assert set(r2) == {"ok", "audio_file_id", "size_bytes"}
            assert not os.path.exists(dest2), "이미 처리된 통화의 재업로드 파일은 남지 않아야 함"
            assert os.listdir(d2) == []
        finally:
            await db.pool.close()
    run(go())


def test_upload_same_day_reupload_replaces(tmp_path):
    async def go():
        db.pool = await asyncpg.create_pool(URL, min_size=1, max_size=2)
        try:
            async with db.pool.acquire() as conn:
                uid = await conn.fetchval(
                    "INSERT INTO users (email, password_hash, name) VALUES ($1,'x','t') RETURNING id",
                    f"u-{uuid.uuid4().hex[:8]}@test")
            ev = f"call-{uuid.uuid4().hex[:8]}"
            dest, d, call = _call_upload(str(tmp_path), uid, ev)
            r1 = await call()
            _, _, call2 = _call_upload(str(tmp_path), uid, ev)
            r2 = await call2()
            assert r1["audio_file_id"] == r2["audio_file_id"]
            assert os.listdir(d) == [os.path.basename(dest)]
            async with db.pool.acquire() as conn:
                n = await conn.fetchval("SELECT count(*) FROM audio_files WHERE event_id=$1", ev)
            assert n == 1
        finally:
            await db.pool.close()
    run(go())


def _load_agent_worker():
    os.environ.setdefault("DATABASE_URL", URL)
    path = os.path.join(os.path.dirname(__file__), "..", "..", "agent-worker", "worker.py")
    spec = importlib.util.spec_from_file_location("agent_worker_under_test", path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def test_agent_worker_failure_sets_processed_at(monkeypatch):
    aw = _load_agent_worker()

    async def go():
        pool = await asyncpg.create_pool(URL, min_size=1, max_size=2, command_timeout=60)
        try:
            async with pool.acquire() as conn:
                uid, (aid,) = await _setup(conn, 1)
                ev = await conn.fetchval("SELECT event_id FROM audio_files WHERE id=$1", aid)
                tid = await conn.fetchval(
                    """INSERT INTO transcripts (audio_file_id, event_id, user_id, text, summary_status)
                       VALUES ($1, $2, $3, '안녕하세요', 'PENDING') RETURNING id""", aid, ev, uid)
                pv = await aw.ensure_prompt_version(conn, "call_summarize")
                row = await conn.fetchrow("SELECT id, text, event_id, user_id FROM transcripts WHERE id=$1", tid)

            async def fail(_p):
                raise RuntimeError("claude exit 1: boom")
            monkeypatch.setattr(aw, "run_claude", fail)
            await aw.process_transcript(pool, row, pv, "{{TRANSCRIPT}}")
            async with pool.acquire() as conn:
                r = await conn.fetchrow("SELECT summary_status, processed_at FROM transcripts WHERE id=$1", tid)
            assert r["summary_status"] == "FAILED" and r["processed_at"] is not None

            # 한 회차 정상 처리 경로(풀 기반 run_cycle)
            async def ok(_p):
                return {"result": '{"summary": "요약"}'}
            monkeypatch.setattr(aw, "run_claude", ok)
            monkeypatch.setattr(aw, "CALL_GAP_SEC", 0)
            async with pool.acquire() as conn:
                await conn.execute("UPDATE transcripts SET summary_status='DONE' WHERE summary_status='PENDING'")
                await conn.execute("UPDATE transcripts SET summary_status='PENDING' WHERE id=$1", tid)
            busy = await aw.run_cycle(pool, {"pv": None, "body": ""})
            assert busy
            async with pool.acquire() as conn:
                st = await conn.fetchval("SELECT summary_status FROM transcripts WHERE id=$1", tid)
            assert st == "DONE"
        finally:
            await pool.close()
    run(go())
