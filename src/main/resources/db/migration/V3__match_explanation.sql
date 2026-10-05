-- V3__match_explanation.sql — persisted AI explanations with staleness detection (Phase 3).
-- ai_explanation (V1) keeps the plain explanation text; the new columns hold the structured
-- payload and what it was generated from. A stored explanation is current only while
-- explanation_input_hash equals the hash of the prompt the API would send now
-- (DATA_MODEL.md). computed_at is NOT used: every score refresh advances it.
-- The score upsert never touches any of these columns.

ALTER TABLE job_match
    ADD COLUMN explanation_payload      jsonb,
    ADD COLUMN explanation_input_hash   char(64),
    ADD COLUMN explanation_model        varchar(200),
    ADD COLUMN explanation_generated_at timestamptz;

-- All or nothing: an explanation is either fully recorded or absent. Every column in the second
-- branch needs an explicit IS NOT NULL: a CHECK that evaluates to NULL passes.
ALTER TABLE job_match
    ADD CONSTRAINT ck_job_match_explanation_complete CHECK (
        (ai_explanation IS NULL AND explanation_payload IS NULL AND explanation_input_hash IS NULL
            AND explanation_model IS NULL AND explanation_generated_at IS NULL)
        OR
        (ai_explanation IS NOT NULL AND btrim(ai_explanation) <> ''
            AND explanation_payload IS NOT NULL AND jsonb_typeof(explanation_payload) = 'object'
            AND explanation_input_hash IS NOT NULL AND explanation_input_hash ~ '^[0-9a-f]{64}$'
            AND explanation_model IS NOT NULL AND btrim(explanation_model) <> ''
            AND explanation_generated_at IS NOT NULL));

COMMENT ON COLUMN job_match.ai_explanation IS 'AI explanation text (NULL = none). Current only while explanation_input_hash matches.';
COMMENT ON COLUMN job_match.explanation_payload IS 'Validated AI output: {headline, explanation, strengths[], gaps[]}.';
COMMENT ON COLUMN job_match.explanation_input_hash IS 'sha256 hex of the exact prompt (system + user) the explanation was generated from.';
COMMENT ON COLUMN job_match.explanation_model IS 'provider/model label, e.g. ollama/qwen2.5:7b-instruct.';
COMMENT ON COLUMN job_match.explanation_generated_at IS 'When the explanation was generated.';
