"""
REST 라우터 — PostgREST 를 대체. 현행 안드로이드 앱(origin/dev PcApi.kt)이 쓰는 모든
/rest/* 경로를 구현한다. PostgREST 스타일 쿼리 파라미터(select/order/limit/eq/gte/neq)를
그대로 받아 APK 를 안 바꿔도 되게 한다.

경로:
  POST   /rest/events                         이벤트 배열 upsert (ON CONFLICT id)
  PATCH  /rest/events?id=eq.<id>              이벤트 metadata_json 부분 수정
  GET    /rest/events?user_id=&type=&select=&limit=   이벤트(통화) 경량 조회
  GET    /rest/todos?user_id=&order=&limit=   내 할 일 목록
  POST   /rest/todos                          할 일 배열 upsert (ON CONFLICT id)
  PATCH  /rest/todos?id=eq.<id>               할 일 부분 수정
  GET    /rest/summaries?user_id=&order=&limit=  내 요약 목록
  GET    /rest/summaries?id=eq.<id>&limit=1   요약 1건 조회
  PATCH  /rest/summaries?id=eq.<id>           요약 부분 수정(pinned)
  GET    /rest/transcripts?select=&order=&limit=  통화 받아쓰기 목록
  GET    /rest/appointments?user_id=&order=&start_at=gte.&limit=  내 약속 목록
  PATCH  /rest/appointments?id=eq.<id>        약속 부분 수정(confirmed)
  DELETE /rest/appointments?id=eq.<id>        약속 삭제
  PATCH  /rest/devices?id=eq.<id>             디바이스 수정(fcm_token/last_seen)
  GET    /rest/failed_items?order=&limit=&stage=  처리 실패 목록(뷰)
  POST   /rest/rpc/retry_failed_item          수동 재시도(SECURITY DEFINER 함수)

보안(핵심): 모든 /rest/* 는 db.rls_connection() 안에서 실행 → SET LOCAL ROLE + set_app_context
로 기존 03-rls.sql 행 격리가 그대로 걸린다. 여기 SQL 은 user_id 필터를 직접 넣지 않아도
RLS 가 본인 데이터만 노출한다(앱이 보내는 user_id=eq.<self> 필터도 그대로 반영).
GET 은 json_agg 로 PostgreSQL 이 직접 JSON 을 만들게 해 PostgREST 와 동일한 타입
직렬화(timestamptz ISO, jsonb/uuid 등)를 재현한다.

select= 지원: `select=col1,col2` 를 파싱해 테이블별 컬럼 화이트리스트와 교집합(요청 순서
유지)을 내려주고, select 가 없으면 테이블 기본(=전체) 컬럼을 PostgREST 와 동일한 순서로 낸다.
"""

from typing import Optional

from fastapi import APIRouter, Body, Header, Query, Response

from . import db
from .jwt_utils import verify_access_from_header

router = APIRouter()

# 테이블/뷰별 컬럼 목록 — PostgREST 가 select 미지정 시 내려주는 "전체 컬럼"을 테이블 정의
# 순서 그대로 재현한다. select= 화이트리스트(주입 방지)로도 쓰인다.
COLUMNS = {
    "events": [
        "id", "user_id", "device_id", "type", "source_package", "title", "content",
        "timestamp", "device_timestamp", "metadata_json", "processing_status",
        "processed_at", "processing_error",
    ],
    "todos": [
        "id", "user_id", "content", "source", "source_event_id", "due_at",
        "related_person", "status", "completion_confidence", "created_at",
        "updated_at", "completed_at", "source_excerpt", "priority",
    ],
    "summaries": [
        "id", "transcript_id", "event_id", "user_id", "summary", "raw_json",
        "prompt_version_id", "pushed", "pushed_at", "created_at", "pinned",
    ],
    "appointments": [
        "id", "user_id", "source_event_id", "title", "start_at", "end_at",
        "location", "with_person", "confidence", "confirmed", "created_at",
        "source_excerpt",
    ],
    "transcripts": [
        "id", "audio_file_id", "event_id", "user_id", "text", "language",
        "summary_status", "error_message", "created_at", "processed_at",
        "retry_count", "segments_json", "processing_at", "attempt_count",
    ],
    "failed_items": [
        "event_id", "type", "stage", "error_message", "label", "occurred_at",
        "failed_at",
    ],
    "app_alerts": ["id", "level", "title", "body", "created_at"],
}

# 테이블별 정렬 허용 컬럼 화이트리스트 (order 파라미터 주입 방지)
ORDER_WHITELIST = {
    "todos": {"updated_at", "created_at", "due_at", "status"},
    "summaries": {"created_at", "pushed_at"},
    "appointments": {"start_at", "created_at"},
    "transcripts": {"created_at"},
    "events": {"timestamp", "device_timestamp"},
    "failed_items": {"occurred_at", "failed_at"},
    "app_alerts": {"id", "created_at"},
}

# PostgREST 비교 연산자 → SQL 연산자
_OPS = {"eq": "=", "neq": "<>", "gt": ">", "gte": ">=", "lt": "<", "lte": "<="}


def get_claims(authorization: Optional[str]) -> dict:
    """access JWT 검증 후 클레임 반환 (role 클레임 = PG 역할명)."""
    return verify_access_from_header(authorization)


def select_columns(table: str, select: Optional[str]) -> list[str]:
    """`select=c1,c2` 파싱 → 화이트리스트 교집합(요청 순서 유지). 없으면 전체 컬럼."""
    full = COLUMNS[table]
    if not select:
        return list(full)
    wl = set(full)
    cols: list[str] = []
    for raw in select.split(","):
        name = raw.strip()
        # PostgREST 의 alias:col / col::cast 문법은 앱이 안 쓰므로 단순 컬럼명만 인정.
        if name == "*":
            return list(full)
        if name in wl and name not in cols:
            cols.append(name)
    return cols or list(full)


def parse_order(value: Optional[str], table: str, default_col: str, default_dir: str):
    """'col.desc' | 'col.asc' → (col, 'DESC'|'ASC'). 미지정/화이트리스트 밖이면 기본값."""
    col, direction = default_col, default_dir
    if value:
        part = value.split(",")[0].strip()  # 단일 정렬만 사용 (앱이 하나만 보냄)
        if "." in part:
            c, d = part.rsplit(".", 1)
            d = d.lower()
        else:
            c, d = part, "asc"
        if c in ORDER_WHITELIST.get(table, set()) and d in ("asc", "desc"):
            col, direction = c, d
    return col, direction.upper()


def parse_limit(value: Optional[int], default: int) -> int:
    try:
        n = int(value) if value is not None else default
    except (TypeError, ValueError):
        n = default
    # PostgREST 는 db-max-rows 미설정(무제한)이라 앱의 limit=2000/5000 을 그대로 받아야 한다.
    return max(1, min(n, 100000))


def parse_op(value: str):
    """'eq.<v>' / 'gte.<v>' / 'neq.<v>' → (SQL연산자, 값). 접두어 없으면 ('=', 값)."""
    if "." in value:
        op, val = value.split(".", 1)
        if op in _OPS:
            return _OPS[op], val
    return "=", value


def strip_op(value: str, op: str) -> str:
    """'eq.<v>' 에서 값만 추출. 접두어 없으면 값 그대로."""
    prefix = op + "."
    if value.startswith(prefix):
        return value[len(prefix):]
    return value


async def _run_select(claims, table, cols, where_sql, args, order_col, order_dir, limit):
    """선택 컬럼으로 json_agg 결과(text)를 만들어 그대로 반환 — PostgREST 호환."""
    col_sql = ", ".join('"' + c + '"' for c in cols)
    order_sql = f' ORDER BY "{order_col}" {order_dir}' if order_col else ""
    inner = f"SELECT {col_sql} FROM {table}{where_sql}{order_sql} LIMIT {limit}"
    sql = f"SELECT coalesce(json_agg(_t), '[]')::text FROM ({inner}) _t"
    async with db.rls_connection(claims) as conn:
        data = await conn.fetchval(sql, *args)
    return Response(content=data, media_type="application/json")


# ===== events =====
@router.post("/rest/events")
async def post_events(
    events: list[dict] = Body(...),
    authorization: Optional[str] = Header(None),
):
    claims = get_claims(authorization)
    async with db.rls_connection(claims) as conn:
        for ev in events:
            # user_id 는 WITH CHECK (user_id = current_user_id()) 로 RLS 가 본인 것만 허용.
            await conn.execute(
                """
                INSERT INTO events
                    (id, user_id, device_id, type, source_package, title, content,
                     timestamp, device_timestamp, metadata_json)
                VALUES
                    ($1, $2, $3::uuid, $4, $5, $6, $7,
                     $8::text::timestamptz, $9::text::timestamptz, $10::jsonb)
                ON CONFLICT (id) DO UPDATE SET
                    user_id          = EXCLUDED.user_id,
                    device_id        = EXCLUDED.device_id,
                    type             = EXCLUDED.type,
                    source_package   = EXCLUDED.source_package,
                    title            = EXCLUDED.title,
                    content          = EXCLUDED.content,
                    timestamp        = EXCLUDED.timestamp,
                    device_timestamp = EXCLUDED.device_timestamp,
                    metadata_json    = EXCLUDED.metadata_json
                """,
                ev.get("id"),
                ev.get("user_id"),
                ev.get("device_id"),
                ev.get("type"),
                ev.get("source_package"),
                ev.get("title"),
                ev.get("content"),
                ev.get("timestamp"),
                ev.get("device_timestamp"),
                _json_or_none(ev.get("metadata_json")),
            )
    return Response(status_code=201)


@router.patch("/rest/events")
async def patch_event(
    id: str = Query(...),  # "eq.<id>"
    patch: dict = Body(...),
    authorization: Optional[str] = Header(None),
):
    claims = get_claims(authorization)
    event_id = strip_op(id, "eq")
    # EventMetaPatch 는 metadata_json 만. 없으면 no-op.
    if "metadata_json" not in patch:
        return Response(status_code=204)
    async with db.rls_connection(claims) as conn:
        await conn.execute(
            "UPDATE events SET metadata_json = $1::jsonb WHERE id = $2",
            _json_or_none(patch.get("metadata_json")),
            event_id,
        )
    return Response(status_code=204)


@router.get("/rest/events")
async def list_events(
    user_id: Optional[str] = Query(None),   # "eq.<id>"
    type: Optional[str] = Query(None),      # "eq.call"
    select: Optional[str] = Query(None),
    order: Optional[str] = Query(None),
    limit: Optional[int] = Query(None),
    authorization: Optional[str] = Header(None),
):
    claims = get_claims(authorization)
    cols = select_columns("events", select)
    col, direction = parse_order(order, "events", "timestamp", "desc")
    lim = parse_limit(limit, 1000)
    where, args, idx = [], [], 1
    if user_id:
        op, val = parse_op(user_id)
        where.append(f"user_id {op} ${idx}")
        args.append(int(val))
        idx += 1
    if type:
        op, val = parse_op(type)
        where.append(f"type {op} ${idx}")
        args.append(val)
        idx += 1
    where_sql = (" WHERE " + " AND ".join(where)) if where else ""
    return await _run_select(claims, "events", cols, where_sql, args, col, direction, lim)


# ===== todos =====
@router.get("/rest/todos")
async def list_todos(
    user_id: Optional[str] = Query(None),   # "eq.<id>"
    select: Optional[str] = Query(None),
    order: Optional[str] = Query(None),
    limit: Optional[int] = Query(None),
    authorization: Optional[str] = Header(None),
):
    claims = get_claims(authorization)
    cols = select_columns("todos", select)
    col, direction = parse_order(order, "todos", "updated_at", "desc")
    lim = parse_limit(limit, 200)
    where, args, idx = [], [], 1
    if user_id:
        op, val = parse_op(user_id)
        where.append(f"user_id {op} ${idx}")
        args.append(int(val))
        idx += 1
    where_sql = (" WHERE " + " AND ".join(where)) if where else ""
    return await _run_select(claims, "todos", cols, where_sql, args, col, direction, lim)


@router.post("/rest/todos")
async def upsert_todos(
    todos: list[dict] = Body(...),
    authorization: Optional[str] = Header(None),
):
    claims = get_claims(authorization)
    async with db.rls_connection(claims) as conn:
        for t in todos:
            await conn.execute(
                """
                INSERT INTO todos
                    (id, user_id, content, source, source_event_id, due_at,
                     related_person, status, completion_confidence,
                     created_at, updated_at, completed_at, source_excerpt, priority)
                VALUES
                    ($1, $2, $3, $4, $5, $6::text::timestamptz,
                     $7, $8, $9,
                     $10::text::timestamptz, $11::text::timestamptz, $12::text::timestamptz,
                     $13, $14)
                ON CONFLICT (id) DO UPDATE SET
                    content               = EXCLUDED.content,
                    source                = EXCLUDED.source,
                    source_event_id       = EXCLUDED.source_event_id,
                    due_at                = EXCLUDED.due_at,
                    related_person        = EXCLUDED.related_person,
                    status                = EXCLUDED.status,
                    completion_confidence = EXCLUDED.completion_confidence,
                    updated_at            = EXCLUDED.updated_at,
                    completed_at          = EXCLUDED.completed_at,
                    source_excerpt        = EXCLUDED.source_excerpt,
                    priority              = EXCLUDED.priority
                """,
                t.get("id"),
                t.get("user_id"),
                t.get("content"),
                t.get("source"),
                t.get("source_event_id"),
                t.get("due_at"),
                t.get("related_person"),
                t.get("status"),
                t.get("completion_confidence", 0.0),
                t.get("created_at"),
                t.get("updated_at"),
                t.get("completed_at"),
                t.get("source_excerpt"),
                t.get("priority", 0.0),
            )
    return Response(status_code=201)


@router.patch("/rest/todos")
async def patch_todo(
    id: str = Query(...),  # "eq.<id>"
    patch: dict = Body(...),
    authorization: Optional[str] = Header(None),
):
    claims = get_claims(authorization)
    todo_id = strip_op(id, "eq")
    allowed = {
        "status": "",
        "content": "",
        "due_at": "::text::timestamptz",
        "completed_at": "::text::timestamptz",
        "updated_at": "::text::timestamptz",
    }
    sets, args, idx = [], [], 1
    for field, cast in allowed.items():
        if field in patch:
            sets.append(f"{field} = ${idx}{cast}")
            args.append(patch[field])
            idx += 1
    if not sets:
        return Response(status_code=204)
    args.append(todo_id)
    sql = f"UPDATE todos SET {', '.join(sets)} WHERE id = ${idx}"
    async with db.rls_connection(claims) as conn:
        await conn.execute(sql, *args)
    return Response(status_code=204)


# ===== summaries =====
@router.get("/rest/summaries")
async def list_summaries(
    id: Optional[str] = Query(None),         # "eq.<id>" (단건 조회)
    user_id: Optional[str] = Query(None),    # "eq.<id>"
    select: Optional[str] = Query(None),
    order: Optional[str] = Query(None),
    limit: Optional[int] = Query(None),
    authorization: Optional[str] = Header(None),
):
    claims = get_claims(authorization)
    cols = select_columns("summaries", select)
    col, direction = parse_order(order, "summaries", "created_at", "desc")
    lim = parse_limit(limit, 100)
    where, args, idx = [], [], 1
    if id:
        op, val = parse_op(id)
        where.append(f"id {op} ${idx}::uuid")
        args.append(val)
        idx += 1
    if user_id:
        op, val = parse_op(user_id)
        where.append(f"user_id {op} ${idx}")
        args.append(int(val))
        idx += 1
    where_sql = (" WHERE " + " AND ".join(where)) if where else ""
    return await _run_select(claims, "summaries", cols, where_sql, args, col, direction, lim)


@router.patch("/rest/summaries")
async def patch_summary(
    id: str = Query(...),  # "eq.<id>"
    patch: dict = Body(...),
    authorization: Optional[str] = Header(None),
):
    claims = get_claims(authorization)
    summary_id = strip_op(id, "eq")
    # SummaryPatch 는 pinned 만.
    if "pinned" not in patch:
        return Response(status_code=204)
    async with db.rls_connection(claims) as conn:
        await conn.execute(
            "UPDATE summaries SET pinned = $1 WHERE id = $2::uuid",
            patch.get("pinned"),
            summary_id,
        )
    return Response(status_code=204)


# ===== transcripts =====
@router.get("/rest/transcripts")
async def list_transcripts(
    select: Optional[str] = Query(None),
    order: Optional[str] = Query(None),
    limit: Optional[int] = Query(None),
    authorization: Optional[str] = Header(None),
):
    claims = get_claims(authorization)
    cols = select_columns("transcripts", select)
    col, direction = parse_order(order, "transcripts", "created_at", "desc")
    lim = parse_limit(limit, 100)
    return await _run_select(claims, "transcripts", cols, "", [], col, direction, lim)


# ===== appointments =====
@router.get("/rest/appointments")
async def list_appointments(
    user_id: Optional[str] = Query(None),    # "eq.<id>"
    select: Optional[str] = Query(None),
    order: Optional[str] = Query(None),
    start_at: Optional[str] = Query(None),   # "gte.<iso>" (선택)
    limit: Optional[int] = Query(None),
    authorization: Optional[str] = Header(None),
):
    claims = get_claims(authorization)
    cols = select_columns("appointments", select)
    col, direction = parse_order(order, "appointments", "start_at", "asc")
    lim = parse_limit(limit, 100)
    where, args, idx = [], [], 1
    if user_id:
        op, val = parse_op(user_id)
        where.append(f"user_id {op} ${idx}")
        args.append(int(val))
        idx += 1
    if start_at:
        op, val = parse_op(start_at)
        where.append(f"start_at {op} ${idx}::text::timestamptz")
        args.append(val)
        idx += 1
    where_sql = (" WHERE " + " AND ".join(where)) if where else ""
    return await _run_select(claims, "appointments", cols, where_sql, args, col, direction, lim)


@router.patch("/rest/appointments")
async def patch_appointment(
    id: str = Query(...),  # "eq.<id>"
    patch: dict = Body(...),
    authorization: Optional[str] = Header(None),
):
    claims = get_claims(authorization)
    appt_id = strip_op(id, "eq")
    # AppointmentPatch 는 confirmed 만.
    if "confirmed" not in patch:
        return Response(status_code=204)
    async with db.rls_connection(claims) as conn:
        await conn.execute(
            "UPDATE appointments SET confirmed = $1 WHERE id = $2::uuid",
            patch.get("confirmed"),
            appt_id,
        )
    return Response(status_code=204)


@router.delete("/rest/appointments")
async def delete_appointment(
    id: str = Query(...),  # "eq.<id>"
    authorization: Optional[str] = Header(None),
):
    claims = get_claims(authorization)
    appt_id = strip_op(id, "eq")
    async with db.rls_connection(claims) as conn:
        await conn.execute("DELETE FROM appointments WHERE id = $1::uuid", appt_id)
    return Response(status_code=204)


# ===== devices =====
@router.patch("/rest/devices")
async def patch_device(
    id: str = Query(...),  # "eq.<deviceId>"
    patch: dict = Body(...),
    authorization: Optional[str] = Header(None),
):
    claims = get_claims(authorization)
    device_id = strip_op(id, "eq")
    allowed = {
        "fcm_token": "",
        "last_seen": "::text::timestamptz",
    }
    sets, args, idx = [], [], 1
    for field, cast in allowed.items():
        if field in patch:
            sets.append(f"{field} = ${idx}{cast}")
            args.append(patch[field])
            idx += 1
    if not sets:
        return Response(status_code=204)
    args.append(device_id)
    sql = f"UPDATE devices SET {', '.join(sets)} WHERE id = ${idx}::uuid"
    async with db.rls_connection(claims) as conn:
        await conn.execute(sql, *args)
    return Response(status_code=204)


# ===== failed_items (뷰) =====
@router.get("/rest/failed_items")
async def list_failed(
    stage: Optional[str] = Query(None),      # "neq.event" 등
    select: Optional[str] = Query(None),
    order: Optional[str] = Query(None),
    limit: Optional[int] = Query(None),
    authorization: Optional[str] = Header(None),
):
    claims = get_claims(authorization)
    cols = select_columns("failed_items", select)
    col, direction = parse_order(order, "failed_items", "occurred_at", "desc")
    lim = parse_limit(limit, 500)
    where, args, idx = [], [], 1
    if stage:
        op, val = parse_op(stage)
        where.append(f"stage {op} ${idx}")
        args.append(val)
        idx += 1
    where_sql = (" WHERE " + " AND ".join(where)) if where else ""
    return await _run_select(claims, "failed_items", cols, where_sql, args, col, direction, lim)


# ===== app_alerts (장애 알림함, 2026-10-10) =====
# 앱이 요약 폴링 때 같이 조회 → 새 장애 알림을 로컬 알림으로 띄움. RLS 로 어드민만 보임.
@router.get("/rest/app_alerts")
async def list_app_alerts(
    id: Optional[str] = Query(None),          # "gt.<마지막으로 본 id>"
    select: Optional[str] = Query(None),
    order: Optional[str] = Query(None),
    limit: Optional[int] = Query(None),
    authorization: Optional[str] = Header(None),
):
    claims = get_claims(authorization)
    cols = select_columns("app_alerts", select)
    col, direction = parse_order(order, "app_alerts", "id", "desc")
    lim = parse_limit(limit, 20)
    where, args, idx = [], [], 1
    if id:
        op, val = parse_op(id)
        try:
            val = int(val)
        except (TypeError, ValueError):
            val = 0
        where.append(f"id {op} ${idx}")
        args.append(val)
        idx += 1
    where_sql = (" WHERE " + " AND ".join(where)) if where else ""
    return await _run_select(claims, "app_alerts", cols, where_sql, args, col, direction, lim)


# ===== rpc =====
@router.post("/rest/rpc/retry_failed_item")
async def retry_failed_item(
    body: dict = Body(...),
    authorization: Optional[str] = Header(None),
):
    claims = get_claims(authorization)
    p_event_id = body.get("p_event_id")
    # SECURITY DEFINER 함수. current_user_id() 를 app.user_id GUC 로 읽으므로 rls_connection
    # 안에서 호출해야 한다(소유자 본인 건만 PENDING 으로 되돌림). 되돌린 (stage, reset_count) 반환.
    sql = (
        "SELECT coalesce(json_agg(_t), '[]')::text FROM "
        "(SELECT stage, reset_count FROM retry_failed_item($1)) _t"
    )
    async with db.rls_connection(claims) as conn:
        data = await conn.fetchval(sql, p_event_id)
    return Response(content=data, media_type="application/json")


# ===== util =====
def _json_or_none(value):
    import json
    if value is None:
        return None
    if isinstance(value, str):
        return value
    return json.dumps(value)
