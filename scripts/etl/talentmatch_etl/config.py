"""Paths and database configuration for the TalentMatch ETL.

Precedence for DB settings: process environment > scripts/.env > defaults.
The .env file is read with python-dotenv's ``dotenv_values`` so loading the
config never mutates ``os.environ``. The password is never logged or repr'd.
"""

from __future__ import annotations

import logging
import os
from dataclasses import dataclass, field
from pathlib import Path
from typing import Mapping

from dotenv import dotenv_values

logger = logging.getLogger(__name__)

# <repo>/scripts/etl/talentmatch_etl/config.py -> parents[3] == <repo>
REPO_ROOT: Path = Path(__file__).resolve().parents[3]
SCRIPTS_DIR: Path = REPO_ROOT / "scripts"
ETL_DIR: Path = SCRIPTS_DIR / "etl"
DEFAULT_ENV_FILE: Path = SCRIPTS_DIR / ".env"
DEFAULT_RAW_DIR: Path = ETL_DIR / "data" / "raw"
DEFAULT_REPORTS_DIR: Path = ETL_DIR / "data" / "reports"

# Same names/defaults as scripts/start_db.sh (DB_HOST is ETL-only).
DEFAULTS: dict[str, str] = {
    "DB_HOST": "localhost",
    "DB_PORT": "5432",
    "DB_NAME": "talentmatch",
    "DB_USER": "talentmatch",
    "DB_PASSWORD": "talentmatch",  # local dev only
}


def _quote(value: str) -> str:
    """Quote a libpq conninfo value (single quotes, backslash escapes)."""
    return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'"


@dataclass(frozen=True)
class DbConfig:
    """Connection settings for the local PostgreSQL instance."""

    host: str
    port: int
    name: str
    user: str
    password: str = field(repr=False)

    def conninfo(self) -> str:
        """Return a libpq keyword/value connection string."""
        parts = {
            "host": self.host,
            "port": str(self.port),
            "dbname": self.name,
            "user": self.user,
            "password": self.password,
            "connect_timeout": "10",
            "application_name": "talentmatch-etl",
        }
        return " ".join(f"{k}={_quote(v)}" for k, v in parts.items())

    def safe_dsn(self) -> str:
        """Return a password-masked URL suitable for logs."""
        return f"postgresql://{self.user}:***@{self.host}:{self.port}/{self.name}"


def load_db_config(
    env_file: Path | str | None = None,
    environ: Mapping[str, str] | None = None,
) -> DbConfig:
    """Resolve DB settings from the environment, an optional .env file, and defaults.

    ``env_file`` defaults to ``<repo>/scripts/.env``; a missing file is fine.
    ``environ`` defaults to ``os.environ`` (injectable for tests).
    Raises ValueError if DB_PORT is not a valid port number.
    """
    env = os.environ if environ is None else environ
    explicit = env_file is not None
    path = Path(env_file) if explicit else DEFAULT_ENV_FILE

    file_values: dict[str, str | None] = {}
    if path.is_file():
        file_values = dict(dotenv_values(path))
        logger.debug("Loaded DB settings file %s", path)
    elif explicit:
        logger.warning("Env file %s not found; using environment/defaults", path)

    def pick(key: str) -> str:
        for source in (env.get(key), file_values.get(key)):
            if source is not None and source.strip() != "":
                return source.strip() if key != "DB_PASSWORD" else source
        return DEFAULTS[key]

    raw_port = pick("DB_PORT")
    try:
        port = int(raw_port)
    except ValueError:
        raise ValueError(f"DB_PORT must be an integer, got {raw_port!r}") from None
    if not 1 <= port <= 65535:
        raise ValueError(f"DB_PORT out of range: {port}")

    return DbConfig(
        host=pick("DB_HOST"),
        port=port,
        name=pick("DB_NAME"),
        user=pick("DB_USER"),
        password=pick("DB_PASSWORD"),
    )
