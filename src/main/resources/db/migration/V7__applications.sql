-- V7__applications.sql — tailored CVs, cover letters and the application tracker (Phase 7).
-- application: one row per job the owner tracks (at most one per job). It keeps a snapshot of the
--   job's title, company and URL, so it outlives the job (job_id becomes NULL when the job is deleted).
-- application_document: immutable versions of a tailored CV or cover letter (AI-generated or an owner
--   edit), each built from one owner_profile_version. Only the approval may be recorded later, once.
-- application_document_file: the PDF and DOCX rendered when a version is approved (what gets sent).
-- application_generation: queued / running / finished AI generations (at most one active per application).
-- application_event: status history.
-- All JDBC-only (not mapped in JPA). PII: document content, files and notes hold the owner's CV data.

CREATE TABLE application (
    id                      uuid           PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id                  uuid           REFERENCES job(id) ON DELETE SET NULL,
    job_title               varchar(300)   NOT NULL CHECK (btrim(job_title) <> ''),
    job_company             varchar(200)   NOT NULL CHECK (btrim(job_company) <> ''),
    job_url                 varchar(2000)  CHECK (job_url ~ '^https?://'),
    status                  varchar(12)    NOT NULL DEFAULT 'NEW',
    status_changed_at       timestamptz    NOT NULL DEFAULT now(),
    applied_on              date,
    notes                   text           CHECK (notes IS NULL OR (btrim(notes) <> '' AND char_length(notes) <= 10000)),
    sent_cv_id              uuid,
    -- Constant columns so the composite FKs below can also pin the document kind.
    sent_cv_kind            varchar(12)    NOT NULL DEFAULT 'CV' CHECK (sent_cv_kind = 'CV'),
    sent_cover_letter_id    uuid,
    sent_cover_letter_kind  varchar(12)    NOT NULL DEFAULT 'COVER_LETTER'
                                             CHECK (sent_cover_letter_kind = 'COVER_LETTER'),
    created_at              timestamptz    NOT NULL DEFAULT now(),
    updated_at              timestamptz    NOT NULL DEFAULT now(),
    -- Must match com.talentmatch.application.ApplicationStatus.
    CONSTRAINT ck_application_status CHECK (status IN ('NEW', 'SHORTLISTED', 'APPLIED', 'INTERVIEW', 'OFFER',
        'REJECTED', 'WITHDRAWN', 'DISMISSED')),
    -- Applied statuses need the date; earlier statuses have no date and no sent versions.
    CONSTRAINT ck_application_applied CHECK (
        (status IN ('APPLIED', 'INTERVIEW', 'OFFER', 'REJECTED', 'WITHDRAWN') AND applied_on IS NOT NULL)
        OR (status IN ('NEW', 'SHORTLISTED', 'DISMISSED') AND applied_on IS NULL
            AND sent_cv_id IS NULL AND sent_cover_letter_id IS NULL))
);
CREATE UNIQUE INDEX uq_application_job ON application (job_id) WHERE job_id IS NOT NULL;
CREATE INDEX ix_application_status_updated ON application (status, updated_at DESC);
CREATE TRIGGER trg_application_updated_at
    BEFORE UPDATE ON application
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE application_document (
    id                     uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    application_id         uuid          NOT NULL REFERENCES application(id) ON DELETE CASCADE,
    kind                   varchar(12)   NOT NULL CHECK (kind IN ('CV', 'COVER_LETTER')),
    version                integer       NOT NULL CHECK (version >= 1),
    source                 varchar(5)    NOT NULL CHECK (source IN ('AI', 'EDIT')),
    based_on_id            uuid,
    profile_version        integer       NOT NULL REFERENCES owner_profile_version(version),
    job_updated_at         timestamptz,  -- job.updated_at the version was built against (NULL: job gone)
    content                jsonb         NOT NULL CHECK (jsonb_typeof(content) = 'object'),
    findings               jsonb         NOT NULL DEFAULT '[]' CHECK (jsonb_typeof(findings) = 'array'),
    model                  varchar(200),
    input_hash             char(64)      CHECK (input_hash ~ '^[0-9a-f]{64}$'),
    approved_at            timestamptz,
    approval_acknowledged  boolean,
    created_at             timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT uq_application_document_version UNIQUE (application_id, kind, version),
    CONSTRAINT uq_application_document_owner UNIQUE (id, application_id, kind),
    -- An edit's base is a version of the same application and kind.
    CONSTRAINT fk_application_document_based_on FOREIGN KEY (based_on_id, application_id, kind)
        REFERENCES application_document (id, application_id, kind),
    CONSTRAINT ck_application_document_source CHECK (
        (source = 'AI' AND model IS NOT NULL AND btrim(model) <> '' AND input_hash IS NOT NULL AND based_on_id IS NULL)
        OR (source = 'EDIT' AND based_on_id IS NOT NULL AND model IS NULL AND input_hash IS NULL)),
    CONSTRAINT ck_application_document_approval CHECK ((approved_at IS NULL) = (approval_acknowledged IS NULL))
);
CREATE INDEX ix_application_document_app ON application_document (application_id, kind, version DESC);

ALTER TABLE application
    ADD CONSTRAINT fk_application_sent_cv FOREIGN KEY (sent_cv_id, id, sent_cv_kind)
        REFERENCES application_document (id, application_id, kind),
    ADD CONSTRAINT fk_application_sent_cover_letter FOREIGN KEY (sent_cover_letter_id, id, sent_cover_letter_kind)
        REFERENCES application_document (id, application_id, kind);

-- Append-only. The only UPDATE records the approval once; the only DELETE is the cascade from
-- deleting the application itself (the parent row is already gone when the cascade runs).
CREATE FUNCTION application_document_append_only() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF EXISTS (SELECT 1 FROM application a WHERE a.id = OLD.application_id) THEN
            RAISE EXCEPTION 'application_document is append-only (document %)', OLD.id;
        END IF;
        RETURN OLD;
    END IF;
    IF OLD.approved_at IS NOT NULL OR NEW.approved_at IS NULL
            OR (NEW.id, NEW.application_id, NEW.kind, NEW.version, NEW.source, NEW.based_on_id,
                NEW.profile_version, NEW.job_updated_at, NEW.content, NEW.findings, NEW.model,
                NEW.input_hash, NEW.created_at)
               IS DISTINCT FROM
               (OLD.id, OLD.application_id, OLD.kind, OLD.version, OLD.source, OLD.based_on_id,
                OLD.profile_version, OLD.job_updated_at, OLD.content, OLD.findings, OLD.model,
                OLD.input_hash, OLD.created_at) THEN
        RAISE EXCEPTION 'application_document is append-only (document %)', OLD.id;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_application_document_append_only
    BEFORE UPDATE OR DELETE ON application_document
    FOR EACH ROW EXECUTE FUNCTION application_document_append_only();

CREATE TABLE application_document_file (
    document_id  uuid          NOT NULL REFERENCES application_document(id) ON DELETE CASCADE,
    format       varchar(4)    NOT NULL CHECK (format IN ('PDF', 'DOCX')),
    content      bytea         NOT NULL,
    size_bytes   integer       NOT NULL CHECK (size_bytes > 0 AND size_bytes <= 5242880),
    sha256       char(64)      NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    created_at   timestamptz   NOT NULL DEFAULT now(),
    PRIMARY KEY (document_id, format),
    CONSTRAINT ck_application_document_file_size CHECK (size_bytes = octet_length(content))
);

-- Files exist only for approved versions and never change; deleted only by the cascade.
CREATE FUNCTION application_document_file_guard() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NOT EXISTS (SELECT 1 FROM application_document d
                       WHERE d.id = NEW.document_id AND d.approved_at IS NOT NULL) THEN
            RAISE EXCEPTION 'files are stored for approved versions only (document %)', NEW.document_id;
        END IF;
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        IF EXISTS (SELECT 1 FROM application_document d WHERE d.id = OLD.document_id) THEN
            RAISE EXCEPTION 'application_document_file is append-only (document %)', OLD.document_id;
        END IF;
        RETURN OLD;
    END IF;
    RAISE EXCEPTION 'application_document_file is append-only (document %)', OLD.document_id;
END;
$$;
CREATE TRIGGER trg_application_document_file_guard
    BEFORE INSERT OR UPDATE OR DELETE ON application_document_file
    FOR EACH ROW EXECUTE FUNCTION application_document_file_guard();

CREATE TABLE application_generation (
    id                        uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    application_id            uuid          NOT NULL REFERENCES application(id) ON DELETE CASCADE,
    want_cv                   boolean       NOT NULL,
    want_cover_letter         boolean       NOT NULL,
    mention_notice_period     boolean       NOT NULL DEFAULT false,
    status                    varchar(10)   NOT NULL DEFAULT 'PENDING',
    failure_reason            varchar(40),
    attempts                  integer       NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    model                     varchar(200),
    profile_version           integer       REFERENCES owner_profile_version(version),
    cv_document_id            uuid          REFERENCES application_document(id),
    cv_failure                varchar(40),
    cover_letter_document_id  uuid          REFERENCES application_document(id),
    cover_letter_failure      varchar(40),
    queued_at                 timestamptz   NOT NULL DEFAULT now(),
    started_at                timestamptz,
    finished_at               timestamptz,
    updated_at                timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT ck_generation_kinds CHECK (want_cv OR want_cover_letter),
    CONSTRAINT ck_generation_status CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED')),
    -- Must match com.talentmatch.application.tailoring.GenerationFailure.
    CONSTRAINT ck_generation_failure_values CHECK (
        (failure_reason IS NULL OR failure_reason IN ('AI_DISABLED', 'TIMEOUT', 'PROVIDER_ERROR', 'REFUSED',
            'INVALID_OUTPUT', 'QUEUE_FULL', 'CONTEXT_OVERFLOW', 'TOO_MANY_ATTEMPTS',
            'REMOTE_GENERATION_DISABLED', 'JOB_DELETED'))
        AND (cv_failure IS NULL OR cv_failure IN ('TIMEOUT', 'PROVIDER_ERROR', 'REFUSED', 'INVALID_OUTPUT',
            'CONTEXT_OVERFLOW'))
        AND (cover_letter_failure IS NULL OR cover_letter_failure IN ('TIMEOUT', 'PROVIDER_ERROR', 'REFUSED',
            'INVALID_OUTPUT', 'CONTEXT_OVERFLOW'))),
    CONSTRAINT ck_generation_failure CHECK (
        (status = 'FAILED' AND failure_reason IS NOT NULL) OR (status <> 'FAILED' AND failure_reason IS NULL)),
    CONSTRAINT ck_generation_running CHECK (status <> 'RUNNING' OR started_at IS NOT NULL),
    CONSTRAINT ck_generation_finished CHECK ((status IN ('SUCCEEDED', 'FAILED')) = (finished_at IS NOT NULL)),
    CONSTRAINT ck_generation_succeeded CHECK (status <> 'SUCCEEDED'
        OR (model IS NOT NULL AND (cv_document_id IS NOT NULL OR cover_letter_document_id IS NOT NULL))),
    CONSTRAINT ck_generation_cv_result CHECK ((want_cv OR (cv_document_id IS NULL AND cv_failure IS NULL))
        AND (cv_document_id IS NULL OR cv_failure IS NULL)),
    CONSTRAINT ck_generation_cl_result CHECK (
        (want_cover_letter OR (cover_letter_document_id IS NULL AND cover_letter_failure IS NULL))
        AND (cover_letter_document_id IS NULL OR cover_letter_failure IS NULL))
);
CREATE UNIQUE INDEX uq_application_generation_active ON application_generation (application_id)
    WHERE status IN ('PENDING', 'RUNNING');
CREATE INDEX ix_application_generation_status ON application_generation (status)
    WHERE status IN ('PENDING', 'RUNNING');
CREATE INDEX ix_application_generation_app ON application_generation (application_id, queued_at DESC);
CREATE TRIGGER trg_application_generation_updated_at
    BEFORE UPDATE ON application_generation
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE application_event (
    id              bigint        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    application_id  uuid          NOT NULL REFERENCES application(id) ON DELETE CASCADE,
    from_status     varchar(12),  -- NULL for the creation event
    to_status       varchar(12)   NOT NULL,
    forced          boolean       NOT NULL DEFAULT false,
    at              timestamptz   NOT NULL DEFAULT now()
);
CREATE INDEX ix_application_event_app ON application_event (application_id, at, id);

COMMENT ON TABLE application IS 'Jobs the owner tracks (PII: notes). At most one per job; job snapshot survives job deletion.';
COMMENT ON TABLE application_document IS 'Immutable tailored CV / cover letter versions (PII). Approval is recorded once.';
COMMENT ON COLUMN application_document.content IS 'Self-contained document: facts copied from profile_version plus rephrased text.';
COMMENT ON COLUMN application_document.findings IS 'Grounding findings [{severity, code, path, value, message}] at creation time.';
COMMENT ON TABLE application_document_file IS 'PDF and DOCX rendered at approval: the exact bytes the owner sends (PII).';
COMMENT ON TABLE application_generation IS 'AI generation queue rows (one active per application; recovered on startup).';
