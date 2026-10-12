"""테스트 공통 설정 — app.config 가 import 시점에 필수 env 를 읽으므로 먼저 더미 값을 넣는다.
(디비에 실제로 붙지 않는 순수 로직 테스트만 둔다)"""

import os
import sys
import tempfile

_tmp = tempfile.mkdtemp(prefix="jjongpal-test-")
os.environ.setdefault("DATABASE_URL", "postgresql://dummy:dummy@127.0.0.1:1/dummy")
os.environ.setdefault("JWT_SECRET", "test-only-not-a-secret")
os.environ.setdefault("STORAGE_ROOT", os.path.join(_tmp, "storage"))
os.environ.setdefault("STATIC_ROOT", os.path.join(_tmp, "static"))
os.environ.setdefault("RUN_WORKERS", "false")

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
