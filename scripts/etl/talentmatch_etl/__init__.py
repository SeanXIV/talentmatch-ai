"""TalentMatch AI ETL: read raw contract CSVs, clean them, and load PostgreSQL.

Pipeline: sources (adapters write contract CSVs) -> contract.read_raw ->
cleaning.clean_bundle -> loader.load (single transaction, idempotent upserts).
"""

__version__ = "0.1.0"
