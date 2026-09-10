# -*- coding: utf-8 -*-
"""환경변수 기반 설정. 비밀값은 코드에 박지 않고 env/마운트 파일에서 읽는다.

원본(canary.py / reaper.sh)의 임계값·주기를 그대로 기본값으로 옮기되,
전부 env 로 덮어쓸 수 있게 한다.
"""
from __future__ import annotations

import os
from dataclasses import dataclass


def _int(name: str, default: int) -> int:
    v = os.environ.get(name)
    return int(v) if v not in (None, "") else default


def _str(name: str, default: str) -> str:
    v = os.environ.get(name)
    return v if v not in (None, "") else default


@dataclass
class Config:
    # ── DB (원본과 동일: canary/reaper 모두 DATABASE_URL 사용) ──
    database_url: str

    # ── 알림 채널 ──
    # <secret> Discord 웹훅 URL. 원본 canary 는 이 env 를 선언만 하고 실제 발송엔
    # 안 썼다(폰 FCM 푸시만 사용). 여기선 설정돼 있으면 웹훅에도 같이 보낸다.
    alert_webhook: str
    # <secret> FCM 서비스 계정 JSON 경로 (컨테이너에 마운트)
    fcm_project_id: str
    fcm_credentials: str

    # ── 상태 파일 (중복 알림 방지 + last_run + digest 날짜 기록) ──
    # 재시작에도 상태가 유지되도록 볼륨 마운트 경로 권장.
    state_file: str

    # ── 주기(초). cron 원본: reaper 5분 / canary 15분 / auth 60분 ──
    reaper_interval_sec: int       # reaper.sh: */5
    canary_interval_sec: int       # canary.py: */15 (stt_delay·all_fail·auto_heal 공용)
    auth_interval_sec: int         # canary AUTH_CHECK_MIN=60분
    tick_sec: int                  # 스케줄러 틱(얼마나 자주 due 를 검사)

    # ── 일일 요약 시각 "HH:MM" (cron 원본: 0 9 * * *) ──
    digest_time: str

    # ── canary 임계값 (원본 상수 그대로) ──
    summary_stall_h: int           # SUMMARY_STALL_H = 3
    transcribe_stall_h: int        # TRANSCRIBE_STALL_H = 1
    stuck_min: int                 # STUCK_MIN = 30
    retry_max: int                 # RETRY_MAX = 3
    retry_wait_min: int            # RETRY_WAIT_MIN = 30

    # ── 인증(login_expiry) 점검: 헤드리스 claude 호출 ──
    # 주의: python:3.12-slim 컨테이너엔 claude CLI/node 가 없다.
    # 바이너리가 없거나 auth_enabled=false 면 이 체크는 '건너뜀'(알림 X, 로그만).
    auth_enabled: bool
    claude_bin: str
    claude_config_dir: str

    log_level: str


def load() -> Config:
    db = os.environ.get("DATABASE_URL")
    if not db:
        raise SystemExit("DATABASE_URL 환경변수가 필요합니다")
    return Config(
        database_url=db,
        alert_webhook=_str("JJ_ALERT_WEBHOOK", ""),
        fcm_project_id=_str("FIREBASE_PROJECT_ID", "jjongpal-app"),
        fcm_credentials=_str("FIREBASE_CREDENTIALS", "/secrets/service-account.json"),
        state_file=_str("WATCHDOG_STATE_FILE", "/data/state.json"),
        reaper_interval_sec=_int("WATCHDOG_REAPER_INTERVAL_SEC", 300),
        canary_interval_sec=_int("WATCHDOG_CANARY_INTERVAL_SEC", 900),
        auth_interval_sec=_int("WATCHDOG_AUTH_INTERVAL_SEC", 3600),
        tick_sec=_int("WATCHDOG_TICK_SEC", 15),
        digest_time=_str("WATCHDOG_DIGEST_TIME", "09:00"),
        summary_stall_h=_int("WATCHDOG_SUMMARY_STALL_H", 3),
        transcribe_stall_h=_int("WATCHDOG_TRANSCRIBE_STALL_H", 1),
        stuck_min=_int("WATCHDOG_STUCK_MIN", 30),
        retry_max=_int("WATCHDOG_RETRY_MAX", 3),
        retry_wait_min=_int("WATCHDOG_RETRY_WAIT_MIN", 30),
        auth_enabled=_str("WATCHDOG_AUTH_ENABLED", "false").lower() in ("1", "true", "yes"),
        claude_bin=_str("CLAUDE_BIN", "/home/weplay/.nvm/versions/node/v22.22.0/bin/claude"),
        claude_config_dir=_str("CLAUDE_CONFIG_DIR", "/home/weplay/.claude-jjongpal"),
        log_level=_str("WATCHDOG_LOG_LEVEL", "INFO"),
    )
