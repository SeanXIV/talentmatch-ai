"""Checklist 11-15: generate + clean_and_load against a scratch PostgreSQL DB.

Uses the session ``scratch_db`` fixture (see conftest.py); skipped when the
database is unreachable. The main ``talentmatch`` database is never written.
"""

from __future__ import annotations

import csv
import json
from pathlib import Path

import pytest

from conftest import DATA_TABLES, all_counts, count, db_env, run_script
from talentmatch_etl.cleaning import clean_bundle, normalize_email, normalize_text
from talentmatch_etl.contract import read_raw
from talentmatch_etl.loader import load
from talentmatch_etl.sources.synthetic import generate

N_CAND, N_JOBS = 60, 20


# --------------------------------------------------------------------------
# helpers
# --------------------------------------------------------------------------

@pytest.fixture(scope="module")
def dirty_raw(tmp_path_factory) -> tuple[Path, dict]:
    out = tmp_path_factory.mktemp("dirty_raw")
    m = generate(out, seed=42, n_candidates=N_CAND, n_jobs=N_JOBS, dirty_ratio=0.2)
    return out, m


@pytest.fixture
def clean_raw(tmp_path) -> tuple[Path, dict]:
    out = tmp_path / "clean_raw"
    m = generate(out, seed=11, n_candidates=15, n_jobs=5, dirty_ratio=0.0)
    return out, m


def run_load(raw: Path, reports: Path, *extra: str, **env: str):
    return run_script("clean_and_load.py", "--input", str(raw), "--reports", str(reports),
                      "--env-file", str(reports / "no-such.env"), *extra, env=db_env(**env))


def ok_load(raw: Path, reports: Path, *extra: str) -> dict:
    res = run_load(raw, reports, *extra)
    assert res.returncode == 0, f"exit {res.returncode}\nSTDOUT:\n{res.stdout}\nSTDERR:\n{res.stderr}"
    return json.loads((reports / "summary.json").read_text(encoding="utf-8"))


def read_rows(path: Path) -> list[dict[str, str]]:
    with path.open(encoding="utf-8", newline="") as fh:
        return list(csv.DictReader(fh))


def write_rows(path: Path, rows: list[dict[str, str]]) -> None:
    with path.open("w", encoding="utf-8", newline="") as fh:
        w = csv.DictWriter(fh, fieldnames=list(rows[0].keys()), lineterminator="\n")
        w.writeheader()
        w.writerows(rows)


def snapshot(conn) -> dict[str, list]:
    return {
        "candidate": conn.execute("SELECT email, updated_at FROM candidate ORDER BY email").fetchall(),
        "job": conn.execute("SELECT title, company, updated_at FROM job ORDER BY 1, 2").fetchall(),
        "skill": conn.execute("SELECT name, category, updated_at FROM skill ORDER BY 1").fetchall(),
        "candidate_skill": conn.execute(
            "SELECT candidate_id, skill_id, years_experience FROM candidate_skill ORDER BY 1, 2").fetchall(),
        "job_skill": conn.execute(
            "SELECT job_id, skill_id, required FROM job_skill ORDER BY 1, 2").fetchall(),
    }


# --------------------------------------------------------------------------
# 11. generate + load counts
# --------------------------------------------------------------------------

def test_generate_and_load_counts(db, dirty_raw, tmp_path):
    raw, m = dirty_raw
    summary = ok_load(raw, tmp_path / "rep")
    assert summary["status"] == "ok"
    assert count(db, "candidate") == N_CAND
    assert count(db, "job") == N_JOBS
    assert db.execute("SELECT count(*) FROM candidate WHERE email <> lower(btrim(email))"
                      ).fetchone()[0] == 0
    n_skill, n_distinct = db.execute(
        "SELECT count(*), count(DISTINCT lower(name)) FROM skill").fetchone()
    assert n_skill == n_distinct

    exp = m["expected_counts"]
    assert {
        "skills": count(db, "skill"), "candidates": count(db, "candidate"),
        "jobs": count(db, "job"), "candidate_skills": count(db, "candidate_skill"),
        "job_skills": count(db, "job_skill"),
    } == exp
    assert summary["rejects_by_rule"] == m["expected_rejects"]
    assert summary["clean_counts"] == exp
    load_stats = summary["load"]
    assert load_stats["candidate"]["inserted"] == N_CAND
    assert load_stats["job"]["inserted"] == N_JOBS
    assert load_stats["skill"]["inserted"] == exp["skills"]
    assert all(s["updated"] == 0 and s["deleted"] == 0 for s in load_stats.values())


def test_uncataloged_skills_null_category(db, dirty_raw, tmp_path):
    raw, m = dirty_raw
    ok_load(raw, tmp_path / "rep")
    null_cat = {r[0].lower() for r in db.execute(
        "SELECT name FROM skill WHERE category IS NULL").fetchall()}
    assert null_cat == {n.lower() for n in m["uncataloged_skills"]}


def test_candidate_skill_links_resolve_to_expected_candidates(db, dirty_raw, tmp_path):
    raw, _ = dirty_raw
    ok_load(raw, tmp_path / "rep")
    clean = clean_bundle(read_raw(raw))
    db_pairs = set(db.execute(
        "SELECT c.email, lower(s.name), cs.years_experience FROM candidate_skill cs "
        "JOIN candidate c ON c.id = cs.candidate_id JOIN skill s ON s.id = cs.skill_id").fetchall())
    expected = {(r.email, r.skill_key, r.years_experience)
                for r in clean.candidate_skills.itertuples()}
    assert db_pairs == expected


# --------------------------------------------------------------------------
# 12. rejected refs are reported and absent from DB
# --------------------------------------------------------------------------

def test_rejected_refs_in_rejects_csv_and_absent_from_db(db, dirty_raw, tmp_path):
    raw, m = dirty_raw
    rep = tmp_path / "rep"
    ok_load(raw, rep)
    rejects = read_rows(rep / "rejects.csv")
    by_rule: dict[str, set[str]] = {}
    for r in rejects:
        by_rule.setdefault(r["rule"], set()).add(r["ref"])
    assert sum(len([r for r in rejects if r["rule"] == k]) for k in by_rule) == len(rejects)

    d = m["defects"]
    # defect type -> rule for rows that must be rejected
    must_reject = {
        "blank_full_name": "blank_full_name", "invalid_email": "invalid_email",
        "duplicate_candidate_email_variant": "duplicate_natural_key",
        "blank_title": "blank_title", "blank_company": "blank_company",
        "duplicate_job_whitespace_variant": "duplicate_natural_key",
        "orphan_ref": "orphan_parent", "duplicate_candidate_exact": "duplicate_row",
        "duplicate_job_exact": "duplicate_row", "blank_skill_name": "blank_skill_name",
        "negative_years": "invalid_years", "non_numeric_years": "invalid_years",
        "invalid_required": "invalid_required",
    }
    for defect, rule in must_reject.items():
        missing = set(d[defect]) - by_rule.get(rule, set())
        assert not missing, f"{defect}: refs not in rejects.csv as {rule}: {sorted(missing)}"

    cand_rows = {r["candidate_ref"]: r for r in read_rows(raw / "candidates.csv")
                 if r["candidate_ref"].startswith("CX")}
    db_emails = {e for (e,) in db.execute("SELECT email FROM candidate").fetchall()}
    for ref in d["blank_full_name"] + d["invalid_email"]:
        email = normalize_email(cand_rows[ref]["email"])
        assert email not in db_emails, f"{ref} ({email}) loaded despite reject"

    job_rows = {r["job_ref"]: r for r in read_rows(raw / "jobs.csv")
                if r["job_ref"].startswith("JX")}
    db_jobs = set(db.execute("SELECT title, company FROM job").fetchall())
    for ref in d["blank_title"] + d["blank_company"]:
        key = (normalize_text(job_rows[ref]["title"]), normalize_text(job_rows[ref]["company"]))
        assert key not in db_jobs
    db_companies = {c for (_, c) in db_jobs}
    for ref in d["blank_title"]:
        # blank_title rows use a fresh fake company that only that row carries
        comp = normalize_text(job_rows[ref]["company"])
        clean_companies = {normalize_text(r["company"]) for r in read_rows(raw / "jobs.csv")
                           if r["job_ref"].startswith("J0")}
        if comp not in clean_companies:
            assert comp not in db_companies


# --------------------------------------------------------------------------
# 13. idempotency
# --------------------------------------------------------------------------

def test_rerun_is_noop(db, dirty_raw, tmp_path):
    raw, _ = dirty_raw
    ok_load(raw, tmp_path / "r1")
    before_counts, before = all_counts(db), snapshot(db)
    summary = ok_load(raw, tmp_path / "r2")
    for table, s in summary["load"].items():
        assert (s["inserted"], s["updated"], s["deleted"]) == (0, 0, 0), (table, s)
        assert s["unchanged"] > 0, (table, s)
    assert all_counts(db) == before_counts
    assert snapshot(db) == before


# --------------------------------------------------------------------------
# 14. single-row change and link sync
# --------------------------------------------------------------------------

def test_changed_summary_updates_exactly_one_candidate(db, clean_raw, tmp_path):
    raw, _ = clean_raw
    ok_load(raw, tmp_path / "r1")
    rows = read_rows(raw / "candidates.csv")
    target = next(r for r in rows if r["candidate_ref"] == "C00001")
    target["summary"] = "CHANGED SUMMARY for test"
    write_rows(raw / "candidates.csv", rows)
    email = normalize_email(target["email"])
    old_ts = db.execute("SELECT updated_at FROM candidate WHERE email = %s", (email,)).fetchone()[0]

    s = ok_load(raw, tmp_path / "r2")["load"]
    assert s["candidate"] == {"inserted": 0, "updated": 1, "unchanged": 14, "deleted": 0}
    for t in ("skill", "job", "candidate_skill", "job_skill"):
        assert (s[t]["inserted"], s[t]["updated"], s[t]["deleted"]) == (0, 0, 0), t
    summary, new_ts = db.execute(
        "SELECT summary, updated_at FROM candidate WHERE email = %s", (email,)).fetchone()
    assert summary == "CHANGED SUMMARY for test"
    assert new_ts > old_ts


def test_removed_link_pruned_unless_no_sync(db, clean_raw, tmp_path):
    raw, m = clean_raw
    ok_load(raw, tmp_path / "r1")
    n_links = count(db, "candidate_skill")
    assert n_links == m["expected_counts"]["candidate_skills"]

    links = read_rows(raw / "candidate_skills.csv")
    victim = next(r for r in links if r["candidate_ref"] == "C00001")
    links.remove(victim)
    write_rows(raw / "candidate_skills.csv", links)

    s = ok_load(raw, tmp_path / "r2", "--no-sync-links")["load"]
    assert s["candidate_skill"]["deleted"] == 0
    assert count(db, "candidate_skill") == n_links

    s = ok_load(raw, tmp_path / "r3")["load"]
    assert s["candidate_skill"]["deleted"] == 1
    assert s["job_skill"]["deleted"] == 0
    assert count(db, "candidate_skill") == n_links - 1
    gone = db.execute(
        "SELECT count(*) FROM candidate_skill cs JOIN candidate c ON c.id = cs.candidate_id "
        "JOIN skill s ON s.id = cs.skill_id WHERE lower(s.name) = lower(%s)",
        (victim["skill_name"].strip(),)).fetchone()[0]
    assert gone == sum(1 for r in links if r["skill_name"].strip().lower()
                       == victim["skill_name"].strip().lower())


def test_prune_scoped_to_batch_parents(db, clean_raw, tmp_path):
    """Links of candidates absent from the batch are never deleted."""
    raw, _ = clean_raw
    ok_load(raw, tmp_path / "r1")
    n_cand, n_links = count(db, "candidate"), count(db, "candidate_skill")
    cands = [r for r in read_rows(raw / "candidates.csv") if r["candidate_ref"] != "C00002"]
    links = [r for r in read_rows(raw / "candidate_skills.csv") if r["candidate_ref"] != "C00002"]
    write_rows(raw / "candidates.csv", cands)
    write_rows(raw / "candidate_skills.csv", links)
    s = ok_load(raw, tmp_path / "r2")["load"]
    assert s["candidate_skill"]["deleted"] == 0
    assert count(db, "candidate") == n_cand
    assert count(db, "candidate_skill") == n_links


# --------------------------------------------------------------------------
# 15. dry-run / strict / bad password
# --------------------------------------------------------------------------

def test_dry_run_no_db_changes(db, dirty_raw, tmp_path):
    raw, m = dirty_raw
    rep = tmp_path / "rep"
    res = run_load(raw, rep, "--dry-run")
    assert res.returncode == 0, res.stderr
    assert set(all_counts(db).values()) == {0}
    summary = json.loads((rep / "summary.json").read_text(encoding="utf-8"))
    assert summary["status"] == "dry_run" and summary["load"] is None
    assert summary["rejects_by_rule"] == m["expected_rejects"]
    assert len(read_rows(rep / "rejects.csv")) == sum(m["expected_rejects"].values())


def test_dry_run_works_with_unreachable_db(dirty_raw, tmp_path):
    raw, _ = dirty_raw
    res = run_load(raw, tmp_path / "rep", "--dry-run", DB_HOST="127.0.0.1", DB_PORT="1")
    assert res.returncode == 0, res.stderr


def test_strict_with_rejects_exits_2_and_loads_nothing(db, dirty_raw, tmp_path):
    raw, _ = dirty_raw
    rep = tmp_path / "rep"
    res = run_load(raw, rep, "--strict")
    assert res.returncode == 2, res.stderr
    assert set(all_counts(db).values()) == {0}
    summary = json.loads((rep / "summary.json").read_text(encoding="utf-8"))
    assert summary["status"] == "rejected_strict"
    assert (rep / "rejects.csv").is_file()


def test_strict_clean_input_loads(db, clean_raw, tmp_path):
    raw, m = clean_raw
    ok_load(raw, tmp_path / "rep", "--strict")
    assert count(db, "candidate") == m["expected_counts"]["candidates"]


def test_bad_password_exits_1_no_writes(db, dirty_raw, tmp_path):
    raw, _ = dirty_raw
    rep = tmp_path / "rep"
    res = run_load(raw, rep, DB_PASSWORD="definitely-wrong")
    assert res.returncode == 1, res.stdout + res.stderr
    assert "definitely-wrong" not in res.stdout + res.stderr
    assert set(all_counts(db).values()) == {0}
    summary = json.loads((rep / "summary.json").read_text(encoding="utf-8"))
    assert summary["status"] == "failed"


def test_contract_error_exits_1(tmp_path):
    (tmp_path / "raw").mkdir()
    res = run_load(tmp_path / "raw", tmp_path / "rep")
    assert res.returncode == 1


# --------------------------------------------------------------------------
# loader.load: caller owns the transaction
# --------------------------------------------------------------------------

def test_loader_never_commits(db, scratch_db, dirty_raw):
    import psycopg
    from conftest import _conninfo
    raw, m = dirty_raw
    clean = clean_bundle(read_raw(raw))
    with psycopg.connect(_conninfo(scratch_db)) as conn:
        stats = load(conn, clean)
        assert stats["candidate"].inserted == N_CAND
        assert conn.execute("SELECT count(*) FROM candidate").fetchone()[0] == N_CAND
        conn.rollback()
    assert set(all_counts(db).values()) == {0}


def test_loader_failure_mid_batch_leaves_nothing(db, scratch_db, dirty_raw):
    """A RuntimeError raised mid-load (link to unknown parent) rolls back everything."""
    import psycopg
    from conftest import _conninfo
    raw, _ = dirty_raw
    clean = clean_bundle(read_raw(raw))
    clean.candidate_skills.loc[0, "email"] = "nobody@nowhere.example"
    with pytest.raises(RuntimeError):
        with psycopg.connect(_conninfo(scratch_db)) as conn:
            load(conn, clean)
    assert set(all_counts(db).values()) == {0}


def test_existing_skill_spelling_not_renamed(db, scratch_db, clean_raw):
    import psycopg
    from conftest import _conninfo
    raw, _ = clean_raw
    db.execute("INSERT INTO skill (name, category) VALUES ('JAVA', NULL)")
    clean = clean_bundle(read_raw(raw))
    with psycopg.connect(_conninfo(scratch_db)) as conn:
        stats = load(conn, clean)
    assert db.execute("SELECT name, category FROM skill WHERE lower(name) = 'java'"
                      ).fetchall() == [("JAVA", "Languages")]
    assert stats["skill"].updated == 1


# --------------------------------------------------------------------------
# Phase 5 (V5): the ETL only touches MANUAL jobs
# --------------------------------------------------------------------------

def test_load_ignores_feed_jobs_with_the_same_title_and_company(db, scratch_db, clean_raw):
    """V5 partial key: FEED jobs sharing (title, company) are neither updated nor linked."""
    import psycopg
    from conftest import _conninfo
    raw, _ = clean_raw
    clean = clean_bundle(read_raw(raw))
    keys = sorted({(r["title"], r["company"]) for r in clean.jobs.to_dict("records")})
    for title, company in keys:
        db.execute("INSERT INTO job (title, company, description, origin, updated_at) "
                   "VALUES (%s, %s, 'feed text', 'FEED', now() - interval '1 day')", (title, company))
    feed_before = db.execute("SELECT id, description, updated_at FROM job WHERE origin = 'FEED' "
                             "ORDER BY id").fetchall()

    with psycopg.connect(_conninfo(scratch_db)) as conn:
        stats = load(conn, clean)
        conn.commit()
    assert stats["job"].inserted == len(keys)
    assert db.execute("SELECT count(*) FROM job WHERE origin = 'MANUAL'").fetchone()[0] == len(keys)
    assert db.execute("SELECT id, description, updated_at FROM job WHERE origin = 'FEED' "
                      "ORDER BY id").fetchall() == feed_before
    assert db.execute("SELECT count(*) FROM job_skill js JOIN job j ON j.id = js.job_id "
                      "WHERE j.origin = 'FEED'").fetchone()[0] == 0

    # a rerun is still a no-op for jobs (the partial-index ON CONFLICT matches the MANUAL rows)
    with psycopg.connect(_conninfo(scratch_db)) as conn:
        again = load(conn, clean)
        conn.commit()
    assert (again["job"].inserted, again["job"].updated) == (0, 0)
    assert db.execute("SELECT count(*) FROM job").fetchone()[0] == 2 * len(keys)
