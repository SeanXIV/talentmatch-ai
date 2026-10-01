# TalentMatch ETL (Phase 1 data layer)

Generates synthetic raw CSVs, cleans them, and loads PostgreSQL idempotently.
Python >= 3.10. Run everything from the repository root.

```bash
python -m venv .venv && . .venv/bin/activate
pip install -r scripts/etl/requirements.txt      # or requirements-dev.txt for pytest
bash scripts/start_db.sh                          # Postgres 16 + Flyway migrations

python scripts/etl/generate_synthetic.py --seed 42 --candidates 200 --jobs 50 \
    --dirty-ratio 0.1 --out scripts/etl/data/raw
python scripts/etl/clean_and_load.py --input scripts/etl/data/raw \
    --reports scripts/etl/data/reports
```

`clean_and_load.py` options: `--env-file PATH` (default `scripts/.env`),
`--dry-run` (no DB), `--no-sync-links` (keep links missing from the batch),
`--strict` (exit 2 and load nothing if any row is rejected), `--log-level`.

DB settings: process env > `scripts/.env` > defaults (`DB_HOST`, `DB_PORT`,
`DB_NAME`, `DB_USER`, `DB_PASSWORD`; see `scripts/.env.example`).

Outputs (gitignored, under `scripts/etl/data/`):
- `raw/*.csv` + `raw/_manifest.json` (expected counts and injected defects)
- `reports/rejects.csv` (`source_file,line,ref,rule,detail,raw_json`)
- `reports/summary.json` (read/clean/reject/merge counts and per-table load stats)

Exit codes: 0 success (rejects allowed), 1 fatal, 2 `--strict` with rejects.
Reruns with the same input change nothing (all rows reported `unchanged`).

New sources implement `talentmatch_etl.sources.base.SourceAdapter` and write
contract CSVs into a raw directory; the cleaner and loader stay unchanged.
