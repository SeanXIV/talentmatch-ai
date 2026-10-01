"""Entry point: clean raw CSVs and load them into PostgreSQL (see talentmatch_etl.cli_load)."""
import sys

from talentmatch_etl.cli_load import main

if __name__ == "__main__":
    sys.exit(main())
