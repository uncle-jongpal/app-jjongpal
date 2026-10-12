import pytest
from fastapi import HTTPException

from app.upload import decide_duplicate_action, mp4_has_moov, safe_event_id

P = "/storage/audio/1/2026-10-13/ev1.m4a"
OTHER = "/storage/audio/1/2026-10-12/ev1.m4a"


@pytest.mark.parametrize("status,path,present,expected", [
    ("DONE", None, False, "discard"),        # 받아쓰기 끝나 원본 지워짐 → 새 파일 버림
    ("DONE", P, True, "discard"),
    ("SKIPPED", None, False, "discard"),
    ("PENDING", None, False, "discard"),     # 파일 소모됨
    ("PROCESSING", P, True, "discard"),      # 처리 중 파일은 절대 안 건드림
    ("PENDING", P, True, "replace"),         # 같은 날 재업로드 → 원자적 교체
    ("FAILED", P, False, "replace"),
    ("PENDING", OTHER, True, "discard"),     # 다른 날, 기존 파일 멀쩡
    ("FAILED", OTHER, False, "restore"),     # 다른 날, 기존 파일 없음 → 복구
])
def test_decide_duplicate_action(status, path, present, expected):
    assert decide_duplicate_action(status, path, P, present) == expected


def _box(t: bytes, payload: bytes = b"") -> bytes:
    return (8 + len(payload)).to_bytes(4, "big") + t + payload


def test_mp4_has_moov(tmp_path):
    ok = tmp_path / "ok.m4a"
    ok.write_bytes(_box(b"ftyp", b"M4A ") + _box(b"mdat", b"\0" * 100) + _box(b"moov", b"\0" * 8))
    assert mp4_has_moov(str(ok))

    cut = tmp_path / "cut.m4a"
    cut.write_bytes(_box(b"ftyp", b"M4A ") + (1000).to_bytes(4, "big") + b"mdat" + b"\0" * 50)
    assert not mp4_has_moov(str(cut))

    assert not mp4_has_moov(str(tmp_path / "missing.m4a"))


@pytest.mark.parametrize("ev", ["call_123", "a-b.c", "x" * 200])
def test_safe_event_id_ok(ev):
    assert safe_event_id(ev) == ev


@pytest.mark.parametrize("ev", ["../etc", "a/b", "a b", "", "x" * 201])
def test_safe_event_id_rejects(ev):
    with pytest.raises(HTTPException):
        safe_event_id(ev)
