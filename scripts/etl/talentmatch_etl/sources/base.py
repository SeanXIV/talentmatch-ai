"""Source adapter protocol.

An adapter produces contract CSVs (see talentmatch_etl.contract) into
``out_dir``; clean_and_load only ever reads that directory. Adapters may
write a subset of files (e.g. a jobs feed writes jobs.csv, job_skills.csv
and skills.csv only); missing files are treated as empty by the reader.
"""

from __future__ import annotations

from pathlib import Path
from typing import Protocol, runtime_checkable


@runtime_checkable
class SourceAdapter(Protocol):
    """Writes contract CSVs for one data source."""

    name: str

    def produce(self, out_dir: Path) -> Path:
        """Write contract CSVs into ``out_dir`` and return it."""
        ...
