# -*- coding: utf-8 -*-
"""중앙 스케줄러 루프.

- 등록된 각 체크를 interval_sec 마다 실행, last_run 을 state 에 기록.
- 체크 결과의 states(지속 상태)는 상태 전이 시에만 알림(canary transition 과 동일):
    나쁨으로 '진입' → 해당 알림 1회 / 정상으로 '복귀' → "<title> — 해소됨" 1회.
- 결과의 notifications(일회성)는 조건 맞으면 매 실행 발송(예: 자동 복구 통지).
- 일일요약(digest)은 WATCHDOG_DIGEST_TIME 에 하루 1회.
- 체크 하나가 예외로 죽어도 루프는 계속(해당 체크만 건너뜀).
"""
from __future__ import annotations

import asyncio
import datetime
import logging
import time
import traceback
from dataclasses import dataclass

from .checks.base import Check, StateCondition, Notification

log = logging.getLogger("watchdog.scheduler")


@dataclass
class Context:
    cfg: object
    db: object
    alerter: object
    state: object


class Scheduler:
    def __init__(self, ctx: Context, checks: list[Check], digest: Check):
        self.ctx = ctx
        self.checks = checks
        self.digest = digest

    async def _fire_state(self, key: str, cond: StateCondition) -> None:
        """canary.py transition() 과 동일한 상태 전이 알림."""
        was = self.ctx.state.get_flag(key)
        if cond.active and not was:
            await self.ctx.alerter.notify(cond.title, cond.lines, cond.level)
            self.ctx.state.set_flag(key, True)
        elif not cond.active and was:
            await self.ctx.alerter.notify(f"{cond.title} — 해소됨", ["정상으로 돌아왔어."], "ok")
            self.ctx.state.set_flag(key, False)

    async def _run_check(self, check: Check) -> None:
        try:
            result = await check.run(self.ctx)
        except Exception:
            log.error("체크 %s 실패:\n%s", check.name, traceback.format_exc())
            # 감시 스크립트 자체 실패에 준하는 알림 (canary 의 최상위 except 와 유사)
            await self.ctx.alerter.notify(
                f"감시 체크 '{check.name}' 가 실패했어",
                [f"```{traceback.format_exc()[-1200:]}```"], "crit")
            return
        for key, cond in result.states.items():
            await self._fire_state(key, cond)
        for n in result.notifications:
            await self.ctx.alerter.notify(n.title, n.lines, n.level)
        if result.data:
            log.info("체크 %s: %s", check.name, result.data)

    def _digest_due(self, now_dt: datetime.datetime) -> bool:
        try:
            hh, mm = (int(x) for x in self.ctx.cfg.digest_time.split(":"))
        except Exception:
            hh, mm = 9, 0
        today = now_dt.date().isoformat()
        if self.ctx.state.digest_date == today:
            return False
        return (now_dt.hour, now_dt.minute) >= (hh, mm)

    async def run_forever(self) -> None:
        log.info("watchdog 스케줄러 시작 — 체크 %d개, tick=%ds",
                 len(self.checks), self.ctx.cfg.tick_sec)
        while True:
            now = time.time()
            for check in self.checks:
                if now - self.ctx.state.last_run(check.name) >= check.interval_sec:
                    await self._run_check(check)
                    self.ctx.state.set_last_run(check.name, time.time())
            # 일일요약
            if self._digest_due(datetime.datetime.now()):
                await self._run_check(self.digest)
                self.ctx.state.digest_date = datetime.date.today().isoformat()
            try:
                self.ctx.state.save()
            except Exception as e:
                log.warning("상태 저장 실패: %s", e)
            await asyncio.sleep(self.ctx.cfg.tick_sec)
