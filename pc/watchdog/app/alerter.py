# -*- coding: utf-8 -*-
"""알림 발송 — 폰 FCM 푸시(+선택적 Discord 웹훅).

원본 canary.py 의 notify() 를 그대로 옮긴다:
  - devices 테이블에서 활성 기기의 fcm_token 을 읽어
  - FCM HTTP v1 data-only 메시지({type:alert,title,body,level})로 푸시 (베젤 안 거침)
  - 폰은 마크다운을 못 살리므로 본문에서 **, ` 를 떼고 900자로 자른다

추가: 장애(warn·crit)는 Discord 에도 보낸다. JJ_ALERT_WEBHOOK 이 있으면 웹훅,
없으면 JJ_ALERT_BOT_TOKEN + JJ_ALERT_CHANNEL_ID 로 봇이 채널에 직접 올린다.
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

        # 2026-10-10: 앱(0.5.7~)은 FCM 대신 이 알림함을 폴링해 로컬 알림을 띄운다 → 항상 먼저 기록.
        await self._save_app_alert(title, body_text, level)
        await self._push_fcm(title, body_text, level)
        await self._post_webhook(title, lines, level)

    async def _save_app_alert(self, title: str, body_text: str, level: str) -> None:
        try:
            await self.db.execute(
                "INSERT INTO app_alerts (level, title, body) VALUES ($1, $2, $3)",
                level, f"쫑팔 · {title}", body_text,
            )
        except Exception as e:
            log.warning("앱 알림함 기록 실패: %s", e)

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
        # 디스코드에는 장애(warn·crit)만 올린다. 해소·복구·일일요약은 앱 알림함에만 (2026-10-13 형 결정)
        if level not in ("warn", "crit"):
            return
        url = self.cfg.alert_webhook
        headers = {}
        if not url and self.cfg.alert_bot_token and self.cfg.alert_channel_id:
            url = f"https://discord.com/api/v10/channels/{self.cfg.alert_channel_id}/messages"
            # 기본 UA 는 Cloudflare 가 막을 수 있어 디스코드 봇 형식 UA 를 붙인다
            headers = {"Authorization": f"Bot {self.cfg.alert_bot_token}",
                       "User-Agent": "DiscordBot (https://jjongpal, 1.0)"}
        if not url:
            return
        emoji = {"crit": "🚨", "warn": "⚠️"}[level]
        # Discord 규칙: 가로줄/표 금지 → 헤더 텍스트 + 불릿만. 2000자 제한.
        body = "\n".join(f"- {ln}" for ln in lines)
        content = f"{emoji} **쫑팔 · {title}**\n{body}"[:1900]
        try:
            async with httpx.AsyncClient(timeout=15.0) as client:
                r = await client.post(url, json={"content": content}, headers=headers)
                if r.status_code >= 300:
                    log.warning("디스코드 발송 실패 %s", r.status_code)
        except Exception as e:
            log.warning("디스코드 발송 오류: %s", type(e).__name__)
