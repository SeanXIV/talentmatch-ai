"""Checklist 9-10: deterministic synthetic generator (no DB)."""

from __future__ import annotations

import csv
import filecmp
import json
from pathlib import Path

import pytest

from conftest import run_script
from talentmatch_etl.cleaning import clean_bundle, normalize_email
from talentmatch_etl.contract import FILES, read_raw
from talentmatch_etl.sources.base import SourceAdapter
from talentmatch_etl.sources.synthetic import ALL_DEFECTS, MANIFEST_FILE, SyntheticSource, generate

ALL_FILES = sorted(FILES.values()) + [MANIFEST_FILE]


def gen(out: Path, **kw) -> dict:
    params = dict(seed=42, n_candidates=60, n_jobs=20, dirty_ratio=0.2)
    params.update(kw)
    return generate(out, **params)


def read_rows(path: Path) -> list[dict[str, str]]:
    with path.open(encoding="utf-8", newline="") as fh:
        return list(csv.DictReader(fh))


# ---- 9. determinism ---------------------------------------------------------

def test_same_seed_byte_identical(tmp_path):
    gen(tmp_path / "a")
    gen(tmp_path / "b")
    match, mismatch, errors = filecmp.cmpfiles(tmp_path / "a", tmp_path / "b", ALL_FILES,
                                               shallow=False)
    assert mismatch == [] and errors == [], (mismatch, errors)
    assert sorted(match) == sorted(ALL_FILES)


def test_cli_same_seed_byte_identical(tmp_path):
    for d in ("a", "b"):
        res = run_script("generate_synthetic.py", "--seed", "7", "--candidates", "30",
                         "--jobs", "10", "--dirty-ratio", "0.1", "--out", str(tmp_path / d))
        assert res.returncode == 0, res.stderr
        assert "expected clean" in res.stdout
    _, mismatch, errors = filecmp.cmpfiles(tmp_path / "a", tmp_path / "b", ALL_FILES,
                                           shallow=False)
    assert mismatch == [] and errors == []


def test_different_seed_differs(tmp_path):
    gen(tmp_path / "a", seed=1)
    gen(tmp_path / "b", seed=2)
    assert (tmp_path / "a" / "candidates.csv").read_bytes() != \
        (tmp_path / "b" / "candidates.csv").read_bytes()


def test_csvs_use_lf_line_endings(tmp_path):
    gen(tmp_path)
    for name in FILES.values():
        assert b"\r\n" not in (tmp_path / name).read_bytes(), name


# ---- 10. dirty ratio --------------------------------------------------------

def test_dirty_ratio_zero_has_no_rejects(tmp_path):
    m = gen(tmp_path, dirty_ratio=0.0)
    assert m["expected_rejects"] == {}
    assert all(refs == [] for refs in m["defects"].values())
    assert m["uncataloged_skills"] == []
    result = clean_bundle(read_raw(tmp_path))
    assert result.rejects == []
    assert result.merges == []
    assert result.counts() == m["expected_counts"]
    assert m["expected_counts"]["candidates"] == 60
    assert m["expected_counts"]["jobs"] == 20


@pytest.mark.parametrize("seed,n_c,n_j,ratio", [
    (42, 60, 20, 0.2), (42, 200, 50, 0.1), (3, 5, 2, 0.05), (99, 10, 3, 1.0), (1, 1, 1, 0.5),
])
def test_dirty_ratio_positive_every_defect_and_manifest_matches_cleaner(tmp_path, seed, n_c, n_j, ratio):
    m = gen(tmp_path, seed=seed, n_candidates=n_c, n_jobs=n_j, dirty_ratio=ratio)
    missing = [d for d in ALL_DEFECTS if not m["defects"].get(d)]
    assert missing == [], f"defect types never injected: {missing}"

    result = clean_bundle(read_raw(tmp_path))
    assert result.counts() == m["expected_counts"]
    assert result.rejects_by_rule() == m["expected_rejects"]
    assert m["expected_counts"]["candidates"] == n_c
    assert m["expected_counts"]["jobs"] == n_j
    # every uncataloged skill loads (by key) with category None
    cats = {n.lower(): c for n, c in zip(result.skills["name"], result.skills["category"])}
    for name in m["uncataloged_skills"]:
        assert cats[name.lower()] is None


@pytest.mark.parametrize("seed,n_c,n_j,ratio", [(42, 60, 20, 0.2), (99, 10, 3, 1.0), (1, 1, 1, 0.5)])
def test_uncataloged_skill_spelling_matches_manifest(tmp_path, seed, n_c, n_j, ratio):
    """Uncataloged skills should be stored with the spelling listed in the manifest,
    not a case/whitespace variant from a duplicate_skill_in_parent row."""
    m = gen(tmp_path, seed=seed, n_candidates=n_c, n_jobs=n_j, dirty_ratio=ratio)
    result = clean_bundle(read_raw(tmp_path))
    uncataloged = sorted(n for n, c in zip(result.skills["name"], result.skills["category"])
                         if c is None)
    assert uncataloged == m["uncataloged_skills"]


def test_manifest_shape(tmp_path):
    m = gen(tmp_path)
    on_disk = json.loads((tmp_path / MANIFEST_FILE).read_text(encoding="utf-8"))
    assert on_disk == m
    for key in ("expected_counts", "expected_rejects", "defects", "uncataloged_skills",
                "files", "params", "seed", "faker_version"):
        assert key in m
    assert m["params"] == {"seed": 42, "n_candidates": 60, "n_jobs": 20, "dirty_ratio": 0.2}
    for name, n in m["files"].items():
        assert len(read_rows(tmp_path / name)) == n


def test_ref_formats(tmp_path):
    m = gen(tmp_path)
    refs = {r["candidate_ref"] for r in read_rows(tmp_path / "candidates.csv")}
    assert "C00001" in refs and "CX0001" in refs
    jrefs = {r["job_ref"] for r in read_rows(tmp_path / "jobs.csv")}
    assert "J00001" in jrefs and "JX0001" in jrefs
    assert "CORPHAN0001" in m["defects"]["orphan_ref"]
    assert "JORPHAN0001" in m["defects"]["orphan_ref"]


def test_email_variant_merges_extra_skill(tmp_path):
    gen(tmp_path)
    rows = read_rows(tmp_path / "candidates.csv")
    result = clean_bundle(read_raw(tmp_path))
    variants = [r for r in result.rejects if r.rule == "duplicate_natural_key"
                and r.source_file == "candidates.csv"]
    assert variants
    links = read_rows(tmp_path / "candidate_skills.csv")
    for v in variants:
        email = normalize_email(v.raw["email"])
        extra = [l for l in links if l["candidate_ref"] == v.ref]
        assert extra, f"variant {v.ref} has no skill link"
        kept = result.candidate_skills[result.candidate_skills["email"] == email]
        assert extra[0]["skill_name"].lower() in set(kept["skill_key"])
    assert any(r["candidate_ref"].startswith("C0") for r in rows)


@pytest.mark.parametrize("kw", [dict(n_candidates=0), dict(n_jobs=0), dict(dirty_ratio=-0.1),
                                dict(dirty_ratio=1.5)])
def test_invalid_params(tmp_path, kw):
    with pytest.raises(ValueError):
        gen(tmp_path, **kw)


def test_cli_invalid_params_exit_1(tmp_path):
    res = run_script("generate_synthetic.py", "--candidates", "0", "--out", str(tmp_path))
    assert res.returncode == 1


def test_synthetic_source_adapter(tmp_path):
    src = SyntheticSource(seed=5, n_candidates=4, n_jobs=2, dirty_ratio=0.0)
    assert isinstance(src, SourceAdapter)
    assert src.produce(tmp_path) == tmp_path
    assert (tmp_path / "candidates.csv").is_file()
