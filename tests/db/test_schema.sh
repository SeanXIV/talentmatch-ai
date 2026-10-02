#!/usr/bin/env bash
# test_schema.sh - Verify the TalentMatch AI schema (V1 + V2) in the running
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
    IF NOT EXISTS (SELECT 1 FROM flyway_schema_history
                   WHERE version = '2' AND success) THEN
        RAISE EXCEPTION 'FAIL: flyway_schema_history has no successful V2';
    END IF;
    RAISE NOTICE 'PASS: PostgreSQL 16, Flyway V1 + V2 success';
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

-- ---------- V2 skill-link staleness triggers ----------
-- tgtype bits: 1 = ROW, 2 = BEFORE, 4 = INSERT, 8 = DELETE, 16 = UPDATE.
DO $$
DECLARE r record; t record;
BEGIN
    IF to_regprocedure('touch_candidate_from_links()') IS NULL
       OR to_regprocedure('touch_job_from_links()') IS NULL THEN
        RAISE EXCEPTION 'FAIL: missing V2 trigger function(s) touch_candidate_from_links / touch_job_from_links';
    END IF;
    FOR r IN SELECT * FROM (VALUES
        ('candidate_skill', 'trg_candidate_skill_touch_ins', 4,  'touch_candidate_from_links', false, true),
        ('candidate_skill', 'trg_candidate_skill_touch_upd', 16, 'touch_candidate_from_links', true,  true),
        ('candidate_skill', 'trg_candidate_skill_touch_del', 8,  'touch_candidate_from_links', true,  false),
        ('job_skill',       'trg_job_skill_touch_ins',       4,  'touch_job_from_links',       false, true),
        ('job_skill',       'trg_job_skill_touch_upd',       16, 'touch_job_from_links',       true,  true),
        ('job_skill',       'trg_job_skill_touch_del',       8,  'touch_job_from_links',       true,  false)
    ) AS v(tbl, trg, event_bit, fn, has_old, has_new) LOOP
        SELECT tg.tgtype::int AS tgtype, p.proname::text AS fn, tg.tgenabled,
               tg.tgoldtable, tg.tgnewtable
          INTO t
          FROM pg_trigger tg JOIN pg_proc p ON p.oid = tg.tgfoid
         WHERE tg.tgname = r.trg AND NOT tg.tgisinternal
           AND tg.tgrelid = ('public.' || r.tbl)::regclass;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'FAIL: missing trigger %.%', r.tbl, r.trg;
        END IF;
        IF t.tgtype & 1 <> 0 OR t.tgtype & 2 <> 0 THEN
            RAISE EXCEPTION 'FAIL: % must be AFTER ... FOR EACH STATEMENT (tgtype %)', r.trg, t.tgtype;
        END IF;
        IF t.tgtype & (4 | 8 | 16) <> r.event_bit THEN
            RAISE EXCEPTION 'FAIL: % fires on the wrong event(s) (tgtype %)', r.trg, t.tgtype;
        END IF;
        IF t.fn <> r.fn THEN
            RAISE EXCEPTION 'FAIL: % executes % instead of %', r.trg, t.fn, r.fn;
        END IF;
        IF t.tgenabled = 'D' THEN
            RAISE EXCEPTION 'FAIL: % is disabled', r.trg;
        END IF;
        IF (t.tgoldtable IS NOT NULL) <> r.has_old OR (t.tgnewtable IS NOT NULL) <> r.has_new
           OR (r.has_old AND t.tgoldtable <> 'old_links')
           OR (r.has_new AND t.tgnewtable <> 'new_links') THEN
            RAISE EXCEPTION 'FAIL: % has wrong transition tables (old=%, new=%)',
                r.trg, t.tgoldtable, t.tgnewtable;
        END IF;
    END LOOP;
    RAISE NOTICE 'PASS: V2 link-touch triggers present (AFTER, statement-level, transition tables)';
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

-- ---------- V2 behavioral checks (rolled back) ----------
-- now() is constant inside this transaction, so parents start with an old updated_at and
-- a "bump" means updated_at = now(). To re-arm a parent, its own V1 BEFORE UPDATE trigger
-- (which would force now()) is disabled for that one UPDATE; everything is rolled back.
BEGIN;

CREATE FUNCTION pg_temp.rearm(tbl text, row_id uuid, ts timestamptz) RETURNS void
LANGUAGE plpgsql AS $f$
BEGIN
    EXECUTE format('ALTER TABLE %I DISABLE TRIGGER %I', tbl, 'trg_' || tbl || '_updated_at');
    EXECUTE format('UPDATE %I SET updated_at = $1 WHERE id = $2', tbl) USING ts, row_id;
    EXECUTE format('ALTER TABLE %I ENABLE TRIGGER %I', tbl, 'trg_' || tbl || '_updated_at');
END;
$f$;

DO $$
DECLARE
    old_ts constant timestamptz := now() - interval '1 day';
    c_id uuid; j_id uuid; s1 uuid; s2 uuid;
    ts timestamptz; n int;
BEGIN
    INSERT INTO skill (name) VALUES ('QaV2SkillOne') RETURNING id INTO s1;
    INSERT INTO skill (name) VALUES ('QaV2SkillTwo') RETURNING id INTO s2;
    INSERT INTO candidate (full_name, email, updated_at)
    VALUES ('QA V2', 'qa.v2@example.com', old_ts) RETURNING id INTO c_id;
    INSERT INTO job (title, company, updated_at)
    VALUES ('QA V2 Engineer', 'QA V2 Corp', old_ts) RETURNING id INTO j_id;

    -- candidate_skill INSERT bumps candidate.updated_at
    INSERT INTO candidate_skill (candidate_id, skill_id, years_experience) VALUES (c_id, s1, 3);
    SELECT updated_at INTO ts FROM candidate WHERE id = c_id;
    IF ts <> now() THEN
        RAISE EXCEPTION 'FAIL: candidate_skill INSERT did not bump candidate.updated_at (%)', ts;
    END IF;
    RAISE NOTICE 'PASS: candidate_skill INSERT bumps candidate.updated_at';

    -- ETL no-op rerun: conditional upsert with identical values + prune with same keep set
    PERFORM pg_temp.rearm('candidate', c_id, old_ts);
    INSERT INTO candidate_skill (candidate_id, skill_id, years_experience)
    SELECT * FROM unnest(ARRAY[c_id]::uuid[], ARRAY[s1]::uuid[], ARRAY[3]::int[])
    ON CONFLICT (candidate_id, skill_id) DO UPDATE
        SET years_experience = EXCLUDED.years_experience
        WHERE candidate_skill.years_experience IS DISTINCT FROM EXCLUDED.years_experience;
    GET DIAGNOSTICS n = ROW_COUNT;
    IF n <> 0 THEN
        RAISE EXCEPTION 'FAIL: identical conditional upsert wrote % row(s)', n;
    END IF;
    DELETE FROM candidate_skill cs
    WHERE cs.candidate_id = ANY(ARRAY[c_id]::uuid[])
      AND NOT EXISTS (SELECT 1 FROM unnest(ARRAY[c_id]::uuid[], ARRAY[s1]::uuid[]) AS k(c, s)
                      WHERE k.c = cs.candidate_id AND k.s = cs.skill_id);
    GET DIAGNOSTICS n = ROW_COUNT;
    IF n <> 0 THEN
        RAISE EXCEPTION 'FAIL: same-keep-set prune deleted % row(s)', n;
    END IF;
    SELECT updated_at INTO ts FROM candidate WHERE id = c_id;
    IF ts <> old_ts THEN
        RAISE EXCEPTION 'FAIL: zero-row upsert/prune bumped candidate.updated_at (% -> %)', old_ts, ts;
    END IF;
    RAISE NOTICE 'PASS: zero-row conditional upsert + prune leave candidate.updated_at unchanged';

    -- conditional upsert that changes years (UPDATE) bumps
    INSERT INTO candidate_skill (candidate_id, skill_id, years_experience)
    SELECT * FROM unnest(ARRAY[c_id]::uuid[], ARRAY[s1]::uuid[], ARRAY[4]::int[])
    ON CONFLICT (candidate_id, skill_id) DO UPDATE
        SET years_experience = EXCLUDED.years_experience
        WHERE candidate_skill.years_experience IS DISTINCT FROM EXCLUDED.years_experience;
    SELECT updated_at INTO ts FROM candidate WHERE id = c_id;
    IF ts <> now() THEN
        RAISE EXCEPTION 'FAIL: candidate_skill UPDATE did not bump candidate.updated_at (%)', ts;
    END IF;
    RAISE NOTICE 'PASS: candidate_skill UPDATE bumps candidate.updated_at';

    -- DELETE bumps
    PERFORM pg_temp.rearm('candidate', c_id, old_ts);
    DELETE FROM candidate_skill WHERE candidate_id = c_id AND skill_id = s1;
    SELECT updated_at INTO ts FROM candidate WHERE id = c_id;
    IF ts <> now() THEN
        RAISE EXCEPTION 'FAIL: candidate_skill DELETE did not bump candidate.updated_at (%)', ts;
    END IF;
    RAISE NOTICE 'PASS: candidate_skill DELETE bumps candidate.updated_at';

    -- job_skill: INSERT bumps; identical upsert + prune do not; UPDATE and DELETE bump
    INSERT INTO job_skill (job_id, skill_id, required) VALUES (j_id, s1, true), (j_id, s2, false);
    SELECT updated_at INTO ts FROM job WHERE id = j_id;
    IF ts <> now() THEN
        RAISE EXCEPTION 'FAIL: job_skill INSERT did not bump job.updated_at (%)', ts;
    END IF;
    PERFORM pg_temp.rearm('job', j_id, old_ts);
    INSERT INTO job_skill (job_id, skill_id, required)
    SELECT * FROM unnest(ARRAY[j_id, j_id]::uuid[], ARRAY[s1, s2]::uuid[], ARRAY[true, false]::boolean[])
    ON CONFLICT (job_id, skill_id) DO UPDATE
        SET required = EXCLUDED.required
        WHERE job_skill.required IS DISTINCT FROM EXCLUDED.required;
    DELETE FROM job_skill js
    WHERE js.job_id = ANY(ARRAY[j_id]::uuid[])
      AND NOT EXISTS (SELECT 1 FROM unnest(ARRAY[j_id, j_id]::uuid[], ARRAY[s1, s2]::uuid[]) AS k(j, s)
                      WHERE k.j = js.job_id AND k.s = js.skill_id);
    SELECT updated_at INTO ts FROM job WHERE id = j_id;
    IF ts <> old_ts THEN
        RAISE EXCEPTION 'FAIL: zero-row job_skill upsert/prune bumped job.updated_at (% -> %)', old_ts, ts;
    END IF;
    UPDATE job_skill SET required = true WHERE job_id = j_id AND skill_id = s2;
    SELECT updated_at INTO ts FROM job WHERE id = j_id;
    IF ts <> now() THEN
        RAISE EXCEPTION 'FAIL: job_skill UPDATE did not bump job.updated_at (%)', ts;
    END IF;
    PERFORM pg_temp.rearm('job', j_id, old_ts);
    DELETE FROM job_skill WHERE job_id = j_id AND skill_id = s2;
    SELECT updated_at INTO ts FROM job WHERE id = j_id;
    IF ts <> now() THEN
        RAISE EXCEPTION 'FAIL: job_skill DELETE did not bump job.updated_at (%)', ts;
    END IF;
    RAISE NOTICE 'PASS: job_skill INSERT/UPDATE/DELETE bump job.updated_at; zero-row upsert + prune do not';

    -- skill delete cascades to links and bumps the parents
    PERFORM pg_temp.rearm('job', j_id, old_ts);
    DELETE FROM skill WHERE id = s1;
    SELECT updated_at INTO ts FROM job WHERE id = j_id;
    IF ts <> now() THEN
        RAISE EXCEPTION 'FAIL: skill delete (cascading to job_skill) did not bump job.updated_at (%)', ts;
    END IF;
    RAISE NOTICE 'PASS: skill delete cascades to links and bumps the parent';
END $$;

ROLLBACK;
SQL

echo "[test_schema] ALL CHECKS PASSED"
