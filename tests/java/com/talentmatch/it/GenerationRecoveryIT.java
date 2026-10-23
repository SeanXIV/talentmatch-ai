package com.talentmatch.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.talentmatch.application.GenerationRecovery;
import com.talentmatch.application.content.DocumentKind;
import com.talentmatch.application.tailoring.GenerationFailure;
import com.talentmatch.support.AbstractGenerationIT;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Phase 7 §9.2 item 6: {@link GenerationRecovery#requeueUnfinished()} (RUNNING → PENDING → runs; out of
 * attempts → TOO_MANY_ATTEMPTS; a full queue → QUEUE_FULL; a re-run keeps the kind an interrupted attempt
 * already stored). Interrupted rows are simulated with SQL.
 */
class GenerationRecoveryIT extends AbstractGenerationIT {

    @Autowired
    private GenerationRecovery recovery;

    private int profileVersion;

    private UUID trackedApplication(String title) {
        return createTracked(initechJob(title));
    }

    private UUID insertGeneration(UUID applicationId, String status, int attempts, boolean wantCv,
                                  boolean wantLetter, UUID cvDocumentId) {
        return jdbc.queryForObject("INSERT INTO application_generation (application_id, want_cv, want_cover_letter, "
                        + "status, attempts, started_at, model, profile_version, cv_document_id) "
                        + "VALUES (?, ?, ?, ?, ?, CASE WHEN ? = 'RUNNING' THEN now() END, ?, ?, ?) RETURNING id",
                UUID.class, applicationId, wantCv, wantLetter, status, attempts, status,
                "RUNNING".equals(status) ? "fake/tailor" : null, "RUNNING".equals(status) ? profileVersion : null,
                cvDocumentId);
    }

    /** A CV version as an interrupted attempt would have stored it. */
    private UUID insertCv(UUID applicationId) {
        return jdbc.queryForObject("INSERT INTO application_document (application_id, kind, version, source, "
                + "profile_version, content, model, input_hash) VALUES (?, 'CV', 1, 'AI', ?, '{}'::jsonb, "
                + "'fake/tailor', ?) RETURNING id", UUID.class, applicationId, profileVersion, "a".repeat(64));
    }

    @Test
    void aRunningGenerationIsRequeuedAndRuns() {
        profileVersion = saveProfile();
        UUID appId = trackedApplication("Senior Java Developer");
        UUID genId = insertGeneration(appId, "RUNNING", 1, true, true, null);

        recovery.requeueUnfinished();
        JsonNode done = awaitGeneration(appId, genId, "SUCCEEDED");
        assertThat(done.get("attempts").asInt()).isEqualTo(2);
        assertThat(done.get("results").size()).isEqualTo(2);
        assertThat(documentCount(appId, "CV")).isEqualTo(1);
        assertThat(documentCount(appId, "COVER_LETTER")).isEqualTo(1);
        assertThat(tailor.calls()).isEqualTo(2);
    }

    @Test
    void aPendingGenerationIsQueuedAgain() {
        profileVersion = saveProfile();
        UUID appId = trackedApplication("Senior Java Developer");
        UUID genId = insertGeneration(appId, "PENDING", 0, false, true, null);

        recovery.requeueUnfinished();
        JsonNode done = awaitGeneration(appId, genId, "SUCCEEDED");
        assertThat(done.get("attempts").asInt()).isEqualTo(1);
        assertThat(tailor.calls(DocumentKind.CV)).isZero();
        assertThat(documentCount(appId, "COVER_LETTER")).isEqualTo(1);
    }

    @Test
    void outOfAttemptsIsTooManyAttemptsWithoutAModelCall() {
        profileVersion = saveProfile();
        UUID appId = trackedApplication("Senior Java Developer");
        UUID genId = insertGeneration(appId, "RUNNING", GenerationFailure.MAX_ATTEMPTS, true, true, null);

        recovery.requeueUnfinished();
        JsonNode failed = awaitGeneration(appId, genId, "FAILED");
        assertThat(failed.get("failureReason").asText()).isEqualTo("TOO_MANY_ATTEMPTS");
        assertThat(failed.get("hint").asText()).isEqualTo(GenerationFailure.TOO_MANY_ATTEMPTS.hint());
        assertThat(failed.get("attempts").asInt()).isEqualTo(GenerationFailure.MAX_ATTEMPTS);
        assertThat(failed.get("finishedAt").isNull()).isFalse();
        awaitAiIdle();
        assertThat(tailor.calls()).isZero();
        assertThat(documentRows(appId)).isEmpty();
    }

    @Test
    void aFullQueueIsQueueFull() throws Exception {
        profileVersion = saveProfile();
        // occupy the single worker so nothing is taken off the queue (capacity 10)
        CountDownLatch hold = new CountDownLatch(1);
        CountDownLatch holding = new CountDownLatch(1);
        applicationExecutor.execute(() -> {
            holding.countDown();
            try {
                hold.await(60, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        List<UUID> apps = new ArrayList<>();
        List<UUID> gens = new ArrayList<>();
        try {
            assertThat(holding.await(10, TimeUnit.SECONDS)).isTrue();
            for (int i = 0; i < 11; i++) {
                UUID app = trackedApplication("Role " + i);
                apps.add(app);
                gens.add(insertGeneration(app, "PENDING", 0, false, true, null));
            }
            recovery.requeueUnfinished();

            List<String> reasons = jdbc.queryForList("SELECT failure_reason FROM application_generation "
                    + "WHERE status = 'FAILED'", String.class);
            assertThat(reasons).containsExactly("QUEUE_FULL");
            JsonNode last = getGeneration(apps.get(10), gens.get(10)).json();
            assertThat(last.get("status").asText()).as("the newest pending one doesn't fit").isEqualTo("FAILED");
            assertThat(last.get("failureReason").asText()).isEqualTo("QUEUE_FULL");
            assertThat(last.get("hint").asText()).isEqualTo(GenerationFailure.QUEUE_FULL.hint());
            assertThat(last.get("attempts").asInt()).isZero();
        } finally {
            hold.countDown();
        }
        for (int i = 0; i < 10; i++) {
            awaitGeneration(apps.get(i), gens.get(i), "SUCCEEDED");
        }
        awaitAiIdle();
        assertThat(tailor.calls(DocumentKind.COVER_LETTER)).isEqualTo(10);
    }

    @Test
    void aRerunSkipsTheKindAlreadyStoredSoThereIsNoDuplicateCv() {
        profileVersion = saveProfile();
        UUID appId = trackedApplication("Senior Java Developer");
        UUID cvId = insertCv(appId);
        UUID genId = insertGeneration(appId, "RUNNING", 1, true, true, cvId);

        recovery.requeueUnfinished();
        JsonNode done = awaitGeneration(appId, genId, "SUCCEEDED");
        assertThat(tailor.calls(DocumentKind.CV)).as("the stored CV is kept").isZero();
        assertThat(tailor.calls(DocumentKind.COVER_LETTER)).isEqualTo(1);
        assertThat(documentCount(appId, "CV")).isEqualTo(1);
        assertThat(documentCount(appId, "COVER_LETTER")).isEqualTo(1);
        assertThat(result(done, "CV").get("documentId").asText()).isEqualTo(cvId.toString());
        assertThat(result(done, "COVER_LETTER").get("documentId").isNull()).isFalse();
        assertThat(done.get("attempts").asInt()).isEqualTo(2);
    }

    @Test
    void finishedGenerationsAreLeftAlone() {
        profileVersion = saveProfile();
        UUID appId = trackedApplication("Senior Java Developer");
        UUID genId = jdbc.queryForObject("INSERT INTO application_generation (application_id, want_cv, "
                + "want_cover_letter, status, failure_reason, attempts, started_at, finished_at) "
                + "VALUES (?, true, true, 'FAILED', 'TIMEOUT', 1, now(), now()) RETURNING id", UUID.class, appId);

        recovery.requeueUnfinished();
        awaitAiIdle();
        assertThat(getGeneration(appId, genId).json().get("status").asText()).isEqualTo("FAILED");
        assertThat(getGeneration(appId, genId).json().get("failureReason").asText()).isEqualTo("TIMEOUT");
        assertThat(tailor.calls()).isZero();
    }
}
