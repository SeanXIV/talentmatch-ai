package com.talentmatch.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.talentmatch.profile.ProfileRecovery;
import com.talentmatch.support.AbstractAiApiIT;
import com.talentmatch.support.Api.Res;
import com.talentmatch.support.FakeExtractionModel;
import com.talentmatch.support.TestPdfs;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Phase 4 integration: CV upload → extraction (fake model) → review → PUT /api/profile (test plan). */
class ProfileIT extends AbstractAiApiIT {

    private static final String UPLOAD = "/api/profile/resume";

    @Autowired
    ProfileRecovery recovery;

    // ------------------------------------------------------------------ helpers

    private Res upload(byte[] pdf, String fileName) {
        return api.upload(UPLOAD, "file", fileName, "application/pdf", pdf);
    }

    private UUID uploadOk(byte[] pdf) {
        Res r = upload(pdf, "cv.pdf");
        assertThat(r.status()).as(r.toString()).isEqualTo(202);
        return UUID.fromString(r.json().get("id").asText());
    }

    private JsonNode awaitStatus(UUID id, String status) {
        JsonNode[] last = new JsonNode[1];
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(100)).until(() -> {
            Res r = api.get(UPLOAD + "/" + id);
            last[0] = r.json();
            return r.status() == 200 && status.equals(r.json().get("status").asText());
        });
        return last[0];
    }

    private ObjectNode putBody(UUID resumeId, Boolean createMissing, JsonNode profile) {
        ObjectNode body = mapper.createObjectNode();
        if (resumeId != null) {
            body.put("resumeId", resumeId.toString());
        }
        if (createMissing != null) {
            body.put("createMissingSkills", createMissing);
        }
        body.set("profile", profile);
        return body;
    }

    private Res put(JsonNode body) {
        return api.put("/api/profile", body.toString());
    }

    /** A minimal valid profile using only skills the base class creates. */
    private ObjectNode manualProfile(String name, String email) {
        ObjectNode p = mapper.createObjectNode();
        p.put("fullName", name);
        p.put("email", email);
        p.put("headline", "Backend Engineer");
        ArrayNode skills = p.putArray("skills");
        skills.addObject().put("name", "Java").put("years", 5);
        skills.addObject().put("name", "SQL");
        return p;
    }

    private UUID ownerCandidateId() {
        return jdbc.queryForObject("SELECT candidate_id FROM owner_profile", UUID.class);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    // ------------------------------------------------------------------ happy path

    @Test
    void uploadExtractReviewSaveAndMatch() {
        UUID job = backendJob();
        Res up = upload(TestPdfs.cv(), "Ada CV.pdf");
        assertThat(up.status()).as(up.toString()).isEqualTo(202);
        UUID id = UUID.fromString(up.json().get("id").asText());
        assertThat(up.header("Location")).isEqualTo("/api/profile/resume/" + id);
        assertThat(up.json().get("status").asText()).isIn("PENDING", "RUNNING", "SUCCEEDED");
        assertThat(up.json().get("fileName").asText()).isEqualTo("Ada CV.pdf");
        assertThat(up.json().get("pageCount").asInt()).isEqualTo(1);

        JsonNode done = awaitStatus(id, "SUCCEEDED");
        assertThat(done.get("model").asText()).isEqualTo(FakeExtractionModel.LABEL);
        assertThat(done.at("/draft/fullName").asText()).isEqualTo("Ada Lovelace");
        assertThat(done.at("/draft/experience/0/technologies/0").asText()).isEqualTo("Java");
        assertThat(done.get("warnings").isArray()).isTrue();
        assertThat(done.get("warnings")).as("default draft is grounded").isEmpty();
        assertThat(done.get("extractionFinishedAt").isNull()).isFalse();
        assertThat(extractor.calls()).isEqualTo(1);
        assertThat(extractor.lastUserText()).contains("Acme Ltd");

        // PostgreSQL is not a known skill: without the flag the save is refused and nothing is created
        Res refused = put(putBody(id, null, done.get("draft")));
        JsonNode err = assertError(refused, 400, "VALIDATION_FAILED");
        assertThat(fields(err)).containsExactly("profile.skills[1].name");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM skill WHERE lower(name) = 'postgresql'", Integer.class))
                .isZero();
        assertThat(count("candidate")).isZero();

        Res saved = put(putBody(id, true, done.get("draft")));
        assertThat(saved.status()).as(saved.toString()).isEqualTo(200);
        UUID candidateId = UUID.fromString(saved.json().get("candidateId").asText());
        assertThat(saved.json().get("resumeId").asText()).isEqualTo(id.toString());
        assertThat(saved.json().get("version").asInt()).isEqualTo(1);
        assertThat(saved.json().at("/profile/fullName").asText()).isEqualTo("Ada Lovelace");
        List<String> skillNames = new ArrayList<>();
        saved.json().get("skills").forEach(s -> skillNames.add(s.get("name").asText()));
        assertThat(skillNames).containsExactlyInAnyOrder("Java", "PostgreSQL", "Docker");

        Res got = api.get("/api/profile");
        assertThat(got.status()).isEqualTo(200);
        assertThat(got.json().get("candidateId").asText()).isEqualTo(candidateId.toString());
        assertThat(got.json().at("/profile/email").asText()).isEqualTo("ada@example.com");

        Res candidate = api.get("/api/candidates/" + candidateId);
        assertThat(candidate.status()).isEqualTo(200);
        assertThat(candidate.json().get("fullName").asText()).isEqualTo("Ada Lovelace");
        assertThat(candidate.json().get("summary").asText()).isEqualTo("Backend Engineer");

        assertThat(candidateNames(matches(job))).contains("Ada Lovelace");
    }

    @Test
    void secondSaveUpdatesTheSameCandidateAndAppendsAVersion() {
        Res first = put(putBody(null, null, manualProfile("Ada Lovelace", "ada@example.com")));
        assertThat(first.status()).as(first.toString()).isEqualTo(200);
        UUID candidateId = UUID.fromString(first.json().get("candidateId").asText());

        ObjectNode changed = manualProfile("Ada King", "ada.king@example.com");
        ((ArrayNode) changed.get("skills")).addObject().put("name", "Docker");
        Res second = put(putBody(null, null, changed));
        assertThat(second.status()).as(second.toString()).isEqualTo(200);
        assertThat(second.json().get("candidateId").asText()).isEqualTo(candidateId.toString());
        assertThat(second.json().get("version").asInt()).isEqualTo(2);
        assertThat(count("candidate")).isEqualTo(1);
        assertThat(count("owner_profile_version")).isEqualTo(2);
        assertThat(api.get("/api/profile").json().get("version").asInt()).as("GET returns the latest").isEqualTo(2);
        assertThat(api.get("/api/profile").json().at("/profile/fullName").asText()).isEqualTo("Ada King");
        assertThat(jdbc.queryForList("SELECT profile->>'fullName' FROM owner_profile_version ORDER BY version",
                String.class)).containsExactly("Ada Lovelace", "Ada King");
        // a refused save adds no version
        assertError(put(putBody(null, null, manualProfile("Ada King", "not-an-email"))), 400, "VALIDATION_FAILED");
        assertThat(count("owner_profile_version")).isEqualTo(2);
        // owner-typed years are accepted by the STRICT save (no grounding on PUT)
        assertThat(second.json().get("skills")).anyMatch(sk -> sk.get("name").asText().equals("Java")
                && sk.get("yearsExperience").asInt() == 5);
        JsonNode c = api.get("/api/candidates/" + candidateId).json();
        assertThat(c.get("fullName").asText()).isEqualTo("Ada King");
        assertThat(c.get("skills")).hasSize(3);
    }

    @Test
    void getProfileBeforeAnySaveIs404() {
        assertError(api.get("/api/profile"), 404, "PROFILE_NOT_FOUND");
    }

    // ------------------------------------------------------------------ upload errors

    @Test
    void nonPdfIs415() {
        Res r = api.upload(UPLOAD, "file", "cv.pdf", "application/pdf",
                "Ada Lovelace, Backend Engineer".getBytes(StandardCharsets.UTF_8));
        assertError(r, 415, "UNSUPPORTED_MEDIA_TYPE");
        Res docx = api.upload(UPLOAD, "file", "cv.docx", "application/octet-stream",
                new byte[] {'P', 'K', 3, 4, 0, 0});
        assertError(docx, 415, "UNSUPPORTED_MEDIA_TYPE");
        assertThat(count("resume")).isZero();
    }

    @Test
    void jsonBodyIs415WithTheMultipartHint() {
        Res r = api.post(UPLOAD, "{\"file\":\"cv.pdf\"}");
        JsonNode b = assertError(r, 415, "UNSUPPORTED_MEDIA_TYPE");
        assertThat(b.get("message").asText()).contains("multipart/form-data").contains("'file'");
    }

    @Test
    void overTheResumeLimitIs413WithOurMessage() {
        Res r = upload(TestPdfs.ofSize(5_767_168), "big.pdf"); // 5.5 MB: under multipart's 6MB, over our 5MB
        JsonNode b = assertError(r, 413, "PAYLOAD_TOO_LARGE");
        assertThat(b.get("message").asText()).contains("5.5 MB").contains("5.0 MB");
        assertThat(count("resume")).isZero();
    }

    @Test
    void overTheRequestLimitIs413JsonNotAConnectionReset() {
        Res r = upload(TestPdfs.ofSize(8 * 1024 * 1024), "huge.pdf");
        assertError(r, 413, "PAYLOAD_TOO_LARGE");
    }

    @Test
    void missingFilePartIs400() {
        Res r = api.upload(UPLOAD, "document", "cv.pdf", "application/pdf", TestPdfs.cv());
        JsonNode b = assertError(r, 400, "VALIDATION_FAILED");
        assertThat(fields(b)).containsExactly("file");
        Res empty = api.upload(UPLOAD, "file", "cv.pdf", "application/pdf", new byte[0]);
        assertThat(fields(assertError(empty, 400, "VALIDATION_FAILED"))).containsExactly("file");
    }

    @Test
    void scannedPdfIs400Unreadable() {
        JsonNode b = assertError(upload(TestPdfs.blank(), "scan.pdf"), 400, "RESUME_UNREADABLE");
        assertThat(b.get("message").asText()).contains("scanned");
        assertThat(count("resume")).isZero();
        assertThat(extractor.calls()).isZero();
    }

    @Test
    void corruptAndProtectedPdfsAre400Not500() {
        assertError(upload(TestPdfs.corrupt(), "bad.pdf"), 400, "RESUME_UNREADABLE");
        assertThat(assertError(upload(TestPdfs.userPassword("pw"), "locked.pdf"), 400, "RESUME_UNREADABLE")
                .get("message").asText()).contains("password");
        assertError(upload(TestPdfs.noCopy(), "nocopy.pdf"), 400, "RESUME_UNREADABLE");
        assertError(upload(TestPdfs.manyPages(21), "book.pdf"), 400, "RESUME_UNREADABLE");
    }

    // ------------------------------------------------------------------ dedup, retry, in progress

    @Test
    void sameFileTwiceReturnsTheSameCvWithoutReExtracting() {
        byte[] pdf = TestPdfs.cv(); // the same bytes: PDFBox writes a fresh document id on every save
        UUID id = uploadOk(pdf);
        awaitStatus(id, "SUCCEEDED");
        Res again = upload(pdf, "renamed.pdf");
        assertThat(again.status()).as(again.toString()).isEqualTo(200);
        assertThat(again.json().get("id").asText()).isEqualTo(id.toString());
        awaitAiIdle();
        assertThat(extractor.calls()).isEqualTo(1);
        assertThat(count("resume")).isEqualTo(1);
    }

    @Test
    void afterAFailureTheSameFileIsANewCv() {
        extractor.fail(new RuntimeException("connection refused"));
        byte[] pdf = TestPdfs.cv();
        UUID failed = uploadOk(pdf);
        JsonNode f = awaitStatus(failed, "FAILED");
        assertThat(f.get("failureReason").asText()).isEqualTo("PROVIDER_ERROR");
        assertThat(f.get("message").asText()).contains("ollama serve");
        assertThat(f.get("draft").isNull()).isTrue();

        extractor.reset();
        UUID fresh = uploadOk(pdf);
        assertThat(fresh).isNotEqualTo(failed);
        awaitStatus(fresh, "SUCCEEDED");
    }

    @Test
    void failedExtractionCanBeRetried() {
        extractor.fail(new RuntimeException("connection refused"));
        UUID id = uploadOk(TestPdfs.cv());
        awaitStatus(id, "FAILED");
        extractor.reset();
        Res retry = api.post(UPLOAD + "/" + id + "/extract", null);
        assertThat(retry.status()).as(retry.toString()).isEqualTo(202);
        JsonNode done = awaitStatus(id, "SUCCEEDED");
        assertThat(done.get("failureReason").isNull()).isTrue();
        assertThat(done.at("/draft/fullName").asText()).isEqualTo("Ada Lovelace");
    }

    @Test
    void retryOrDeleteWhileRunningIs409() throws Exception {
        CountDownLatch release = extractor.block();
        UUID id = uploadOk(TestPdfs.cv());
        assertThat(extractor.awaitStarted(10)).isTrue();
        awaitStatus(id, "RUNNING");

        assertError(api.post(UPLOAD + "/" + id + "/extract", null), 409, "RESUME_EXTRACTION_IN_PROGRESS");
        assertError(api.delete(UPLOAD + "/" + id), 409, "RESUME_EXTRACTION_IN_PROGRESS");

        release.countDown();
        awaitStatus(id, "SUCCEEDED");
        assertThat(api.delete(UPLOAD + "/" + id).status()).isEqualTo(204);
        assertError(api.get(UPLOAD + "/" + id), 404, "RESUME_NOT_FOUND");
    }

    @Test
    void finishLengthFailsWithInvalidOutputAndContextHint() {
        extractor.respond(FakeExtractionModel.DEFAULT_JSON, dev.langchain4j.model.output.FinishReason.LENGTH,
                new dev.langchain4j.model.output.TokenUsage(1000, 4096));
        UUID id = uploadOk(TestPdfs.cv());
        JsonNode f = awaitStatus(id, "FAILED");
        assertThat(f.get("failureReason").asText()).isEqualTo("INVALID_OUTPUT");
        assertThat(f.get("model").asText()).isEqualTo(FakeExtractionModel.LABEL);
    }

    @Test
    void ungroundedDraftValuesComeBackAsWarnings() {
        extractor.respondJson(FakeExtractionModel.DEFAULT_JSON.replace("\"Acme Ltd\"", "\"Globex\"")
                .replace("{\"name\":\"Docker\",\"years\":null}", "{\"name\":\"Docker\",\"years\":7}"));
        UUID id = uploadOk(TestPdfs.cv());
        JsonNode done = awaitStatus(id, "SUCCEEDED");
        List<String> paths = new ArrayList<>();
        done.get("warnings").forEach(w -> paths.add(w.get("path").asText()));
        assertThat(paths).contains("experience[0].company", "skills[2].years");
        assertThat(done.at("/draft/skills/2/years").isNull()).as("ungrounded years dropped").isTrue();
        assertThat(done.get("message").asText()).contains("item(s) to check");
    }

    // ------------------------------------------------------------------ file download

    @Test
    void downloadReturnsTheOriginalBytesWithSafeHeaders() {
        byte[] pdf = TestPdfs.cv();
        Res up = upload(pdf, "Ada \"Lovelace\"\r\nX-Injected: 1 Müller.pdf");
        assertThat(up.status()).as(up.toString()).isEqualTo(202);
        UUID id = UUID.fromString(up.json().get("id").asText());

        HttpResponse<byte[]> file = api.getBytes(UPLOAD + "/" + id + "/file");
        assertThat(file.statusCode()).isEqualTo(200);
        assertThat(file.body()).isEqualTo(pdf);
        assertThat(file.headers().firstValue("Content-Type").orElse("")).startsWith("application/pdf");
        assertThat(file.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
        assertThat(file.headers().firstValue("X-Injected")).isEmpty();
        String cd = file.headers().firstValue("Content-Disposition").orElseThrow();
        assertThat(cd).startsWith("attachment").doesNotContain("\r").doesNotContain("\n")
                .contains("filename*=UTF-8''");
        assertThat(api.getBytes(UPLOAD + "/" + UUID.randomUUID() + "/file").statusCode()).isEqualTo(404);
    }

    // ------------------------------------------------------------------ PUT validation

    @Test
    void putValidation() {
        ObjectNode noName = manualProfile(null, "ada@example.com");
        noName.remove("fullName");
        assertThat(fields(assertError(put(putBody(null, null, noName)), 400, "VALIDATION_FAILED")))
                .containsExactly("profile.fullName");

        ObjectNode badEmail = manualProfile("Ada", "not-an-email");
        assertThat(fields(assertError(put(putBody(null, null, badEmail)), 400, "VALIDATION_FAILED")))
                .containsExactly("profile.email");

        ObjectNode noEmail = manualProfile("Ada", null);
        noEmail.remove("email");
        assertThat(fields(assertError(put(putBody(null, null, noEmail)), 400, "VALIDATION_FAILED")))
                .containsExactly("profile.email");

        assertThat(fields(assertError(put(putBody(UUID.randomUUID(), null, manualProfile("Ada", "a@example.com"))),
                400, "VALIDATION_FAILED"))).containsExactly("resumeId");

        assertThat(fields(assertError(api.put("/api/profile", "{}"), 400, "VALIDATION_FAILED")))
                .containsExactly("profile");

        ObjectNode badDate = manualProfile("Ada", "a@example.com");
        badDate.putArray("experience").addObject().put("title", "Engineer").put("company", "Acme")
                .put("startDate", "2021-13");
        assertThat(fields(assertError(put(putBody(null, null, badDate)), 400, "VALIDATION_FAILED")))
                .containsExactly("profile.experience[0].startDate");

        ObjectNode unknown = manualProfile("Ada", "a@example.com");
        ((ArrayNode) unknown.get("skills")).addObject().put("name", "Erlang");
        assertThat(fields(assertError(put(putBody(null, false, unknown)), 400, "VALIDATION_FAILED")))
                .containsExactly("profile.skills[2].name");

        assertThat(count("candidate")).isZero();
        assertThat(count("owner_profile")).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM skill WHERE name = 'Erlang'", Integer.class)).isZero();
    }

    @Test
    void malformedJsonIs400() {
        assertError(api.put("/api/profile", "{\"profile\": "), 400, "MALFORMED_REQUEST");
    }

    @Test
    void nearDuplicateCreatedSkillGetsAWarning() {
        skill("PostgreSQL");
        ObjectNode p = manualProfile("Ada", "a@example.com");
        ((ArrayNode) p.get("skills")).addObject().put("name", "Postgres");
        Res r = put(putBody(null, true, p));
        assertThat(r.status()).as(r.toString()).isEqualTo(200);
        assertThat(r.json().get("warnings")).hasSize(1);
        assertThat(r.json().at("/warnings/0/message").asText()).contains("PostgreSQL");
    }

    // ------------------------------------------------------------------ concurrency, owner guards, email

    @Test
    void twoParallelFirstSavesCreateExactlyOneCandidate() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Res>> results = new ArrayList<>();
            for (String email : List.of("ada@example.com", "ada.l@example.com")) {
                results.add(pool.submit(() -> {
                    go.await();
                    return put(putBody(null, null, manualProfile("Ada Lovelace", email)));
                }));
            }
            go.countDown();
            for (Future<Res> f : results) {
                Res r = f.get();
                assertThat(r.status()).as(r.toString()).isEqualTo(200);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(count("candidate")).isEqualTo(1);
        assertThat(count("owner_profile")).isEqualTo(1);
        assertThat(count("owner_profile_version")).isEqualTo(2);
    }

    @Test
    void ownersCandidateCannotBeDeletedOrEditedViaCandidatesApi() {
        assertThat(put(putBody(null, null, manualProfile("Ada Lovelace", "ada@example.com"))).status()).isEqualTo(200);
        UUID owner = ownerCandidateId();
        assertError(api.delete("/api/candidates/" + owner), 409, "DATA_CONFLICT");
        assertError(api.put("/api/candidates/" + owner, candidateBody("Mallory", "m@example.com", null, "Java")),
                409, "DATA_CONFLICT");
        assertThat(api.get("/api/profile").status()).isEqualTo(200);
        assertThat(api.get("/api/candidates/" + owner).json().get("fullName").asText()).isEqualTo("Ada Lovelace");
    }

    @Test
    void emailUsedByAnotherCandidateIs409() {
        candidate("Ada Test", "ada@example.com", "Java");
        UUID existing = jdbc.queryForObject("SELECT id FROM candidate", UUID.class);
        Res r = put(putBody(null, null, manualProfile("Ada Lovelace", "ada@example.com")));
        JsonNode b = assertError(r, 409, "EMAIL_ALREADY_EXISTS");
        assertThat(fields(b)).containsExactly("profile.email");
        assertThat(fieldMessages(b).get("profile.email")).contains(existing.toString());
        assertThat(count("owner_profile")).isZero();
        assertThat(count("owner_profile_version")).isZero();
        assertThat(count("candidate")).isEqualTo(1);
        assertThat(api.get("/api/candidates/" + existing).json().get("fullName").asText()).isEqualTo("Ada Test");
    }

    // ------------------------------------------------------------------ recovery, listing, deletion

    @Test
    void recoveryRequeuesInterruptedWork() {
        UUID id = uploadOk(TestPdfs.cv());
        awaitStatus(id, "SUCCEEDED");
        // simulate a crash mid-extraction
        jdbc.update("UPDATE resume SET status = 'RUNNING', draft = NULL, warnings = NULL, extraction_model = NULL, "
                + "extraction_started_at = now(), extraction_finished_at = NULL, attempts = 1 WHERE id = ?", id);
        int before = extractor.calls();
        recovery.requeueUnfinished();
        awaitStatus(id, "SUCCEEDED");
        assertThat(extractor.calls()).isEqualTo(before + 1);
    }

    @Test
    void repeatedlyInterruptedCvFailsWithTooManyAttempts() {
        UUID id = uploadOk(TestPdfs.cv());
        awaitStatus(id, "SUCCEEDED");
        jdbc.update("UPDATE resume SET status = 'RUNNING', draft = NULL, warnings = NULL, extraction_model = NULL, "
                + "extraction_started_at = now(), attempts = 3 WHERE id = ?", id);
        recovery.requeueUnfinished();
        JsonNode f = awaitStatus(id, "FAILED");
        assertThat(f.get("failureReason").asText()).isEqualTo("TOO_MANY_ATTEMPTS");
        // a manual retry starts over
        assertThat(api.post(UPLOAD + "/" + id + "/extract", null).status()).isEqualTo(202);
        awaitStatus(id, "SUCCEEDED");
    }

    @Test
    void listIsNewestFirstWithoutDrafts() {
        UUID first = uploadOk(TestPdfs.cv());
        awaitStatus(first, "SUCCEEDED");
        UUID second = uploadOk(TestPdfs.manyPages(2));
        awaitStatus(second, "SUCCEEDED");
        Res list = api.get("/api/profile/resumes");
        assertThat(list.status()).isEqualTo(200);
        assertThat(list.json()).hasSize(2);
        assertThat(list.json().get(0).get("id").asText()).isEqualTo(second.toString());
        assertThat(list.json().get(1).get("id").asText()).isEqualTo(first.toString());
        assertThat(list.json().get(0).get("pageCount").asInt()).isEqualTo(2);
        assertThat(list.json().get(0).get("warningCount").asInt()).isZero();
        assertThat(list.json().get(0).has("draft")).isFalse();
        assertThat(list.body()).doesNotContain("Acme Ltd").doesNotContain("extractedText");
    }

    @Test
    void deletingTheCvKeepsTheConfirmedProfile() {
        UUID id = uploadOk(TestPdfs.cv());
        JsonNode done = awaitStatus(id, "SUCCEEDED");
        assertThat(put(putBody(id, true, done.get("draft"))).status()).isEqualTo(200);
        assertThat(api.delete(UPLOAD + "/" + id).status()).isEqualTo(204);
        Res profile = api.get("/api/profile");
        assertThat(profile.status()).isEqualTo(200);
        assertThat(profile.json().get("resumeId").isNull()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM owner_profile_version WHERE resume_id IS NOT NULL",
                Integer.class)).isZero();
        assertError(api.delete(UPLOAD + "/" + UUID.randomUUID()), 404, "RESUME_NOT_FOUND");
    }

    @Test
    void unknownResumeIs404AndBadIdIs400() {
        assertError(api.get(UPLOAD + "/" + UUID.randomUUID()), 404, "RESUME_NOT_FOUND");
        assertError(api.post(UPLOAD + "/" + UUID.randomUUID() + "/extract", null), 404, "RESUME_NOT_FOUND");
        assertError(api.get(UPLOAD + "/not-a-uuid"), 400, "INVALID_ID");
    }

    // ------------------------------------------------------------------ S8 gate

    @Test
    void explanationsGetAiBusyWhileACvIsBeingRead() throws Exception {
        UUID job = backendJob();
        candidateWithSummary("Bob Builder", "bob@example.com", "Bob summary.", "Java:3", "SQL");
        CountDownLatch release = extractor.block();
        UUID id = uploadOk(TestPdfs.cv());
        assertThat(extractor.awaitStarted(10)).isTrue();

        JsonNode page = getMatches(job, null).json();
        assertThat(reasons(page)).containsOnly("AI_BUSY");
        assertThat(fake.calls()).as("no explanation call while the local model is busy").isZero();

        release.countDown();
        awaitStatus(id, "SUCCEEDED");
        assertThat(circuit.consecutiveFailures()).as("AI_BUSY is not a provider failure").isZero();
    }
}
