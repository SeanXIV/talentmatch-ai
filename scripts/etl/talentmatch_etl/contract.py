"""Raw CSV contract: file names, required columns, max lengths, and the reader.

All values are read as strings (no NA inference). Each frame gets a ``_line``
column holding the 1-based CSV line of the record (header = line 1, first data
row = 2; assumes one physical line per record).
"""

from __future__ import annotations

import logging
from dataclasses import dataclass
from pathlib import Path

import pandas as pd

logger = logging.getLogger(__name__)

LINE_COL = "_line"

# Logical name (== RawBundle field) -> file name.
FILES: dict[str, str] = {
    "skills": "skills.csv",
    "candidates": "candidates.csv",
    "jobs": "jobs.csv",
    "candidate_skills": "candidate_skills.csv",
    "job_skills": "job_skills.csv",
}

# File name -> ordered required columns.
COLUMNS: dict[str, list[str]] = {
    "skills.csv": ["skill_name", "category"],
    "candidates.csv": ["candidate_ref", "full_name", "email", "summary"],
    "jobs.csv": ["job_ref", "title", "company", "description"],
    "candidate_skills.csv": ["candidate_ref", "skill_name", "years_experience"],
    "job_skills.csv": ["job_ref", "skill_name", "required"],
}

# varchar sizes from V1__init_schema.sql.
MAX_LEN: dict[str, int] = {
    "full_name": 200,
    "email": 320,
    "title": 300,
    "company": 200,
    "skill_name": 100,
    "category": 100,
}


class ContractError(Exception):
    """The raw input violates the CSV contract (fatal for the run)."""


@dataclass
class RawBundle:
    """All raw contract frames (string columns + ``_line``)."""

    candidates: pd.DataFrame
    jobs: pd.DataFrame
    skills: pd.DataFrame
    candidate_skills: pd.DataFrame
    job_skills: pd.DataFrame


def empty_frame(columns: list[str]) -> pd.DataFrame:
    """Return an empty frame with the given string columns plus ``_line``."""
    data = {c: pd.Series([], dtype=object) for c in columns}
    data[LINE_COL] = pd.Series([], dtype="int64")
    return pd.DataFrame(data)


def _read_one(path: Path, columns: list[str]) -> pd.DataFrame:
    try:
        df = pd.read_csv(
            path,
            dtype=str,
            keep_default_na=False,
            na_filter=False,
            encoding="utf-8-sig",  # utf-8, tolerating a BOM from spreadsheet exports
        )
    except pd.errors.EmptyDataError as exc:
        raise ContractError(f"{path.name}: file is empty (no header row)") from exc
    except (pd.errors.ParserError, UnicodeDecodeError) as exc:
        raise ContractError(f"{path.name}: cannot parse CSV: {exc}") from exc

    df.columns = [str(c).strip() for c in df.columns]
    missing = [c for c in columns if c not in df.columns]
    if missing:
        raise ContractError(f"{path.name}: missing required column(s): {', '.join(missing)}")
    extra = [c for c in df.columns if c not in columns]
    if extra:
        logger.warning("%s: ignoring extra column(s): %s", path.name, ", ".join(extra))

    df = df[columns].fillna("").astype(object).reset_index(drop=True)
    df[LINE_COL] = pd.Series(range(2, len(df) + 2), index=df.index, dtype="int64")
    return df


def read_raw(input_dir: Path | str) -> RawBundle:
    """Read all contract CSVs from ``input_dir``.

    Missing files are treated as empty (with a warning) unless both
    candidates.csv and jobs.csv are missing, which raises ContractError.
    """
    input_dir = Path(input_dir)
    if not input_dir.is_dir():
        raise ContractError(f"input directory not found: {input_dir}")

    frames: dict[str, pd.DataFrame] = {}
    missing_files: list[str] = []
    for key, filename in FILES.items():
        path = input_dir / filename
        columns = COLUMNS[filename]
        if not path.is_file():
            logger.warning("%s not found in %s; treating as empty", filename, input_dir)
            missing_files.append(filename)
            frames[key] = empty_frame(columns)
            continue
        frames[key] = _read_one(path, columns)
        logger.info("Read %d row(s) from %s", len(frames[key]), filename)

    if FILES["candidates"] in missing_files and FILES["jobs"] in missing_files:
        raise ContractError(
            f"neither {FILES['candidates']} nor {FILES['jobs']} found in {input_dir}"
        )
    return RawBundle(**frames)
