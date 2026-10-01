#!/usr/bin/env bash
# test_schema.sh - Verify the TalentMatch AI V1 schema in the running
# talentmatch-postgres container. Behavioral checks run in a transaction that
# is rolled back, so no test data is left behind.
#
# Usage: bash tests/db/test_schema.sh
# Env:   DOCKER (default: docker), DB_CONTAINER, DB_USER, DB_NAME
# Exits non-zero on the first failed check.
set -euo pipefail

DOCKER="${DOCKER:-docker}"
DB_CONTAINER="${DB_CONTAINER:-talentmatch-postgres}"
DB_USER="${DB_USER:-talentmatch}"
DB_NAME="${DB_NAME:-talentmatch}"

"$DOCKER" exec -i "$DB_CONTAINER" \
    psql -X -q -v ON_ERROR_STOP=1 -U "$DB_USER" -d "$DB_NAME" <<'SQL'
\set QUIET on
\pset tuples_only on

-- ---------- Engine & migration history ----------
DO $$
BEGIN
    IF current_setting('server_version_num')::int / 10000 <> 16 THEN
        RAISE EXCEPTION 'FAIL: expected PostgreSQL 16, got %', version();
    END IF;
    IF NOT EXISTS (SELECT 1 FROM flyway_schema_history
                   WHERE version = '1' AND success) THEN
        RAISE EXCEPTION 'FAIL: flyway_schema_history has no successful V1';
    END IF;
    RAISE NOTICE 'PASS: PostgreSQL 16, Flyway V1 success';
END $$;

-- ---------- Tables ----------
DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['candidate','job','skill','candidate_skill','job_skill','job_match'] LOOP
        IF to_regclass('public.' || t) IS NULL THEN
            RAISE EXCEPTION 'FAIL: missing table %', t;
        END IF;
    END LOOP;
    IF to_regclass('public."match"') IS NOT NULL THEN
        RAISE EXCEPTION 'FAIL: unexpected table "match" exists';
    END IF;
    RAISE NOTICE 'PASS: tables present, no "match" table';
END $$;

-- ---------- Constraints ----------
DO $$
DECLARE r record;
BEGIN
    FOR r IN SELECT * FROM (VALUES
        ('candidate',       'uq_candidate_email',            'u'),
        ('candidate',       'ck_candidate_email_normalized', 'c'),
        ('job',             'uq_job_title_company',          'u'),
        ('job_match',       'uq_job_match_candidate_job',    'u'),
        ('candidate_skill', 'candidate_skill_pkey',          'p'),
        ('job_skill',       'job_skill_pkey',                'p')
    ) AS v(tbl, con, typ) LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_constraint
                       WHERE conname = r.con AND contype = r.typ::"char"
                         AND conrelid = ('public.' || r.tbl)::regclass) THEN
            RAISE EXCEPTION 'FAIL: missing constraint %.% (type %)', r.tbl, r.con, r.typ;
        END IF;
    END LOOP;
    -- composite PK column sets
    IF (SELECT array_agg(a.attname::text ORDER BY a.attname)
          FROM pg_constraint c
          JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = ANY (c.conkey)
         WHERE c.conname = 'candidate_skill_pkey') <> ARRAY['candidate_id','skill_id'] THEN
        RAISE EXCEPTION 'FAIL: candidate_skill PK is not (candidate_id, skill_id)';
    END IF;
    IF (SELECT array_agg(a.attname::text ORDER BY a.attname)
          FROM pg_constraint c
          JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = ANY (c.conkey)
         WHERE c.conname = 'job_skill_pkey') <> ARRAY['job_id','skill_id'] THEN
        RAISE EXCEPTION 'FAIL: job_skill PK is not (job_id, skill_id)';
    END IF;
    RAISE NOTICE 'PASS: constraints and composite PKs present';
END $$;

-- ---------- Indexes ----------
DO $$
DECLARE i text;
BEGIN
    FOREACH i IN ARRAY ARRAY['uq_skill_name_lower','ix_job_match_job_score',
                             'ix_candidate_skill_skill','ix_job_skill_skill'] LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_indexes
                       WHERE schemaname = 'public' AND indexname = i) THEN
            RAISE EXCEPTION 'FAIL: missing index %', i;
        END IF;
    END LOOP;
    RAISE NOTICE 'PASS: indexes present';
END $$;

-- ---------- Triggers ----------
DO $$
DECLARE r record;
BEGIN
    FOR r IN SELECT * FROM (VALUES
        ('candidate', 'trg_candidate_updated_at'),
        ('job',       'trg_job_updated_at'),
        ('skill',     'trg_skill_updated_at'),
        ('job_match', 'trg_job_match_updated_at')
    ) AS v(tbl, trg) LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_trigger
                       WHERE tgname = r.trg AND NOT tgisinternal
                         AND tgrelid = ('public.' || r.tbl)::regclass) THEN
            RAISE EXCEPTION 'FAIL: missing trigger %.%', r.tbl, r.trg;
        END IF;
    END LOOP;
    RAISE NOTICE 'PASS: updated_at triggers present';
END $$;

-- ---------- Behavioral checks (rolled back) ----------
BEGIN;

DO $$
DECLARE
    c_id uuid; j_id uuid; s_id uuid;
    old_ts timestamptz; new_ts timestamptz;
    n int;
BEGIN
    -- Upsert advances updated_at without setting it explicitly
    INSERT INTO candidate (full_name, email, updated_at)
    VALUES ('QA Test', 'qa.test@example.com', now() - interval '1 day')
    RETURNING id, updated_at INTO c_id, old_ts;
    INSERT INTO candidate (full_name, email)
    VALUES ('QA Test Renamed', 'qa.test@example.com')
    ON CONFLICT (email) DO UPDATE SET full_name = EXCLUDED.full_name
    RETURNING updated_at INTO new_ts;
    IF NOT new_ts > old_ts THEN
        RAISE EXCEPTION 'FAIL: upsert did not advance updated_at (% -> %)', old_ts, new_ts;
    END IF;
    RAISE NOTICE 'PASS: ON CONFLICT DO UPDATE advances updated_at';

    -- Duplicate email rejected
    BEGIN
        INSERT INTO candidate (full_name, email) VALUES ('Dup', 'qa.test@example.com');
        RAISE EXCEPTION 'FAIL: duplicate email accepted';
    EXCEPTION WHEN unique_violation THEN
        RAISE NOTICE 'PASS: duplicate email rejected';
    END;

    -- Non-normalized email rejected
    BEGIN
        INSERT INTO candidate (full_name, email) VALUES ('Foo', '  Foo@X.com');
        RAISE EXCEPTION 'FAIL: non-normalized email accepted';
    EXCEPTION WHEN check_violation THEN
        RAISE NOTICE 'PASS: non-normalized email rejected';
    END;

    -- Duplicate (title, company) job rejected
    INSERT INTO job (title, company) VALUES ('QA Engineer', 'QA Corp') RETURNING id INTO j_id;
    BEGIN
        INSERT INTO job (title, company) VALUES ('QA Engineer', 'QA Corp');
        RAISE EXCEPTION 'FAIL: duplicate (title, company) accepted';
    EXCEPTION WHEN unique_violation THEN
        RAISE NOTICE 'PASS: duplicate (title, company) rejected';
    END;

    -- Case-insensitive skill uniqueness
    INSERT INTO skill (name) VALUES ('QaJava') RETURNING id INTO s_id;
    BEGIN
        INSERT INTO skill (name) VALUES ('qajava');
        RAISE EXCEPTION 'FAIL: case-insensitive duplicate skill accepted';
    EXCEPTION WHEN unique_violation THEN
        RAISE NOTICE 'PASS: case-insensitive duplicate skill rejected';
    END;

    -- job_match uniqueness and score range
    INSERT INTO job_match (candidate_id, job_id, score) VALUES (c_id, j_id, 0.5);
    BEGIN
        INSERT INTO job_match (candidate_id, job_id, score) VALUES (c_id, j_id, 0.7);
        RAISE EXCEPTION 'FAIL: duplicate (candidate_id, job_id) accepted';
    EXCEPTION WHEN unique_violation THEN
        RAISE NOTICE 'PASS: duplicate job_match pair rejected';
    END;
    BEGIN
        UPDATE job_match SET score = 1.5 WHERE candidate_id = c_id AND job_id = j_id;
        RAISE EXCEPTION 'FAIL: score 1.5 accepted';
    EXCEPTION WHEN check_violation THEN
        RAISE NOTICE 'PASS: score 1.5 rejected';
    END;

    -- Cascade delete
    INSERT INTO candidate_skill (candidate_id, skill_id, years_experience) VALUES (c_id, s_id, 3);
    DELETE FROM candidate WHERE id = c_id;
    SELECT (SELECT count(*) FROM candidate_skill WHERE candidate_id = c_id)
         + (SELECT count(*) FROM job_match       WHERE candidate_id = c_id) INTO n;
    IF n <> 0 THEN
        RAISE EXCEPTION 'FAIL: candidate delete did not cascade (% rows left)', n;
    END IF;
    RAISE NOTICE 'PASS: candidate delete cascades to candidate_skill and job_match';
END $$;

ROLLBACK;
SQL

echo "[test_schema] ALL CHECKS PASSED"
