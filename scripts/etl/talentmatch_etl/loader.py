"""Idempotent, set-based loader for cleaned ETL data (psycopg 3).

``load`` runs every statement on the given connection and NEVER commits or
rolls back; the caller owns the transaction. Each batch is deduplicated
before its statement so ON CONFLICT DO UPDATE never touches a row twice.
Conditional DO UPDATE ... WHERE clauses make no-op reruns write nothing
(so updated_at stays unchanged).

Counting: inserted = RETURNING rows with (xmax = 0); updated = other
returned rows; unchanged = batch size - returned rows.
Only candidate_skill / job_skill rows are ever deleted (sync pruning, scoped
to parents in the batch); candidate, job, skill and job_match never are.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass
from typing import TYPE_CHECKING, Any, Callable, Iterable, TypeVar
from uuid import UUID

import pandas as pd

from .cleaning import CleanResult, skill_key

if TYPE_CHECKING:  # pragma: no cover
    import psycopg

logger = logging.getLogger(__name__)

K = TypeVar("K")

TABLES = ("skill", "candidate", "job", "candidate_skill", "job_skill")


@dataclass
class TableStats:
    """Per-table row outcome counts for one load."""

    inserted: int = 0
    updated: int = 0
    unchanged: int = 0
    deleted: int = 0


# --------------------------------------------------------------------------
# SQL
# --------------------------------------------------------------------------

SQL_UPSERT_SKILL = """
INSERT INTO skill (name, category)
SELECT * FROM unnest(%s::text[], %s::text[])
ON CONFLICT ((lower(name))) DO UPDATE
    SET category = COALESCE(EXCLUDED.category, skill.category)
    WHERE skill.category IS DISTINCT FROM COALESCE(EXCLUDED.category, skill.category)
RETURNING (xmax = 0) AS inserted
"""

# Join on lower(name) (same expression as uq_skill_name_lower) and return the
# caller's key, so Python/PostgreSQL lower() differences cannot drop ids.
SQL_SKILL_IDS = """
SELECT u.k, s.id
FROM unnest(%s::text[], %s::text[]) AS u(k, n)
JOIN skill s ON lower(s.name) = lower(u.n)
"""

SQL_UPSERT_CANDIDATE = """
INSERT INTO candidate (full_name, email, summary)
SELECT * FROM unnest(%s::text[], %s::text[], %s::text[])
ON CONFLICT (email) DO UPDATE
    SET full_name = EXCLUDED.full_name, summary = EXCLUDED.summary
    WHERE (candidate.full_name, candidate.summary)
          IS DISTINCT FROM (EXCLUDED.full_name, EXCLUDED.summary)
RETURNING (xmax = 0) AS inserted
"""

SQL_CANDIDATE_IDS = "SELECT email, id FROM candidate WHERE email = ANY(%s::text[])"

# ETL jobs are MANUAL (the column default). Since V5 the natural key is the
# partial unique index uq_job_title_company_manual, and PostgreSQL only infers
# a partial index when ON CONFLICT repeats its predicate. FEED jobs (job feed)
# may share a title and company and are never touched by the ETL.
SQL_UPSERT_JOB = """
INSERT INTO job (title, company, description)
SELECT * FROM unnest(%s::text[], %s::text[], %s::text[])
ON CONFLICT (title, company) WHERE origin = 'MANUAL' DO UPDATE
    SET description = EXCLUDED.description
    WHERE job.description IS DISTINCT FROM EXCLUDED.description
RETURNING (xmax = 0) AS inserted
"""

SQL_JOB_IDS = """
SELECT j.title, j.company, j.id
FROM job j
JOIN unnest(%s::text[], %s::text[]) AS u(t, c) ON j.title = u.t AND j.company = u.c
WHERE j.origin = 'MANUAL'
"""

SQL_UPSERT_CANDIDATE_SKILL = """
INSERT INTO candidate_skill (candidate_id, skill_id, years_experience)
SELECT * FROM unnest(%s::uuid[], %s::uuid[], %s::int[])
ON CONFLICT (candidate_id, skill_id) DO UPDATE
    SET years_experience = EXCLUDED.years_experience
    WHERE candidate_skill.years_experience IS DISTINCT FROM EXCLUDED.years_experience
RETURNING (xmax = 0) AS inserted
"""

SQL_UPSERT_JOB_SKILL = """
INSERT INTO job_skill (job_id, skill_id, required)
SELECT * FROM unnest(%s::uuid[], %s::uuid[], %s::boolean[])
ON CONFLICT (job_id, skill_id) DO UPDATE
    SET required = EXCLUDED.required
    WHERE job_skill.required IS DISTINCT FROM EXCLUDED.required
RETURNING (xmax = 0) AS inserted
"""

SQL_PRUNE_CANDIDATE_SKILL = """
DELETE FROM candidate_skill cs
WHERE cs.candidate_id = ANY(%s::uuid[])
  AND NOT EXISTS (
      SELECT 1 FROM unnest(%s::uuid[], %s::uuid[]) AS k(c, s)
      WHERE k.c = cs.candidate_id AND k.s = cs.skill_id
  )
"""

SQL_PRUNE_JOB_SKILL = """
DELETE FROM job_skill js
WHERE js.job_id = ANY(%s::uuid[])
  AND NOT EXISTS (
      SELECT 1 FROM unnest(%s::uuid[], %s::uuid[]) AS k(j, s)
      WHERE k.j = js.job_id AND k.s = js.skill_id
  )
"""


# --------------------------------------------------------------------------
# Helpers
# --------------------------------------------------------------------------

def _val(v: Any) -> Any:
    """Map pandas NA/NaN to None; pass everything else through."""
    if v is None:
        return None
    try:
        if pd.isna(v):
            return None
    except (TypeError, ValueError):
        pass
    return v


def _rows(df: pd.DataFrame) -> list[dict[str, Any]]:
    if df is None or len(df) == 0:
        return []
    return [{k: _val(v) for k, v in r.items()} for r in df.to_dict("records")]


def _dedupe(rows: Iterable[dict[str, Any]], key: Callable[[dict[str, Any]], K],
            combine: Callable[[dict[str, Any], dict[str, Any]], None] | None = None,
            what: str = "rows") -> list[dict[str, Any]]:
    """Keep the first row per key (optionally folding later rows into it)."""
    seen: dict[K, dict[str, Any]] = {}
    dupes = 0
    for r in rows:
        k = key(r)
        if k in seen:
            dupes += 1
            if combine is not None:
                combine(seen[k], r)
        else:
            seen[k] = dict(r)
    if dupes:
        logger.warning("Loader collapsed %d duplicate %s in batch", dupes, what)
    return list(seen.values())


def _tally(stats: TableStats | None, returned: list[tuple[Any, ...]], batch: int) -> None:
    if stats is None:
        return
    inserted = sum(1 for (flag,) in returned if flag)
    stats.inserted += inserted
    stats.updated += len(returned) - inserted
    stats.unchanged += batch - len(returned)


def _opt_int(v: Any) -> int | None:
    return None if v is None else int(v)


def _years_combine(kept: dict[str, Any], new: dict[str, Any]) -> None:
    a, b = kept["years_experience"], new["years_experience"]
    kept["years_experience"] = b if a is None else (a if b is None else max(a, b))


def _required_combine(kept: dict[str, Any], new: dict[str, Any]) -> None:
    kept["required"] = bool(kept["required"] or new["required"])


# --------------------------------------------------------------------------
# Upserts
# --------------------------------------------------------------------------

def upsert_skills(cur: "psycopg.Cursor", df: pd.DataFrame,
                  stats: TableStats | None = None) -> dict[str, UUID]:
    """Upsert skills (never renames); return {skill_key(name): id}."""
    rows = _dedupe(_rows(df), key=lambda r: skill_key(r["name"]), what="skills")
    if not rows:
        return {}
    names = [r["name"] for r in rows]
    cats = [r["category"] for r in rows]
    cur.execute(SQL_UPSERT_SKILL, (names, cats))
    _tally(stats, cur.fetchall(), len(rows))

    keys = [skill_key(n) for n in names]
    cur.execute(SQL_SKILL_IDS, (keys, names))
    ids = {k: i for k, i in cur.fetchall()}
    _require_all(keys, ids, "skill")
    return ids


def upsert_candidates(cur: "psycopg.Cursor", df: pd.DataFrame,
                      stats: TableStats | None = None) -> dict[str, UUID]:
    """Upsert candidates by email; return {email: id}."""
    rows = _dedupe(_rows(df), key=lambda r: r["email"], what="candidates")
    if not rows:
        return {}
    emails = [r["email"] for r in rows]
    cur.execute(SQL_UPSERT_CANDIDATE, (
        [r["full_name"] for r in rows], emails, [r["summary"] for r in rows],
    ))
    _tally(stats, cur.fetchall(), len(rows))

    cur.execute(SQL_CANDIDATE_IDS, (emails,))
    ids = {e: i for e, i in cur.fetchall()}
    _require_all(emails, ids, "candidate")
    return ids


def upsert_jobs(cur: "psycopg.Cursor", df: pd.DataFrame,
                stats: TableStats | None = None) -> dict[tuple[str, str], UUID]:
    """Upsert MANUAL jobs by (title, company); return {(title, company): id}."""
    rows = _dedupe(_rows(df), key=lambda r: (r["title"], r["company"]), what="jobs")
    if not rows:
        return {}
    titles = [r["title"] for r in rows]
    companies = [r["company"] for r in rows]
    cur.execute(SQL_UPSERT_JOB, (titles, companies, [r["description"] for r in rows]))
    _tally(stats, cur.fetchall(), len(rows))

    cur.execute(SQL_JOB_IDS, (titles, companies))
    ids = {(t, c): i for t, c, i in cur.fetchall()}
    _require_all(list(zip(titles, companies)), ids, "job")
    return ids


def upsert_candidate_skills(cur: "psycopg.Cursor", df: pd.DataFrame,
                            candidate_ids: dict[str, UUID], skill_ids: dict[str, UUID],
                            stats: TableStats | None = None) -> list[tuple[UUID, UUID]]:
    """Upsert candidate_skill rows; return the (candidate_id, skill_id) pairs loaded."""
    resolved = []
    for r in _rows(df):
        resolved.append({
            "cid": _lookup(candidate_ids, r["email"], "candidate"),
            "sid": _lookup(skill_ids, r["skill_key"], "skill"),
            "years_experience": _opt_int(r["years_experience"]),
        })
    rows = _dedupe(resolved, key=lambda r: (r["cid"], r["sid"]),
                   combine=_years_combine, what="candidate_skill rows")
    if not rows:
        return []
    cids = [r["cid"] for r in rows]
    sids = [r["sid"] for r in rows]
    cur.execute(SQL_UPSERT_CANDIDATE_SKILL, (cids, sids, [r["years_experience"] for r in rows]))
    _tally(stats, cur.fetchall(), len(rows))
    return list(zip(cids, sids))


def upsert_job_skills(cur: "psycopg.Cursor", df: pd.DataFrame,
                      job_ids: dict[tuple[str, str], UUID], skill_ids: dict[str, UUID],
                      stats: TableStats | None = None) -> list[tuple[UUID, UUID]]:
    """Upsert job_skill rows; return the (job_id, skill_id) pairs loaded."""
    resolved = []
    for r in _rows(df):
        resolved.append({
            "jid": _lookup(job_ids, (r["title"], r["company"]), "job"),
            "sid": _lookup(skill_ids, r["skill_key"], "skill"),
            "required": True if r["required"] is None else bool(r["required"]),
        })
    rows = _dedupe(resolved, key=lambda r: (r["jid"], r["sid"]),
                   combine=_required_combine, what="job_skill rows")
    if not rows:
        return []
    jids = [r["jid"] for r in rows]
    sids = [r["sid"] for r in rows]
    cur.execute(SQL_UPSERT_JOB_SKILL, (jids, sids, [r["required"] for r in rows]))
    _tally(stats, cur.fetchall(), len(rows))
    return list(zip(jids, sids))


def prune_candidate_skills(cur: "psycopg.Cursor", parent_ids: list[UUID],
                           keep_pairs: list[tuple[UUID, UUID]]) -> int:
    """Delete candidate_skill rows of ``parent_ids`` not in ``keep_pairs``; return count."""
    if not parent_ids:
        return 0
    cur.execute(SQL_PRUNE_CANDIDATE_SKILL, (
        list(parent_ids), [c for c, _ in keep_pairs], [s for _, s in keep_pairs],
    ))
    return max(cur.rowcount, 0)


def prune_job_skills(cur: "psycopg.Cursor", parent_ids: list[UUID],
                     keep_pairs: list[tuple[UUID, UUID]]) -> int:
    """Delete job_skill rows of ``parent_ids`` not in ``keep_pairs``; return count."""
    if not parent_ids:
        return 0
    cur.execute(SQL_PRUNE_JOB_SKILL, (
        list(parent_ids), [j for j, _ in keep_pairs], [s for _, s in keep_pairs],
    ))
    return max(cur.rowcount, 0)


def _lookup(ids: dict[Any, UUID], key: Any, what: str) -> UUID:
    try:
        return ids[key]
    except KeyError:
        raise RuntimeError(f"no {what} id for key {key!r} (link references a parent not in batch)") from None


def _require_all(keys: Iterable[Any], ids: dict[Any, UUID], what: str) -> None:
    missing = [k for k in keys if k not in ids]
    if missing:
        raise RuntimeError(f"{len(missing)} {what} row(s) not found after upsert, e.g. {missing[0]!r}")


# --------------------------------------------------------------------------
# Entry point
# --------------------------------------------------------------------------

def load(conn: "psycopg.Connection", clean: CleanResult, *,
         sync_links: bool = True) -> dict[str, TableStats]:
    """Load a CleanResult in the caller's transaction; return per-table stats.

    With ``sync_links`` the skill links of every candidate/job in this batch
    are made to match the batch exactly (stale links deleted).
    """
    stats = {t: TableStats() for t in TABLES}
    with conn.cursor() as cur:
        skill_ids = upsert_skills(cur, clean.skills, stats["skill"])
        candidate_ids = upsert_candidates(cur, clean.candidates, stats["candidate"])
        job_ids = upsert_jobs(cur, clean.jobs, stats["job"])
        cand_pairs = upsert_candidate_skills(cur, clean.candidate_skills, candidate_ids,
                                             skill_ids, stats["candidate_skill"])
        job_pairs = upsert_job_skills(cur, clean.job_skills, job_ids, skill_ids,
                                      stats["job_skill"])
        if sync_links:
            stats["candidate_skill"].deleted = prune_candidate_skills(
                cur, list(candidate_ids.values()), cand_pairs)
            stats["job_skill"].deleted = prune_job_skills(
                cur, list(job_ids.values()), job_pairs)
    for table, s in stats.items():
        logger.info("%s: inserted=%d updated=%d unchanged=%d deleted=%d",
                    table, s.inserted, s.updated, s.unchanged, s.deleted)
    return stats
