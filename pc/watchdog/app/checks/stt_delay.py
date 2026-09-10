# -*- coding: utf-8 -*-
"""stt_delay — STT 파이프라인 단계 지연 감지 (canary.py 1·3번 이식).

두 단계의 '밀림'을 감지한다. 둘 다 상태 전이 시에만 알린다.
  - summary_stall  : 요약(transcripts.summary_status='PENDING') 이 SUMMARY_STALL_H 시간 넘게
                     밀려 있고, 최근 10분 안에 새 요약이 하나도 안 나왔을 때.
  - transcribe_stall: 받아쓰기(audio_files.transcript_status='PENDING', 파일 있음) 가
                     TRANSCRIBE_STALL_H 시간 넘게 밀려 있고, 최근 20분 안에 새 받아쓰기가
                     하나도 안 나왔을 때.

'최근 진행이 있으면 밀림이 아니라 처리중' 이라는 원본 논리를 그대로 유지.
"""
from __future__ import annotations

from .base import Check, CheckResult, StateCondition


class SttDelayCheck(Check):
    name = "stt_delay"

    async def run(self, ctx) -> CheckResult:
        cfg = ctx.cfg
        res = CheckResult()

        # ── 요약 단계 지연 (canary 1) ──
        s = await ctx.db.fetchrow_dict(
            """
            select count(*) as waiting,
                   coalesce(extract(epoch from now()-min(coalesce(processed_at, created_at)))/3600,0) as oldest_h
            from transcripts where summary_status='PENDING'
            """
        )
        prog = await ctx.db.fetchrow_dict(
            "select count(*) as n from summaries where created_at > now()-interval '10 minutes'"
        )
        summary_stall = s["waiting"] > 0 and s["oldest_h"] > cfg.summary_stall_h and prog["n"] == 0
        res.states["summary_stall"] = StateCondition(
            active=summary_stall,
            title="요약이 밀려 있어",
            lines=[
                f"대기 **{s['waiting']}건**, 가장 오래된 게 **{s['oldest_h']:.1f}시간째**.",
                "워커가 살아 있어도 결과가 안 나오는 상황일 수 있어.",
            ],
            level="crit",
        )

        # ── 받아쓰기 단계 지연 (canary 3) ──
        t = await ctx.db.fetchrow_dict(
            """
            select count(*) as waiting,
                   coalesce(extract(epoch from now()-min(coalesce(processed_at, uploaded_at)))/3600,0) as oldest_h
            from audio_files where transcript_status='PENDING' and file_path is not null
            """
        )
        tprog = await ctx.db.fetchrow_dict(
            "select count(*) as n from transcripts where created_at > now()-interval '20 minutes'"
        )
        transcribe_stall = t["waiting"] > 0 and t["oldest_h"] > cfg.transcribe_stall_h and tprog["n"] == 0
        res.states["transcribe_stall"] = StateCondition(
            active=transcribe_stall,
            title="받아쓰기가 밀려 있어",
            lines=[f"대기 **{t['waiting']}건**, 가장 오래된 게 **{t['oldest_h']:.1f}시간째**."],
            level="warn",
        )

        res.data = {
            "summary_waiting": s["waiting"], "summary_oldest_h": round(s["oldest_h"], 2),
            "transcribe_waiting": t["waiting"], "transcribe_oldest_h": round(t["oldest_h"], 2),
            "summary_stall": summary_stall, "transcribe_stall": transcribe_stall,
        }
        return res
