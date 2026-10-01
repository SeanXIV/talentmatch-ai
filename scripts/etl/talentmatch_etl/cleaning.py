"""Pure cleaning/validation of raw contract frames (no database access).

Each table cleaner processes rows in file order, rejects at most once per row
(first failing rule wins) and returns object-dtype frames whose values are
plain Python ``str``/``int``/``bool``/``None`` (never NaN).

Rule ids (``Reject.rule``): missing_ref, blank_full_name, blank_email,
invalid_email, too_long, blank_title, blank_company, duplicate_row,
ref_conflict, duplicate_natural_key, blank_skill_name, invalid_years,
invalid_required, orphan_parent, duplicate_catalog_skill.
"""

from __future__ import annotations

import logging
import re
from dataclasses import dataclass, field
from typing import Any, Mapping

import pandas as pd

from .contract import FILES, LINE_COL, MAX_LEN, RawBundle
from .rejects import Reject

logger = logging.getLogger(__name__)

_EMAIL_RE = re.compile(r"[^@\s]+@[^@\s]+\.[^@\s]+")
_WS_RE = re.compile(r"\s+")
_INT_RE = re.compile(r"([+-]?\d+)(?:\.0*)?")
_TRUE = frozenset({"true", "t", "yes", "y", "1"})
_FALSE = frozenset({"false", "f", "no", "n", "0"})
MAX_YEARS = 60
MAX_EMAIL_LEN = 320

SKILL_COLUMNS = ["name", "category"]
CANDIDATE_COLUMNS = ["email", "full_name", "summary"]
JOB_COLUMNS = ["title", "company", "description"]
CANDIDATE_SKILL_COLUMNS = ["email", "skill_key", "skill_name", "years_experience"]
JOB_SKILL_COLUMNS = ["title", "company", "skill_key", "skill_name", "required"]


# --------------------------------------------------------------------------
# Scalar normalizers
# --------------------------------------------------------------------------

def normalize_text(s: Any) -> str | None:
    """Strip surrounding whitespace; empty -> None."""
    if s is None:
        return None
    s = str(s).strip()
    return s or None


def normalize_email(s: Any) -> str | None:
    """Strip and lowercase; empty -> None."""
    s = normalize_text(s)
    return s.lower() if s is not None else None


def is_valid_email(s: str | None) -> bool:
    """Basic shape check: local@domain.tld, no whitespace, <= 320 chars."""
    if not s or len(s) > MAX_EMAIL_LEN:
        return False
    return _EMAIL_RE.fullmatch(s) is not None


def normalize_skill_name(s: Any) -> str | None:
    """Strip and collapse internal whitespace runs to one space; empty -> None."""
    s = normalize_text(s)
    return _WS_RE.sub(" ", s) if s is not None else None


def skill_key(name: str) -> str:
    """Case-insensitive identity of a (normalized) skill name."""
    return name.lower()


def parse_bool(s: Any) -> bool:
    """Parse true/t/yes/y/1 and false/f/no/n/0 (case-insensitive); blank -> True."""
    v = "" if s is None else str(s).strip().lower()
    if v == "" or v in _TRUE:
        return True
    if v in _FALSE:
        return False
    raise ValueError(f"not a boolean: {s!r}")


def parse_years(s: Any) -> int | None:
    """Parse years of experience: blank -> None; "3" or "3.0" -> 3; range 0..60."""
    v = "" if s is None else str(s).strip()
    if v == "":
        return None
    m = _INT_RE.fullmatch(v)
    if m is None:
        raise ValueError(f"not an integer: {s!r}")
    n = int(m.group(1))
    if n < 0:
        raise ValueError(f"negative years: {n}")
    if n > MAX_YEARS:
        raise ValueError(f"years above {MAX_YEARS}: {n}")
    return n


# --------------------------------------------------------------------------
# Helpers
# --------------------------------------------------------------------------

def _records(df: pd.DataFrame) -> list[dict[str, Any]]:
    return df.to_dict("records") if len(df) else []


def _frame(records: list[dict[str, Any]], columns: list[str]) -> pd.DataFrame:
    """Build an object-dtype frame that preserves None/int/bool exactly."""
    return pd.DataFrame(
        {c: pd.Series([r[c] for r in records], dtype=object) for c in columns},
        columns=columns,
    )


def _line(row: Mapping[str, Any]) -> int:
    return int(row.get(LINE_COL, 0))


def _reject(source_file: str, row: Mapping[str, Any], ref: str | None,
            rule: str, detail: str) -> Reject:
    raw = {k: v for k, v in row.items() if k != LINE_COL}
    return Reject(source_file=source_file, line=_line(row), ref=ref,
                  rule=rule, detail=detail, raw=raw)


def _too_long(values: Mapping[str, str | None]) -> str | None:
    for col, val in values.items():
        limit = MAX_LEN[col]
        if val is not None and len(val) > limit:
            return f"{col} has {len(val)} characters (max {limit})"
    return None


def _catalog_spelling(catalog: pd.DataFrame | None) -> dict[str, str]:
    if catalog is None or len(catalog) == 0:
        return {}
    return {skill_key(n): n for n in catalog["name"]}


# --------------------------------------------------------------------------
# Table cleaners
# --------------------------------------------------------------------------

def clean_skill_catalog(df: pd.DataFrame) -> tuple[pd.DataFrame, list[Reject]]:
    """Clean skills.csv -> frame[name, category]; first spelling per key wins."""
    src = FILES["skills"]
    rejects: list[Reject] = []
    out: list[dict[str, Any]] = []
    seen: dict[str, int] = {}
    for row in _records(df):
        raw_name = row.get("skill_name")
        name = normalize_skill_name(raw_name)
        category = normalize_text(row.get("category"))
        if name is None:
            rejects.append(_reject(src, row, raw_name, "blank_skill_name", "skill_name is blank"))
            continue
        detail = _too_long({"skill_name": name, "category": category})
        if detail:
            rejects.append(_reject(src, row, name, "too_long", detail))
            continue
        key = skill_key(name)
        if key in seen:
            rejects.append(_reject(src, row, name, "duplicate_catalog_skill",
                                   f"same skill as line {seen[key]}; first entry kept"))
            continue
        seen[key] = _line(row)
        out.append({"name": name, "category": category})
    return _frame(out, SKILL_COLUMNS), rejects


def clean_candidates(
    df: pd.DataFrame,
) -> tuple[pd.DataFrame, list[Reject], dict[str, str]]:
    """Clean candidates.csv -> (frame[email, full_name, summary], rejects, ref->email).

    ``ref_to_email`` includes aliased refs (duplicate_natural_key) so their
    skill links merge into the kept candidate.
    """
    src = FILES["candidates"]
    rejects: list[Reject] = []
    kept: list[dict[str, Any]] = []
    ref_to_email: dict[str, str] = {}
    seen_refs: dict[str, tuple[tuple[Any, ...], int]] = {}
    owners: dict[str, tuple[str, int]] = {}  # email -> (ref, line) of kept row

    for row in _records(df):
        ref = normalize_text(row.get("candidate_ref"))
        if ref is None:
            rejects.append(_reject(src, row, None, "missing_ref", "candidate_ref is blank"))
            continue
        full_name = normalize_text(row.get("full_name"))
        if full_name is None:
            rejects.append(_reject(src, row, ref, "blank_full_name", "full_name is blank"))
            continue
        email = normalize_email(row.get("email"))
        if email is None:
            rejects.append(_reject(src, row, ref, "blank_email", "email is blank"))
            continue
        detail = _too_long({"full_name": full_name, "email": email})
        if detail:
            rejects.append(_reject(src, row, ref, "too_long", detail))
            continue
        if not is_valid_email(email):
            rejects.append(_reject(src, row, ref, "invalid_email", f"invalid email: {email!r}"))
            continue
        summary = normalize_text(row.get("summary"))
        values = (email, full_name, summary)
        line = _line(row)

        prev = seen_refs.get(ref)
        if prev is not None:
            prev_values, prev_line = prev
            if prev_values == values:
                rejects.append(_reject(src, row, ref, "duplicate_row",
                                       f"identical to line {prev_line}"))
            elif prev_values[0] != email:
                rejects.append(_reject(src, row, ref, "ref_conflict",
                                       f"ref used at line {prev_line} for email {prev_values[0]!r}"))
            else:
                rejects.append(_reject(src, row, ref, "ref_conflict",
                                       f"ref used at line {prev_line} with different values"))
            continue
        seen_refs[ref] = (values, line)

        owner = owners.get(email)
        if owner is not None:
            ref_to_email[ref] = email
            rejects.append(_reject(src, row, ref, "duplicate_natural_key",
                                   f"email already provided by ref {owner[0]} (line {owner[1]}); "
                                   f"ref aliased to kept row"))
            continue
        owners[email] = (ref, line)
        ref_to_email[ref] = email
        kept.append({"email": email, "full_name": full_name, "summary": summary})

    return _frame(kept, CANDIDATE_COLUMNS), rejects, ref_to_email


def clean_jobs(
    df: pd.DataFrame,
) -> tuple[pd.DataFrame, list[Reject], dict[str, tuple[str, str]]]:
    """Clean jobs.csv -> (frame[title, company, description], rejects, ref->(title, company)).

    Natural key is (title, company) after trim only (case-sensitive).
    """
    src = FILES["jobs"]
    rejects: list[Reject] = []
    kept: list[dict[str, Any]] = []
    ref_to_jobkey: dict[str, tuple[str, str]] = {}
    seen_refs: dict[str, tuple[tuple[Any, ...], int]] = {}
    owners: dict[tuple[str, str], tuple[str, int]] = {}

    for row in _records(df):
        ref = normalize_text(row.get("job_ref"))
        if ref is None:
            rejects.append(_reject(src, row, None, "missing_ref", "job_ref is blank"))
            continue
        title = normalize_text(row.get("title"))
        if title is None:
            rejects.append(_reject(src, row, ref, "blank_title", "title is blank"))
            continue
        company = normalize_text(row.get("company"))
        if company is None:
            rejects.append(_reject(src, row, ref, "blank_company", "company is blank"))
            continue
        detail = _too_long({"title": title, "company": company})
        if detail:
            rejects.append(_reject(src, row, ref, "too_long", detail))
            continue
        description = normalize_text(row.get("description"))
        key = (title, company)
        values = (title, company, description)
        line = _line(row)

        prev = seen_refs.get(ref)
        if prev is not None:
            prev_values, prev_line = prev
            if prev_values == values:
                rejects.append(_reject(src, row, ref, "duplicate_row",
                                       f"identical to line {prev_line}"))
            elif prev_values[:2] != key:
                rejects.append(_reject(src, row, ref, "ref_conflict",
                                       f"ref used at line {prev_line} for job {prev_values[:2]!r}"))
            else:
                rejects.append(_reject(src, row, ref, "ref_conflict",
                                       f"ref used at line {prev_line} with different values"))
            continue
        seen_refs[ref] = (values, line)

        owner = owners.get(key)
        if owner is not None:
            ref_to_jobkey[ref] = key
            rejects.append(_reject(src, row, ref, "duplicate_natural_key",
                                   f"(title, company) already provided by ref {owner[0]} "
                                   f"(line {owner[1]}); ref aliased to kept row"))
            continue
        owners[key] = (ref, line)
        ref_to_jobkey[ref] = key
        kept.append({"title": title, "company": company, "description": description})

    return _frame(kept, JOB_COLUMNS), rejects, ref_to_jobkey


def _max_years(a: int | None, b: int | None) -> int | None:
    if a is None:
        return b
    if b is None:
        return a
    return max(a, b)


def clean_candidate_skills(
    df: pd.DataFrame,
    ref_to_email: Mapping[str, str],
    catalog: pd.DataFrame | None = None,
    merges: list[dict[str, Any]] | None = None,
) -> tuple[pd.DataFrame, list[Reject]]:
    """Clean candidate_skills.csv -> frame[email, skill_key, skill_name, years_experience].

    Same candidate + same skill key merge into one row keeping MAX years
    (None lowest); merge info is appended to ``merges`` if given.
    """
    src = FILES["candidate_skills"]
    spelling = _catalog_spelling(catalog)
    rejects: list[Reject] = []
    merged: dict[tuple[str, str], dict[str, Any]] = {}
    lines: dict[tuple[str, str], list[int]] = {}

    for row in _records(df):
        ref = normalize_text(row.get("candidate_ref"))
        if ref is None:
            rejects.append(_reject(src, row, None, "missing_ref", "candidate_ref is blank"))
            continue
        email = ref_to_email.get(ref)
        if email is None:
            rejects.append(_reject(src, row, ref, "orphan_parent",
                                   "candidate_ref is unknown or its candidate was rejected"))
            continue
        name = normalize_skill_name(row.get("skill_name"))
        if name is None:
            rejects.append(_reject(src, row, ref, "blank_skill_name", "skill_name is blank"))
            continue
        detail = _too_long({"skill_name": name})
        if detail:
            rejects.append(_reject(src, row, ref, "too_long", detail))
            continue
        try:
            years = parse_years(row.get("years_experience"))
        except ValueError as exc:
            rejects.append(_reject(src, row, ref, "invalid_years", str(exc)))
            continue

        key = skill_key(name)
        k = (email, key)
        existing = merged.get(k)
        if existing is None:
            merged[k] = {"email": email, "skill_key": key,
                         "skill_name": spelling.get(key, name), "years_experience": years}
            lines[k] = [_line(row)]
        else:
            existing["years_experience"] = _max_years(existing["years_experience"], years)
            lines[k].append(_line(row))

    if merges is not None:
        for (email, key), ls in lines.items():
            if len(ls) > 1:
                merges.append({"source_file": src, "parent": email, "skill_key": key,
                               "lines": ls, "detail": "merged duplicate skill (max years kept)"})
    return _frame(list(merged.values()), CANDIDATE_SKILL_COLUMNS), rejects


def clean_job_skills(
    df: pd.DataFrame,
    ref_to_jobkey: Mapping[str, tuple[str, str]],
    catalog: pd.DataFrame | None = None,
    merges: list[dict[str, Any]] | None = None,
) -> tuple[pd.DataFrame, list[Reject]]:
    """Clean job_skills.csv -> frame[title, company, skill_key, skill_name, required].

    Same job + same skill key merge into one row with required = any(required).
    """
    src = FILES["job_skills"]
    spelling = _catalog_spelling(catalog)
    rejects: list[Reject] = []
    merged: dict[tuple[str, str, str], dict[str, Any]] = {}
    lines: dict[tuple[str, str, str], list[int]] = {}

    for row in _records(df):
        ref = normalize_text(row.get("job_ref"))
        if ref is None:
            rejects.append(_reject(src, row, None, "missing_ref", "job_ref is blank"))
            continue
        jobkey = ref_to_jobkey.get(ref)
        if jobkey is None:
            rejects.append(_reject(src, row, ref, "orphan_parent",
                                   "job_ref is unknown or its job was rejected"))
            continue
        name = normalize_skill_name(row.get("skill_name"))
        if name is None:
            rejects.append(_reject(src, row, ref, "blank_skill_name", "skill_name is blank"))
            continue
        detail = _too_long({"skill_name": name})
        if detail:
            rejects.append(_reject(src, row, ref, "too_long", detail))
            continue
        try:
            required = parse_bool(row.get("required"))
        except ValueError as exc:
            rejects.append(_reject(src, row, ref, "invalid_required", str(exc)))
            continue

        key = skill_key(name)
        k = (jobkey[0], jobkey[1], key)
        existing = merged.get(k)
        if existing is None:
            merged[k] = {"title": jobkey[0], "company": jobkey[1], "skill_key": key,
                         "skill_name": spelling.get(key, name), "required": required}
            lines[k] = [_line(row)]
        else:
            existing["required"] = bool(existing["required"] or required)
            lines[k].append(_line(row))

    if merges is not None:
        for (title, company, key), ls in lines.items():
            if len(ls) > 1:
                merges.append({"source_file": src, "parent": f"{title} @ {company}",
                               "skill_key": key, "lines": ls,
                               "detail": "merged duplicate skill (required = any)"})
    return _frame(list(merged.values()), JOB_SKILL_COLUMNS), rejects


def build_skill_set(
    catalog_df: pd.DataFrame,
    cand_links: pd.DataFrame,
    job_links: pd.DataFrame,
) -> pd.DataFrame:
    """Union of catalog skills and linked skills -> frame[name, category].

    Spelling/category come from the catalog when the key is cataloged,
    otherwise the first normalized spelling seen (category None).
    """
    out: dict[str, dict[str, Any]] = {}
    for row in _records(catalog_df):
        out.setdefault(skill_key(row["name"]), {"name": row["name"], "category": row["category"]})
    for links in (cand_links, job_links):
        if len(links) == 0:
            continue
        for name in links["skill_name"]:
            out.setdefault(skill_key(name), {"name": name, "category": None})
    return _frame(list(out.values()), SKILL_COLUMNS)


@dataclass
class CleanResult:
    """Cleaned, deduplicated data ready for loading, plus rejects and merge info."""

    skills: pd.DataFrame
    candidates: pd.DataFrame
    jobs: pd.DataFrame
    candidate_skills: pd.DataFrame
    job_skills: pd.DataFrame
    rejects: list[Reject] = field(default_factory=list)
    merges: list[dict[str, Any]] = field(default_factory=list)

    def counts(self) -> dict[str, int]:
        """Row counts of the clean tables."""
        return {
            "skills": len(self.skills),
            "candidates": len(self.candidates),
            "jobs": len(self.jobs),
            "candidate_skills": len(self.candidate_skills),
            "job_skills": len(self.job_skills),
        }

    def rejects_by_rule(self) -> dict[str, int]:
        """Reject counts keyed by rule id (sorted by rule)."""
        counts: dict[str, int] = {}
        for r in self.rejects:
            counts[r.rule] = counts.get(r.rule, 0) + 1
        return dict(sorted(counts.items()))


def clean_bundle(bundle: RawBundle) -> CleanResult:
    """Run every cleaner over a RawBundle."""
    rejects: list[Reject] = []
    merges: list[dict[str, Any]] = []

    catalog, r = clean_skill_catalog(bundle.skills)
    rejects += r
    candidates, r, ref_to_email = clean_candidates(bundle.candidates)
    rejects += r
    jobs, r, ref_to_jobkey = clean_jobs(bundle.jobs)
    rejects += r
    cand_links, r = clean_candidate_skills(bundle.candidate_skills, ref_to_email, catalog, merges)
    rejects += r
    job_links, r = clean_job_skills(bundle.job_skills, ref_to_jobkey, catalog, merges)
    rejects += r
    skills = build_skill_set(catalog, cand_links, job_links)

    result = CleanResult(skills=skills, candidates=candidates, jobs=jobs,
                         candidate_skills=cand_links, job_skills=job_links,
                         rejects=rejects, merges=merges)
    logger.info("Cleaned: %s; rejects=%d; merges=%d",
                result.counts(), len(rejects), len(merges))
    return result
