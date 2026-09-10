# -*- coding: utf-8 -*-
"""digest — 매일 아침 현황 요약 (canary.py --digest 이식).

스케줄러가 WATCHDOG_DIGEST_TIME(기본 09:00)에 하루 1회 실행한다(주기 기반 아님).
인증 상태는 login_expiry 체크가 state 에 남긴 마지막 값을 읽는다(없으면 정상 가정).
요약은 항상 발송(상태 전이 아님). dead_sum(재시도 소진) 유무로 레벨만 조정.
"""
from __future__ import annotations

from .base import Check, CheckResult, Notification


class DigestJob(Check):
    name = "digest"

    async def run(self, ctx) -> CheckResult:
        cfg = ctx.cfg
        res = CheckResult()
        retry_max = int(cfg.retry_max)

        d = await ctx.db.fetchrow_dict(
            f"""
          select (select count(*) from audio_files where uploaded_at::date=current_date) as today_audio,
                 (select count(*) from summaries  where created_at::date=current_date) as today_sum,
                 (select count(*) from transcripts where summary_status='PENDING') as pend_sum,
                 (select count(*) from transcripts where summary_status='FAILED') as fail_sum,
                 (select count(*) from transcripts where summary_status='FAILED' and retry_count>={retry_max}) as dead_sum,
                 (select count(*) from audio_files where transcript_status='PENDING' and file_path is not null) as pend_tr,
                 (select count(*) from audio_files where transcript_status='FAILED') as fail_tr
            """
        )
        auth_ok = bool(ctx.state.get("auth_ok", True))
        lines = [
            f"오늘 올라온 통화 **{d['today_audio']}건** · 요약 완료 **{d['today_sum']}건**",
            f"요약 대기 {d['pend_sum']} · 실패 {d['fail_sum']} (재시도 소진 **{d['dead_sum']}**)",
            f"받아쓰기 대기 {d['pend_tr']} · 실패 {d['fail_tr']}",
            f"인증 상태: {'정상' if auth_ok else '**끊김**'}",
        ]
        level = "ok" if auth_ok and d["dead_sum"] == 0 else "warn"
        res.notifications.append(Notification("오늘의 현황", lines, level))
        res.data = dict(d)
        return res
