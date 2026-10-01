"""Shared fixtures for the TalentMatch ETL tests.

Puts scripts/etl on sys.path so ``import talentmatch_etl`` works.

Integration tests use a scratch database (default ``talentmatch_test``) that
is created inside the running Postgres instance, migrated with V1 SQL, and
dropped at the end of the session. The real ``talentmatch`` database is only
used to issue CREATE/DROP DATABASE for the scratch DB; its data is never
touched. Integration tests are skipped when psycopg or the server is not
available.

Env overrides: DB_HOST, DB_PORT, DB_USER, DB_PASSWORD, DB_NAME (admin DB used
to create the scratch DB), TM_TEST_DB (scratch DB name).
"""

from __future__ import annotations

import os
import subprocess
import sys
from pathlib import Path
from typing import Any

import pandas as pd
import pytest

REPO_ROOT = Path(__file__).resolve().parents[2]
ETL_DIR = REPO_ROOT / "scripts" / "etl"
MIGRATION_SQL = REPO_ROOT / "src" / "main" / "resources" / "db" / "migration" / "V1__init_schema.sql"

if str(ETL_DIR) not in sys.path:
    sys.path.insert(0, str(ETL_DIR))

from talentmatch_etl.contract import LINE_COL  # noqa: E402

DB_HOST = os.environ.get("DB_HOST", "localhost")
DB_PORT = os.environ.get("DB_PORT", "5432")
DB_USER = os.environ.get("DB_USER", "talentmatch")
DB_PASSWORD = os.environ.get("DB_PASSWORD", "talentmatch")
ADMIN_DB = os.environ.get("DB_NAME", "talentmatch")
TEST_DB = os.environ.get("TM_TEST_DB", "talentmatch_test")

DATA_TABLES = ("job_match", "candidate_skill", "job_skill", "candidate", "job", "skill")


# --------------------------------------------------------------------------
# DataFrame helper
# --------------------------------------------------------------------------

def make_df(columns: list[str], rows: list[list[str]], start_line: int = 2) -> pd.DataFrame:
    """Build a raw-contract-like frame (object strings + ``_line``)."""
    data: dict[str, Any] = {c: pd.Series([r[i] for r in rows], dtype=object)
                            for i, c in enumerate(columns)}
    data[LINE_COL] = pd.Series(range(start_line, start_line + len(rows)), dtype="int64")
    return pd.DataFrame(data)


# --------------------------------------------------------------------------
# Subprocess helpers
# --------------------------------------------------------------------------

def run_script(script: str, *args: str, env: dict[str, str] | None = None,
               cwd: Path = REPO_ROOT) -> subprocess.CompletedProcess:
    """Run scripts/etl/<script> with the current interpreter."""
    full_env = dict(os.environ)
    if env:
        full_env.update(env)
    return subprocess.run(
        [sys.executable, str(ETL_DIR / script), *args],
        cwd=str(cwd), env=full_env, capture_output=True, text=True, timeout=300,
    )


def db_env(**overrides: str) -> dict[str, str]:
    """Process env pointing the loader CLI at the scratch DB."""
    env = {
        "DB_HOST": DB_HOST, "DB_PORT": DB_PORT, "DB_NAME": TEST_DB,
        "DB_USER": DB_USER, "DB_PASSWORD": DB_PASSWORD,
    }
    env.update(overrides)
    return env


# --------------------------------------------------------------------------
# Database fixtures
# --------------------------------------------------------------------------

def _conninfo(dbname: str, password: str = DB_PASSWORD) -> str:
    return (f"host={DB_HOST} port={DB_PORT} dbname={dbname} user={DB_USER} "
            f"password={password} connect_timeout=5")


@pytest.fixture(scope="session")
def scratch_db():
    """Create + migrate the scratch DB once per session; drop it afterwards."""
    try:
        import psycopg
    except ImportError as exc:  # pragma: no cover
        pytest.skip(f"psycopg not installed: {exc}")
    try:
        admin = psycopg.connect(_conninfo(ADMIN_DB), autocommit=True)
    except Exception as exc:  # pragma: no cover
        pytest.skip(f"PostgreSQL not reachable at {DB_HOST}:{DB_PORT}/{ADMIN_DB}: {exc}")
    if TEST_DB == ADMIN_DB:
        pytest.fail("TM_TEST_DB must differ from the main database")
    with admin:
        admin.execute(f'DROP DATABASE IF EXISTS "{TEST_DB}"')
        admin.execute(f'CREATE DATABASE "{TEST_DB}"')
    with psycopg.connect(_conninfo(TEST_DB), autocommit=True) as conn:
        conn.execute(MIGRATION_SQL.read_text(encoding="utf-8"))
    yield TEST_DB
    with psycopg.connect(_conninfo(ADMIN_DB), autocommit=True) as admin:
        admin.execute(f'DROP DATABASE IF EXISTS "{TEST_DB}" WITH (FORCE)')


@pytest.fixture
def db(scratch_db):
    """Autocommit connection to an emptied scratch DB."""
    import psycopg
    with psycopg.connect(_conninfo(scratch_db), autocommit=True) as conn:
        conn.execute("TRUNCATE " + ", ".join(DATA_TABLES) + " CASCADE")
        yield conn


def count(conn, table: str) -> int:
    return conn.execute(f"SELECT count(*) FROM {table}").fetchone()[0]


def all_counts(conn) -> dict[str, int]:
    return {t: count(conn, t) for t in DATA_TABLES}
