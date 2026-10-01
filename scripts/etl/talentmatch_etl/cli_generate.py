"""CLI: generate deterministic synthetic raw contract CSVs (+ _manifest.json).

Exit codes: 0 success, 1 failure (invalid parameters or I/O error).
"""

from __future__ import annotations

import argparse
import logging
import sys
from pathlib import Path
from typing import Sequence

from .config import DEFAULT_RAW_DIR
from .sources.synthetic import MANIFEST_FILE, generate

logger = logging.getLogger("talentmatch_etl.cli_generate")


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        prog="generate_synthetic.py",
        description="Generate deterministic synthetic TalentMatch raw CSVs.",
    )
    p.add_argument("--seed", type=int, default=42, help="RNG seed (default: %(default)s)")
    p.add_argument("--candidates", type=int, default=200,
                   help="number of clean candidates (default: %(default)s)")
    p.add_argument("--jobs", type=int, default=50,
                   help="number of clean jobs (default: %(default)s)")
    p.add_argument("--dirty-ratio", type=float, default=0.10,
                   help="share of defective rows per file, 0..1 (default: %(default)s)")
    p.add_argument("--out", type=Path, default=DEFAULT_RAW_DIR,
                   help="output directory (default: %(default)s)")
    p.add_argument("--log-level", default="INFO",
                   choices=["DEBUG", "INFO", "WARNING", "ERROR"],
                   help="logging level (default: %(default)s)")
    return p


def main(argv: Sequence[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    logging.basicConfig(
        level=getattr(logging, args.log_level),
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
        stream=sys.stderr,
    )
    try:
        manifest = generate(args.out, seed=args.seed, n_candidates=args.candidates,
                            n_jobs=args.jobs, dirty_ratio=args.dirty_ratio)
    except ValueError as exc:
        logger.error("Invalid parameters: %s", exc)
        return 1
    except OSError as exc:
        logger.error("Cannot write output: %s", exc)
        return 1

    expected = ", ".join(f"{k}={v}" for k, v in manifest["expected_counts"].items())
    rejects = sum(manifest["expected_rejects"].values())
    print(f"Synthetic data written to {args.out} (manifest: {MANIFEST_FILE})")
    print(f"  expected clean: {expected}")
    print(f"  expected rejects: {rejects}")
    return 0
