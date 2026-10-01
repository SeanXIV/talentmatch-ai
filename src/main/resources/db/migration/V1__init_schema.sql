-- V1__init_schema.sql — TalentMatch AI initial schema (PostgreSQL 16)

-- Shared trigger function: keeps updated_at correct even when the ETL's ON CONFLICT DO UPDATE doesn't set it.
CREATE FUNCTION set_updated_at() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    NEW.updated_at := now();
    RETURN NEW;
END;
$$;

-- Natural key: email must be stored normalized (lowercase, trimmed) and unique.
CREATE TABLE candidate (
    id          uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    full_name   varchar(200)  NOT NULL CHECK (btrim(full_name) <> ''),
    email       varchar(320)  NOT NULL,
    summary     text,
    created_at  timestamptz   NOT NULL DEFAULT now(),
    updated_at  timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT uq_candidate_email UNIQUE (email),
    CONSTRAINT ck_candidate_email_normalized CHECK (email = lower(btrim(email)) AND email <> '')
);
CREATE TRIGGER trg_candidate_updated_at
    BEFORE UPDATE ON candidate
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE job (
    id           uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    title        varchar(300)  NOT NULL CHECK (btrim(title)   <> ''),
    company      varchar(200)  NOT NULL CHECK (btrim(company) <> ''),
    description  text,
    created_at   timestamptz   NOT NULL DEFAULT now(),
    updated_at   timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT uq_job_title_company UNIQUE (title, company)
);
CREATE TRIGGER trg_job_updated_at
    BEFORE UPDATE ON job
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Skill names are trimmed and unique case-insensitively (via lower(name) index).
CREATE TABLE skill (
    id          uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    name        varchar(100)  NOT NULL CHECK (name = btrim(name) AND name <> ''),
    category    varchar(100),
    updated_at  timestamptz   NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_skill_name_lower ON skill (lower(name));
CREATE TRIGGER trg_skill_updated_at
    BEFORE UPDATE ON skill
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE candidate_skill (
    candidate_id      uuid     NOT NULL REFERENCES candidate(id) ON DELETE CASCADE,
    skill_id          uuid     NOT NULL REFERENCES skill(id)     ON DELETE CASCADE,
    years_experience  integer  CHECK (years_experience IS NULL OR years_experience >= 0),
    PRIMARY KEY (candidate_id, skill_id)
);
CREATE INDEX ix_candidate_skill_skill ON candidate_skill (skill_id);

-- required = true: weighted 10 points in scoring; false (nice-to-have): 5 points.
CREATE TABLE job_skill (
    job_id    uuid     NOT NULL REFERENCES job(id)   ON DELETE CASCADE,
    skill_id  uuid     NOT NULL REFERENCES skill(id) ON DELETE CASCADE,
    required  boolean  NOT NULL DEFAULT true,
    PRIMARY KEY (job_id, skill_id)
);
CREATE INDEX ix_job_skill_skill ON job_skill (skill_id);

-- job_match: one row per (candidate, job) pair; score is normalized to 0–1:
-- earned points / max points (required skill = 10, nice-to-have = 5).
CREATE TABLE job_match (
    id              uuid              PRIMARY KEY DEFAULT gen_random_uuid(),
    candidate_id    uuid              NOT NULL REFERENCES candidate(id) ON DELETE CASCADE,
    job_id          uuid              NOT NULL REFERENCES job(id)       ON DELETE CASCADE,
    score           double precision  NOT NULL CHECK (score >= 0 AND score <= 1),
    ai_explanation  text,
    computed_at     timestamptz       NOT NULL DEFAULT now(),
    updated_at      timestamptz       NOT NULL DEFAULT now(),
    CONSTRAINT uq_job_match_candidate_job UNIQUE (candidate_id, job_id)
);
CREATE INDEX ix_job_match_job_score ON job_match (job_id, score DESC);
CREATE TRIGGER trg_job_match_updated_at
    BEFORE UPDATE ON job_match
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
