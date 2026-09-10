# -*- coding: utf-8 -*-
"""체크 레지스트리 — 새 감시 항목은 '여기 한 곳'에만 등록한다.

새 감시 항목 추가 절차:
  1) checks/ 에 새 모듈을 만들고 base.Check 를 상속한 클래스를 작성
  2) 아래 build_checks() 의 리스트에 인스턴스를 추가(이름·주기 지정)
  3) 끝. 스케줄러가 알아서 주기마다 돌리고 상태 전이 알림을 처리한다.

주기(interval_sec)는 cfg 에서 주입 → env 로 덮어쓸 수 있다(config.py 참조).
"""
from __future__ import annotations

from typing import List

from .base import Check
from .stt_delay import SttDelayCheck
from .all_fail import AllFailCheck
from .login_expiry import LoginExpiryCheck
from .reaper import ReaperCheck
from .digest import DigestJob


def build_checks(cfg) -> List[Check]:
    """주기적으로 도는 체크들. (digest 는 시각 기반이라 여기 없음 → build_digest)"""
    return [
        # canary 계열 — 15분 주기 (WATCHDOG_CANARY_INTERVAL_SEC)
        SttDelayCheck(interval_sec=cfg.canary_interval_sec),
        AllFailCheck(interval_sec=cfg.canary_interval_sec),
        # 인증 점검 — 60분 주기 (WATCHDOG_AUTH_INTERVAL_SEC)
        LoginExpiryCheck(interval_sec=cfg.auth_interval_sec),
        # reaper — 5분 주기 (WATCHDOG_REAPER_INTERVAL_SEC)
        ReaperCheck(interval_sec=cfg.reaper_interval_sec),
    ]


def build_digest(cfg) -> DigestJob:
    """시각 기반 일일요약 (WATCHDOG_DIGEST_TIME, 기본 09:00)."""
    return DigestJob()
