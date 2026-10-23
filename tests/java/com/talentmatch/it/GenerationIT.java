package com.talentmatch.it;

import static com.talentmatch.application.tailoring.TailoringFixtures.E1H1;
import static com.talentmatch.application.tailoring.TailoringFixtures.E2H1;
import static com.talentmatch.application.tailoring.TailoringFixtures.EMAIL;
import static com.talentmatch.application.tailoring.TailoringFixtures.NAME;
import static com.talentmatch.application.tailoring.TailoringFixtures.PHONE;
import static com.talentmatch.support.FakeTailoringModel.G0;
import static com.talentmatch.support.FakeTailoringModel.G1;
import static com.talentmatch.support.FakeTailoringModel.G2;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.talentmatch.application.content.DocumentKind;
import com.talentmatch.application.tailoring.GenerationFailure;
import com.talentmatch.application.tailoring.TailoringFixtures;
import com.talentmatch.support.AbstractGenerationIT;
import com.talentmatch.support.Api.Res;
import com.talentmatch.support.FakeTailoringModel;
import dev.langchain4j.exception.InternalServerException;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * Phase 7 §9.2 items 3 (happy path), 4 (grounding through the pipeline), 5 (generation states),
 * 10 (deletion during a run) and 11 (logging), against the scripted {@link FakeTailoringModel}.
 */
@ExtendWith(OutputCaptureExtension.class)
class GenerationIT extends AbstractGenerationIT {

    private static final String KINDS_INVALID = "kinds must contain CV and/or COVER_LETTER, each at most once.";
    private static final String JOB_GONE = "The job for this application no longer exists, so documents can't be "
            + "tailored to it. Existing versions are kept.";
    private static final ZoneId ZONE = ZoneId.of("Africa/Johannesburg");

    @Autowired
    private MeterRegistry meters;

    private long timerCount(String kind, String outcome) {
        Timer t = meters.find("talentmatch.ai.tailoring").tags("kind", kind, "outcome", outcome, "provider", "fake",
                "model", "tailor").timer();
        return t == null ? 0 : t.count();
    }

    // ------------------------------------------------------------------ §9.2.3 happy path

    @Test
    void createWithGenerateStoresAiCvAndLetterVersionOne() {
        int profileVersion = saveProfile();
        UUID job = initechJob();

        Res created = createApplication(job, "generate", true);
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        JsonNode app = created.json();
        UUID appId = id(app);
        JsonNode queued = app.get("latestGeneration");
        assertThat(queued.get("status").asText()).isEqualTo("PENDING");
        assertThat(queued.get("wantCv").asBoolean()).isTrue();
        assertThat(queued.get("wantCoverLetter").asBoolean()).isTrue();
        assertThat(queued.get("applicationId").asText()).isEqualTo(appId.toString());
        assertThat(queued.get("attempts").asInt()).isZero();
        assertThat(queued.get("results").size()).isZero();
        UUID genId = generationId(app);

        JsonNode done = awaitGeneration(appId, genId, "SUCCEEDED");
        assertThat(done.get("model").asText()).isEqualTo(FakeTailoringModel.LABEL);
        assertThat(done.get("profileVersion").asInt()).isEqualTo(profileVersion);
        assertThat(done.get("attempts").asInt()).isEqualTo(1);
        assertThat(done.get("failureReason").isNull()).isTrue();
        assertThat(done.get("hint").isNull()).isTrue();
        assertThat(done.get("startedAt").isNull()).isFalse();
        assertThat(done.get("finishedAt").isNull()).isFalse();
        assertThat(done.get("results").size()).isEqualTo(2);
        assertThat(done.at("/results/0/kind").asText()).isEqualTo("CV");
        assertThat(done.at("/results/1/kind").asText()).isEqualTo("COVER_LETTER");

        assertThat(tailor.calls(DocumentKind.CV)).isEqualTo(1);
        assertThat(tailor.calls(DocumentKind.COVER_LETTER)).isEqualTo(1);

        // stored versions
        Map<String, Object> cv = onlyDocument(appId, "CV");
        Map<String, Object> letter = onlyDocument(appId, "COVER_LETTER");
        for (Map<String, Object> d : List.of(cv, letter)) {
            assertThat(d.get("version")).isEqualTo(1);
            assertThat(d.get("source")).isEqualTo("AI");
            assertThat(d.get("model")).isEqualTo(FakeTailoringModel.LABEL);
            assertThat((String) d.get("input_hash")).matches("[0-9a-f]{64}");
            assertThat(d.get("profile_version")).isEqualTo(profileVersion);
            assertThat(d.get("based_on_id")).isNull();
            assertThat(d.get("job_updated_at")).isNotNull();
        }
        assertThat(cv.get("input_hash")).isNotEqualTo(letter.get("input_hash"));
        assertThat(result(done, "CV").get("documentId").asText()).isEqualTo(cv.get("id").toString());
        assertThat(result(done, "COVER_LETTER").get("documentId").asText()).isEqualTo(letter.get("id").toString());
        assertThat(result(done, "CV").get("failureReason").isNull()).isTrue();

        // the prompts carry refs and the job description, never the owner's contact details
        String cvPrompt = tailor.userTexts(DocumentKind.CV).get(0);
        String letterPrompt = tailor.userTexts(DocumentKind.COVER_LETTER).get(0);
        for (String prompt : List.of(cvPrompt, letterPrompt)) {
            assertThat(prompt).contains("E1.H1", "E2.H1", "<job_description>", "</job_description>",
                    "Zephyrine Quillfeather");
            assertThat(prompt).doesNotContain(NAME, EMAIL, PHONE, TailoringFixtures.CREDENTIAL_ID,
                    TailoringFixtures.GITHUB);
        }

        // the CV's facts equal the profile
        JsonNode cvContent = json(cv.get("content"));
        assertThat(cvContent.at("/header/fullName").asText()).isEqualTo(NAME);
        assertThat(cvContent.at("/header/email").asText()).isEqualTo(EMAIL);
        assertThat(cvContent.at("/header/phone").asText()).isEqualTo(PHONE);
        assertThat(cvContent.at("/experience/0/ref").asText()).isEqualTo("E1");
        assertThat(cvContent.at("/experience/0/title").asText()).isEqualTo("Senior Backend Engineer");
        assertThat(cvContent.at("/experience/0/company").asText()).isEqualTo("Acme Ltd");
        assertThat(cvContent.at("/experience/0/startDate").asText()).isEqualTo("2021-03");
        assertThat(cvContent.at("/experience/0/highlights/0/text").asText()).isEqualTo(E1H1);
        assertThat(cvContent.at("/experience/1/company").asText()).isEqualTo("Globex");
        assertThat(cvContent.at("/experience/1/highlights/0/text").asText()).isEqualTo(E2H1);
        assertThat(cvContent.at("/education/0/institution").asText()).isEqualTo("University of Stellenbosch");
        assertThat(codes(json(cv.get("findings")))).isEmpty();

        // the letter has the fixed parts
        JsonNode letterContent = json(letter.get("content"));
        assertThat(letterContent.get("salutation").asText()).isEqualTo("Dear Hiring Manager,");
        assertThat(letterContent.get("closing").asText()).isEqualTo("Kind regards,");
        assertThat(letterContent.get("signature").asText()).isEqualTo(NAME);
        assertThat(letterContent.get("company").asText()).isEqualTo("Initech");
        assertThat(letterContent.get("jobTitle").asText()).isEqualTo("Senior Java Developer");
        assertThat(letterContent.get("date").asText()).as("ISO date in the configured zone")
                .isEqualTo(LocalDate.ofInstant(clock.instant(), ZONE).toString());
        assertThat(letterContent.at("/header/fullName").asText()).isEqualTo(NAME);
        List<String> paragraphs = mapper.convertValue(letterContent.get("paragraphs"),
                mapper.getTypeFactory().constructCollectionType(List.class, String.class));
        assertThat(paragraphs).containsExactly(G0, G1, G2, G0, G1, G2);
        assertThat(codes(json(letter.get("findings")))).as("98 words is below the 180 minimum").containsExactly("LENGTH");

        // the application view and the list show the generation and the versions
        JsonNode view = api.get(BASE + "/" + appId).json();
        assertThat(view.at("/latestGeneration/id").asText()).isEqualTo(genId.toString());
        assertThat(view.at("/latestGeneration/status").asText()).isEqualTo("SUCCEEDED");
        assertThat(view.at("/documents/cv/versions").asInt()).isEqualTo(1);
        assertThat(view.at("/documents/cv/latest/version").asInt()).isEqualTo(1);
        assertThat(view.at("/documents/cv/latest/source").asText()).isEqualTo("AI");
        assertThat(view.at("/documents/coverLetter/versions").asInt()).isEqualTo(1);
        assertThat(view.at("/documents/coverLetter/latest/findingCounts/warning").asInt()).isEqualTo(1);
        JsonNode list = api.get(BASE).json();
        assertThat(list.at("/content/0/generationStatus").asText()).isEqualTo("SUCCEEDED");
        assertThat(list.at("/content/0/latestCvVersion").asInt()).isEqualTo(1);
    }

    @Test
    void theLetterDateIsTodayInTheConfiguredZoneNearMidnightUtc() {
        saveProfile();
        LocalDate utcToday = LocalDate.now(java.time.ZoneOffset.UTC);
        java.time.Instant lateUtc = utcToday.atTime(23, 30).toInstant(java.time.ZoneOffset.UTC);
        Duration shift = Duration.between(clock.instant(), lateUtc);
        clock.advance(shift);
        try {
            JsonNode app = createGenerating(initechJob(), "kinds", List.of("COVER_LETTER"));
            UUID appId = id(app);
            awaitGeneration(appId, generationId(app), "SUCCEEDED");
            JsonNode letter = json(onlyDocument(appId, "COVER_LETTER").get("content"));
            // Africa/Johannesburg is UTC+2 without DST: 23:30 UTC is already tomorrow there
            assertThat(letter.get("date").asText()).isEqualTo(utcToday.plusDays(1).toString());
        } finally {
            clock.advance(shift.negated());
        }
    }

    @Test
    void postGenerationsIs202WithLocationAndANewVersionIsTwo() {
        saveProfile();
        UUID appId = id(createGenerating(initechJob()));
        awaitAiIdle();

        Res r = postGeneration(appId, "{\"kinds\": [\"cv\"]}");
        assertThat(r.status()).as(r.toString()).isEqualTo(202);
        UUID genId = id(r.json());
        assertThat(r.header("Location")).isEqualTo(BASE + "/" + appId + "/generations/" + genId);
        assertThat(r.json().get("status").asText()).isEqualTo("PENDING");
        assertThat(r.json().get("wantCv").asBoolean()).isTrue();
        assertThat(r.json().get("wantCoverLetter").asBoolean()).isFalse();

        JsonNode done = awaitGeneration(appId, genId, "SUCCEEDED");
        assertThat(done.get("results").size()).isEqualTo(1);
        assertThat(documentCount(appId, "CV")).isEqualTo(2);
        assertThat(documentCount(appId, "COVER_LETTER")).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT version FROM application_document WHERE application_id = ? "
                + "AND kind = 'CV' ORDER BY version", Integer.class, appId)).containsExactly(1, 2);
        assertThat(tailor.calls(DocumentKind.CV)).isEqualTo(2);
        assertThat(tailor.calls(DocumentKind.COVER_LETTER)).isEqualTo(1);
    }

    @Test
    void noBodyOrAnEmptyBodyMeansBothKinds() {
        saveProfile();
        UUID appId = createTracked(initechJob());

        Res noBody = postGeneration(appId, null);
        assertThat(noBody.status()).as(noBody.toString()).isEqualTo(202);
        assertThat(noBody.json().get("wantCv").asBoolean()).isTrue();
        assertThat(noBody.json().get("wantCoverLetter").asBoolean()).isTrue();
        assertThat(generationRow(id(noBody.json())).get("mention_notice_period")).isEqualTo(false);
        awaitGeneration(appId, id(noBody.json()), "SUCCEEDED");

        Res empty = postGeneration(appId, "{}");
        assertThat(empty.status()).as(empty.toString()).isEqualTo(202);
        assertThat(empty.json().get("wantCv").asBoolean()).isTrue();
        assertThat(empty.json().get("wantCoverLetter").asBoolean()).isTrue();
        awaitGeneration(appId, id(empty.json()), "SUCCEEDED");
        assertThat(documentCount(appId, "CV")).isEqualTo(2);
        assertThat(documentCount(appId, "COVER_LETTER")).isEqualTo(2);
    }

    @Test
    void aTailoringTimerIsRecordedPerKindAndOutcome() {
        saveProfile();
        long cvBefore = timerCount("CV", "success");
        long letterBefore = timerCount("COVER_LETTER", "invalid_output");
        tailor.respondJson(DocumentKind.COVER_LETTER, "not json");
        JsonNode app = createGenerating(initechJob());
        awaitGeneration(id(app), generationId(app), "SUCCEEDED");
        assertThat(timerCount("CV", "success")).isEqualTo(cvBefore + 1);
        assertThat(timerCount("COVER_LETTER", "invalid_output")).isEqualTo(letterBefore + 1);
    }

    // ------------------------------------------------------------------ §9.2.4 grounding

    @Test
    void anInventedNumberIsRevertedAndAnUnknownRefDropped() {
        saveProfile();
        tailor.respondJson(DocumentKind.CV, """
                {"headline": null, "summary": null, "skills": ["Java", "PostgreSQL"],
                 "experience": [{"ref": "E1", "highlights": [
                                    {"ref": "E1.H1", "text": "Cut API latency by 45%% for 12,000 daily users"},
                                    {"ref": "E1.H9", "text": "Invented a time machine"}]},
                                {"ref": "E2", "highlights": [{"ref": "E2.H1", "text": "%s"}]}],
                 "projects": [], "certifications": ["C1"]}""".formatted(E2H1));
        JsonNode app = createGenerating(initechJob(), "kinds", List.of("CV"));
        UUID appId = id(app);
        awaitGeneration(appId, generationId(app), "SUCCEEDED");

        Map<String, Object> cv = onlyDocument(appId, "CV");
        JsonNode content = json(cv.get("content"));
        JsonNode e1 = content.at("/experience/0/highlights");
        assertThat(e1.size()).as("the unknown ref is dropped").isEqualTo(1);
        assertThat(e1.at("/0/ref").asText()).isEqualTo("E1.H1");
        assertThat(e1.at("/0/text").asText()).as("reverted to the profile's text").isEqualTo(E1H1);
        assertThat(cv.get("content").toString()).doesNotContain("45%", "time machine");

        JsonNode findings = json(cv.get("findings"));
        assertThat(codes(findings)).contains("REVERTED", "UNKNOWN_REF");
        findings.forEach(f -> assertThat(f.get("severity").asText()).as("CV repairs are INFO").isEqualTo("INFO"));
        boolean unknownRef = false;
        for (JsonNode f : findings) {
            if ("UNKNOWN_REF".equals(f.get("code").asText())) {
                assertThat(f.get("value").asText()).isEqualTo("E1.H9");
                unknownRef = true;
            }
        }
        assertThat(unknownRef).isTrue();
        assertThat(api.get(BASE + "/" + appId).json().at("/documents/cv/latest/findingCounts/info").asInt())
                .isEqualTo(findings.size());
    }

    /**
     * §9.2.4 literally: "a letter with '12 years' → BLOCKING". The profile's only 12 is inside "12,000" (E1.H1),
     * so 12 is not a profile number (regression guard: profile numbers once included each ","/"."-separated part,
     * "12,000" → 12000, 12, 000; now {@code ProfileText.wholeNumbers}).
     */
    @Test
    void aLetterClaimingTwelveYearsGetsABlockingFinding() {
        assertBlockingNumber("12");
    }

    /** The same pipeline path with a number that no profile number contains. */
    @Test
    void aLetterClaimingThirteenYearsGetsABlockingFinding() {
        assertBlockingNumber("13");
    }

    /** 96 words in 6 paragraphs (fewer than 90 words is rejected outright), one with an invented number. */
    private void assertBlockingNumber(String n) {
        saveProfile();
        tailor.respondJson(DocumentKind.COVER_LETTER, FakeTailoringModel.letter(G0,
                "I bring " + n + " years of backend experience with Java and PostgreSQL.", G1, G2, G1, G2));
        JsonNode app = createGenerating(initechJob(), "kinds", List.of("COVER_LETTER"));
        UUID appId = id(app);
        awaitGeneration(appId, generationId(app), "SUCCEEDED");

        Map<String, Object> letter = onlyDocument(appId, "COVER_LETTER");
        JsonNode findings = json(letter.get("findings"));
        JsonNode blocking = null;
        for (JsonNode f : findings) {
            if ("BLOCKING".equals(f.get("severity").asText())) {
                blocking = f;
            }
        }
        assertThat(blocking).as("findings %s", findings).isNotNull();
        assertThat(blocking.get("code").asText()).isEqualTo("NUMBER_NOT_IN_PROFILE");
        assertThat(blocking.get("value").asText()).as("the token only").isEqualTo(n);
        assertThat(blocking.get("path").asText()).isEqualTo("paragraphs[1]");
        assertThat(json(letter.get("content")).at("/paragraphs/1").asText()).as("the paragraph is kept")
                .contains(n + " years");
        assertThat(documentCount(appId, "CV")).isZero();
        assertThat(api.get(BASE + "/" + appId).json().at("/documents/coverLetter/latest/findingCounts/blocking")
                .asInt()).isEqualTo(1);
    }

    @Test
    void garbageForTheLetterFailsThatKindOnlyAndTheCvIsStored() {
        saveProfile();
        tailor.respondJson(DocumentKind.COVER_LETTER, "this is { not json");
        JsonNode app = createGenerating(initechJob());
        UUID appId = id(app);
        JsonNode done = awaitGeneration(appId, generationId(app), "SUCCEEDED");

        assertThat(done.get("failureReason").isNull()).isTrue();
        JsonNode cv = result(done, "CV");
        JsonNode letter = result(done, "COVER_LETTER");
        assertThat(cv.get("documentId").isNull()).isFalse();
        assertThat(cv.get("failureReason").isNull()).isTrue();
        assertThat(letter.get("documentId").isNull()).isTrue();
        assertThat(letter.get("failureReason").asText()).isEqualTo("INVALID_OUTPUT");
        assertThat(letter.get("hint").asText()).isEqualTo(GenerationFailure.INVALID_OUTPUT.hint());
        assertThat(documentCount(appId, "CV")).isEqualTo(1);
        assertThat(documentCount(appId, "COVER_LETTER")).isZero();
        assertThat(generationRow(generationId(app)).get("cover_letter_failure")).isEqualTo("INVALID_OUTPUT");
    }

    @Test
    void garbageForTheCvFailsThatKindOnlyAndTheLetterIsStored() {
        saveProfile();
        tailor.respondJson(DocumentKind.CV, "[1, 2, 3");
        JsonNode app = createGenerating(initechJob());
        UUID appId = id(app);
        JsonNode done = awaitGeneration(appId, generationId(app), "SUCCEEDED");
        assertThat(result(done, "CV").get("failureReason").asText()).isEqualTo("INVALID_OUTPUT");
        assertThat(result(done, "COVER_LETTER").get("documentId").isNull()).isFalse();
        assertThat(documentCount(appId, "CV")).isZero();
        assertThat(documentCount(appId, "COVER_LETTER")).isEqualTo(1);
    }

    @Test
    void bothKindsFailingFailsTheGenerationWithTheCvsFailure() {
        saveProfile();
        tailor.respondJson(DocumentKind.CV, "garbage");
        tailor.fail(DocumentKind.COVER_LETTER, new RuntimeException(new SocketTimeoutException("read timed out")));
        JsonNode app = createGenerating(initechJob());
        UUID appId = id(app);
        JsonNode done = awaitGeneration(appId, generationId(app), "FAILED");

        assertThat(done.get("failureReason").asText()).isEqualTo("INVALID_OUTPUT");
        assertThat(done.get("hint").asText()).isEqualTo(GenerationFailure.INVALID_OUTPUT.hint());
        assertThat(done.get("model").asText()).isEqualTo(FakeTailoringModel.LABEL);
        assertThat(done.get("finishedAt").isNull()).isFalse();
        assertThat(result(done, "CV").get("failureReason").asText()).isEqualTo("INVALID_OUTPUT");
        assertThat(result(done, "COVER_LETTER").get("failureReason").asText()).isEqualTo("TIMEOUT");
        assertThat(documentRows(appId)).isEmpty();
        assertThat(api.get(BASE).json().at("/content/0/generationStatus").asText()).isEqualTo("FAILED");
    }

    @Test
    void onlyTheLetterWantedAndFailingFailsWithTheLettersFailure() {
        saveProfile();
        tailor.fail(DocumentKind.COVER_LETTER, new RuntimeException(new SocketTimeoutException("read timed out")));
        JsonNode app = createGenerating(initechJob(), "kinds", List.of("COVER_LETTER"));
        UUID appId = id(app);
        JsonNode done = awaitGeneration(appId, generationId(app), "FAILED");
        assertThat(done.get("failureReason").asText()).isEqualTo("TIMEOUT");
        assertThat(done.get("results").size()).isEqualTo(1);
        assertThat(tailor.calls(DocumentKind.CV)).isZero();
        assertThat(documentRows(appId)).isEmpty();
    }

    // ------------------------------------------------------------------ §9.2.5 states

    @Test
    void aSecondPostWhileRunningOrPendingIs409GenerationInProgress() throws Exception {
        saveProfile();
        CountDownLatch release = tailor.block(DocumentKind.CV);
        JsonNode a = createGenerating(initechJob("Senior Java Developer"));
        UUID appA = id(a);
        UUID genA = generationId(a);
        assertThat(tailor.awaitStarted(10)).isTrue();
        awaitGeneration(appA, genA, "RUNNING");

        JsonNode running = assertError(postGeneration(appA, null), 409, "GENERATION_IN_PROGRESS");
        assertThat(running.get("message").asText()).isEqualTo("A generation for this application is already RUNNING "
                + "(generation " + genA + "). Wait for it with GET /api/applications/" + appA + "/generations/"
                + genA + ".");

        // B queues behind A (one worker), so it stays PENDING
        JsonNode b = createGenerating(initechJob("Java Developer"));
        UUID appB = id(b);
        UUID genB = generationId(b);
        JsonNode pending = assertError(postGeneration(appB, "{\"kinds\": [\"CV\"]}"), 409, "GENERATION_IN_PROGRESS");
        assertThat(pending.get("message").asText()).contains("already PENDING (generation " + genB + ")");
        assertThat(getGeneration(appB, genB).json().get("status").asText()).isEqualTo("PENDING");

        release.countDown();
        awaitGeneration(appA, genA, "SUCCEEDED");
        awaitGeneration(appB, genB, "SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM application_generation", Integer.class)).isEqualTo(2);

        Res again = postGeneration(appA, null);
        assertThat(again.status()).as("a finished generation no longer blocks a new one").isEqualTo(202);
    }

    @Test
    void aModelTimeoutIsTimeout() {
        saveProfile();
        tailor.fail(new RuntimeException(new SocketTimeoutException("read timed out")));
        JsonNode app = createGenerating(initechJob());
        JsonNode done = awaitGeneration(id(app), generationId(app), "FAILED");
        assertThat(done.get("failureReason").asText()).isEqualTo("TIMEOUT");
        assertThat(done.get("hint").asText()).contains("talentmatch.applications.generation.call-timeout");
        assertThat(result(done, "CV").get("failureReason").asText()).isEqualTo("TIMEOUT");
        assertThat(result(done, "COVER_LETTER").get("failureReason").asText()).isEqualTo("TIMEOUT");
        assertThat(documentRows(id(app))).isEmpty();
    }

    @Test
    void aServerErrorIsProviderError() {
        saveProfile();
        tailor.fail(new InternalServerException("500 Internal Server Error"));
        JsonNode app = createGenerating(initechJob());
        JsonNode done = awaitGeneration(id(app), generationId(app), "FAILED");
        assertThat(done.get("failureReason").asText()).isEqualTo("PROVIDER_ERROR");
        assertThat(done.get("hint").asText()).isEqualTo(GenerationFailure.PROVIDER_ERROR.hint());
        assertThat(circuit.consecutiveFailures()).as("the circuit breaker is not fed").isZero();
    }

    @Test
    void tokenUsageAtTheContextLimitIsContextOverflow() {
        saveProfile();
        // 12288 - 32 = 12256 tokens or more
        TokenUsage full = new TokenUsage(12000, 256);
        tailor.respond(DocumentKind.CV, FakeTailoringModel.CV_JSON, FinishReason.STOP, full);
        tailor.respond(DocumentKind.COVER_LETTER, FakeTailoringModel.LETTER_JSON, FinishReason.STOP, full);
        JsonNode app = createGenerating(initechJob());
        JsonNode done = awaitGeneration(id(app), generationId(app), "FAILED");
        assertThat(done.get("failureReason").asText()).isEqualTo("CONTEXT_OVERFLOW");
        assertThat(done.get("hint").asText()).contains("talentmatch.ai.ollama.context-tokens");
        assertThat(documentRows(id(app))).isEmpty();
    }

    @Test
    void finishReasonLengthIsInvalidOutput() {
        saveProfile();
        TokenUsage usage = new TokenUsage(3000, 2048);
        tailor.respond(DocumentKind.CV, FakeTailoringModel.CV_JSON, FinishReason.LENGTH, usage);
        tailor.respond(DocumentKind.COVER_LETTER, FakeTailoringModel.LETTER_JSON, FinishReason.LENGTH, usage);
        JsonNode app = createGenerating(initechJob());
        JsonNode done = awaitGeneration(id(app), generationId(app), "FAILED");
        assertThat(done.get("failureReason").asText()).isEqualTo("INVALID_OUTPUT");
        assertThat(result(done, "CV").get("failureReason").asText()).isEqualTo("INVALID_OUTPUT");
        assertThat(result(done, "COVER_LETTER").get("failureReason").asText()).isEqualTo("INVALID_OUTPUT");
    }

    @Test
    void aJobDeletedBeforeTheRunIsJobDeletedAndBeforeThePostIs409DataConflict() throws Exception {
        saveProfile();
        // A holds the single worker, so B's generation waits in the queue while its job is deleted
        CountDownLatch release = tailor.block(DocumentKind.CV);
        JsonNode a = createGenerating(initechJob("Senior Java Developer"));
        assertThat(tailor.awaitStarted(10)).isTrue();
        UUID jobB = initechJob("Java Developer");
        JsonNode b = createGenerating(jobB);
        UUID appB = id(b);
        UUID genB = generationId(b);
        assertThat(api.delete("/api/jobs/" + jobB).status()).isEqualTo(204);

        release.countDown();
        awaitGeneration(id(a), generationId(a), "SUCCEEDED");
        JsonNode failed = awaitGeneration(appB, genB, "FAILED");
        assertThat(failed.get("failureReason").asText()).isEqualTo("JOB_DELETED");
        assertThat(failed.get("hint").asText()).isEqualTo(JOB_GONE);
        assertThat(failed.get("model").asText()).isEqualTo(FakeTailoringModel.LABEL);
        assertThat(documentRows(appB)).isEmpty();
        assertThat(tailor.calls(DocumentKind.CV)).as("only A's CV was generated").isEqualTo(1);

        // now the job is gone: a new POST is refused up front
        JsonNode conflict = assertError(postGeneration(appB, null), 409, "DATA_CONFLICT");
        assertThat(conflict.get("message").asText()).isEqualTo(JOB_GONE);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM application_generation WHERE application_id = ?",
                Integer.class, appB)).isEqualTo(1);
    }

    @Test
    void withoutAProfileCreateWithGenerateIs404AndCreatesNothing() {
        UUID job = initechJob();
        assertError(createApplication(job, "generate", true), 404, "PROFILE_NOT_FOUND");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM application", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM application_event", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM application_generation", Integer.class)).isZero();

        // the profile check comes before the job check
        assertError(createApplication(UUID.randomUUID(), "generate", true), 404, "PROFILE_NOT_FOUND");

        // tracking without generate needs no profile; a generation POST then needs one
        UUID appId = createTracked(job);
        assertThat(api.get(BASE + "/" + appId).json().get("latestGeneration").isNull()).isTrue();
        assertError(postGeneration(appId, null), 404, "PROFILE_NOT_FOUND");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM application_generation", Integer.class)).isZero();
        assertThat(tailor.calls()).isZero();
    }

    @Test
    void invalidKindsAre400OnBothEndpoints() {
        saveProfile();
        UUID job = initechJob();
        for (String kinds : List.of("[\"PDF\"]", "[]", "[\"CV\", \"cv\"]", "[\"CV\", null]", "[\"\"]")) {
            JsonNode err = assertError(api.post(BASE, "{\"jobId\": \"" + job + "\", \"generate\": true, \"kinds\": "
                    + kinds + "}"), 400, "VALIDATION_FAILED");
            assertThat(fieldMessages(err)).as(kinds).containsEntry("kinds", KINDS_INVALID);
        }
        // validated even without generate
        assertError(api.post(BASE, "{\"jobId\": \"" + job + "\", \"kinds\": [\"PDF\"]}"), 400, "VALIDATION_FAILED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM application", Integer.class)).isZero();

        UUID appId = createTracked(job);
        for (String kinds : List.of("[\"PDF\"]", "[]", "[\"COVER_LETTER\", \"COVER_LETTER\"]")) {
            JsonNode err = assertError(postGeneration(appId, "{\"kinds\": " + kinds + "}"), 400, "VALIDATION_FAILED");
            assertThat(fieldMessages(err)).as(kinds).containsExactly(Map.entry("kinds", KINDS_INVALID));
        }
        assertError(postGeneration(appId, "{\"kinds\": [\"CV\"], \"bogus\": 1}"), 400, "MALFORMED_REQUEST");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM application_generation", Integer.class)).isZero();
        assertThat(tailor.calls()).isZero();
    }

    @Test
    void unknownIdsAre404AndBadIds400() {
        saveProfile();
        UUID missing = UUID.randomUUID();
        JsonNode noApp = assertError(postGeneration(missing, null), 404, "APPLICATION_NOT_FOUND");
        assertThat(noApp.get("message").asText()).isEqualTo("No application with id " + missing + ".");

        JsonNode a = createGenerating(initechJob("Senior Java Developer"));
        UUID appA = id(a);
        UUID genA = generationId(a);
        UUID appB = createTracked(initechJob("Java Developer"));
        awaitAiIdle();

        UUID genMissing = UUID.randomUUID();
        JsonNode noGen = assertError(getGeneration(appA, genMissing), 404, "GENERATION_NOT_FOUND");
        assertThat(noGen.get("message").asText()).isEqualTo("Application " + appA + " has no generation "
                + genMissing + ".");
        assertError(getGeneration(appB, genA), 404, "GENERATION_NOT_FOUND");
        assertError(getGeneration(missing, genA), 404, "GENERATION_NOT_FOUND");
        assertError(api.get(BASE + "/not-a-uuid/generations/" + genA), 400, "INVALID_ID");
        assertError(api.get(BASE + "/" + appA + "/generations/nope"), 400, "INVALID_ID");
        assertError(api.post(BASE + "/nope/generations", null), 400, "INVALID_ID");
        assertThat(getGeneration(appA, genA).status()).isEqualTo(200);
    }

    @Test
    void mentionNoticePeriodAddsTheSentenceFromPreferences() {
        saveProfile();
        Res prefs = api.put("/api/preferences", "{\"preferences\": {\"noticePeriodDays\": 30}}");
        assertThat(prefs.status()).as(prefs.toString()).isEqualTo(200);
        UUID appId = createTracked(initechJob());

        Res with = postGeneration(appId, "{\"kinds\": [\"COVER_LETTER\"], \"mentionNoticePeriod\": true}");
        assertThat(with.status()).as(with.toString()).isEqualTo(202);
        assertThat(generationRow(id(with.json())).get("mention_notice_period")).isEqualTo(true);
        awaitGeneration(appId, id(with.json()), "SUCCEEDED");
        Res without = postGeneration(appId, "{\"kinds\": [\"COVER_LETTER\"], \"mentionNoticePeriod\": false}");
        awaitGeneration(appId, id(without.json()), "SUCCEEDED");
        assertThat(tailor.calls(DocumentKind.CV)).isZero();

        List<Map<String, Object>> letters = documentRows(appId);
        assertThat(letters).hasSize(2);
        JsonNode v1 = json(letters.get(0).get("content")).get("paragraphs");
        JsonNode v2 = json(letters.get(1).get("content")).get("paragraphs");
        assertThat(v1.size()).isEqualTo(7);
        assertThat(v1.get(6).asText()).isEqualTo("I am available to start after a notice period of 30 days.");
        assertThat(v2.size()).isEqualTo(6);
        assertThat(letters.get(1).get("content").toString()).doesNotContain("notice period");
        assertThat(codes(json(letters.get(0).get("findings")))).doesNotContain("NOTICE_PERIOD_UNKNOWN");
    }

    @Test
    void mentionNoticePeriodWithoutPreferencesIsNoticePeriodUnknown() {
        saveProfile();
        JsonNode app = createGenerating(initechJob(), "kinds", List.of("COVER_LETTER"), "mentionNoticePeriod", true);
        UUID appId = id(app);
        assertThat(generationRow(generationId(app)).get("mention_notice_period")).isEqualTo(true);
        awaitGeneration(appId, generationId(app), "SUCCEEDED");
        Map<String, Object> letter = onlyDocument(appId, "COVER_LETTER");
        assertThat(json(letter.get("content")).get("paragraphs").size()).isEqualTo(6);
        assertThat(codes(json(letter.get("findings")))).contains("NOTICE_PERIOD_UNKNOWN");
    }

    // ------------------------------------------------------------------ §9.2.10 deletion during a run

    @Test
    void deletingTheApplicationWhileTheCvIsGeneratedLeavesNoRowsAndNoErrors(CapturedOutput output) throws Exception {
        saveProfile();
        CountDownLatch release = tailor.block(DocumentKind.CV);
        JsonNode app = createGenerating(initechJob());
        UUID appId = id(app);
        assertThat(tailor.awaitStarted(10)).isTrue();
        awaitGeneration(appId, generationId(app), "RUNNING");

        assertThat(api.delete(BASE + "/" + appId).status()).isEqualTo(204);
        release.countDown();
        awaitAiIdle();

        assertThat(tailor.calls(DocumentKind.CV)).isEqualTo(1);
        assertThat(tailor.calls(DocumentKind.COVER_LETTER)).as("the run stops once the result is discarded").isZero();
        assertNoApplicationRows();
        assertThat(errorLines(output)).isEmpty();
        assertThat(output.getAll()).contains("outcome=discarded");
    }

    @Test
    void deletingTheApplicationWhileTheLetterIsGeneratedDropsTheStoredCvToo(CapturedOutput output) throws Exception {
        saveProfile();
        CountDownLatch release = tailor.block(DocumentKind.COVER_LETTER);
        JsonNode app = createGenerating(initechJob());
        UUID appId = id(app);
        await().atMost(Duration.ofSeconds(10)).until(() -> tailor.calls(DocumentKind.COVER_LETTER) == 1);
        assertThat(documentCount(appId, "CV")).as("the CV is visible while the letter is generated").isEqualTo(1);
        assertThat(getGeneration(appId, generationId(app)).json().at("/results/0/kind").asText()).isEqualTo("CV");

        assertThat(api.delete(BASE + "/" + appId).status()).isEqualTo(204);
        release.countDown();
        awaitAiIdle();

        assertNoApplicationRows();
        assertThat(errorLines(output)).isEmpty();
    }

    private void assertNoApplicationRows() {
        for (String table : List.of("application", "application_document", "application_generation",
                "application_event")) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class)).as(table).isZero();
        }
    }

    private static List<String> errorLines(CapturedOutput output) {
        return output.getAll().lines().filter(l -> l.contains("ERROR") || l.contains("Exception")).toList();
    }

    // ------------------------------------------------------------------ §9.2.11 logging

    @Test
    void logsCarryIdsAndCountsButNoTextOrNames(CapturedOutput output) {
        saveProfile();
        String notes = "Referred by Quentin Marblewright over coffee";
        tailor.respondJson(DocumentKind.COVER_LETTER, FakeTailoringModel.letter(G0,
                "I bring 13 years of backend experience with Java and PostgreSQL.", G1, G2, G1, G2));
        JsonNode app = createGenerating(initechJob(), "notes", notes);
        UUID appId = id(app);
        UUID genId = generationId(app);
        awaitGeneration(appId, genId, "SUCCEEDED");
        // a failing kind too (garbage), so its paths are logged as well
        tailor.respondJson(DocumentKind.CV, "{\"headline\": \"Secret Garbage Headline\"");
        Res again = postGeneration(appId, null);
        awaitGeneration(appId, id(again.json()), "SUCCEEDED");

        String all = output.getAll();
        assertThat(all).contains("Tailoring application=" + appId + " generation=" + genId + " kind=CV model="
                + FakeTailoringModel.LABEL + " outcome=success");
        assertThat(all).contains("kind=COVER_LETTER", "blocking=1", "outcome=invalid_output");
        List<String> forbidden = List.of(
                notes, "Quentin", "Marblewright",                                    // notes
                E1H1, E2H1, TailoringFixtures.E1H2, "API latency",                    // highlight text
                G0, G1, "13 years", "backend experience", "Secret Garbage",           // generated text
                JOB_DESCRIPTION, "Zephyrine", "Quillfeather",                         // job description
                NAME, "Ndlovu", "Müller", EMAIL, PHONE,                               // owner
                "is not in your profile");                                            // finding messages
        for (String s : forbidden) {
            assertThat(all).as("logs must not contain '%s'", s).doesNotContain(s);
        }
    }
}
