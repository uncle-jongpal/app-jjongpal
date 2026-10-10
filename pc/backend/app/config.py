"""
쫑팔이삼촌 — 통합 백엔드 설정 (환경 변수 모음)

auth-service / upload-receiver / whisper-worker / fcm-pusher / PostgREST 를 하나의
FastAPI(ASGI) 앱으로 합치면서, 각 서비스가 쓰던 환경 변수를 여기 한곳에 모았다.
값 자체는 로그에 찍지 않는다(시크릿 보호).
"""

import os

# ===== 디비 / 인증 =====
DATABASE_URL = os.environ["DATABASE_URL"]
JWT_SECRET = os.environ["JWT_SECRET"]
JWT_ALG = "HS256"
ACCESS_TTL = int(os.environ.get("ACCESS_TOKEN_TTL_SEC", 3600))
REFRESH_TTL = int(os.environ.get("REFRESH_TOKEN_TTL_SEC", 60 * 60 * 24 * 90))

# app_role(admin/user) → PostgreSQL 역할명. auth-service 의 매핑과 동일.
APP_ROLE_TO_PG_ROLE = {
    "admin": "jjongpal_admin",
    "user": "jjongpal_user",
}
# /rest/* 에서 SET ROLE 로 전환 허용되는 PG 역할 화이트리스트(주입 방지).
ALLOWED_PG_ROLES = {"jjongpal_user", "jjongpal_admin"}

# ===== 저장 =====
# upload-receiver 와 동일: 통화 파일 루트. 컨테이너 안 마운트 경로.
STORAGE_ROOT = os.environ.get("STORAGE_ROOT", "/storage")
# 정적 파일(앱 빌드 APK 등) 디렉터리. nginx 가 /static/ 으로 서빙하던 것을 StaticFiles 로 대체.
STATIC_ROOT = os.environ.get("STATIC_ROOT", "/static")

# 업로드 제한 (upload-receiver 와 동일)
ALLOWED_EXT = {".m4a", ".mp3", ".wav", ".ogg", ".amr"}
MP4_EXT = {".m4a", ".mp4"}
MAX_BYTES = 200 * 1024 * 1024  # 200MB (nginx client_max_body_size 200M 과 일치)
MIN_BYTES = 4 * 1024  # 4KB 미만 = 빈/깨진 토막

# ===== 백그라운드 워커 게이트 =====
# 기본 true. 스테이징에서 운영 대기열을 이중 소비하지 않도록 false 로 끌 수 있음.
RUN_WORKERS = os.environ.get("RUN_WORKERS", "true").strip().lower() in ("1", "true", "yes", "on")

# ===== whisper 전달 워커 =====
WHISPER_LANGUAGE = os.environ.get("WHISPER_LANGUAGE", "ko")
WHISPER_POLL_INTERVAL_SEC = int(os.environ.get("WHISPER_POLL_INTERVAL_SEC", "5"))
WHISPER_BATCH_SIZE = int(os.environ.get("WHISPER_BATCH_SIZE", "3"))
WHISPER_BACKEND_URL = os.environ.get("WHISPER_BACKEND_URL", "").strip().rstrip("/")
WHISPER_TIMEOUT_SEC = int(os.environ.get("WHISPER_TIMEOUT_SEC", "600"))
WHISPER_BACKEND_BACKOFF_SEC = int(os.environ.get("WHISPER_BACKEND_BACKOFF_SEC", "15"))

# ===== FCM 푸시 워커 =====
# 워커가 켜질 때만 필요. 꺼져 있으면 없어도 앱은 뜬다(지연 검증).
FIREBASE_PROJECT_ID = os.environ.get("FIREBASE_PROJECT_ID", "")
FIREBASE_CREDENTIALS = os.environ.get("FIREBASE_CREDENTIALS", "/secrets/service-account.json")
FCM_POLL_INTERVAL_SEC = int(os.environ.get("FCM_POLL_INTERVAL_SEC", "5"))
FCM_BATCH_SIZE = int(os.environ.get("FCM_BATCH_SIZE", "10"))

# ===== 디비 풀 =====
# auth/upload/rest/workers 가 공유하는 단일 asyncpg 풀.
DB_POOL_MIN = int(os.environ.get("DB_POOL_MIN", "2"))
DB_POOL_MAX = int(os.environ.get("DB_POOL_MAX", "10"))
# 디비 문장 하나의 최대 대기(초). 연결이 소리 없이 끊긴(반쯤 열린) 경우 무한 대기 방지.
# 2026-10-09 디비 연결 리셋 후 받아쓰기 워커가 로그 없이 19시간 멈춘 사고 재발 방지.
DB_COMMAND_TIMEOUT_SEC = float(os.environ.get("DB_COMMAND_TIMEOUT_SEC", "60"))
