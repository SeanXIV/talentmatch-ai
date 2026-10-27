-- V6__seeker_matches.sql — seeker-facing explanations (Phase 6). Same rules as V3:
-- written only by the guarded UPDATE (MatchJdbcRepository.saveExplanation(SEEKER, …)),
-- never by the score upsert; current only while seeker_explanation_input_hash equals the hash
-- of the seeker prompt the API would send now.
ALTER TABLE job_match
    ADD COLUMN seeker_explanation_payload      jsonb,
    ADD COLUMN seeker_explanation_input_hash   char(64),
    ADD COLUMN seeker_explanation_model        varchar(200),
    ADD COLUMN seeker_explanation_generated_at timestamptz;

-- All or nothing, as in V3. There is no seeker text column: the payload's 'explanation' key is the text.
ALTER TABLE job_match
    ADD CONSTRAINT ck_job_match_seeker_explanation_complete CHECK (
        (seeker_explanation_payload IS NULL AND seeker_explanation_input_hash IS NULL
            AND seeker_explanation_model IS NULL AND seeker_explanation_generated_at IS NULL)
        OR
        (seeker_explanation_payload IS NOT NULL AND jsonb_typeof(seeker_explanation_payload) = 'object'
            AND seeker_explanation_payload ? 'explanation'
            AND seeker_explanation_input_hash IS NOT NULL AND seeker_explanation_input_hash ~ '^[0-9a-f]{64}$'
            AND seeker_explanation_model IS NOT NULL AND btrim(seeker_explanation_model) <> ''
            AND seeker_explanation_generated_at IS NOT NULL));

-- Candidate-side ranking (score sort) and counting.
CREATE INDEX ix_job_match_candidate_score ON job_match (candidate_id, score DESC);

COMMENT ON COLUMN job_match.seeker_explanation_payload IS
  'Validated seeker-facing AI output {headline, explanation, strengths[], gaps[]} (second person). NULL = none.';
COMMENT ON COLUMN job_match.seeker_explanation_input_hash IS
  'sha256 hex of the exact seeker prompt (seeker system + user message) it was generated from.';
COMMENT ON COLUMN job_match.seeker_explanation_model IS 'provider/model label.';
COMMENT ON COLUMN job_match.seeker_explanation_generated_at IS 'When the seeker explanation was generated.';
