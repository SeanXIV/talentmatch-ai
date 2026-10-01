"""Checklist 4-7: table cleaners and reject rules (no DB)."""

from __future__ import annotations

import math

import pandas as pd
import pytest

from conftest import make_df
from talentmatch_etl.cleaning import (
    clean_bundle,
    clean_candidate_skills,
    clean_candidates,
    clean_job_skills,
    clean_jobs,
    clean_skill_catalog,
)
from talentmatch_etl.contract import COLUMNS, RawBundle, empty_frame

CAND_COLS = COLUMNS["candidates.csv"]
JOB_COLS = COLUMNS["jobs.csv"]
SKILL_COLS = COLUMNS["skills.csv"]
CS_COLS = COLUMNS["candidate_skills.csv"]
JS_COLS = COLUMNS["job_skills.csv"]


def only(rejects, rule):
    found = [r for r in rejects if r.rule == rule]
    assert len(found) == 1, f"expected one {rule!r} reject, got {[(r.rule, r.line) for r in rejects]}"
    return found[0]


def assert_reject(rejects, rule, source_file, line, ref):
    r = only(rejects, rule)
    assert (r.source_file, r.line, r.ref) == (source_file, line, ref), r
    assert "_line" not in r.raw
    return r


def assert_no_nan(df: pd.DataFrame):
    for col in df.columns:
        for v in df[col]:
            assert not (isinstance(v, float) and math.isnan(v)), f"NaN in {col}"


# ---- 4. each rule id --------------------------------------------------------

def test_candidate_rules():
    df = make_df(CAND_COLS, [
        ["C1", "Ann", "ann@x.com", ""],             # 2 ok
        ["", "Bob", "bob@x.com", ""],               # 3 missing_ref
        ["C3", "  ", "c3@x.com", ""],               # 4 blank_full_name
        ["C4", "Dan", "   ", ""],                   # 5 blank_email
        ["C5", "Eve", "eve@example", ""],           # 6 invalid_email
        ["C6", "F" * 201, "f@x.com", ""],           # 7 too_long
        ["C1", "Ann", " ANN@x.com", ""],            # 8 duplicate_row (same normalized values)
        ["C1", "Ann", "other@x.com", ""],           # 9 ref_conflict
        ["C9", "Ann Two", "Ann@X.com", "s"],        # 10 duplicate_natural_key
    ])
    out, rejects, ref_to_email = clean_candidates(df)
    src = "candidates.csv"
    assert_reject(rejects, "missing_ref", src, 3, None)
    assert_reject(rejects, "blank_full_name", src, 4, "C3")
    assert_reject(rejects, "blank_email", src, 5, "C4")
    assert_reject(rejects, "invalid_email", src, 6, "C5")
    assert_reject(rejects, "too_long", src, 7, "C6")
    assert_reject(rejects, "duplicate_row", src, 8, "C1")
    assert_reject(rejects, "ref_conflict", src, 9, "C1")
    assert_reject(rejects, "duplicate_natural_key", src, 10, "C9")
    assert len(rejects) == 8
    assert list(out["email"]) == ["ann@x.com"]
    assert ref_to_email == {"C1": "ann@x.com", "C9": "ann@x.com"}
    assert out["summary"].tolist() == [None]
    assert_no_nan(out)


def test_candidate_ref_conflict_same_email_different_values():
    df = make_df(CAND_COLS, [["C1", "Ann", "a@x.com", ""], ["C1", "Ann B", "a@x.com", ""]])
    _, rejects, _ = clean_candidates(df)
    assert_reject(rejects, "ref_conflict", "candidates.csv", 3, "C1")


def test_candidate_too_long_email():
    email = "a" * 320 + "@x.com"
    _, rejects, _ = clean_candidates(make_df(CAND_COLS, [["C1", "Ann", email, ""]]))
    assert_reject(rejects, "too_long", "candidates.csv", 2, "C1")


def test_job_rules():
    df = make_df(JOB_COLS, [
        ["J1", "Dev", "Acme", "d"],                 # 2 ok
        ["", "Dev", "Beta", ""],                    # 3 missing_ref
        ["J3", " ", "Acme", ""],                    # 4 blank_title
        ["J4", "Dev", "", ""],                      # 5 blank_company
        ["J5", "T" * 301, "Acme", ""],              # 6 too_long
        ["J1", " Dev", "Acme ", "d"],               # 7 duplicate_row
        ["J1", "Ops", "Acme", "d"],                 # 8 ref_conflict
        ["J8", "Dev", "  Acme", "other"],           # 9 duplicate_natural_key
    ])
    out, rejects, ref_to_jobkey = clean_jobs(df)
    src = "jobs.csv"
    assert_reject(rejects, "missing_ref", src, 3, None)
    assert_reject(rejects, "blank_title", src, 4, "J3")
    assert_reject(rejects, "blank_company", src, 5, "J4")
    assert_reject(rejects, "too_long", src, 6, "J5")
    assert_reject(rejects, "duplicate_row", src, 7, "J1")
    assert_reject(rejects, "ref_conflict", src, 8, "J1")
    assert_reject(rejects, "duplicate_natural_key", src, 9, "J8")
    assert len(rejects) == 7
    assert out[["title", "company", "description"]].values.tolist() == [["Dev", "Acme", "d"]]
    assert ref_to_jobkey == {"J1": ("Dev", "Acme"), "J8": ("Dev", "Acme")}


def test_skill_catalog_rules():
    df = make_df(SKILL_COLS, [
        ["Java", "Languages"],                      # 2 ok
        ["  ", "x"],                                # 3 blank_skill_name
        [" JAVA ", "Other"],                        # 4 duplicate_catalog_skill
        ["S" * 101, ""],                            # 5 too_long
        ["Machine  Learning", ""],                  # 6 ok, collapsed, category None
    ])
    out, rejects = clean_skill_catalog(df)
    src = "skills.csv"
    r = only(rejects, "blank_skill_name")
    assert (r.source_file, r.line) == (src, 3)
    assert_reject(rejects, "duplicate_catalog_skill", src, 4, "JAVA")
    only(rejects, "too_long")
    assert out.values.tolist() == [["Java", "Languages"], ["Machine Learning", None]]


def test_candidate_skill_rules():
    ref_to_email = {"C1": "a@x.com"}
    df = make_df(CS_COLS, [
        ["C1", "Java", "3"],                        # 2 ok
        ["", "Java", "1"],                          # 3 missing_ref
        ["CZ", "Java", "1"],                        # 4 orphan_parent
        ["C1", "  ", "1"],                          # 5 blank_skill_name
        ["C1", "S" * 101, "1"],                     # 6 too_long
        ["C1", "Go", "-1"],                         # 7 invalid_years
        ["C1", "Go", "abc"],                        # 8 invalid_years
    ])
    out, rejects = clean_candidate_skills(df, ref_to_email)
    src = "candidate_skills.csv"
    assert_reject(rejects, "missing_ref", src, 3, None)
    assert_reject(rejects, "orphan_parent", src, 4, "CZ")
    assert_reject(rejects, "blank_skill_name", src, 5, "C1")
    assert_reject(rejects, "too_long", src, 6, "C1")
    inv = [r for r in rejects if r.rule == "invalid_years"]
    assert [(r.line, r.ref) for r in inv] == [(7, "C1"), (8, "C1")]
    assert out.values.tolist() == [["a@x.com", "java", "Java", 3]]


def test_job_skill_rules():
    ref_to_jobkey = {"J1": ("Dev", "Acme")}
    df = make_df(JS_COLS, [
        ["J1", "Java", "true"],                     # 2 ok
        ["", "Java", "true"],                       # 3 missing_ref
        ["JZ", "Java", "true"],                     # 4 orphan_parent
        ["J1", "", "true"],                         # 5 blank_skill_name
        ["J1", "Go", "maybe"],                      # 6 invalid_required
        ["J1", "S" * 101, "true"],                  # 7 too_long
        ["J1", "Rust", ""],                         # 8 ok, blank -> True
    ])
    out, rejects = clean_job_skills(df, ref_to_jobkey)
    src = "job_skills.csv"
    assert_reject(rejects, "missing_ref", src, 3, None)
    assert_reject(rejects, "orphan_parent", src, 4, "JZ")
    assert_reject(rejects, "blank_skill_name", src, 5, "J1")
    assert_reject(rejects, "invalid_required", src, 6, "J1")
    assert_reject(rejects, "too_long", src, 7, "J1")
    assert out.values.tolist() == [["Dev", "Acme", "java", "Java", True],
                                   ["Dev", "Acme", "rust", "Rust", True]]


def test_all_rule_ids_covered_by_clean_bundle():
    bundle = RawBundle(
        skills=make_df(SKILL_COLS, [["Java", "L"], ["java", "L"], ["", ""]]),
        candidates=make_df(CAND_COLS, [
            ["C1", "Ann", "a@x.com", ""], ["", "B", "b@x.com", ""], ["C3", "", "c@x.com", ""],
            ["C4", "D", "", ""], ["C5", "E", "bad", ""], ["C6", "F" * 201, "f@x.com", ""],
            ["C1", "Ann", "a@x.com", ""], ["C1", "Ann", "z@x.com", ""], ["C9", "Ann", "A@x.com", ""],
        ]),
        jobs=make_df(JOB_COLS, [["J1", "Dev", "Acme", ""], ["J2", "", "Acme", ""],
                                ["J3", "Dev", " ", ""]]),
        candidate_skills=make_df(CS_COLS, [["C1", "Java", "x"], ["C3", "Java", "1"]]),
        job_skills=make_df(JS_COLS, [["J1", "Java", "maybe"]]),
    )
    result = clean_bundle(bundle)
    expected = {"missing_ref", "blank_full_name", "blank_email", "invalid_email", "too_long",
                "blank_title", "blank_company", "duplicate_row", "ref_conflict",
                "duplicate_natural_key", "blank_skill_name", "invalid_years",
                "invalid_required", "orphan_parent", "duplicate_catalog_skill"}
    assert set(result.rejects_by_rule()) == expected
    assert sum(result.rejects_by_rule().values()) == len(result.rejects)


# ---- 5. merges --------------------------------------------------------------

def test_email_variant_first_wins_and_alias_skills_merge():
    cands = make_df(CAND_COLS, [
        ["C1", "Ann", "ann@x.com", "first"],
        ["C2", "Ann Other", "  ANN@X.COM ", "second"],
    ])
    out, rejects, ref_to_email = clean_candidates(cands)
    assert out.values.tolist() == [["ann@x.com", "Ann", "first"]]
    assert_reject(rejects, "duplicate_natural_key", "candidates.csv", 3, "C2")

    links = make_df(CS_COLS, [["C1", "Java", "2"], ["C2", "Python", "4"], ["C2", "java", "9"]])
    merges: list = []
    cs, cs_rejects = clean_candidate_skills(links, ref_to_email, merges=merges)
    assert cs_rejects == []
    rows = sorted(cs[["email", "skill_key", "years_experience"]].values.tolist())
    assert rows == [["ann@x.com", "java", 9], ["ann@x.com", "python", 4]]
    assert len(merges) == 1 and merges[0]["lines"] == [2, 4]


def test_duplicate_skill_in_parent_max_years():
    links = make_df(CS_COLS, [["C1", "Java", "3"], ["C1", " java", "5"], ["C1", "JAVA", ""]])
    cs, rejects = clean_candidate_skills(links, {"C1": "a@x.com"})
    assert rejects == []
    assert cs.values.tolist() == [["a@x.com", "java", "Java", 5]]


def test_duplicate_skill_years_none_lowest():
    links = make_df(CS_COLS, [["C1", "Go", ""], ["C1", "go", ""]])
    cs, _ = clean_candidate_skills(links, {"C1": "a@x.com"})
    assert cs["years_experience"].tolist() == [None]
    assert_no_nan(cs)


def test_duplicate_skill_in_job_required_or():
    links = make_df(JS_COLS, [["J1", "Java", "false"], ["J1", "JAVA", "yes"], ["J1", "Go", "no"],
                              ["J1", " go", "0"]])
    js, rejects = clean_job_skills(links, {"J1": ("Dev", "Acme")})
    assert rejects == []
    assert js[["skill_key", "required"]].values.tolist() == [["java", True], ["go", False]]
    assert all(type(v) is bool for v in js["required"])


def test_link_spelling_uses_catalog():
    catalog, _ = clean_skill_catalog(make_df(SKILL_COLS, [["PostgreSQL", "Data"]]))
    links = make_df(CS_COLS, [["C1", " postgresql ", "1"], ["C1", "Elixir", "2"]])
    cs, _ = clean_candidate_skills(links, {"C1": "a@x.com"}, catalog)
    assert cs["skill_name"].tolist() == ["PostgreSQL", "Elixir"]


def test_build_skill_set_union_with_uncataloged_category_none():
    bundle = RawBundle(
        skills=make_df(SKILL_COLS, [["Java", "Languages"]]),
        candidates=make_df(CAND_COLS, [["C1", "Ann", "a@x.com", ""]]),
        jobs=make_df(JOB_COLS, [["J1", "Dev", "Acme", ""]]),
        candidate_skills=make_df(CS_COLS, [["C1", "JAVA", "1"], ["C1", "Elixir", ""]]),
        job_skills=make_df(JS_COLS, [["J1", "elixir", "true"]]),
    )
    result = clean_bundle(bundle)
    assert result.skills.values.tolist() == [["Java", "Languages"], ["Elixir", None]]
    assert result.counts() == {"skills": 2, "candidates": 1, "jobs": 1,
                               "candidate_skills": 2, "job_skills": 1}


# ---- 6. child of rejected parent -------------------------------------------

def test_child_of_rejected_parent_is_orphan():
    bundle = RawBundle(
        skills=empty_frame(SKILL_COLS),
        candidates=make_df(CAND_COLS, [["C1", "", "a@x.com", ""]]),       # blank_full_name
        jobs=make_df(JOB_COLS, [["J1", "Dev", "", ""]]),                  # blank_company
        candidate_skills=make_df(CS_COLS, [["C1", "Java", "1"]]),
        job_skills=make_df(JS_COLS, [["J1", "Java", "true"]]),
    )
    result = clean_bundle(bundle)
    assert_reject(result.rejects, "blank_full_name", "candidates.csv", 2, "C1")
    orphans = [r for r in result.rejects if r.rule == "orphan_parent"]
    assert sorted((r.source_file, r.line, r.ref) for r in orphans) == [
        ("candidate_skills.csv", 2, "C1"), ("job_skills.csv", 2, "J1")]
    assert result.counts() == {"skills": 0, "candidates": 0, "jobs": 0,
                               "candidate_skills": 0, "job_skills": 0}


# ---- 7. job natural key ----------------------------------------------------

def test_job_title_company_trimmed_only_case_variants_separate():
    df = make_df(JOB_COLS, [
        ["J1", "  Senior  Dev ", " Acme Corp ", ""],
        ["J2", "senior  dev", "Acme Corp", ""],
        ["J3", "Senior  Dev", "ACME CORP", ""],
    ])
    out, rejects, _ = clean_jobs(df)
    assert rejects == []
    assert out[["title", "company"]].values.tolist() == [
        ["Senior  Dev", "Acme Corp"], ["senior  dev", "Acme Corp"], ["Senior  Dev", "ACME CORP"]]
    assert out["description"].tolist() == [None, None, None]


def test_empty_inputs():
    result = clean_bundle(RawBundle(
        skills=empty_frame(SKILL_COLS), candidates=empty_frame(CAND_COLS),
        jobs=empty_frame(JOB_COLS), candidate_skills=empty_frame(CS_COLS),
        job_skills=empty_frame(JS_COLS)))
    assert result.rejects == []
    assert set(result.counts().values()) == {0}
