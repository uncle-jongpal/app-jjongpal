# -*- coding: utf-8 -*-
"""watchdog 엔트리포인트.

백엔드와 의도적으로 분리된 단일 인스턴스. 같은 DB(DATABASE_URL)에 독립 연결해
등록된 체크들을 주기마다 돌린다. 백엔드가 멈춰도 이 프로세스는 계속 산다.
"""
from __future__ import annotations

import asyncio
import logging
import traceback

from . import config
from .db import Database
from .state import State
from .alerter import Alerter
from .scheduler import Scheduler, Context
from .checks.registry import build_checks, build_digest


async def _amain() -> None:
    cfg = config.load()
    logging.basicConfig(
        level=getattr(logging, cfg.log_level.upper(), logging.INFO),
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
    )
    log = logging.getLogger("watchdog")

    db = Database(cfg.database_url)
    await db.connect()
    state = State(cfg.state_file)
    alerter = Alerter(db, cfg)
    ctx = Context(cfg=cfg, db=db, alerter=alerter, state=state)

    checks = build_checks(cfg)
    digest = build_digest(cfg)
    sched = Scheduler(ctx, checks, digest)

    try:
        await sched.run_forever()
    finally:
        await db.close()


def main() -> None:
    try:
        asyncio.run(_amain())
    except KeyboardInterrupt:
        pass
    except Exception:
        # 최상위 실패는 스택만 남기고 0이 아닌 코드로 종료 (docker restart 정책이 되살림)
        traceback.print_exc()
        raise


if __name__ == "__main__":
    main()
