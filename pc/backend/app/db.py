"""
디비 계층 — 단일 공유 asyncpg 풀 + 요청별 RLS 컨텍스트.

중요(보안 핵심):
- 이 풀은 DATABASE_URL 의 사용자(jjongpal = 테이블 소유자)로 접속한다.
  PostgreSQL 에서 **테이블 소유자는 기본적으로 RLS 를 우회**한다(FORCE ROW LEVEL SECURITY 아님).
  따라서 /rest/* 요청을 소유자 그대로 실행하면 행 격리(03-rls.sql)가 전혀 걸리지 않아
  다른 사용자 데이터가 새어 나간다.
- PostgREST 는 이를 피하려고 매 요청마다 JWT 의 role 클레임으로 SET ROLE 하고(=소유자 아님 →
  RLS 적용 대상), 사전 함수 set_app_context() 로 app.user_id / app.user_role 를 세팅했다.
- rls_connection() 은 그 흐름을 그대로 재현한다:
    트랜잭션 안에서
      1) SET LOCAL ROLE <화이트리스트검증된 PG역할>   → 소유자 탈출, RLS 적용
      2) request.jwt.claims GUC 에 원본 클레임 주입
      3) public.set_app_context() 호출 → app.user_id / app.user_role 설정(기존 함수 그대로 재사용)
  SET LOCAL / set_config(..., is_local=true) 는 트랜잭션 종료 시 자동 복원되므로,
  커넥션이 풀로 반환돼도 다음 요청이 소유자 컨텍스트로 깨끗이 돌아간다.

auth / upload / workers 는 RLS 대신 소유자 + 코드에서 user_id 수동 필터 방식을 그대로 쓴다
(기존과 동일) → rls_connection 을 거치지 않고 pool.acquire() 만 쓴다.
"""

import json
from contextlib import asynccontextmanager
from typing import Optional

import asyncpg
from fastapi import HTTPException

from . import config

pool: Optional[asyncpg.Pool] = None


async def init_pool() -> None:
    global pool
    pool = await asyncpg.create_pool(
        config.DATABASE_URL,
        min_size=config.DB_POOL_MIN,
        max_size=config.DB_POOL_MAX,
        command_timeout=config.DB_COMMAND_TIMEOUT_SEC,
        # 오래 놀던 연결은 버리고 새로 맺음(끊긴 연결 재사용 방지)
        max_inactive_connection_lifetime=300,
    )


async def close_pool() -> None:
    if pool is not None:
        await pool.close()


@asynccontextmanager
async def rls_connection(claims: dict):
    """JWT 클레임으로 RLS 컨텍스트를 건 커넥션을 트랜잭션 안에서 내준다.

    claims: 검증된 access JWT 페이로드(user_id 문자열, role=PG역할명, app_role 포함).
    """
    pg_role = claims.get("role")
    # 주입 방지: SET ROLE 대상은 알려진 역할만 허용. 그 외는 거부(권한 없음).
    if pg_role not in config.ALLOWED_PG_ROLES:
        raise HTTPException(status_code=403, detail="unknown role")

    claims_json = json.dumps(claims)

    async with pool.acquire() as conn:
        async with conn.transaction():
            # 1) 소유자 탈출 → RLS 적용 대상 역할로 전환 (트랜잭션 한정)
            #    pg_role 은 위에서 화이트리스트 검증됨 → 식별자 직접 삽입 안전.
            await conn.execute(f'SET LOCAL ROLE "{pg_role}"')
            # 2) PostgREST 가 넣어주던 request.jwt.claims 를 동일하게 주입 (트랜잭션 한정)
            await conn.execute(
                "SELECT set_config('request.jwt.claims', $1, true)", claims_json
            )
            # 3) 기존 사전 함수 그대로 호출 → app.user_id / app.user_role 설정
            await conn.execute("SELECT public.set_app_context()")
            yield conn
