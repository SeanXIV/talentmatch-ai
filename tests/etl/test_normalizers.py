"""Checklist 1-3: scalar normalizers and parsers (no DB)."""

import pytest

from talentmatch_etl.cleaning import (
    is_valid_email,
    normalize_email,
    normalize_skill_name,
    normalize_text,
    parse_bool,
    parse_years,
    skill_key,
)


# ---- 1. email -------------------------------------------------------------

def test_normalize_email_strips_and_lowercases():
    assert normalize_email("  Foo@X.COM ") == "foo@x.com"


@pytest.mark.parametrize("blank", ["", "   ", "\t", None])
def test_normalize_email_blank_is_none(blank):
    assert normalize_email(blank) is None


@pytest.mark.parametrize("good", ["foo@x.com", "a.b+c@sub.example.org"])
def test_is_valid_email_accepts(good):
    assert is_valid_email(good)


@pytest.mark.parametrize("bad", ["foo", "a@b", "", None, "a b@x.com", "@x.com",
                                 "foo@", "a@@x.com", "x.example.com"])
def test_is_valid_email_rejects(bad):
    assert not is_valid_email(bad)


def test_is_valid_email_rejects_over_320_chars():
    email = "a" * 310 + "@example.com"  # 322 chars
    assert len(email) > 320
    assert not is_valid_email(email)
    ok = "a" * 308 + "@example.com"  # exactly 320
    assert len(ok) == 320
    assert is_valid_email(ok)


def test_normalize_text():
    assert normalize_text("  x  ") == "x"
    assert normalize_text("  ") is None
    assert normalize_text(None) is None


# ---- 2. skill names ---------------------------------------------------------

def test_normalize_skill_name_collapses_whitespace():
    assert normalize_skill_name(" Machine  Learning ") == "Machine Learning"
    assert normalize_skill_name("Machine\t\tLearning") == "Machine Learning"
    assert normalize_skill_name("   ") is None


def test_skill_key_case_insensitive():
    keys = {skill_key(normalize_skill_name(s)) for s in ("JAVA", " java", "Java")}
    assert keys == {"java"}


# ---- 3. bool / years --------------------------------------------------------

@pytest.mark.parametrize("value,expected", [
    ("", True), ("  ", True), (None, True), ("true", True), ("Yes", True), ("Y", True),
    ("1", True), ("T", True), ("No", False), ("false", False), ("0", False),
    ("n", False), (" F ", False),
])
def test_parse_bool(value, expected):
    assert parse_bool(value) is expected


@pytest.mark.parametrize("bad", ["maybe", "2", "unknown", "yess"])
def test_parse_bool_invalid(bad):
    with pytest.raises(ValueError):
        parse_bool(bad)


@pytest.mark.parametrize("value,expected", [
    ("", None), ("  ", None), (None, None), ("3", 3), ("3.0", 3), (" 7 ", 7),
    ("0", 0), ("60", 60), ("3.00", 3),
])
def test_parse_years(value, expected):
    assert parse_years(value) == expected


@pytest.mark.parametrize("bad", ["-1", "abc", "61", "3.5", "five", "~2", "n/a"])
def test_parse_years_invalid(bad):
    with pytest.raises(ValueError):
        parse_years(bad)
