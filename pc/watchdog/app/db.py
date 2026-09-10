# -*- coding: utf-8 -*-
"""DB 접근 래퍼 (asyncpg 풀).

백엔드와 '의도적으로' 분리된 별도 인스턴스 — 같은 DB(DATABASE_URL)에 붙되,
백엔드가 멈춰도 이 감시는 계속 돌도록 독립 연결을 쓴다.
"""
from __future__ import annotations

import asyncpg


class Database:
    def __init__(self, dsn: str):
        self._dsn = dsn
        self._pool: asyncpg.Pool | None = None

    async def connect(self) -> None:
        if self._pool is None:
            # 단일 인스턴스이므로 작은 풀로 충분.
            self._pool = await asyncpg.create_pool(self._dsn, min_size=1, max_size=3)

    async def close(self) -> None:
        if self._pool is not None:
            await self._pool.close()
            self._pool = None

    @property
    def pool(self) -> asyncpg.Pool:
        if self._pool is None:
            raise RuntimeError("DB 풀이 아직 연결되지 않았습니다")
        return self._pool

    async def fetchrow_dict(self, q: str, *args) -> dict:
        async with self.pool.acquire() as conn:
            r = await conn.fetchrow(q, *args)
            return dict(r) if r else {}

    async def fetch(self, q: str, *args) -> list:
        async with self.pool.acquire() as conn:
            return await conn.fetch(q, *args)

    async def execute(self, q: str, *args) -> str:
        async with self.pool.acquire() as conn:
            return await conn.execute(q, *args)
