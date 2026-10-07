-- V4__owner_profile.sql — the owner's uploaded CV and confirmed master profile (Phase 4).
-- resume keeps the original PDF, its extracted text and the AI-extracted draft profile.
-- owner_profile is a single row: the profile the owner reviewed and confirmed, linked to the
-- owner's candidate row (so the existing scoring and matching work against real jobs).
-- owner_profile_version is the append-only history: one row per successful save; owner_profile
-- points at the current version (Phase 7 records which version a tailored CV was built from).

CREATE TABLE resume (
    id                      uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    file_name               varchar(255)  NOT NULL CHECK (btrim(file_name) <> ''),
    content_type            varchar(100)  NOT NULL,
    size_bytes              integer       NOT NULL CHECK (size_bytes > 0),
    sha256                  char(64)      NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    content                 bytea         NOT NULL,
    page_count              integer       NOT NULL CHECK (page_count > 0),
    extracted_text          text          NOT NULL CHECK (btrim(extracted_text) <> ''),
    status                  varchar(20)   NOT NULL DEFAULT 'PENDING',
    failure_reason          varchar(40),
    attempts                integer       NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    extraction_model        varchar(200),
    draft                   jsonb,
    warnings                jsonb,
    uploaded_at             timestamptz   NOT NULL DEFAULT now(),
    extraction_started_at   timestamptz,
    extraction_finished_at  timestamptz,
    updated_at              timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT ck_resume_status CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED')),
    -- Must match com.talentmatch.profile.ExtractionFailure (an unknown value would fail every read).
    CONSTRAINT ck_resume_failure_reason CHECK (failure_reason IS NULL OR failure_reason IN (
        'AI_DISABLED', 'TIMEOUT', 'PROVIDER_ERROR', 'REFUSED', 'INVALID_OUTPUT', 'QUEUE_FULL',
        'CONTEXT_OVERFLOW', 'TOO_MANY_ATTEMPTS', 'REMOTE_EXTRACTION_DISABLED')),
    CONSTRAINT ck_resume_size CHECK (size_bytes = octet_length(content)),
    CONSTRAINT ck_resume_running_started CHECK (status <> 'RUNNING' OR extraction_started_at IS NOT NULL),
    CONSTRAINT ck_resume_succeeded_model CHECK (status <> 'SUCCEEDED' OR extraction_model IS NOT NULL),
    -- A failure reason exactly when FAILED; a draft (object) and warnings (array) exactly when SUCCEEDED.
    -- Explicit IS NOT NULL everywhere: a CHECK that evaluates to NULL passes.
    CONSTRAINT ck_resume_failure CHECK (
        (status = 'FAILED' AND failure_reason IS NOT NULL AND btrim(failure_reason) <> '')
        OR (status <> 'FAILED' AND failure_reason IS NULL)),
    CONSTRAINT ck_resume_draft CHECK (
        (status = 'SUCCEEDED' AND draft IS NOT NULL AND jsonb_typeof(draft) = 'object'
            AND warnings IS NOT NULL AND jsonb_typeof(warnings) = 'array')
        OR (status <> 'SUCCEEDED' AND draft IS NULL AND warnings IS NULL))
);
CREATE INDEX ix_resume_sha256 ON resume (sha256);
CREATE INDEX ix_resume_status ON resume (status) WHERE status IN ('PENDING', 'RUNNING');
CREATE TRIGGER trg_resume_updated_at
    BEFORE UPDATE ON resume
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE owner_profile_version (
    version       integer      PRIMARY KEY CHECK (version >= 1),
    profile       jsonb        NOT NULL CHECK (jsonb_typeof(profile) = 'object'),
    -- SET NULL when the upload is deleted; the confirmed history itself is kept.
    resume_id     uuid         REFERENCES resume(id) ON DELETE SET NULL,
    confirmed_at  timestamptz  NOT NULL DEFAULT now()
);

-- Append-only: rows are never deleted or changed. The only allowed UPDATE is the foreign key's
-- own ON DELETE SET NULL of resume_id when an upload is deleted. (TRUNCATE, an admin/test
-- operation, is not a row event and is not blocked.)
CREATE FUNCTION owner_profile_version_append_only() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'owner_profile_version is append-only (version %)', OLD.version;
    END IF;
    IF NEW.version IS DISTINCT FROM OLD.version
            OR NEW.profile IS DISTINCT FROM OLD.profile
            OR NEW.confirmed_at IS DISTINCT FROM OLD.confirmed_at
            OR NEW.resume_id IS NOT NULL THEN
        RAISE EXCEPTION 'owner_profile_version is append-only (version %)', OLD.version;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_owner_profile_version_append_only
    BEFORE UPDATE OR DELETE ON owner_profile_version
    FOR EACH ROW EXECUTE FUNCTION owner_profile_version_append_only();

CREATE TABLE owner_profile (
    id            boolean      PRIMARY KEY DEFAULT true CHECK (id),
    -- RESTRICT: deleting the owner's candidate must never silently delete the confirmed profile
    -- (CandidateService.delete answers 409 first; this is the backstop).
    candidate_id  uuid         NOT NULL UNIQUE REFERENCES candidate(id) ON DELETE RESTRICT,
    resume_id     uuid         REFERENCES resume(id) ON DELETE SET NULL,
    profile       jsonb        NOT NULL CHECK (jsonb_typeof(profile) = 'object'),
    -- The current version; its history row holds the same profile.
    version       integer      NOT NULL REFERENCES owner_profile_version(version),
    confirmed_at  timestamptz  NOT NULL DEFAULT now(),
    created_at    timestamptz  NOT NULL DEFAULT now(),
    updated_at    timestamptz  NOT NULL DEFAULT now()
);
CREATE TRIGGER trg_owner_profile_updated_at
    BEFORE UPDATE ON owner_profile
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON TABLE resume IS 'Uploaded CVs (PII): original PDF, extracted text and the AI-extracted draft profile.';
COMMENT ON COLUMN resume.attempts IS 'Extraction claims since upload or the last manual retry; after 3 the CV is FAILED/TOO_MANY_ATTEMPTS (a CV that crashes the app is not re-run forever).';
COMMENT ON COLUMN resume.warnings IS 'Grounding warnings for the draft: values not found in the CV text, for the owner to check.';
COMMENT ON TABLE owner_profile IS 'Single row: the owner''s confirmed master profile, linked to their candidate row.';
COMMENT ON TABLE owner_profile_version IS 'Append-only history of confirmed profiles (PII): one row per successful PUT /api/profile.';
