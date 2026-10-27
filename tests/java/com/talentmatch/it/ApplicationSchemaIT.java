package com.talentmatch.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.talentmatch.support.AbstractApiIT;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

/**
 * Phase 7 §9.2 item 1: V7 constraints and triggers, checked directly via JDBC. Includes the step-1 gate
 * check (§11.1): deleting an application cascades through the append-only and file-guard triggers.
 */
class ApplicationSchemaIT extends AbstractApiIT {

    private static final String HASH = "b".repeat(64);
    private static final byte[] BYTES = {1, 2, 3};

    private UUID job;
    private int profileVersion;

    @BeforeEach
    void seed() {
        job = jobWithoutSkills("Backend Engineer", "Acme");
        profileVersion = jdbc.queryForObject("INSERT INTO owner_profile_version (version, profile) VALUES (1, '{}') "
                + "RETURNING version", Integer.class);
    }

    // ------------------------------------------------------------------ helpers

    private void rejected(String sql, Object... args) {
        assertThatThrownBy(() -> jdbc.update(sql, args)).as(sql).isInstanceOf(DataAccessException.class);
    }

    private UUID application(UUID jobId) {
        return jdbc.queryForObject("INSERT INTO application (job_id, job_title, job_company) VALUES (?, 'T', 'C') "
                + "RETURNING id", UUID.class, jobId);
    }

    /** An AI version (unapproved). */
    private UUID aiDoc(UUID app, String kind, int version) {
        return jdbc.queryForObject("INSERT INTO application_document (application_id, kind, version, source, "
                + "profile_version, content, model, input_hash) VALUES (?, ?, ?, 'AI', ?, '{}', 'fake/m', ?) "
                + "RETURNING id", UUID.class, app, kind, version, profileVersion, HASH);
    }

    /** An EDIT version based on {@code base} (unapproved). */
    private UUID editDoc(UUID app, String kind, int version, UUID base) {
        return jdbc.queryForObject("INSERT INTO application_document (application_id, kind, version, source, "
                + "based_on_id, profile_version, content) VALUES (?, ?, ?, 'EDIT', ?, ?, '{}') RETURNING id",
                UUID.class, app, kind, version, base, profileVersion);
    }

    private void approve(UUID doc) {
        jdbc.update("UPDATE application_document SET approved_at = now(), approval_acknowledged = false WHERE id = ?",
                doc);
    }

    private void file(UUID doc, String format) {
        jdbc.update("INSERT INTO application_document_file (document_id, format, content, size_bytes, sha256) "
                + "VALUES (?, ?, ?, 3, ?)", doc, format, BYTES, HASH);
    }

    private UUID succeededGeneration(UUID app, UUID cvDoc, UUID letterDoc) {
        return jdbc.queryForObject("INSERT INTO application_generation (application_id, want_cv, want_cover_letter, "
                + "status, attempts, model, profile_version, cv_document_id, cover_letter_document_id, started_at, "
                + "finished_at) VALUES (?, true, true, 'SUCCEEDED', 1, 'fake/m', ?, ?, ?, now(), now()) RETURNING id",
                UUID.class, app, profileVersion, cvDoc, letterDoc);
    }

    private int count(String table, String column, UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + column + " = ?", Integer.class, id);
    }

    // ------------------------------------------------------------------ application

    @Test
    void validRowsAreAccepted() {
        UUID app = application(job);
        UUID cv = aiDoc(app, "CV", 1);
        UUID cv2 = editDoc(app, "CV", 2, cv);
        UUID letter = aiDoc(app, "COVER_LETTER", 1);
        approve(cv2);
        approve(letter);
        file(cv2, "PDF");
        file(cv2, "DOCX");
        succeededGeneration(app, cv, letter);
        jdbc.update("INSERT INTO application_event (application_id, from_status, to_status) VALUES (?, NULL, 'NEW')",
                app);
        jdbc.update("UPDATE application SET status = 'APPLIED', applied_on = current_date, sent_cv_id = ?, "
                + "sent_cover_letter_id = ? WHERE id = ?", cv2, letter, app);
        assertThat(jdbc.queryForObject("SELECT status FROM application WHERE id = ?", String.class, app))
                .isEqualTo("APPLIED");
    }

    @Test
    void applicationChecks() {
        UUID app = application(job);
        String where = " WHERE id = '" + app + "'";
        for (String set : List.of(
                "status = 'BOGUS'",
                "status = 'APPLIED'",                                     // applied without a date
                "status = 'INTERVIEW'",
                "status = 'WITHDRAWN'",
                "applied_on = current_date",                              // NEW with a date
                "status = 'DISMISSED', applied_on = current_date",
                "notes = '   '",                                          // blank notes
                "notes = repeat('x', 10001)",                             // too long
                "job_title = ' '",
                "job_company = ''",
                "job_url = 'ftp://x.test/j'",
                "sent_cv_kind = 'COVER_LETTER'",
                "sent_cover_letter_kind = 'CV'")) {
            rejected("UPDATE application SET " + set + where);
        }
        jdbc.update("UPDATE application SET notes = repeat('é', 10000), job_url = 'https://x.test/j'" + where);

        // NEW (or SHORTLISTED, DISMISSED) with a sent id, even one of its own approved versions
        UUID cv = aiDoc(app, "CV", 1);
        approve(cv);
        rejected("UPDATE application SET sent_cv_id = ?" + where, cv);
        rejected("UPDATE application SET status = 'SHORTLISTED', sent_cv_id = ?" + where, cv);
        jdbc.update("UPDATE application SET status = 'REJECTED', applied_on = current_date, sent_cv_id = ?" + where,
                cv);
    }

    @Test
    void oneApplicationPerJob() {
        application(job);
        rejected("INSERT INTO application (job_id, job_title, job_company) VALUES (?, 'T', 'C')", job);
        // detached applications (job deleted) don't collide
        application(null);
        application(null);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM application", Integer.class)).isEqualTo(3);
    }

    @Test
    void deletingTheJobKeepsTheApplicationWithItsSnapshot() {
        UUID app = application(job);
        jdbc.update("DELETE FROM job WHERE id = ?", job);
        assertThat(jdbc.queryForObject("SELECT job_id FROM application WHERE id = ?", UUID.class, app)).isNull();
        assertThat(jdbc.queryForObject("SELECT job_title FROM application WHERE id = ?", String.class, app))
                .isEqualTo("T");
    }

    @Test
    void sentIdsMustBeVersionsOfThisApplicationAndTheRightKind() {
        UUID app = application(job);
        UUID other = application(jobWithoutSkills("Other", "Acme"));
        UUID foreignCv = aiDoc(other, "CV", 1);
        UUID ownLetter = aiDoc(app, "COVER_LETTER", 1);
        UUID ownCv = aiDoc(app, "CV", 1);
        approve(foreignCv);
        approve(ownLetter);
        approve(ownCv);
        String applied = "UPDATE application SET status = 'APPLIED', applied_on = current_date, ";
        rejected(applied + "sent_cv_id = ? WHERE id = ?", foreignCv, app);           // other application
        rejected(applied + "sent_cv_id = ? WHERE id = ?", ownLetter, app);           // letter as CV
        rejected(applied + "sent_cover_letter_id = ? WHERE id = ?", ownCv, app);     // CV as letter
        rejected(applied + "sent_cv_id = ? WHERE id = ?", UUID.randomUUID(), app);   // no such version
        jdbc.update(applied + "sent_cv_id = ?, sent_cover_letter_id = ? WHERE id = ?", ownCv, ownLetter, app);
    }

    // ------------------------------------------------------------------ application_document

    @Test
    void documentChecks() {
        UUID app = application(job);
        UUID other = application(jobWithoutSkills("Other", "Acme"));
        UUID cv = aiDoc(app, "CV", 1);
        UUID letter = aiDoc(app, "COVER_LETTER", 1);
        UUID foreign = aiDoc(other, "CV", 1);
        String ins = "INSERT INTO application_document (application_id, kind, version, source, based_on_id, "
                + "profile_version, content, model, input_hash, approved_at, approval_acknowledged) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?::timestamptz, ?)";
        // duplicate version
        rejected(ins, app, "CV", 1, "AI", null, profileVersion, "{}", "m", HASH, null, null);
        // version 0, unknown kind, unknown source
        rejected(ins, app, "CV", 0, "AI", null, profileVersion, "{}", "m", HASH, null, null);
        rejected(ins, app, "RESUME", 9, "AI", null, profileVersion, "{}", "m", HASH, null, null);
        rejected(ins, app, "CV", 9, "HUMAN", cv, profileVersion, "{}", null, null, null, null);
        // content must be an object; unknown profile version
        rejected(ins, app, "CV", 9, "AI", null, profileVersion, "[]", "m", HASH, null, null);
        rejected(ins, app, "CV", 9, "AI", null, 99, "{}", "m", HASH, null, null);
        // ck_application_document_source
        rejected(ins, app, "CV", 9, "AI", null, profileVersion, "{}", null, HASH, null, null);       // AI, no model
        rejected(ins, app, "CV", 9, "AI", null, profileVersion, "{}", " ", HASH, null, null);        // blank model
        rejected(ins, app, "CV", 9, "AI", null, profileVersion, "{}", "m", null, null, null);        // AI, no hash
        rejected(ins, app, "CV", 9, "AI", null, profileVersion, "{}", "m", "XYZ", null, null);       // bad hash
        rejected(ins, app, "CV", 9, "AI", cv, profileVersion, "{}", "m", HASH, null, null);          // AI with base
        rejected(ins, app, "CV", 9, "EDIT", null, profileVersion, "{}", null, null, null, null);     // EDIT, no base
        rejected(ins, app, "CV", 9, "EDIT", cv, profileVersion, "{}", "m", null, null, null);        // EDIT with model
        rejected(ins, app, "CV", 9, "EDIT", cv, profileVersion, "{}", null, HASH, null, null);       // EDIT with hash
        // based_on across applications and across kinds
        rejected(ins, app, "CV", 9, "EDIT", foreign, profileVersion, "{}", null, null, null, null);
        rejected(ins, app, "CV", 9, "EDIT", letter, profileVersion, "{}", null, null, null, null);
        // approval pairing
        rejected(ins, app, "CV", 9, "AI", null, profileVersion, "{}", "m", HASH, "2026-10-19T10:00:00Z", null);
        rejected(ins, app, "CV", 9, "AI", null, profileVersion, "{}", "m", HASH, null, true);
        // valid edit
        jdbc.update(ins, app, "CV", 2, "EDIT", cv, profileVersion, "{}", null, null, null, null);
        assertThat(count("application_document", "application_id", app)).isEqualTo(3);
    }

    @Test
    void documentsAreAppendOnlyExceptOneApproval() {
        UUID app = application(job);
        UUID cv = aiDoc(app, "CV", 1);
        String where = " WHERE id = '" + cv + "'";
        rejected("UPDATE application_document SET content = '{\"x\":1}'" + where);
        rejected("UPDATE application_document SET findings = '[{}]'" + where);
        rejected("UPDATE application_document SET version = 5" + where);
        // an approval that also changes content
        rejected("UPDATE application_document SET approved_at = now(), approval_acknowledged = true, "
                + "content = '{\"x\":1}'" + where);
        rejected("DELETE FROM application_document" + where);

        approve(cv);   // allowed once
        rejected("UPDATE application_document SET approved_at = now() + interval '1 hour'" + where);
        rejected("UPDATE application_document SET approval_acknowledged = true" + where);
        rejected("UPDATE application_document SET approved_at = NULL, approval_acknowledged = NULL" + where);
        rejected("DELETE FROM application_document" + where);
        assertThat(jdbc.queryForObject("SELECT approval_acknowledged FROM application_document" + where, Boolean.class))
                .isFalse();
    }

    // ------------------------------------------------------------------ application_document_file

    @Test
    void filesOnlyForApprovedVersionsAndNeverChanged() {
        UUID app = application(job);
        UUID cv = aiDoc(app, "CV", 1);
        String ins = "INSERT INTO application_document_file (document_id, format, content, size_bytes, sha256) "
                + "VALUES (?, ?, ?, ?, ?)";
        rejected(ins, cv, "PDF", BYTES, 3, HASH);   // unapproved
        approve(cv);
        rejected(ins, cv, "XLS", BYTES, 3, HASH);
        rejected(ins, cv, "PDF", BYTES, 4, HASH);   // size <> octet_length
        rejected(ins, cv, "PDF", new byte[0], 0, HASH);
        rejected(ins, cv, "PDF", BYTES, 3, "XYZ");
        rejected(ins, UUID.randomUUID(), "PDF", BYTES, 3, HASH);
        file(cv, "PDF");
        rejected(ins, cv, "PDF", BYTES, 3, HASH);   // one per format
        String where = " WHERE document_id = '" + cv + "'";
        rejected("UPDATE application_document_file SET content = ?, sha256 = ?" + where, new byte[] {9, 9, 9}, HASH);
        rejected("UPDATE application_document_file SET created_at = now()" + where);
        rejected("DELETE FROM application_document_file" + where);
        assertThat(count("application_document_file", "document_id", cv)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ application_generation

    @Test
    void generationChecks() {
        UUID app = application(job);
        UUID cv = aiDoc(app, "CV", 1);
        String ins = "INSERT INTO application_generation (application_id, want_cv, want_cover_letter, status, "
                + "failure_reason, model, cv_document_id, cv_failure, cover_letter_failure, started_at, finished_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::timestamptz, ?::timestamptz)";
        String t = "2026-10-19T10:00:00Z";
        // neither kind; unknown status
        rejected(ins, app, false, false, "PENDING", null, null, null, null, null, null, null);
        rejected(ins, app, true, true, "QUEUED", null, null, null, null, null, null, null);
        // FAILED needs a reason, and only FAILED has one; unknown reasons
        rejected(ins, app, true, true, "FAILED", null, null, null, null, null, t, t);
        rejected(ins, app, true, true, "PENDING", "TIMEOUT", null, null, null, null, null, null);
        rejected(ins, app, true, true, "FAILED", "SOMETHING_NEW", null, null, null, null, t, t);
        // per-kind failures: only the per-kind values; not for an unwanted kind; not with a document
        rejected(ins, app, true, true, "SUCCEEDED", null, "m", cv, null, "AI_DISABLED", t, t);
        rejected(ins, app, true, true, "SUCCEEDED", null, "m", cv, "TIMEOUT", null, t, t);
        rejected(ins, app, false, true, "SUCCEEDED", null, "m", cv, null, null, t, t);
        rejected(ins, app, true, false, "FAILED", "INVALID_OUTPUT", null, null, null, "INVALID_OUTPUT", t, t);
        // RUNNING needs started_at; finished_at only (and always) when finished
        rejected(ins, app, true, true, "RUNNING", null, null, null, null, null, null, null);
        rejected(ins, app, true, true, "RUNNING", null, null, null, null, null, t, t);
        rejected(ins, app, true, true, "FAILED", "TIMEOUT", null, null, null, null, t, null);
        // SUCCEEDED needs a model and at least one document
        rejected(ins, app, true, true, "SUCCEEDED", null, null, cv, null, null, t, t);
        rejected(ins, app, true, true, "SUCCEEDED", null, "m", null, "TIMEOUT", "TIMEOUT", t, t);
        rejected("INSERT INTO application_generation (application_id, want_cv, want_cover_letter, attempts) "
                + "VALUES (?, true, true, -1)", app);

        // valid: SUCCEEDED with one kind failed, FAILED, then one active
        jdbc.update(ins, app, true, true, "SUCCEEDED", null, "m", cv, null, "INVALID_OUTPUT", t, t);
        jdbc.update(ins, app, true, false, "FAILED", "AI_DISABLED", null, null, null, null, t, t);
        jdbc.update(ins, app, true, true, "PENDING", null, null, null, null, null, null, null);
        // uq_application_generation_active: a second PENDING or RUNNING one
        rejected(ins, app, true, true, "PENDING", null, null, null, null, null, null, null);
        rejected(ins, app, true, true, "RUNNING", null, null, null, null, null, t, null);
        // another application may have its own active one
        UUID other = application(jobWithoutSkills("Other", "Acme"));
        jdbc.update(ins, other, true, true, "RUNNING", null, null, null, null, null, t, null);
        assertThat(count("application_generation", "application_id", app)).isEqualTo(3);
    }

    // ------------------------------------------------------------------ the cascade (§11.1, step-1 gate)

    @Test
    void deletingTheApplicationCascadesThroughTheTriggers() {
        UUID app = application(job);
        UUID cv = aiDoc(app, "CV", 1);
        UUID cv2 = editDoc(app, "CV", 2, cv);
        UUID cv3 = editDoc(app, "CV", 3, cv2);
        UUID letter = aiDoc(app, "COVER_LETTER", 1);
        approve(cv2);
        approve(letter);
        file(cv2, "PDF");
        file(cv2, "DOCX");
        file(letter, "PDF");
        succeededGeneration(app, cv, letter);
        jdbc.update("INSERT INTO application_generation (application_id, want_cv, want_cover_letter) "
                + "VALUES (?, true, false)", app);
        jdbc.update("INSERT INTO application_event (application_id, from_status, to_status) VALUES (?, NULL, 'NEW')",
                app);
        jdbc.update("INSERT INTO application_event (application_id, from_status, to_status, forced) "
                + "VALUES (?, 'NEW', 'APPLIED', true)", app);
        jdbc.update("UPDATE application SET status = 'APPLIED', applied_on = current_date, sent_cv_id = ?, "
                + "sent_cover_letter_id = ? WHERE id = ?", cv2, letter, app);

        // a second application that must be untouched
        UUID keep = application(jobWithoutSkills("Other", "Acme"));
        UUID keepCv = aiDoc(keep, "CV", 1);
        approve(keepCv);
        file(keepCv, "PDF");

        assertThat(jdbc.update("DELETE FROM application WHERE id = ?", app)).isEqualTo(1);

        assertThat(count("application", "id", app)).isZero();
        assertThat(count("application_document", "application_id", app)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM application_document_file WHERE document_id IN (?, ?, ?, ?)",
                Integer.class, cv, cv2, cv3, letter)).isZero();
        assertThat(count("application_generation", "application_id", app)).isZero();
        assertThat(count("application_event", "application_id", app)).isZero();

        assertThat(count("application_document", "application_id", keep)).isEqualTo(1);
        assertThat(count("application_document_file", "document_id", keepCv)).isEqualTo(1);
        // and the guards still hold for the survivor
        rejected("DELETE FROM application_document WHERE id = ?", keepCv);
        rejected("DELETE FROM application_document_file WHERE document_id = ?", keepCv);
    }

    @Test
    void cascadeAlsoWorksForANewApplicationWithOnlyHistory() {
        UUID app = application(job);
        jdbc.update("INSERT INTO application_event (application_id, from_status, to_status) VALUES (?, NULL, 'NEW')",
                app);
        assertThat(jdbc.update("DELETE FROM application WHERE id = ?", app)).isEqualTo(1);
        assertThat(count("application_event", "application_id", app)).isZero();
    }
}
