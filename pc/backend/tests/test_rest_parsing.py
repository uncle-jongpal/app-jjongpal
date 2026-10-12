import pytest

from app.rest import COLUMNS, parse_limit, parse_op, parse_order, select_columns, strip_op


def test_select_columns_default_is_full():
    assert select_columns("summaries", None) == COLUMNS["summaries"]
    assert select_columns("summaries", "") == COLUMNS["summaries"]
    assert select_columns("summaries", "*") == COLUMNS["summaries"]


def test_select_columns_keeps_request_order_and_dedupes():
    assert select_columns("summaries", "pinned,id,id,summary") == ["pinned", "id", "summary"]


def test_select_columns_drops_unknown_and_injection():
    assert select_columns("summaries", 'id,"x"; drop table users,summary') == ["id", "summary"]


def test_select_columns_all_unknown_falls_back_to_full():
    assert select_columns("summaries", "nope,also_nope") == COLUMNS["summaries"]


@pytest.mark.parametrize("value,expected", [
    (None, ("created_at", "DESC")),
    ("created_at.asc", ("created_at", "ASC")),
    ("pushed_at.desc", ("pushed_at", "DESC")),
    ("pushed_at", ("pushed_at", "ASC")),
    ("created_at.DESC", ("created_at", "DESC")),
    ("summary.desc", ("created_at", "DESC")),          # 화이트리스트 밖 → 기본값
    ("created_at.sideways", ("created_at", "DESC")),   # 방향 이상 → 기본값
    ("created_at; drop table x.asc", ("created_at", "DESC")),
    ("pushed_at.asc,created_at.desc", ("pushed_at", "ASC")),  # 첫 정렬만
])
def test_parse_order(value, expected):
    assert parse_order(value, "summaries", "created_at", "desc") == expected


@pytest.mark.parametrize("value,expected", [
    (None, 50), (10, 10), ("20", 20), ("abc", 50), (0, 1), (-5, 1), (10**9, 100000),
])
def test_parse_limit(value, expected):
    assert parse_limit(value, 50) == expected


@pytest.mark.parametrize("value,expected", [
    ("eq.5", ("=", "5")),
    ("neq.x", ("<>", "x")),
    ("gte.2026-01-01T00:00:00", (">=", "2026-01-01T00:00:00")),
    ("lt.3", ("<", "3")),
    ("lte.3", ("<=", "3")),
    ("gt.3", (">", "3")),
    ("5", ("=", "5")),
    ("like.%a%", ("=", "like.%a%")),  # 모르는 연산자는 값 통째로 등호 비교
    ("eq.a.b", ("=", "a.b")),
])
def test_parse_op(value, expected):
    assert parse_op(value) == expected


def test_strip_op():
    assert strip_op("eq.abc", "eq") == "abc"
    assert strip_op("abc", "eq") == "abc"
    assert strip_op("neq.abc", "eq") == "neq.abc"
