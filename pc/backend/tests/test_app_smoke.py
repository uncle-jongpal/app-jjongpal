"""앱 조립 스모크 — 디비 없이(lifespan 미실행) 라우팅·미들웨어만 확인."""

import importlib
import os

from fastapi.testclient import TestClient

from app import main


def _client():
    # with 블록 없이 쓰면 lifespan(디비 풀 생성)이 돌지 않는다
    return TestClient(main.app)


def test_health():
    r = _client().get("/health")
    assert r.status_code == 200
    assert r.text == "ok\n"


def test_api_docs_disabled_by_default():
    c = _client()
    for path in ("/docs", "/redoc", "/openapi.json"):
        assert c.get(path).status_code == 404, path


def test_api_docs_can_be_enabled(monkeypatch):
    from app import config
    monkeypatch.setattr(config, "ENABLE_API_DOCS", True)
    m = importlib.reload(main)
    try:
        assert TestClient(m.app).get("/openapi.json").status_code == 200
    finally:
        monkeypatch.setattr(config, "ENABLE_API_DOCS", False)
        importlib.reload(main)


def test_gzip_on_large_response_but_not_static():
    from app import config
    big = os.path.join(config.STATIC_ROOT, "big.txt")
    with open(big, "w") as f:
        f.write("a" * 5000)
    c = _client()
    r = c.get("/static/big.txt", headers={"Accept-Encoding": "gzip"})
    assert r.status_code == 200
    assert r.headers.get("content-encoding") is None
    assert r.headers.get("content-length") == "5000"

    # 인증 실패 응답처럼 작은 응답은 압축 안 함
    r = c.get("/rest/todos", headers={"Accept-Encoding": "gzip"})
    assert r.headers.get("content-encoding") is None


def test_gzip_applies_to_api_paths():
    from fastapi import FastAPI
    from fastapi.responses import PlainTextResponse
    mini = FastAPI()

    @mini.get("/big")
    def big():
        return PlainTextResponse("b" * 5000)

    wrapped = main.SelectiveGZipMiddleware(mini, minimum_size=1000)
    r = TestClient(wrapped).get("/big", headers={"Accept-Encoding": "gzip"})
    assert r.headers.get("content-encoding") == "gzip"
    assert r.text == "b" * 5000


def test_body_size_limit():
    c = _client()
    r = c.post("/upload/audio", headers={"Content-Length": str(300 * 1024 * 1024)}, content=b"")
    assert r.status_code == 413
