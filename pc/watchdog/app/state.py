# -*- coding: utf-8 -*-
"""영속 상태 저장 (canary.py 의 state.json 과 같은 역할).

- flags      : 상태 전이 알림 중복 방지용 (키 -> bool, 현재 '나쁨'인지)
- last_run   : 체크별 마지막 실행 시각(epoch)
- digest_date: 마지막으로 일일요약을 보낸 날짜 'YYYY-MM-DD'
- kv         : 체크끼리 공유할 임의 값 (예: 마지막 auth_ok 상태)
"""
from __future__ import annotations

import json
import os
import tempfile
from typing import Any


class State:
    def __init__(self, path: str):
        self.path = path
        self._d: dict = {"flags": {}, "last_run": {}, "digest_date": "", "kv": {}}
        self._load()

    def _load(self) -> None:
        try:
            with open(self.path, encoding="utf-8") as f:
                loaded = json.load(f)
            for k in ("flags", "last_run", "kv"):
                loaded.setdefault(k, {})
            loaded.setdefault("digest_date", "")
            self._d = loaded
        except Exception:
            pass  # 파일 없거나 손상 → 기본값 유지

    def save(self) -> None:
        os.makedirs(os.path.dirname(self.path) or ".", exist_ok=True)
        # 원자적 저장 (중간 실패 시 기존 파일 보존)
        fd, tmp = tempfile.mkstemp(dir=os.path.dirname(self.path) or ".", suffix=".tmp")
        try:
            with os.fdopen(fd, "w", encoding="utf-8") as f:
                json.dump(self._d, f, ensure_ascii=False, indent=1)
            os.replace(tmp, self.path)
        except Exception:
            try:
                os.unlink(tmp)
            except Exception:
                pass
            raise

    # ── 전이 플래그 ──
    def get_flag(self, key: str) -> bool:
        return bool(self._d["flags"].get(key, False))

    def set_flag(self, key: str, val: bool) -> None:
        self._d["flags"][key] = bool(val)

    # ── last_run ──
    def last_run(self, name: str) -> float:
        return float(self._d["last_run"].get(name, 0.0))

    def set_last_run(self, name: str, ts: float) -> None:
        self._d["last_run"][name] = float(ts)

    # ── digest ──
    @property
    def digest_date(self) -> str:
        return self._d.get("digest_date", "")

    @digest_date.setter
    def digest_date(self, v: str) -> None:
        self._d["digest_date"] = v

    # ── 공유 kv ──
    def get(self, key: str, default: Any = None) -> Any:
        return self._d["kv"].get(key, default)

    def set(self, key: str, val: Any) -> None:
        self._d["kv"][key] = val
