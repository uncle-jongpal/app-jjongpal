# -*- coding: utf-8 -*-
"""알림 발송 — 폰 FCM 푸시(+선택적 Discord 웹훅).

원본 canary.py 의 notify() 를 그대로 옮긴다:
  - devices 테이블에서 활성 기기의 fcm_token 을 읽어
  - FCM HTTP v1 data-only 메시지({type:alert,title,body,level})로 푸시 (베젤 안 거침)
  - 폰은 마크다운을 못 살리므로 본문에서 **, ` 를 떼고 900자로 자른다

추가: JJ_ALERT_WEBHOOK 이 설정돼 있으면 Discord 웹훅에도 같은 내용을 텍스트로 보낸다.
(원본은 이 env 를 선언만 하고 실제로는 FCM 만 썼다 — NOTES.md 참조)

비밀값(<secret>)은 로그에 찍지 않는다.
"""
from __future__ import annotations

import logging
from typing import List, Optional

import httpx
from google.oauth2 import service_account
from google.auth.transport.requests import Request as GoogleAuthRequest

log = logging.getLogger("watchdog.alerter")

_FCM_SCOPES = ["https://www.googleapis.com/auth/firebase.messaging"]


class Alerter:
    def __init__(self, db, cfg):
        self.db = db
        self.cfg = cfg
        self._creds: Optional[service_account.Credentials] = None
        self._endpoint = (
            f"https://fcm.googleapis.com/v1/projects/{cfg.fcm_project_id}/messages:send"
        )

    def _access_token(self) -> str:
        if self._creds is None:
            self._creds = service_account.Credentials.from_service_account_file(
                self.cfg.fcm_credentials, scopes=_FCM_SCOPES
            )
        if not self._creds.valid:
            self._creds.refresh(GoogleAuthRequest())
        return self._creds.token

    async def _phone_tokens(self) -> List[str]:
        try:
            rows = await self.db.fetch(
                "SELECT fcm_token FROM devices "
                "WHERE revoked_at IS NULL AND fcm_token IS NOT NULL"
            )
            return [r["fcm_token"] for r in rows]
        except Exception as e:
            log.warning("폰 토큰 조회 실패: %s", e)
            return []

    async def notify(self, title: str, lines: List[str], level: str = "warn") -> None:
        # 폰 알림은 마크다운을 못 살리므로 강조 기호(**, `)는 떼어낸다 (canary 원본과 동일)
        body_text = "\n".join(lines)[:900].replace("**", "").replace("`", "")
        log.info("[알림 %s] %s | %s", level, title, body_text[:80])

        await self._push_fcm(title, body_text, level)
        await self._post_webhook(title, lines, level)

    async def _push_fcm(self, title: str, body_text: str, level: str) -> None:
        tokens = await self._phone_tokens()
        if not tokens:
            log.info("[폰토큰없음] %s", title)
            return
        try:
            tok = self._access_token()
        except Exception as e:
            log.warning("FCM 토큰 발급 실패: %s", e)
            return
        headers = {"Authorization": f"Bearer {tok}", "Content-Type": "application/json"}
        async with httpx.AsyncClient(timeout=15.0) as client:
            for t in tokens:
                msg = {"message": {"token": t, "data": {
                    "type": "alert",
                    "title": f"쫑팔 · {title}",
                    "body": body_text,
                    "level": level,
                }}}
                try:
                    r = await client.post(self._endpoint, json=msg, headers=headers)
                    if r.status_code >= 300:
                        log.warning("폰 푸시 실패 %s %s", r.status_code, r.text[:200])
                except Exception as e:
                    log.warning("폰 푸시 오류: %s", e)

    async def _post_webhook(self, title: str, lines: List[str], level: str) -> None:
        url = self.cfg.alert_webhook
        if not url:
            return
        emoji = {"crit": "🚨", "warn": "⚠️", "info": "🛠️", "ok": "✅"}.get(level, "•")
        # Discord 규칙: 가로줄/표 금지 → 헤더 텍스트 + 불릿만. 2000자 제한.
        body = "\n".join(f"- {ln}" for ln in lines)
        content = f"{emoji} **{title}**\n{body}"[:1900]
        try:
            async with httpx.AsyncClient(timeout=15.0) as client:
                r = await client.post(url, json={"content": content})
                if r.status_code >= 300:
                    log.warning("웹훅 발송 실패 %s", r.status_code)
        except Exception as e:
            log.warning("웹훅 오류: %s", e)
