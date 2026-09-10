"""
쫑팔이삼촌 — 통합 백엔드 (FastAPI / ASGI)

auth-service + upload-receiver + whisper-worker + fcm-pusher + PostgREST 를 한 앱으로 합침.

라우트:
  GET   /health                 → "ok" (평문; nginx 헬스와 동일)
  /auth/*                       → 인증 (login/refresh/logout/health)
  /upload/*                     → 통화 파일 업로드
  /rest/*                       → PostgREST 대체(6개 경로, RLS 로 행 격리)
  /static/*                     → 정적 파일(APK 등) StaticFiles

lifespan 에서 공유 asyncpg 풀 생성 + (RUN_WORKERS 면) whisper/fcm 루프 태스크 기동.

*** 단일 인스턴스 필수: uvicorn --workers 1. 워커 프로세스가 2개 이상이면
    whisper/fcm 폴링 루프가 대기열을 이중 소비한다. ***
"""

import logging
import mimetypes
import os
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.responses import PlainTextResponse
from fastapi.staticfiles import StaticFiles

from . import auth, config, db, rest, upload, workers

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("backend")

# .apk 가 application/vnd.android.package-archive 로 내려가게 등록
# (nginx types 블록이 하던 역할 — StaticFiles 는 mimetypes 를 참조한다)
mimetypes.add_type("application/vnd.android.package-archive", ".apk")


@asynccontextmanager
async def lifespan(app: FastAPI):
    await db.init_pool()
    os.makedirs(config.STORAGE_ROOT, exist_ok=True)

    tasks = []
    if config.RUN_WORKERS:
        import asyncio
        log.info("RUN_WORKERS=on → whisper 백그라운드 루프 기동(fcm 폐기)")
        tasks.append(asyncio.create_task(workers.whisper_loop(), name="whisper_loop"))
    else:
        log.info("RUN_WORKERS=off → 백그라운드 루프 비활성(스테이징 모드)")

    try:
        yield
    finally:
        for t in tasks:
            t.cancel()
        for t in tasks:
            try:
                await t
            except Exception:
                pass
        await db.close_pool()


app = FastAPI(title="jjongpal-backend", lifespan=lifespan)


class MaxBodySizeMiddleware:
    """업로드 200MB 한도(nginx client_max_body_size 200M 대체)를 Content-Length 로 조기 차단.
    실제 스트리밍 한도는 upload 라우터가 바이트 단위로 재검증한다."""

    def __init__(self, app, max_bytes: int):
        self.app = app
        self.max_bytes = max_bytes

    async def __call__(self, scope, receive, send):
        if scope["type"] == "http":
            for name, value in scope.get("headers", []):
                if name == b"content-length":
                    try:
                        if int(value) > self.max_bytes:
                            from starlette.responses import PlainTextResponse as _PT
                            resp = _PT("payload too large", status_code=413)
                            await resp(scope, receive, send)
                            return
                    except ValueError:
                        pass
                    break
        await self.app(scope, receive, send)


app.add_middleware(MaxBodySizeMiddleware, max_bytes=config.MAX_BYTES)

# 라우터 등록
app.include_router(auth.router)
app.include_router(upload.router)
app.include_router(rest.router)


@app.get("/health", response_class=PlainTextResponse)
async def health() -> str:
    return "ok\n"


# 정적 파일 — /static/<filename>. nginx /static/ 서빙 대체.
os.makedirs(config.STATIC_ROOT, exist_ok=True)
app.mount("/static", StaticFiles(directory=config.STATIC_ROOT), name="static")
