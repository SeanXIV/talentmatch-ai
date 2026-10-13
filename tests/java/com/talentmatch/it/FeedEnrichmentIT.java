package com.talentmatch.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.talentmatch.ai.AiCircuitBreaker;
import com.talentmatch.ai.LocalModelGate;
import com.talentmatch.feed.FeedRecovery;
import com.talentmatch.feed.JobSkillEnrichmentService;
import com.talentmatch.feed.skills.JobSkillExtractionModel;
import com.talentmatch.support.AbstractFeedProcessingIT;
import com.talentmatch.support.FakeChatModel;
import com.talentmatch.support.FakeEnrichmentConfig;
import com.talentmatch.support.FakeExtractionModel;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import java.net.SocketTimeoutException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * §9.2 item 10 and the enrichment part of 13 (step 10): the AI stage with a scripted local model.
 * The scheduler is off, so tests call {@code processDue()} and {@code enrichDue()}.
 */
@ExtendWith(OutputCaptureExtension.class)
@Import(FakeEnrichmentConfig.class)
class FeedEnrichmentIT extends AbstractFeedProcessingIT {

    /** Dictionary finds Java and Python; "Go" alone in its sentence is ambiguous, so only the AI adds it. */
    private static final String DESC = "You will build Java services and Python tools. Comfortable with Go. "
            + "Terraform knowledge helps.";
    private static final String JAVA_SQL = "We use Java and SQL.";

    private static final String AI_ANSWER = """
            {"skills":[
              {"name":"Go","requirement":"REQUIRED","evidence":"Comfortable with Go"},
              {"name":"Terraform","requirement":"NICE_TO_HAVE","evidence":"Terraform knowledge helps"},
              {"name":"Rust","requirement":"REQUIRED","evidence":"Rust experience"}]}""";

    @DynamicPropertySource
    static void aiOn(DynamicPropertyRegistry registry) {
        registry.add("talentmatch.ai.enabled", () -> "true");
        registry.add("talentmatch.ai.provider", () -> "ollama");
        registry.add("talentmatch.ai.ollama.base-url", () -> "http://localhost:1");
        registry.add("talentmatch.feed.skills.enrichment.failure-backoff", () -> "1s");
    }

    @Autowired
    private JobSkillEnrichmentService enrichment;
    @Autowired
    private JobSkillExtractionModel model;
    @Autowired
    private LocalModelGate gate;
    @Autowired
    private FeedRecovery feedRecovery;
    @Autowired
    private AiCircuitBreaker circuit;

    private FakeExtractionModel fake;

    @BeforeEach
    void resetFake() {
        fake = (FakeExtractionModel) model.chatModel();
        fake.reset();
        fake.respondJson(AI_ANSWER);
    }

    /** A fresh (notifiable) posting, already processed by the dictionary. */
    private UUID freshJob(String token, String description) {
        UUID s = lever(token, "Acme");
        serve(token, old("base", "Platform Engineer", JAVA_SQL));
        pollOk(s);
        serve(token, old("base", "Platform Engineer", JAVA_SQL), fresh("n", "Backend Engineer", description));
        jdbc.update("UPDATE feed_source SET last_polled_at = NULL, content_hash = NULL, etag = NULL WHERE id = ?", s);
        pollOk(s);
        processor.processDue();
        return jobOf(s, "n");
    }

    private void makeEnrichmentDue() {
        jdbc.update("UPDATE feed_job SET enrichment_not_before = ? WHERE enrichment_not_before IS NOT NULL",
                Timestamp.from(Instant.now().minusSeconds(1)));
    }

    // ------------------------------------------------------------------ 10. enrichment

    @Test
    void aiAddsGroundedSkillsSuggestsUnknownsDropsInventionsAndRescores(CapturedOutput out) {
        skills("Java", "Python", "Go");
        profile("Java", "Go");
        notifications(true, 0.6);
        UUID job = freshJob("acme", DESC);
        assertThat(jobSkills(job)).containsOnlyKeys("Java", "Python");
        Double before = ownerScore(job);
        assertThat(notificationCount()).as("dictionary only: below the threshold").isZero();
        // the base job was enriched too; only the new one matters here
        jdbc.update("UPDATE feed_job SET enrichment_status = 'SKIPPED' WHERE job_id <> ?", job);

        JobSkillEnrichmentService.Report r = enrichment.enrichDue();
        assertThat(r.succeeded()).isEqualTo(1);
        assertThat(fake.calls()).isEqualTo(1);
        String prompt = FakeChatModel.userText(fake.requests().get(0));
        assertThat(prompt).contains("<job_posting>", "Comfortable with Go", "Company: Acme");

        Map<String, Object> f = feedJob(job);
        assertThat(f.get("enrichment_status")).isEqualTo("SUCCEEDED");
        assertThat(f.get("enrichment_model")).isEqualTo(FakeEnrichmentConfig.LABEL);
        assertThat(f.get("enriched_at")).isNotNull();
        assertThat(f.get("process_after")).as("handed back for re-scoring").isNotNull();
        assertThat(f.get("enrichment_attempts")).isEqualTo(1);

        processor.processDue();
        assertThat(jobSkills(job)).containsOnlyKeys("Java", "Python", "Go");
        assertThat(ownerScore(job)).isGreaterThan(before);
        assertThat(notificationCount(job)).as("newly over the threshold while fresh").isEqualTo(1);

        JsonNode view = api.get("/api/feed/jobs/" + job).json();
        assertThat(view.get("enrichmentStatus").asText()).isEqualTo("SUCCEEDED");
        assertThat(view.get("aiSuggestions")).singleElement()
                .satisfies(s -> assertThat(s.get("name").asText()).isEqualTo("Terraform"));
        JsonNode go = null;
        for (JsonNode s : view.get("skills")) {
            if (s.get("name").asText().equals("Go")) {
                go = s;
            }
        }
        assertThat(go).isNotNull();
        assertThat(go.get("source").asText()).isEqualTo("AI");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM skill WHERE name = 'Terraform'", Integer.class))
                .as("suggestions are never created").isZero();

        // again: nothing to enrich, no second notification
        assertThat(enrichment.enrichDue().succeeded()).isZero();
        jdbc.update("UPDATE feed_job SET process_after = now() WHERE job_id = ?", job);
        processor.processDue();
        assertThat(notificationCount(job)).isEqualTo(1);

        assertThat(out.getAll()).contains("Feed enrichment job=" + job, "outcome=success", "accepted=1",
                "suggestions=1", "ungrounded=1").doesNotContain("Comfortable with Go").doesNotContain("Terraform knowledge");
    }

    @Test
    void timeoutRetriesLaterAndPausesTheStage() {
        skills("Java");
        UUID job = freshJob("acme", DESC);
        jdbc.update("UPDATE feed_job SET enrichment_status = 'SKIPPED' WHERE job_id <> ?", job);
        fake.fail(new RuntimeException(new SocketTimeoutException("read timed out")));

        JobSkillEnrichmentService.Report r = enrichment.enrichDue();
        assertThat(r.pausedUntil()).isNotNull();
        Map<String, Object> f = feedJob(job);
        assertThat(f.get("enrichment_status")).isEqualTo("PENDING");
        assertThat(f.get("enrichment_attempts")).as("a provider fault gives the attempt back").isEqualTo(0);
        assertThat(f.get("enrichment_not_before")).isNotNull();

        makeEnrichmentDue();
        assertThat(enrichment.enrichDue().pausedUntil()).as("still paused: no call").isNotNull();
        assertThat(fake.calls()).isEqualTo(1);

        // the 1s pause ends; the next call succeeds
        fake.respondJson(AI_ANSWER);
        await().atMost(Duration.ofSeconds(5)).pollDelay(Duration.ofMillis(1100))
                .until(() -> enrichment.enrichDue().succeeded() == 1);
        assertThat(feedJob(job).get("enrichment_status")).isEqualTo("SUCCEEDED");
    }

    @Test
    void invalidAnswersRetryThenFailAfterMaxAttempts() {
        skills("Java");
        UUID job = freshJob("acme", DESC);
        jdbc.update("UPDATE feed_job SET enrichment_status = 'SKIPPED' WHERE job_id <> ?", job);
        fake.respondJson("this is not json");
        for (int attempt = 1; attempt <= 3; attempt++) {
            makeEnrichmentDue();
            JobSkillEnrichmentService.Report r = enrichment.enrichDue();
            assertThat(r.pausedUntil()).as("no pause for a bad answer").isNull();
        }
        Map<String, Object> f = feedJob(job);
        assertThat(f.get("enrichment_status")).isEqualTo("FAILED");
        assertThat(f.get("enrichment_failure")).isEqualTo("INVALID_OUTPUT");
        assertThat(f.get("enrichment_attempts")).isEqualTo(3);
        assertThat(fake.calls()).isEqualTo(3);
        assertThat(jobSkills(job)).as("the dictionary skills stay").containsOnlyKeys("Java");
    }

    @Test
    void refusalAndTruncationAreRetriedNotAccepted() {
        skills("Java");
        UUID job = freshJob("acme", DESC);
        jdbc.update("UPDATE feed_job SET enrichment_status = 'SKIPPED' WHERE job_id <> ?", job);
        fake.respond(AI_ANSWER, FinishReason.CONTENT_FILTER, new TokenUsage(100, 10));
        enrichment.enrichDue();
        assertThat(feedJob(job).get("enrichment_status")).isEqualTo("PENDING");
        fake.respond(AI_ANSWER, FinishReason.LENGTH, new TokenUsage(100, 600));
        makeEnrichmentDue();
        enrichment.enrichDue();
        assertThat(feedJob(job).get("enrichment_status")).isEqualTo("PENDING");
        assertThat(feedJob(job).get("ai_skills")).isNull();
    }

    @Test
    void cvExtractionHoldingTheModelDefersWithoutCountingAnAttempt() throws Exception {
        skills("Java");
        UUID job = freshJob("acme", DESC);
        jdbc.update("UPDATE feed_job SET enrichment_status = 'SKIPPED' WHERE job_id <> ?", job);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread extraction = new Thread(() -> {
            try {
                gate.acquireExclusive();
                held.countDown();
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                gate.releaseExclusive();
            }
        });
        extraction.start();
        try {
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
            JobSkillEnrichmentService.Report r = enrichment.enrichDue();
            assertThat(r.modelBusy()).isTrue();
            assertThat(fake.calls()).isZero();
            Map<String, Object> f = feedJob(job);
            assertThat(f.get("enrichment_status")).isEqualTo("PENDING");
            assertThat(f.get("enrichment_attempts")).as("attempt given back").isEqualTo(0);
            assertThat(f.get("enrichment_not_before")).isNotNull();
            assertThat(api.get("/api/feed/status").json().get("enrichment").get("modelBusy").asBoolean()).isTrue();
        } finally {
            release.countDown();
            extraction.join(10_000);
        }
        makeEnrichmentDue();
        assertThat(enrichment.enrichDue().succeeded()).isEqualTo(1);
    }

    @Test
    void answerForAChangedDescriptionIsDiscarded() {
        skills("Java");
        UUID job = freshJob("acme", DESC);
        jdbc.update("UPDATE feed_job SET enrichment_status = 'SKIPPED' WHERE job_id <> ?", job);
        java.util.concurrent.atomic.AtomicBoolean first = new java.util.concurrent.atomic.AtomicBoolean(true);
        fake.respond(req -> {
            if (first.getAndSet(false)) {
                // a poll replaced the description while the model was thinking (the processor's reset)
                jdbc.update("UPDATE feed_job SET description_hash = repeat('a', 64), enrichment_status = 'PENDING', "
                        + "enrichment_attempts = 0 WHERE job_id = ?", job);
            }
            return FakeExtractionModel.response(AI_ANSWER, FinishReason.STOP, new TokenUsage(100, 50));
        });
        JobSkillEnrichmentService.Report r = enrichment.enrichDue();
        // the stale answer is discarded; the new description is enriched in the same run
        assertThat(fake.calls()).isEqualTo(2);
        assertThat(r.succeeded()).isEqualTo(1);
        Map<String, Object> f = feedJob(job);
        assertThat(f.get("enrichment_status")).isEqualTo("SUCCEEDED");
        assertThat(f.get("enrichment_attempts")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 13. recovery and status

    @Test
    void recoveryRequeuesInterruptedAndSkippedEnrichments() {
        skills("Java");
        UUID job = freshJob("acme", DESC);
        jdbc.update("UPDATE feed_job SET enrichment_status = 'RUNNING', enrichment_attempts = 1 WHERE job_id = ?", job);
        jdbc.update("UPDATE feed_job SET enrichment_status = 'SKIPPED' WHERE job_id <> ?", job);
        feedRecovery.recover();
        assertThat(feedJob(job).get("enrichment_status")).isEqualTo("PENDING");
        assertThat(feedJob(job).get("enrichment_attempts")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM feed_job WHERE enrichment_status = 'SKIPPED'",
                Integer.class)).as("AI is on: skipped jobs get their turn").isZero();
    }

    @Test
    void statusShowsTheEnrichmentBacklog() {
        skills("Java");
        freshJob("acme", DESC);
        JsonNode e = api.get("/api/feed/status").json().get("enrichment");
        assertThat(e.get("aiEnabled").asBoolean()).isTrue();
        assertThat(e.get("pending").asLong()).isEqualTo(2);
        assertThat(e.get("running").asLong()).isZero();
        assertThat(e.get("failed").asLong()).isZero();
        assertThat(e.get("modelBusy").asBoolean()).isFalse();
    }
    // ------------------------------------------------------------------ 10. provider errors (tester, step 10)

    @Test
    void repeatedProviderErrorsNeverFailTheJobAndLeaveTheExplanationCircuitClosed() {
        skills("Java");
        UUID job = freshJob("acme", DESC);
        jdbc.update("UPDATE feed_job SET enrichment_status = 'SKIPPED' WHERE job_id <> ?", job);
        circuit.recordSuccess();
        fake.fail(new IllegalStateException("connection refused"));
        // one more provider error than max-attempts (3): the stage pause bounds the retries, not the job
        for (int attempt = 1; attempt <= 4; attempt++) {
            final int expected = attempt;
            makeEnrichmentDue();
            // the stage pauses for failure-backoff (1s) after each provider error
            await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(200))
                    .until(() -> {
                        enrichment.enrichDue();
                        return fake.calls() >= expected;
                    });
        }
        Map<String, Object> f = feedJob(job);
        assertThat(f.get("enrichment_status")).isEqualTo("PENDING");
        assertThat(f.get("enrichment_failure")).isNull();
        assertThat(f.get("enrichment_attempts")).as("provider faults are never counted").isEqualTo(0);
        assertThat(f.get("enrichment_not_before")).isNotNull();
        assertThat(fake.calls()).isEqualTo(4);
        assertThat(circuit.state()).as("enrichment never feeds the explanation circuit")
                .isEqualTo(AiCircuitBreaker.State.CLOSED);
        assertThat(circuit.consecutiveFailures()).isZero();
        assertThat(jobSkills(job)).as("the dictionary skills stay").containsOnlyKeys("Java");

        // the provider comes back: the job is enriched
        fake.respondJson(AI_ANSWER);
        makeEnrichmentDue();
        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(200))
                .until(() -> enrichment.enrichDue().succeeded() == 1);
        assertThat(feedJob(job).get("enrichment_status")).isEqualTo("SUCCEEDED");
        assertThat(feedJob(job).get("enrichment_attempts")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ M2: a new description drops the AI result

    @Test
    void aChangedDescriptionClearsTheAiColumnsAndTheAiOnlySkill() {
        skills("Java", "Python", "Go");
        UUID s = lever("acme", "Acme");
        serve("acme", old("base", "Platform Engineer", JAVA_SQL));
        pollOk(s);
        serve("acme", old("base", "Platform Engineer", JAVA_SQL), fresh("n", "Backend Engineer", DESC));
        jdbc.update("UPDATE feed_source SET last_polled_at = NULL, content_hash = NULL, etag = NULL WHERE id = ?", s);
        pollOk(s);
        processor.processDue();
        UUID job = jobOf(s, "n");
        jdbc.update("UPDATE feed_job SET enrichment_status = 'SKIPPED' WHERE job_id <> ?", job);
        assertThat(enrichment.enrichDue().succeeded()).isEqualTo(1);
        processor.processDue();
        assertThat(jobSkills(job)).containsOnlyKeys("Java", "Python", "Go");
        assertThat(feedJob(job).get("ai_skills")).isNotNull();

        // the posting changes: Go and Terraform are gone
        serve("acme", old("base", "Platform Engineer", JAVA_SQL),
                fresh("n", "Backend Engineer", "You will build Java services and Python tools."));
        jdbc.update("UPDATE feed_source SET last_polled_at = NULL, content_hash = NULL, etag = NULL WHERE id = ?", s);
        pollOk(s);
        processor.processDue();

        Map<String, Object> f = feedJob(job);
        assertThat(f.get("enrichment_status")).isEqualTo("PENDING");
        assertThat(f.get("enrichment_attempts")).isEqualTo(0);
        assertThat(f.get("ai_skills")).as("the old description's AI skills don't outlive it").isNull();
        // ai_suggestions is NOT NULL DEFAULT '[]' in V5: cleared means null or an empty array
        Object suggestions = f.get("ai_suggestions");
        assertThat(suggestions == null || String.valueOf(suggestions).replace(" ", "").equals("[]"))
                .as("ai_suggestions cleared, was %s", suggestions).isTrue();
        assertThat(f.get("enrichment_model")).isNull();
        assertThat(f.get("enriched_at")).isNull();
        assertThat(jobSkills(job)).as("the AI-only skill left job_skill").containsOnlyKeys("Java", "Python");
        JsonNode view = api.get("/api/feed/jobs/" + job).json();
        assertThat(view.get("enrichmentStatus").asText()).isEqualTo("PENDING");
        for (JsonNode skill : view.get("skills")) {
            assertThat(skill.get("name").asText()).isNotEqualTo("Go");
        }
    }

    // ------------------------------------------------------------------ M3: jobs without a description

    @Test
    void jobsWithoutADescriptionAreSkippedAndStaySkippedAfterRecovery() {
        skills("Java");
        UUID job = freshJob("acme", DESC);
        UUID other = jdbc.queryForObject("SELECT job_id FROM feed_job WHERE job_id <> ?", UUID.class, job);
        jdbc.update("UPDATE job SET description = NULL WHERE id = ?", job);
        jdbc.update("UPDATE feed_job SET enrichment_status = 'SKIPPED' WHERE job_id = ?", other);

        JobSkillEnrichmentService.Report r = enrichment.enrichDue();
        assertThat(r.skipped()).isEqualTo(1);
        assertThat(fake.calls()).as("nothing to read").isZero();
        assertThat(feedJob(job).get("enrichment_status")).isEqualTo("SKIPPED");
        assertThat(feedJob(job).get("enrichment_failure")).isNull();

        feedRecovery.recover();
        assertThat(feedJob(job).get("enrichment_status")).as("no description: stays SKIPPED").isEqualTo("SKIPPED");
        assertThat(feedJob(other).get("enrichment_status")).as("with a description: queued again")
                .isEqualTo("PENDING");
    }
}
