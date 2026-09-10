# -*- coding: utf-8 -*-
"""login_expiry — 헤드리스 Claude 로그인/토큰 만료 감지 (canary.py 6번 이식).

원본 논리 그대로:
  - 헤드리스 claude 를 1회 호출해 응답이 오는지 본다.
  - 일시적 blip 에 흔들리지 않게, 실패하면 8초 뒤 1회 재확인(연속 2회 실패해야 죽음).
  - 두 오류 메시지에 auth/oauth/expired/401/refresh 키워드가 있을 때만 '인증 죽음'으로 본다
    (그 외 일시 오류는 인증죽음으로 안 침).
  - 상태 전이 시에만 알림.

주의(이식 한계): python:3.12-slim 컨테이너엔 claude CLI/node 런타임이 없다.
  - WATCHDOG_AUTH_ENABLED=false(기본) 이거나 CLAUDE_BIN 이 없으면 이 체크는 '건너뜀'
    (알림 X, 로그만). 컨테이너에서 인증 점검을 쓰려면 호스트의 claude 바이너리 +
    node + CLAUDE_CONFIG_DIR 을 마운트해야 한다. NOTES.md 의 '미해결 질문' 참조.
마지막 auth_ok 상태는 state 에 저장해 일일요약이 읽어간다.
"""
from __future__ import annotations

import asyncio
import json
import logging
import os

from .base import Check, CheckResult, StateCondition

log = logging.getLogger("watchdog.login_expiry")

_AUTH_KEYWORDS = ("auth", "oauth", "expired", "401", "refresh")


class LoginExpiryCheck(Check):
    name = "login_expiry"

    async def _auth_once(self, ctx):
        cfg = ctx.cfg
        env = dict(os.environ)
        env["CLAUDE_CONFIG_DIR"] = cfg.claude_config_dir
        try:
            proc = await asyncio.create_subprocess_exec(
                cfg.claude_bin, "--print", "--output-format", "json",
                stdin=asyncio.subprocess.PIPE,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE,
                env=env,
            )
            try:
                out, _ = await asyncio.wait_for(proc.communicate(b"hi"), timeout=90)
            except asyncio.TimeoutError:
                proc.kill()
                return False, "timeout"
            d = json.loads((out or b"{}").decode("utf-8", "replace") or "{}")
            if d.get("is_error"):
                return False, str(d.get("result") or d.get("subtype") or "unknown")[:200]
            return True, ""
        except Exception as e:
            return False, str(e)[:200]

    async def _check_auth(self, ctx):
        ok, err = await self._auth_once(ctx)
        if ok:
            return True, ""
        await asyncio.sleep(8)  # 일시적 blip 무시: 8초 뒤 재확인
        ok2, err2 = await self._auth_once(ctx)
        if ok2:
            return True, ""
        combined = (err + " " + err2).lower()
        if any(k in combined for k in _AUTH_KEYWORDS):
            return False, err2 or err
        return True, ""  # 일시적 실패는 인증죽음으로 안 침

    async def run(self, ctx) -> CheckResult:
        res = CheckResult()
        cfg = ctx.cfg

        if not cfg.auth_enabled or not os.path.exists(cfg.claude_bin):
            log.info("login_expiry 건너뜀 (auth_enabled=%s, claude_bin 존재=%s)",
                     cfg.auth_enabled, os.path.exists(cfg.claude_bin))
            # 상태를 건드리지 않음(전이 알림 발생 안 하게 states 비움)
            res.data = {"auth_checked": False}
            return res

        ok, err = await self._check_auth(ctx)
        ctx.state.set("auth_ok", ok)  # 일일요약이 읽음

        res.states["auth_dead"] = StateCondition(
            active=not ok,
            title="인증이 끊겼어",
            lines=[
                "헤드리스 클로드 호출 실패 → **요약이 전부 실패**하게 돼.",
                f"오류: `{err}`",
                "→ 운영서버에서 `CLAUDE_CONFIG_DIR=/home/weplay/.claude-jjongpal claude` 로 재로그인 필요",
            ],
            level="crit",
        )
        res.data = {"auth_checked": True, "auth_ok": ok}
        return res
