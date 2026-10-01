"""CLI: read raw contract CSVs, clean them, write reports, and load PostgreSQL.

Exit codes: 0 success (rejects allowed), 1 fatal (contract, I/O, config or
database error), 2 --strict and at least one reject (nothing is loaded).
"""

from __future__ import annotations

import argparse
import logging
import sys
from dataclasses import asdict
from pathlib import Path
from typing import Any, Sequence

from .cleaning import CleanResult, clean_bundle
from .config import DEFAULT_RAW_DIR, DEFAULT_REPORTS_DIR, load_db_config
from .contract import ContractError, RawBundle, read_raw
from .rejects import write_rejects_csv, write_summary_json

logger = logging.getLogger("talentmatch_etl.cli_load")

REJECTS_FILE = "rejects.csv"
SUMMARY_FILE = "summary.json"


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        prog="clean_and_load.py",
        description="Clean TalentMatch raw CSVs and load them into PostgreSQL.",
    )
    p.add_argument("--input", type=Path, default=DEFAULT_RAW_DIR,
                   help="directory containing the contract CSVs (default: %(default)s)")
    p.add_argument("--reports", type=Path, default=DEFAULT_REPORTS_DIR,
                   help="directory for rejects.csv and summary.json (default: %(default)s)")
    p.add_argument("--env-file", type=Path, default=None,
                   help="DB settings file (default: scripts/.env if present)")
    p.add_argument("--dry-run", action="store_true",
                   help="clean and write reports without touching the database")
    p.add_argument("--no-sync-links", action="store_true",
                   help="do not delete skill links missing from this batch")
    p.add_argument("--strict", action="store_true",
                   help="exit 2 without loading if any row is rejected")
    p.add_argument("--log-level", default="INFO",
                   choices=["DEBUG", "INFO", "WARNING", "ERROR"],
                   help="logging level (default: %(default)s)")
    return p


def _raw_counts(bundle: RawBundle) -> dict[str, int]:
    return {
        "skills": len(bundle.skills),
        "candidates": len(bundle.candidates),
        "jobs": len(bundle.jobs),
        "candidate_skills": len(bundle.candidate_skills),
        "job_skills": len(bundle.job_skills),
    }


def _print_summary(summary: dict[str, Any]) -> None:
    def fmt(d: dict[str, Any]) -> str:
        return ", ".join(f"{k}={v}" for k, v in d.items()) or "none"

    print("TalentMatch ETL summary")
    print(f"  status : {summary['status']}")
    print(f"  input  : {summary['input']}")
    print(f"  read   : {fmt(summary['rows_read'])}")
    print(f"  clean  : {fmt(summary['clean_counts'])}")
    print(f"  rejects: {summary['rejects_total']} ({fmt(summary['rejects_by_rule'])})")
    print(f"  merges : {summary['merges_total']}")
    load = summary.get("load")
    if load:
        for table, s in load.items():
            print(f"  {table:<16} inserted={s['inserted']} updated={s['updated']} "
                  f"unchanged={s['unchanged']} deleted={s['deleted']}")
    else:
        print("  load   : skipped")
    print(f"  reports: {summary['reports']}")


def main(argv: Sequence[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    logging.basicConfig(
        level=getattr(logging, args.log_level),
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
        stream=sys.stderr,
    )

    input_dir: Path = args.input
    reports_dir: Path = args.reports

    try:
        bundle = read_raw(input_dir)
    except ContractError as exc:
        logger.error("Contract error: %s", exc)
        return 1
    except OSError as exc:
        logger.error("Cannot read input: %s", exc)
        return 1

    clean: CleanResult = clean_bundle(bundle)
    rejects_path = reports_dir / REJECTS_FILE
    summary_path = reports_dir / SUMMARY_FILE

    summary: dict[str, Any] = {
        "status": "ok",
        "input": str(input_dir),
        "reports": str(reports_dir),
        "dry_run": args.dry_run,
        "strict": args.strict,
        "sync_links": not args.no_sync_links,
        "rows_read": _raw_counts(bundle),
        "clean_counts": clean.counts(),
        "rejects_total": len(clean.rejects),
        "rejects_by_rule": clean.rejects_by_rule(),
        "merges_total": len(clean.merges),
        "merges": clean.merges,
        "load": None,
    }

    try:
        write_rejects_csv(clean.rejects, rejects_path)
    except OSError as exc:
        logger.error("Cannot write %s: %s", rejects_path, exc)
        return 1
    if clean.rejects:
        logger.warning("%d row(s) rejected; see %s", len(clean.rejects), rejects_path)

    exit_code = 0
    if args.strict and clean.rejects:
        summary["status"] = "rejected_strict"
        logger.error("--strict: %d reject(s); database not modified", len(clean.rejects))
        exit_code = 2
    elif args.dry_run:
        summary["status"] = "dry_run"
        logger.info("--dry-run: database not modified")
    else:
        exit_code = _load(clean, args, summary)

    try:
        write_summary_json(summary, summary_path)
    except OSError as exc:
        logger.error("Cannot write %s: %s", summary_path, exc)
        return 1
    _print_summary(summary)
    return exit_code


def _load(clean: CleanResult, args: argparse.Namespace, summary: dict[str, Any]) -> int:
    """Load into PostgreSQL in one transaction; update ``summary``; return exit code."""
    try:
        cfg = load_db_config(args.env_file)
    except ValueError as exc:
        summary["status"] = "failed"
        summary["error"] = str(exc)
        logger.error("Invalid DB configuration: %s", exc)
        return 1

    try:
        import psycopg  # imported lazily so --dry-run works without a DB driver
    except ImportError as exc:
        summary["status"] = "failed"
        summary["error"] = f"psycopg not installed: {exc}"
        logger.error("psycopg is not installed (pip install -r scripts/etl/requirements.txt)")
        return 1

    from .loader import load

    logger.info("Connecting to %s", cfg.safe_dsn())
    try:
        # Context manager commits on success, rolls back on exception.
        with psycopg.connect(cfg.conninfo()) as conn:
            stats = load(conn, clean, sync_links=not args.no_sync_links)
    except (psycopg.Error, RuntimeError) as exc:
        summary["status"] = "failed"
        summary["error"] = f"{type(exc).__name__}: {exc}"
        logger.error("Load failed, transaction rolled back: %s", exc)
        return 1
    summary["load"] = {table: asdict(s) for table, s in stats.items()}
    logger.info("Load committed")
    return 0
