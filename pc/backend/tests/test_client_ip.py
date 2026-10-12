import pytest

from app.common import normalize_ip, parse_client_ip


@pytest.mark.parametrize("raw,expected", [
    ("1.2.3.4", "1.2.3.4"),
    (" 1.2.3.4 ", "1.2.3.4"),
    ("1.2.3.4:5678", "1.2.3.4"),
    ("2001:db8::1", "2001:db8::1"),
    ("2001:DB8:0:0::1", "2001:db8::1"),
    ("[2001:db8::1]:443", "2001:db8::1"),
    ("fe80::1%eth0", "fe80::1"),
    ("::ffff:1.2.3.4", "1.2.3.4"),
    ("", None),
    (None, None),
    ("unknown", None),
    ("1.2.3.4'; drop table users; --", None),
    ("999.1.1.1", None),
    ("<script>", None),
])
def test_normalize_ip(raw, expected):
    assert normalize_ip(raw) == expected


def test_cf_header_wins_over_xff():
    assert parse_client_ip("9.9.9.9", "1.1.1.1, 2.2.2.2", "172.17.0.1") == "9.9.9.9"


def test_invalid_cf_falls_back_to_xff():
    assert parse_client_ip("garbage", "1.1.1.1", "172.17.0.1") == "1.1.1.1"


def test_xff_skips_bogus_entries():
    # 예전엔 맨 앞 'bogus' 를 그대로 디비 inet 에 넣어 500 이 났다
    assert parse_client_ip(None, "bogus, 3.3.3.3, 4.4.4.4", "172.17.0.1") == "3.3.3.3"


def test_all_invalid_xff_falls_back_to_peer():
    assert parse_client_ip(None, "bogus, also-bogus", "172.17.0.1") == "172.17.0.1"


def test_nothing_valid_returns_none():
    assert parse_client_ip("x", "y", "testclient") is None
    assert parse_client_ip(None, None, None) is None
