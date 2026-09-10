# -*- coding: utf-8 -*-
"""reaper — 멈춘 PROCESSING 작업 회수/실패처리 (.jjongpal-reaper.sh 이식).

원본 reaper.sh 는 5분마다 일회성 postgres 컨테이너로 psql 을 띄워 DB 함수를 호출했다:
    SELECT now(), scope, requeued, failed
      FROM public.reap_stuck_processing()
     WHERE requeued>0 OR failed>0

핵심: 실제 회수/실패 판정 로직(무엇이 'stuck'인지, 몇 분 뒤 requeue vs fail 인지)은
전부 이 DB 함수 public.reap_stuck_processing() 안에 있다. 그 함수 본문은 라이브 DB 에만
존재하고 레포(init SQL)·히스토리 어디에도 없다(NOTES.md '미해결 질문' 참조).

따라서 '정확한 의미 보존' 을 위해 내부를 추측해 재구현하지 않고, 원본과 동일하게
같은 DB 함수를 asyncpg 로 호출한다(일회성 docker psql → 상시 asyncpg 호출로만 바뀜).

원본 reaper.sh 는 폰 알림을 안 보내고 로그에만 남겼다 → 여기서도 알림은 안 보내고
활동을 로그/데이터로만 남긴다(디폴트). 필요하면 나중에 통지를 붙일 수 있다.
"""
from __future__ import annotations

import logging

from .base import Check, CheckResult

log = logging.getLogger("watchdog.reaper")


class ReaperCheck(Check):
    name = "reaper"

    async def run(self, ctx) -> CheckResult:
        res = CheckResult()
        rows = await ctx.db.fetch(
            """
            SELECT now() AS at, scope, requeued, failed
              FROM public.reap_stuck_processing()
             WHERE requeued > 0 OR failed > 0
            """
        )
        total_requeued = sum(int(r["requeued"]) for r in rows)
        total_failed = sum(int(r["failed"]) for r in rows)
        detail = [
            {"scope": r["scope"], "requeued": int(r["requeued"]), "failed": int(r["failed"])}
            for r in rows
        ]
        if detail:
            log.info("reaper: %s", detail)
        res.data = {"requeued": total_requeued, "failed": total_failed, "detail": detail}
        return res
