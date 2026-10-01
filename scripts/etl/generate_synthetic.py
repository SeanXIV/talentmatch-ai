"""Entry point: generate deterministic synthetic raw CSVs (see talentmatch_etl.cli_generate)."""
import sys

from talentmatch_etl.cli_generate import main

if __name__ == "__main__":
    sys.exit(main())
