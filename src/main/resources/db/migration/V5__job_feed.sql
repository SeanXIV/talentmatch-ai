-- V5__job_feed.sql — fresh job feed (Phase 5).
-- job gets an origin: MANUAL (API / ETL, the default) or FEED (created by the job feed pipeline).
-- The (title, company) natural key now applies to MANUAL jobs only; feed jobs are deduplicated
-- by feed_job.dedup_key instead. External writers must use
--     ON CONFLICT (title, company) WHERE origin = 'MANUAL'
-- (PostgreSQL only infers a partial unique index when the predicate is repeated).
-- The new tables are read and written with JDBC only (not mapped in JPA).

-- ---------------------------------------------------------------- job origin and uniqueness

ALTER TABLE job ADD COLUMN origin varchar(10) NOT NULL DEFAULT 'MANUAL'
    CONSTRAINT ck_job_origin CHECK (origin IN ('MANUAL', 'FEED'));
ALTER TABLE job DROP CONSTRAINT uq_job_title_company;
CREATE UNIQUE INDEX uq_job_title_company_manual ON job (title, company) WHERE origin = 'MANUAL';
-- Lets feed_job reference (id, 'FEED') so only FEED jobs can have feed rows.
ALTER TABLE job ADD CONSTRAINT uq_job_id_origin UNIQUE (id, origin);
CREATE INDEX ix_job_origin_feed ON job (id) WHERE origin = 'FEED';

-- ---------------------------------------------------------------- skill aliases

CREATE TABLE skill_alias (
    id          uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    skill_id    uuid          NOT NULL REFERENCES skill(id) ON DELETE CASCADE,
    alias       varchar(100)  NOT NULL CHECK (alias = btrim(alias) AND alias <> ''),
    created_at  timestamptz   NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_skill_alias_lower ON skill_alias (lower(alias));
CREATE INDEX ix_skill_alias_skill ON skill_alias (skill_id);

-- ---------------------------------------------------------------- sources, feed jobs, postings

CREATE TABLE feed_source (
    id                     uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    source_key             varchar(300)  NOT NULL UNIQUE,
    kind                   varchar(20)   NOT NULL CHECK (kind IN ('GREENHOUSE', 'LEVER', 'ASHBY', 'ADZUNA')),
    managed_by             varchar(12)   NOT NULL DEFAULT 'OWNER' CHECK (managed_by IN ('OWNER', 'PREFERENCES')),
    state                  varchar(10)   NOT NULL DEFAULT 'ACTIVE' CHECK (state IN ('ACTIVE', 'PAUSED')),
    company_name           varchar(200),
    board_token            varchar(100)  CHECK (board_token ~ '^[A-Za-z0-9._-]{1,100}$'),
    options                jsonb         NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(options) = 'object'),
    poll_interval_seconds  integer       CHECK (poll_interval_seconds BETWEEN 60 AND 86400),
    next_poll_at           timestamptz   NOT NULL DEFAULT now(),
    lease_until            timestamptz,
    last_polled_at         timestamptz,
    last_success_at        timestamptz,
    last_status            varchar(20)   CHECK (last_status IN ('OK', 'NOT_MODIFIED', 'RATE_LIMITED', 'NOT_FOUND',
                               'UNAUTHORIZED', 'ERROR', 'INVALID_RESPONSE', 'TOO_LARGE', 'BUDGET_EXHAUSTED',
                               'SUSPICIOUS_EMPTY')),
    last_error             varchar(300),
    consecutive_failures   integer       NOT NULL DEFAULT 0 CHECK (consecutive_failures >= 0),
    etag                   varchar(300),
    last_modified          varchar(100),
    content_hash           char(64)      CHECK (content_hash ~ '^[0-9a-f]{64}$'),
    baseline_at            timestamptz,
    suspicious_since       timestamptz,
    open_postings          integer       NOT NULL DEFAULT 0 CHECK (open_postings >= 0),
    created_at             timestamptz   NOT NULL DEFAULT now(),
    updated_at             timestamptz   NOT NULL DEFAULT now(),
    -- ATS boards need a token and are always owner-managed; an Adzuna query needs a country.
    -- (options ->> 'country' instead of the jsonb ? operator: '?' is a JDBC placeholder character.)
    CONSTRAINT ck_feed_source_shape CHECK (
        (kind IN ('GREENHOUSE', 'LEVER', 'ASHBY') AND board_token IS NOT NULL AND managed_by = 'OWNER')
        OR (kind = 'ADZUNA' AND board_token IS NULL AND options ->> 'country' IS NOT NULL))
);
CREATE INDEX ix_feed_source_due ON feed_source (next_poll_at) WHERE state = 'ACTIVE';
CREATE TRIGGER trg_feed_source_updated_at
    BEFORE UPDATE ON feed_source
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- One row per canonical (deduplicated) feed job; job holds title, company, description and skills.
CREATE TABLE feed_job (
    job_id                         uuid          PRIMARY KEY,
    origin                         varchar(10)   NOT NULL DEFAULT 'FEED' CHECK (origin = 'FEED'),
    dedup_key                      varchar(600)  NOT NULL,
    first_seen_at                  timestamptz   NOT NULL DEFAULT now(),
    posted_at                      timestamptz,
    closed_at                      timestamptz,
    baseline                       boolean       NOT NULL DEFAULT false,
    primary_url                    varchar(2000) NOT NULL CHECK (primary_url ~ '^https?://'),
    location_text                  varchar(500),
    country_codes                  varchar(2)[]  NOT NULL DEFAULT '{}',
    workplace                      varchar(10)   NOT NULL DEFAULT 'UNKNOWN'
                                     CHECK (workplace IN ('REMOTE', 'HYBRID', 'ONSITE', 'UNKNOWN')),
    employment_type                varchar(50),
    seniority                      varchar(12)   NOT NULL DEFAULT 'UNKNOWN' CHECK (seniority IN
                                     ('INTERN', 'JUNIOR', 'MID', 'SENIOR', 'LEAD', 'PRINCIPAL', 'MANAGER', 'UNKNOWN')),
    salary_min                     numeric(14,2) CHECK (salary_min IS NULL OR salary_min >= 0),
    salary_max                     numeric(14,2) CHECK (salary_max IS NULL OR salary_max >= 0),
    salary_currency                char(3)       CHECK (salary_currency ~ '^[A-Z]{3}$'),
    salary_period                  varchar(5)    CHECK (salary_period IN ('YEAR', 'MONTH', 'DAY', 'HOUR')),
    salary_estimated               boolean       NOT NULL DEFAULT false,
    description_hash               char(64)      CHECK (description_hash ~ '^[0-9a-f]{64}$'),
    dictionary_skills              jsonb         NOT NULL DEFAULT '[]' CHECK (jsonb_typeof(dictionary_skills) = 'array'),
    ai_skills                      jsonb         CHECK (ai_skills IS NULL OR jsonb_typeof(ai_skills) = 'array'),
    ai_suggestions                 jsonb         NOT NULL DEFAULT '[]' CHECK (jsonb_typeof(ai_suggestions) = 'array'),
    enrichment_status              varchar(10)   NOT NULL DEFAULT 'PENDING'
                                     CHECK (enrichment_status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'SKIPPED')),
    -- Must match com.talentmatch.feed.skills.EnrichmentFailure (an unknown value would fail every read).
    enrichment_failure             varchar(40)   CHECK (enrichment_failure IN ('AI_DISABLED', 'TIMEOUT',
                                     'PROVIDER_ERROR', 'REFUSED', 'INVALID_OUTPUT', 'TOO_MANY_ATTEMPTS')),
    enrichment_attempts            integer       NOT NULL DEFAULT 0 CHECK (enrichment_attempts >= 0),
    enrichment_not_before          timestamptz,
    enrichment_model               varchar(200),
    enriched_at                    timestamptz,
    preference_verdict             varchar(10)   CHECK (preference_verdict IN ('PASS', 'FILTERED')),
    filter_reasons                 jsonb         NOT NULL DEFAULT '[]' CHECK (jsonb_typeof(filter_reasons) = 'array'),
    filter_flags                   jsonb         NOT NULL DEFAULT '[]' CHECK (jsonb_typeof(filter_flags) = 'array'),
    evaluated_preferences_version  integer,
    scored_profile_version         integer,
    scored_at                      timestamptz,
    process_after                  timestamptz   DEFAULT now(),
    created_at                     timestamptz   NOT NULL DEFAULT now(),
    updated_at                     timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT fk_feed_job_job FOREIGN KEY (job_id, origin) REFERENCES job (id, origin) ON DELETE CASCADE,
    -- Explicit IS NOT NULL everywhere: a CHECK that evaluates to NULL passes.
    CONSTRAINT ck_feed_job_enrichment_failure CHECK (
        (enrichment_status = 'FAILED' AND enrichment_failure IS NOT NULL)
        OR (enrichment_status <> 'FAILED' AND enrichment_failure IS NULL)),
    CONSTRAINT ck_feed_job_enriched CHECK (enrichment_status <> 'SUCCEEDED'
        OR (ai_skills IS NOT NULL AND enrichment_model IS NOT NULL AND enriched_at IS NOT NULL)),
    CONSTRAINT ck_feed_job_salary CHECK (salary_min IS NULL OR salary_max IS NULL OR salary_min <= salary_max)
);
-- An open job is unique per key; a re-post after closing becomes a new feed job (it is "new" again).
CREATE UNIQUE INDEX uq_feed_job_open_dedup ON feed_job (dedup_key) WHERE closed_at IS NULL;
CREATE INDEX ix_feed_job_first_seen ON feed_job (first_seen_at DESC, job_id);
CREATE INDEX ix_feed_job_process ON feed_job (process_after) WHERE process_after IS NOT NULL;
CREATE INDEX ix_feed_job_enrichment ON feed_job (first_seen_at DESC)
    WHERE enrichment_status IN ('PENDING', 'RUNNING');
CREATE TRIGGER trg_feed_job_updated_at
    BEFORE UPDATE ON feed_job
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE job_posting (
    id                 uuid           PRIMARY KEY DEFAULT gen_random_uuid(),
    source_id          uuid           NOT NULL REFERENCES feed_source(id) ON DELETE CASCADE,
    external_id        varchar(200)   NOT NULL CHECK (btrim(external_id) <> ''),
    job_id             uuid           NOT NULL REFERENCES feed_job(job_id) ON DELETE CASCADE,
    url                varchar(2000)  NOT NULL CHECK (url ~ '^https?://'),
    title              varchar(300)   NOT NULL CHECK (btrim(title) <> ''),
    company            varchar(200)   NOT NULL CHECK (btrim(company) <> ''),
    description        text,
    location_text      varchar(500),
    country_code       char(2),
    workplace          varchar(10)    NOT NULL DEFAULT 'UNKNOWN'
                         CHECK (workplace IN ('REMOTE', 'HYBRID', 'ONSITE', 'UNKNOWN')),
    employment_type    varchar(50),
    salary_min         numeric(14,2),
    salary_max         numeric(14,2),
    salary_currency    char(3),
    salary_period      varchar(5)     CHECK (salary_period IN ('YEAR', 'MONTH', 'DAY', 'HOUR')),
    salary_estimated   boolean        NOT NULL DEFAULT false,
    posted_at          timestamptz,
    source_updated_at  timestamptz,
    first_seen_at      timestamptz    NOT NULL DEFAULT now(),
    last_seen_at       timestamptz    NOT NULL DEFAULT now(),
    closed_at          timestamptz,
    baseline           boolean        NOT NULL DEFAULT false,
    content_hash       char(64)       NOT NULL CHECK (content_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT uq_job_posting_source_external UNIQUE (source_id, external_id)
);
CREATE INDEX ix_job_posting_job ON job_posting (job_id);
CREATE INDEX ix_job_posting_source_open ON job_posting (source_id) WHERE closed_at IS NULL;

-- ---------------------------------------------------------------- preferences, notifications, state
-- Singletons: no row means defaults (no preferences = no filters; no settings = notifications off).

CREATE TABLE job_preferences (
    id           boolean      PRIMARY KEY DEFAULT true CHECK (id),
    preferences  jsonb        NOT NULL CHECK (jsonb_typeof(preferences) = 'object'),
    version      integer      NOT NULL CHECK (version >= 1),
    updated_at   timestamptz  NOT NULL DEFAULT now()
);
CREATE TRIGGER trg_job_preferences_updated_at
    BEFORE UPDATE ON job_preferences
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE notification_settings (
    id            boolean           PRIMARY KEY DEFAULT true CHECK (id),
    enabled       boolean           NOT NULL DEFAULT false,
    -- Widened by a later migration when push channels arrive.
    channel       varchar(10)       NOT NULL DEFAULT 'EMAIL' CHECK (channel IN ('EMAIL')),
    min_score     double precision  NOT NULL DEFAULT 0.6 CHECK (min_score >= 0 AND min_score <= 1),
    max_per_hour  integer           NOT NULL DEFAULT 20 CHECK (max_per_hour BETWEEN 1 AND 200),
    updated_at    timestamptz       NOT NULL DEFAULT now()
);
CREATE TRIGGER trg_notification_settings_updated_at
    BEFORE UPDATE ON notification_settings
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- At most one notification per job, ever (decision g); the channel used is recorded.
CREATE TABLE feed_notification (
    id               uuid              PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id           uuid              NOT NULL UNIQUE REFERENCES feed_job(job_id) ON DELETE CASCADE,
    channel          varchar(10)       NOT NULL CHECK (channel IN ('EMAIL')),
    status           varchar(10)       NOT NULL DEFAULT 'PENDING'
                                         CHECK (status IN ('PENDING', 'SENDING', 'SENT', 'FAILED')),
    score            double precision  NOT NULL CHECK (score >= 0 AND score <= 1),
    attempts         integer           NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at  timestamptz       NOT NULL DEFAULT now(),
    last_error       varchar(300),
    created_at       timestamptz       NOT NULL DEFAULT now(),
    sent_at          timestamptz,
    updated_at       timestamptz       NOT NULL DEFAULT now(),
    CONSTRAINT ck_feed_notification_sent CHECK ((status = 'SENT') = (sent_at IS NOT NULL))
);
CREATE INDEX ix_feed_notification_due ON feed_notification (next_attempt_at)
    WHERE status IN ('PENDING', 'SENDING');
CREATE INDEX ix_feed_notification_created ON feed_notification (created_at DESC);
CREATE TRIGGER trg_feed_notification_updated_at
    BEFORE UPDATE ON feed_notification
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE feed_state (
    id                           boolean      PRIMARY KEY DEFAULT true CHECK (id),
    applied_profile_version      integer,
    applied_preferences_version  integer,
    skill_vocab_fingerprint      varchar(100),
    updated_at                   timestamptz  NOT NULL DEFAULT now()
);
CREATE TRIGGER trg_feed_state_updated_at
    BEFORE UPDATE ON feed_state
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE feed_api_usage (
    provider  varchar(20)  NOT NULL,
    day       date         NOT NULL,
    requests  integer      NOT NULL DEFAULT 0 CHECK (requests >= 0),
    PRIMARY KEY (provider, day)
);

-- ---------------------------------------------------------------- comments

COMMENT ON COLUMN job.origin IS 'MANUAL (API / ETL; unique by title and company) or FEED (owned by the job feed; read-only in /api/jobs).';
COMMENT ON TABLE skill_alias IS 'Alternative names that resolve to a skill (e.g. Postgres -> PostgreSQL); an alias never equals a skill name.';
COMMENT ON TABLE feed_source IS 'Watched job sources: company ATS boards (owner-managed) and aggregator queries (owner- or preference-managed).';
COMMENT ON COLUMN feed_source.last_error IS 'Sanitized: never a response body, a URL with a query string or an API key.';
COMMENT ON COLUMN feed_source.baseline_at IS 'First successful poll; postings seen then are baseline (not new) unless recently posted.';
COMMENT ON COLUMN feed_source.suspicious_since IS 'Set when a poll returned suspiciously few postings; closing waits for a second such poll.';
COMMENT ON TABLE feed_job IS 'One row per canonical (deduplicated) FEED job; job holds title, company, description and skills.';
COMMENT ON COLUMN feed_job.process_after IS 'Non-null: the feed processor must (re)process the job (skills, filter, score, notification).';
COMMENT ON COLUMN feed_job.dictionary_skills IS 'Skills found by the dictionary (only existing skill names or aliases; never invented).';
COMMENT ON COLUMN feed_job.ai_suggestions IS 'Grounded AI-found skills not in the vocabulary; never created automatically.';
COMMENT ON TABLE job_posting IS 'One provider posting (source, external id), attached to a canonical feed job.';
COMMENT ON COLUMN job_posting.description IS 'Cleaned plain text, at most 20000 characters.';
COMMENT ON TABLE job_preferences IS 'Single row: job_preferences is entered by hand by the owner; never written by any AI code path.';
COMMENT ON TABLE notification_settings IS 'Single row: notification switch, channel, score threshold and hourly cap (no row = disabled).';
COMMENT ON TABLE feed_notification IS 'feed_notification: at most one per job, ever; delivery status of the alert.';
COMMENT ON TABLE feed_state IS 'Single row: profile/preferences versions and skill vocabulary fingerprint the feed was last refreshed against.';
COMMENT ON TABLE feed_api_usage IS 'Requests per provider per UTC day (aggregator daily budget).';
