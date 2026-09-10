# -*- coding: utf-8 -*-
"""all_fail — 요약 전부 실패 조건 감지 (canary.py 2번 이식).

조건(원본 그대로): 최근 1시간 요약 성공 0건 + 최근 1시간 실패 3건 이상 + 요약 대기 3건 이상.
(새 통화가 없어 성공 0인데 옛 실패 몇 건만 있는 헛알람을 거르려고 대기 3건 조건을 둠)
상태 전이 시에만 알림.
"""
from __future__ import annotations

from .base import Check, CheckResult, StateCondition


class AllFailCheck(Check):
    name = "all_fail"

    async def run(self, ctx) -> CheckResult:
        res = CheckResult()

        f = await ctx.db.fetchrow_dict(
            """
            select (select count(*) from summaries where created_at > now()-interval '1 hour') as ok,
                   (select count(*) from transcripts
                     where summary_status='FAILED' and coalesce(processed_at,created_at) > now()-interval '1 hour') as fail
            """
        )
        s = await ctx.db.fetchrow_dict(
            "select count(*) as waiting from transcripts where summary_status='PENDING'"
        )
        all_failing = f["ok"] == 0 and f["fail"] >= 3 and s["waiting"] >= 3

        res.states["all_failing"] = StateCondition(
            active=all_failing,
            title="요약이 전부 실패 중",
            lines=[
                f"최근 1시간 성공 **0건**, 실패 **{f['fail']}건**.",
                "인증 만료·프롬프트 오류·모델 응답 문제 가능성.",
            ],
            level="crit",
        )
        res.data = {"ok_1h": f["ok"], "fail_1h": f["fail"], "waiting": s["waiting"],
                    "all_failing": all_failing}
        return res
