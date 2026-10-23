package com.talentmatch.it;

import static com.talentmatch.application.tailoring.TailoringFixtures.NAME;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.talentmatch.support.AbstractGenerationIT;
import com.talentmatch.support.Api.Res;
import java.sql.Connection;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * Phase 7 §9.2 item 9 for the document endpoints: a held application row lock makes PUT, edit and approve
 * 503 SERVICE_BUSY after the 10 s lock timeout with nothing written; parallel edits get consecutive versions;
 * parallel approvals approve once and store one file per format.
 */
@ExtendWith(OutputCaptureExtension.class)
class DocumentConcurrencyIT extends AbstractGenerationIT {

    private static final String SERVICE_BUSY =
            "The server is busy with a conflicting update. Please try again in a few seconds.";

    private UUID generatedCv() {
        saveProfile();
        JsonNode app = createGenerating(initechJob(), "kinds", List.of("CV"));
        UUID appId = id(app);
        awaitGeneration(appId, generationId(app), "SUCCEEDED");
        return appId;
    }

    private UUID cv(UUID appId, int version) {
        return jdbc.queryForObject("SELECT id FROM application_document WHERE application_id = ? AND kind = 'CV' "
                + "AND version = ?", UUID.class, appId, version);
    }

    private String editBody(UUID appId, UUID basedOn) {
        Res r = api.get(BASE + "/" + appId + "/documents/" + basedOn);
        assertThat(r.status()).isEqualTo(200);
        ObjectNode b = mapper.createObjectNode();
        b.put("basedOn", basedOn.toString());
        b.set("content", r.json().get("content"));
        return b.toString();
    }

    private static void lockApplication(Connection c, UUID appId) throws Exception {
        c.setAutoCommit(false);
        try (var ps = c.prepareStatement("SELECT id FROM application WHERE id = ? FOR UPDATE")) {
            ps.setObject(1, appId);
            ps.executeQuery().close();
        }
    }

    /** Waits (at most {@code maxMillis}) until {@code n} backends wait for a lock; true when they do. */
    private boolean awaitLockWaiters(int n, long maxMillis) throws InterruptedException {
        long end = System.currentTimeMillis() + maxMillis;
        while (System.currentTimeMillis() < end) {
            Integer waiting = jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity "
                    + "WHERE datname = current_database() AND wait_event_type = 'Lock'", Integer.class);
            if (waiting != null && waiting >= n) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }

    private static void assertBusy(Res r, long millis) {
        JsonNode err = assertError(r, 503, "SERVICE_BUSY");
        assertThat(err.get("message").asText()).isEqualTo(SERVICE_BUSY);
        assertThat(r.header("Retry-After")).isEqualTo("2");
        assertThat(millis).as("waited for the 10 s lock_timeout").isBetween(9_000L, 30_000L);
    }

    @Test
    void aHeldApplicationLockMakesPutEditAndApproveServiceBusyAndWritesNothing() throws Exception {
        UUID appId = generatedCv();
        UUID cv1 = cv(appId, 1);
        String edit = editBody(appId, cv1);
        try (Connection c = rawConnection()) {
            lockApplication(c, appId);

            long t0 = System.nanoTime();
            Res put = api.put(BASE + "/" + appId, api.json(body("status", "SHORTLISTED", "notes", "after")));
            assertBusy(put, (System.nanoTime() - t0) / 1_000_000);

            t0 = System.nanoTime();
            Res e = api.post(BASE + "/" + appId + "/documents", edit);
            assertBusy(e, (System.nanoTime() - t0) / 1_000_000);

            t0 = System.nanoTime();
            Res a = api.post(BASE + "/" + appId + "/documents/" + cv1 + "/approve", null);
            assertBusy(a, (System.nanoTime() - t0) / 1_000_000);

            // reads and downloads don't take the lock
            assertThat(api.get(BASE + "/" + appId + "/documents/" + cv1).status()).isEqualTo(200);
            assertThat(api.getBytes(BASE + "/" + appId + "/documents/" + cv1 + "/file").statusCode()).isEqualTo(200);
            c.rollback();
        }
        Map<String, Object> app = jdbc.queryForMap("SELECT status, notes FROM application WHERE id = ?", appId);
        assertThat(app.get("status")).isEqualTo("NEW");
        assertThat(app.get("notes")).isNull();
        assertThat(documentCount(appId, "CV")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT approved_at FROM application_document WHERE id = ?", Timestamp.class,
                cv1)).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM application_document_file", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM application_event WHERE application_id = ?",
                Integer.class, appId)).isEqualTo(1);

        // and after the lock is gone the same calls succeed
        assertThat(api.post(BASE + "/" + appId + "/documents", edit).status()).isEqualTo(201);
        assertThat(api.post(BASE + "/" + appId + "/documents/" + cv1 + "/approve", null).status()).isEqualTo(200);
    }

    @Test
    void parallelEditsOfTheSameBaseGetVersionsTwoAndThree() throws Exception {
        UUID appId = generatedCv();
        UUID cv1 = cv(appId, 1);
        String edit = editBody(appId, cv1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try (Connection c = rawConnection()) {
            lockApplication(c, appId);
            List<Future<Res>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> api.post(BASE + "/" + appId + "/documents", edit)));
            }
            assertThat(awaitLockWaiters(2, 7_000)).as("both edits wait for the application lock").isTrue();
            c.rollback();
            List<Integer> versions = new ArrayList<>();
            for (Future<Res> f : futures) {
                Res r = f.get(30, TimeUnit.SECONDS);
                assertThat(r.status()).as(r.toString()).isEqualTo(201);
                assertThat(r.json().get("basedOnId").asText()).isEqualTo(cv1.toString());
                versions.add(r.json().get("version").asInt());
            }
            assertThat(versions).containsExactlyInAnyOrder(2, 3);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForList("SELECT version FROM application_document WHERE application_id = ? "
                + "AND kind = 'CV' ORDER BY version", Integer.class, appId)).containsExactly(1, 2, 3);
    }

    @Test
    void manyParallelEditsGetDistinctConsecutiveVersions() throws Exception {
        UUID appId = generatedCv();
        UUID cv1 = cv(appId, 1);
        String edit = editBody(appId, cv1);
        int n = 6;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Callable<Res>> calls = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                calls.add(() -> api.post(BASE + "/" + appId + "/documents", edit));
            }
            for (Future<Res> f : pool.invokeAll(calls, 60, TimeUnit.SECONDS)) {
                Res r = f.get();
                assertThat(r.status()).as(r.toString()).isEqualTo(201);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForList("SELECT version FROM application_document WHERE application_id = ? "
                + "AND kind = 'CV' ORDER BY version", Integer.class, appId)).containsExactly(1, 2, 3, 4, 5, 6, 7);
    }

    @Test
    void parallelApprovalsApproveOnceWithOneFilePerFormat(CapturedOutput output) throws Exception {
        UUID appId = generatedCv();
        UUID cv1 = cv(appId, 1);
        String path = BASE + "/" + appId + "/documents/" + cv1 + "/approve";
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Res> results = new ArrayList<>();
        try (Connection c = rawConnection()) {
            lockApplication(c, appId);
            List<Future<Res>> futures = new ArrayList<>();
            futures.add(pool.submit(() -> api.post(path, null)));
            futures.add(pool.submit(() -> api.post(path, "{\"acknowledgeFindings\": true}")));
            // both render first, then wait for the lock (released well before the 10 s timeout)
            assertThat(awaitLockWaiters(2, 7_000)).as("both approvals wait for the application lock").isTrue();
            c.rollback();
            for (Future<Res> f : futures) {
                results.add(f.get(30, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }
        for (Res r : results) {
            assertThat(r.status()).as(r.toString()).isEqualTo(200);
            assertThat(r.json().get("files").size()).isEqualTo(2);
        }
        assertThat(results.get(0).json().get("approvedAt").asText())
                .isEqualTo(results.get(1).json().get("approvedAt").asText());
        assertThat(results.get(0).json().get("approvalAcknowledged").asBoolean())
                .isEqualTo(results.get(1).json().get("approvalAcknowledged").asBoolean());
        assertThat(jdbc.queryForList("SELECT format FROM application_document_file WHERE document_id = ? "
                + "ORDER BY format", String.class, cv1)).containsExactly("DOCX", "PDF");

        String all = output.getAll();
        assertThat(all.lines().filter(l -> l.contains("Document approved application=" + appId)).count())
                .isEqualTo(1);
        List<String> already = all.lines().filter(l -> l.contains("Document already approved")).toList();
        assertThat(already).hasSize(1);
        assertThat(already.get(0)).contains("Document already approved application=" + appId + " document=" + cv1);
        assertThat(all).doesNotContain(NAME).doesNotContain("Ndlovu").doesNotContain(".pdf");
    }
}
