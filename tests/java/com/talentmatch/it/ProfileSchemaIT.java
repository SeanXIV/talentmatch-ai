package com.talentmatch.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.talentmatch.support.AbstractApiIT;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

/** Phase 4: V4 constraints, checked directly via JDBC (test plan: schema IT; S4, N2, N3, N10). */
class ProfileSchemaIT extends AbstractApiIT {

    private static final String SHA = "a".repeat(64);
    private UUID candidate;

    @BeforeEach
    void seed() {
        candidate = jdbc.queryForObject("INSERT INTO candidate (full_name, email) VALUES ('Ada', 'ada@example.com') "
                + "RETURNING id", UUID.class);
    }

    private UUID pendingResume() {
        return jdbc.queryForObject("INSERT INTO resume (file_name, content_type, size_bytes, sha256, content, page_count, "
                + "extracted_text) VALUES ('cv.pdf', 'application/pdf', 3, ?, ?, 1, 'text') RETURNING id", UUID.class,
                SHA, new byte[] {1, 2, 3});
    }

    private int version(UUID resumeId) {
        return jdbc.queryForObject("INSERT INTO owner_profile_version (version, profile, resume_id) "
                + "SELECT COALESCE(MAX(version), 0) + 1, '{}'::jsonb, ? FROM owner_profile_version RETURNING version",
                Integer.class, resumeId);
    }

    private void rejected(String sql, Object... args) {
        assertThatThrownBy(() -> jdbc.update(sql, args)).as(sql).isInstanceOf(DataAccessException.class);
    }

    @Test
    void validRowsAreAccepted() {
        UUID r = pendingResume();
        jdbc.update("UPDATE resume SET status = 'RUNNING', extraction_started_at = now(), attempts = 1 WHERE id = ?", r);
        jdbc.update("UPDATE resume SET status = 'SUCCEEDED', extraction_model = 'm', draft = '{}', warnings = '[]', "
                + "extraction_finished_at = now() WHERE id = ?", r);
        int v = version(r);
        jdbc.update("INSERT INTO owner_profile (candidate_id, resume_id, profile, version) VALUES (?, ?, '{}', ?)",
                candidate, r, v);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM owner_profile", Integer.class)).isEqualTo(1);
    }

    @Test
    void resumeStatusChecks() {
        UUID r = pendingResume();
        String base = "UPDATE resume SET ";
        String where = " WHERE id = '" + r + "'";
        for (String set : List.of(
                "status = 'FAILED'",                                                   // FAILED without reason
                "status = 'FAILED', failure_reason = ' '",                             // blank reason
                "failure_reason = 'TIMEOUT'",                                          // reason while PENDING
                "status = 'FAILED', failure_reason = 'SOMETHING_NEW'",                 // unknown reason (N3)
                "status = 'SUCCEEDED', extraction_model = 'm'",                        // no draft/warnings
                "status = 'SUCCEEDED', extraction_model = 'm', draft = '{}'",          // no warnings
                "status = 'SUCCEEDED', extraction_model = 'm', draft = '[]', warnings = '[]'", // draft not an object
                "status = 'SUCCEEDED', extraction_model = 'm', draft = '{}', warnings = '{}'", // warnings not array
                "status = 'SUCCEEDED', draft = '{}', warnings = '[]'",                 // no model (N3)
                "draft = '{}'",                                                        // draft while PENDING
                "status = 'RUNNING'",                                                  // RUNNING without start (N3)
                "status = 'BOGUS'",
                "size_bytes = 4",                                                      // size != content (N3)
                "attempts = -1",
                "sha256 = 'XYZ'",
                "file_name = '  '",
                "extracted_text = ' '")) {
            rejected(base + set + where);
        }
        assertThat(jdbc.queryForObject("SELECT status FROM resume WHERE id = ?", String.class, r)).isEqualTo("PENDING");
    }

    @Test
    void everyExtractionFailureValueIsAllowedByTheCheck() {
        UUID r = pendingResume();
        for (com.talentmatch.profile.ExtractionFailure f : com.talentmatch.profile.ExtractionFailure.values()) {
            jdbc.update("UPDATE resume SET status = 'FAILED', failure_reason = ? WHERE id = ?", f.name(), r);
        }
    }

    @Test
    void ownerProfileIsASingleRow() {
        int v = version(null);
        rejected("INSERT INTO owner_profile (id, candidate_id, profile, version) VALUES (false, ?, '{}', ?)", candidate, v);
        jdbc.update("INSERT INTO owner_profile (candidate_id, profile, version) VALUES (?, '{}', ?)", candidate, v);
        UUID other = jdbc.queryForObject("INSERT INTO candidate (full_name, email) VALUES ('B', 'b@example.com') "
                + "RETURNING id", UUID.class);
        rejected("INSERT INTO owner_profile (candidate_id, profile, version) VALUES (?, '{}', ?)", other, v);
        rejected("UPDATE owner_profile SET profile = '[]'");
        rejected("UPDATE owner_profile SET version = 99");
    }

    @Test
    void deletingTheOwnersCandidateIsRestricted() {
        int v = version(null);
        jdbc.update("INSERT INTO owner_profile (candidate_id, profile, version) VALUES (?, '{}', ?)", candidate, v);
        rejected("DELETE FROM candidate WHERE id = ?", candidate);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM owner_profile", Integer.class)).isEqualTo(1);
    }

    @Test
    void deletingTheResumeNullsTheReferences() {
        UUID r = pendingResume();
        int v = version(r);
        jdbc.update("INSERT INTO owner_profile (candidate_id, resume_id, profile, version) VALUES (?, ?, '{}', ?)",
                candidate, r, v);
        jdbc.update("DELETE FROM resume WHERE id = ?", r);
        assertThat(jdbc.queryForObject("SELECT resume_id FROM owner_profile", UUID.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT resume_id FROM owner_profile_version WHERE version = ?", UUID.class, v))
                .isNull();
    }

    @Test
    void profileHistoryIsAppendOnly() {
        int v = version(null);
        rejected("UPDATE owner_profile_version SET profile = '{\"x\":1}' WHERE version = ?", v);
        rejected("UPDATE owner_profile_version SET confirmed_at = now() - interval '1 day' WHERE version = ?", v);
        rejected("UPDATE owner_profile_version SET resume_id = ? WHERE version = ?", pendingResume(), v);
        rejected("DELETE FROM owner_profile_version WHERE version = ?", v);
        rejected("INSERT INTO owner_profile_version (version, profile) VALUES (0, '{}')");
        rejected("INSERT INTO owner_profile_version (version, profile) VALUES (?, '[]')", v + 1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM owner_profile_version", Integer.class)).isEqualTo(1);
    }
}
