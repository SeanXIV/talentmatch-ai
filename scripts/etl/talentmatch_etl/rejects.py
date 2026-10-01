"""Reject records and report writers (rejects.csv, summary.json)."""

from __future__ import annotations

import csv
import json
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Iterable

REJECT_COLUMNS: list[str] = ["source_file", "line", "ref", "rule", "detail", "raw_json"]


@dataclass
class Reject:
    """One input row that was not loaded, with the rule that rejected it."""

    source_file: str
    line: int
    ref: str | None
    rule: str
    detail: str
    raw: dict[str, Any] = field(default_factory=dict)


def write_rejects_csv(rejects: Iterable[Reject], path: Path | str) -> Path:
    """Write rejects to CSV. Always writes the file (header only if no rejects)."""
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as fh:
        writer = csv.writer(fh, lineterminator="\n")
        writer.writerow(REJECT_COLUMNS)
        for r in rejects:
            writer.writerow([
                r.source_file,
                r.line,
                "" if r.ref is None else r.ref,
                r.rule,
                r.detail,
                json.dumps(r.raw, ensure_ascii=False),
            ])
    return path


def write_summary_json(summary: dict[str, Any], path: Path | str) -> Path:
    """Write the run summary as pretty-printed JSON."""
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="\n") as fh:
        json.dump(summary, fh, indent=2, ensure_ascii=False, default=str)
        fh.write("\n")
    return path
