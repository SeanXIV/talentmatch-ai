-- V2__skill_link_staleness.sql — make skill-link changes visible to match staleness.
-- Any real INSERT/UPDATE/DELETE on candidate_skill / job_skill bumps the parent's
-- updated_at, so job_match.computed_at < max(candidate.updated_at, job.updated_at)
-- detects skill changes. Statement-level triggers with transition tables: one parent
-- UPDATE per statement; statements touching zero rows (no-op ETL reruns) change nothing.
-- The "IS DISTINCT FROM now()" guard skips parents already touched in this transaction.

CREATE FUNCTION touch_candidate_from_links() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        UPDATE candidate SET updated_at = now()
         WHERE id IN (SELECT candidate_id FROM new_links)
           AND updated_at IS DISTINCT FROM now();
    ELSIF TG_OP = 'UPDATE' THEN
        UPDATE candidate SET updated_at = now()
         WHERE id IN (SELECT candidate_id FROM new_links
                      UNION SELECT candidate_id FROM old_links)
           AND updated_at IS DISTINCT FROM now();
    ELSE
        UPDATE candidate SET updated_at = now()
         WHERE id IN (SELECT candidate_id FROM old_links)
           AND updated_at IS DISTINCT FROM now();
    END IF;
    RETURN NULL;
END;
$$;

CREATE FUNCTION touch_job_from_links() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        UPDATE job SET updated_at = now()
         WHERE id IN (SELECT job_id FROM new_links)
           AND updated_at IS DISTINCT FROM now();
    ELSIF TG_OP = 'UPDATE' THEN
        UPDATE job SET updated_at = now()
         WHERE id IN (SELECT job_id FROM new_links
                      UNION SELECT job_id FROM old_links)
           AND updated_at IS DISTINCT FROM now();
    ELSE
        UPDATE job SET updated_at = now()
         WHERE id IN (SELECT job_id FROM old_links)
           AND updated_at IS DISTINCT FROM now();
    END IF;
    RETURN NULL;
END;
$$;

-- PostgreSQL forbids transition tables on multi-event triggers: one trigger per event.
CREATE TRIGGER trg_candidate_skill_touch_ins AFTER INSERT ON candidate_skill
    REFERENCING NEW TABLE AS new_links
    FOR EACH STATEMENT EXECUTE FUNCTION touch_candidate_from_links();
CREATE TRIGGER trg_candidate_skill_touch_upd AFTER UPDATE ON candidate_skill
    REFERENCING OLD TABLE AS old_links NEW TABLE AS new_links
    FOR EACH STATEMENT EXECUTE FUNCTION touch_candidate_from_links();
CREATE TRIGGER trg_candidate_skill_touch_del AFTER DELETE ON candidate_skill
    REFERENCING OLD TABLE AS old_links
    FOR EACH STATEMENT EXECUTE FUNCTION touch_candidate_from_links();

CREATE TRIGGER trg_job_skill_touch_ins AFTER INSERT ON job_skill
    REFERENCING NEW TABLE AS new_links
    FOR EACH STATEMENT EXECUTE FUNCTION touch_job_from_links();
CREATE TRIGGER trg_job_skill_touch_upd AFTER UPDATE ON job_skill
    REFERENCING OLD TABLE AS old_links NEW TABLE AS new_links
    FOR EACH STATEMENT EXECUTE FUNCTION touch_job_from_links();
CREATE TRIGGER trg_job_skill_touch_del AFTER DELETE ON job_skill
    REFERENCING OLD TABLE AS old_links
    FOR EACH STATEMENT EXECUTE FUNCTION touch_job_from_links();
