# -*- coding: utf-8 -*-
"""감시(watchdog) 플러그인 기반 타입 정의.

새 감시 항목을 추가하려면:
  1) 이 디렉터리에 새 모듈을 만들고 `Check` 를 상속한 클래스를 작성한다.
  2) `run(ctx)` 안에서 DB/외부 상태를 조사해 `CheckResult` 를 돌려준다.
  3) `registry.py` 의 CHECKS 목록에 인스턴스를 추가한다. 끝.

상태 전이(state-change) 알림은 스케줄러가 대신 처리한다.
  - 지속 상태(예: "밀려 있음")는 `CheckResult.states` 에 넣는다 → 스케줄러가
    상태 파일로 중복을 제거해, 나쁨으로 '진입'할 때와 '해소'될 때만 1회 알린다.
  - 매번 알려야 하는 일회성 통지(예: "자동 복구했어")는 `notifications` 에 넣는다.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import List, Dict, Any


# 알림 레벨: crit(심각)·warn(경고)·info(정보)·ok(정상복귀)
Level = str


@dataclass
class StateCondition:
    """상태 전이 기반으로 알릴 지속 상태 하나.

    active=True 로 처음 바뀌면 (title, lines, level) 로 알리고,
    active=False 로 돌아오면 "<title> — 해소됨" 을 ok 레벨로 알린다.
    (canary.py 의 transition() 과 동일한 의미)
    """
    active: bool
    title: str
    lines: List[str] = field(default_factory=list)
    level: Level = "crit"


@dataclass
class Notification:
    """상태와 무관하게 이번 실행에서 바로 보낼 통지."""
    title: str
    lines: List[str] = field(default_factory=list)
    level: Level = "info"


@dataclass
class CheckResult:
    # 상태키 -> 지속 상태 (스케줄러가 전이 시에만 알림)
    states: Dict[str, StateCondition] = field(default_factory=dict)
    # 즉시 보낼 통지들 (매 실행마다 조건 맞으면 발송)
    notifications: List[Notification] = field(default_factory=list)
    # 로깅/일일요약용 원자료
    data: Dict[str, Any] = field(default_factory=dict)


class Check:
    """모든 감시 항목의 베이스.

    name         : 상태 파일/로그에서 쓰는 고유 이름
    interval_sec : 이 주기(초)가 지나야 다시 실행
    """
    name: str = "check"
    interval_sec: int = 900

    def __init__(self, name: str | None = None, interval_sec: int | None = None):
        if name is not None:
            self.name = name
        if interval_sec is not None:
            self.interval_sec = interval_sec

    async def run(self, ctx) -> CheckResult:  # pragma: no cover - 인터페이스
        raise NotImplementedError
