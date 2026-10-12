"""받아쓰기 서버 오류 분류: '서버 다운'(시도 안 셈) vs '파일 단위 일시 오류'(시도 셈).

실제 소켓으로 연결 거부·응답 지연·처리 중 끊김을 만들어 requests 가 내는 진짜 예외로 검증한다.
"""

import socket
import threading

import pytest
import requests

from app import workers
from app.workers import classify_http_status, classify_request_error


@pytest.mark.parametrize("code,expected", [
    (200, "ok"), (204, "ok"), (302, "ok"),
    (400, "permanent"), (404, "permanent"), (413, "permanent"), (422, "permanent"),
    (500, "file"), (501, "file"), (507, "file"),
    (502, "down"), (503, "down"), (504, "down"),
])
def test_classify_http_status(code, expected):
    assert classify_http_status(code) == expected


def _free_port() -> int:
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    return port


def _catch(fn):
    try:
        fn()
    except requests.exceptions.RequestException as e:
        return e
    raise AssertionError("예외가 나야 함")


def test_connection_refused_is_down():
    port = _free_port()  # 아무도 안 듣는 포트
    e = _catch(lambda: requests.post(f"http://127.0.0.1:{port}/transcribe", timeout=3))
    assert isinstance(e, requests.exceptions.ConnectionError)
    assert classify_request_error(e) == "down"


def test_dns_failure_is_down():
    e = _catch(lambda: requests.post("http://whisper-host.invalid/transcribe", timeout=3))
    assert classify_request_error(e) == "down"


def test_bad_url_is_down():
    e = _catch(lambda: requests.post("not-a-url/transcribe", timeout=3))
    assert classify_request_error(e) == "down"


class _Server:
    """접속은 받되 (mode=hang) 응답 없이 버티거나 (mode=drop) 요청을 읽고 그냥 끊는 서버."""

    def __init__(self, mode: str):
        self.mode = mode
        self.sock = socket.socket()
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.sock.bind(("127.0.0.1", 0))
        self.sock.listen(5)
        self.port = self.sock.getsockname()[1]
        self.conns = []
        self.t = threading.Thread(target=self._run, daemon=True)
        self.t.start()

    def _run(self):
        try:
            while True:
                c, _ = self.sock.accept()
                self.conns.append(c)
                if self.mode == "drop":
                    try:
                        c.recv(65536)
                    except OSError:
                        pass
                    c.close()
        except OSError:
            pass

    def close(self):
        for c in self.conns:
            try:
                c.close()
            except OSError:
                pass
        self.sock.close()


def test_read_timeout_is_file_level():
    srv = _Server("hang")
    try:
        e = _catch(lambda: requests.post(f"http://127.0.0.1:{srv.port}/transcribe",
                                         data=b"x", timeout=(3, 0.5)))
        assert isinstance(e, requests.exceptions.ReadTimeout)
        assert classify_request_error(e) == "file"
    finally:
        srv.close()


def test_server_drops_mid_request_is_file_level():
    # 특정 파일이 서버를 죽이는 경우 → '처리 중 끊김' → 시도 횟수에 들어가야 독 파일이 결국 FAILED 로 빠짐
    srv = _Server("drop")
    try:
        e = _catch(lambda: requests.post(f"http://127.0.0.1:{srv.port}/transcribe",
                                         data=b"x" * 10, timeout=3))
        assert isinstance(e, requests.exceptions.ConnectionError)
        assert classify_request_error(e) == "file"
    finally:
        srv.close()


def test_transcribe_via_api_raises_backend_down(tmp_path, monkeypatch):
    f = tmp_path / "a.m4a"
    f.write_bytes(b"\0" * 10)
    monkeypatch.setattr(workers.config, "WHISPER_BACKEND_URL", f"http://127.0.0.1:{_free_port()}")
    with pytest.raises(workers.WhisperBackendDown):
        workers._transcribe_via_api(str(f))


def test_transcribe_via_api_raises_file_transient_on_timeout(tmp_path, monkeypatch):
    f = tmp_path / "a.m4a"
    f.write_bytes(b"\0" * 10)
    srv = _Server("hang")
    try:
        monkeypatch.setattr(workers.config, "WHISPER_BACKEND_URL", f"http://127.0.0.1:{srv.port}")
        monkeypatch.setattr(workers.config, "WHISPER_TIMEOUT_SEC", 0.5)
        with pytest.raises(workers.WhisperFileTransient):
            workers._transcribe_via_api(str(f))
    finally:
        srv.close()


class _Resp:
    def __init__(self, code):
        self.status_code = code
        self.text = "err"

    def raise_for_status(self):
        if self.status_code >= 400:
            raise requests.exceptions.HTTPError(f"{self.status_code}")


@pytest.mark.parametrize("code,exc", [
    (503, workers.WhisperBackendDown),
    (502, workers.WhisperBackendDown),
    (500, workers.WhisperFileTransient),
    (400, requests.exceptions.HTTPError),
])
def test_transcribe_via_api_status_mapping(tmp_path, monkeypatch, code, exc):
    f = tmp_path / "a.m4a"
    f.write_bytes(b"\0" * 10)
    monkeypatch.setattr(workers.config, "WHISPER_BACKEND_URL", "http://whisper")
    monkeypatch.setattr(workers.requests, "post", lambda *a, **k: _Resp(code))
    with pytest.raises(exc):
        workers._transcribe_via_api(str(f))


def test_both_are_transient_backend_errors():
    assert issubclass(workers.WhisperBackendDown, workers.TransientBackendError)
    assert issubclass(workers.WhisperFileTransient, workers.TransientBackendError)
