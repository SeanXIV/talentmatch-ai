"""CLI behaviour that needs no database: lazy psycopg import, exit codes."""

from __future__ import annotations

import json
import subprocess
import sys

from conftest import ETL_DIR
from talentmatch_etl.sources.synthetic import generate

# Runs clean_and_load.main with psycopg made unimportable.
BLOCK_PSYCOPG = r"""
import sys
class _Block:
    def find_spec(self, name, path=None, target=None):
        if name == "psycopg" or name.startswith("psycopg."):
            raise ImportError("psycopg blocked for test")
        return None
sys.meta_path.insert(0, _Block())
sys.path.insert(0, sys.argv[1])
from talentmatch_etl.cli_load import main
sys.exit(main(sys.argv[2:]))
"""


def run_blocked(*args: str, cwd) -> subprocess.CompletedProcess:
    return subprocess.run([sys.executable, "-c", BLOCK_PSYCOPG, str(ETL_DIR), *args],
                          cwd=str(cwd), capture_output=True, text=True, timeout=300)


def test_dry_run_without_psycopg(tmp_path):
    raw = tmp_path / "raw"
    generate(raw, seed=1, n_candidates=5, n_jobs=2, dirty_ratio=0.2)
    rep = tmp_path / "rep"
    res = run_blocked("--input", str(raw), "--reports", str(rep), "--dry-run", cwd=tmp_path)
    assert res.returncode == 0, res.stderr
    assert json.loads((rep / "summary.json").read_text())["status"] == "dry_run"
    assert "TalentMatch ETL summary" in res.stdout


def test_load_without_psycopg_exits_1(tmp_path):
    raw = tmp_path / "raw"
    generate(raw, seed=1, n_candidates=5, n_jobs=2, dirty_ratio=0.0)
    rep = tmp_path / "rep"
    res = run_blocked("--input", str(raw), "--reports", str(rep),
                      "--env-file", str(tmp_path / "none.env"), cwd=tmp_path)
    assert res.returncode == 1
    summary = json.loads((rep / "summary.json").read_text())
    assert summary["status"] == "failed" and "psycopg" in summary["error"]


def test_shim_runs_from_other_cwd(tmp_path):
    res = subprocess.run([sys.executable, str(ETL_DIR / "clean_and_load.py"), "--help"],
                         cwd=str(tmp_path), capture_output=True, text=True, timeout=120)
    assert res.returncode == 0 and "--no-sync-links" in res.stdout
    res = subprocess.run([sys.executable, str(ETL_DIR / "generate_synthetic.py"), "--help"],
                         cwd=str(tmp_path), capture_output=True, text=True, timeout=120)
    assert res.returncode == 0 and "--dirty-ratio" in res.stdout


def test_bad_db_port_config_exits_1(tmp_path):
    raw = tmp_path / "raw"
    generate(raw, seed=1, n_candidates=3, n_jobs=1, dirty_ratio=0.0)
    res = subprocess.run([sys.executable, str(ETL_DIR / "clean_and_load.py"), "--input", str(raw),
                          "--reports", str(tmp_path / "rep"), "--env-file", str(tmp_path / "x.env")],
                         cwd=str(tmp_path), capture_output=True, text=True, timeout=300,
                         env={**__import__("os").environ, "DB_PORT": "notaport"})
    assert res.returncode == 1
