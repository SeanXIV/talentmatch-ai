package com.talentmatch.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.talentmatch.ai.LocalModelGate;
import com.talentmatch.support.FakeExtractionModel;
import com.talentmatch.support.TestPdfs;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.InterruptedIOException;
import java.net.http.HttpTimeoutException;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;

/** Phase 4: ResumeExtractionService with a mocked repository and the scripted model (test plan; B2, S1, S6). */
@ExtendWith(OutputCaptureExtension.class)
class ResumeExtractionServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-05T12:00:00Z"), ZoneOffset.UTC);
    private static final ObjectMapper JSON = new ObjectMapper();
    /** Appears only in CV content and model output: must never be logged (S6). */
    private static final String SECRET = "ZebraSecret42";

    private final UUID id = UUID.randomUUID();
    private ResumeRepository repo;
    private FakeExtractionModel fake;
    private ProfileProperties props;

    @BeforeEach
    void setUp() {
        repo = mock(ResumeRepository.class);
        fake = new FakeExtractionModel();
        props = new ProfileProperties(5_242_880, 16_000, null, false);
        when(repo.claim(eq(id), any())).thenReturn(true);
        when(repo.findText(id)).thenReturn(Optional.of(TestPdfs.CV_TEXT));
        when(repo.succeed(eq(id), anyString(), anyString(), anyString(), any())).thenReturn(true);
        when(repo.fail(eq(id), any(), any(), any())).thenReturn(true);
    }

    private static <T> ObjectProvider<T> provider(Class<T> type, T bean) {
        StaticListableBeanFactory f = new StaticListableBeanFactory(bean == null ? Map.of() : Map.of("bean", bean));
        return f.getBeanProvider(type);
    }

    private ResumeExtractionService service(ResumeExtractionModel model, LocalModelGate gate, Executor executor) {
        return new ResumeExtractionService(repo, provider(ResumeExtractionModel.class, model),
                provider(LocalModelGate.class, gate), executor, props, CLOCK,
                provider(MeterRegistry.class, new SimpleMeterRegistry()));
    }

    private ResumeExtractionService service(Integer contextTokens) {
        return service(new ResumeExtractionModel(fake, "fake/m", contextTokens), null, Runnable::run);
    }

    private ResumeExtractionService service() {
        return service((Integer) null);
    }

    private void verifyFailed(ExtractionFailure reason) {
        verify(repo).fail(eq(id), eq(reason), eq("fake/m"), any());
        verify(repo, never()).succeed(any(), any(), any(), any(), any());
    }

    // ------------------------------------------------------------------ success

    @Test
    void successStoresDraftAndWarnings() throws Exception {
        service().run(id);

        ArgumentCaptor<String> draft = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> warnings = ArgumentCaptor.forClass(String.class);
        verify(repo).succeed(eq(id), eq("fake/m"), draft.capture(), warnings.capture(), eq(CLOCK.instant()));
        verify(repo, never()).fail(any(), any(), any(), any());
        JsonNode d = JSON.readTree(draft.getValue());
        assertThat(d.get("fullName").asText()).isEqualTo("Ada Lovelace");
        assertThat(d.at("/skills/0/name").asText()).isEqualTo("Java");
        assertThat(JSON.readTree(warnings.getValue()).isArray()).isTrue();
        assertThat(JSON.readTree(warnings.getValue())).as("default doc is fully grounded").isEmpty();

        // the request carries the hand-built schema and the fenced CV text
        var req = fake.requests().get(0);
        assertThat(req.responseFormat().type()).isEqualTo(ResponseFormatType.JSON);
        assertThat(req.responseFormat().jsonSchema().name()).isEqualTo(ProfileJsonSchema.NAME);
        assertThat(fake.lastUserText()).contains("<cv>").contains("Acme Ltd").contains("</cv>");
    }

    @Test
    void ungroundedNamesAndYearsBecomeWarningsAndYearsAreDropped() throws Exception {
        fake.respondJson(FakeExtractionModel.DEFAULT_JSON
                .replace("{\"name\":\"Docker\",\"years\":null}", "{\"name\":\"Docker\",\"years\":4}")
                .replace("{\"name\":\"Java\",\"years\":null}", "{\"name\":\"Kotlin\",\"years\":null}"));
        service().run(id);
        ArgumentCaptor<String> draft = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> warnings = ArgumentCaptor.forClass(String.class);
        verify(repo).succeed(eq(id), any(), draft.capture(), warnings.capture(), any());
        JsonNode d = JSON.readTree(draft.getValue());
        assertThat(d.at("/skills/2/years").isNull()).as("B3: ungrounded years removed: %s", d).isTrue();
        assertThat(warnings.getValue()).contains("skills[0].name").contains("Kotlin").contains("skills[2].years");
    }

    @Test
    void placeholdersFromTheModelAreStoredAsNull() throws Exception {
        fake.respondJson(FakeExtractionModel.DEFAULT_JSON.replace("\"phone\":null", "\"phone\":\"N/A\"")
                .replace("\"summary\":null", "\"summary\":\"Not provided\""));
        service().run(id);
        ArgumentCaptor<String> draft = ArgumentCaptor.forClass(String.class);
        verify(repo).succeed(eq(id), any(), draft.capture(), any(), any());
        JsonNode d = JSON.readTree(draft.getValue());
        assertThat(d.path("phone").isNull() || d.path("phone").isMissingNode()).as("%s", d).isTrue();
        assertThat(d.path("summary").isNull() || d.path("summary").isMissingNode()).as("%s", d).isTrue();
    }

    @Test
    void markdownFencedJsonIsAccepted() {
        fake.respondJson("```json\n" + FakeExtractionModel.DEFAULT_JSON + "\n```");
        service().run(id);
        verify(repo).succeed(eq(id), any(), any(), any(), any());
    }

    @Test
    void longCvIsCutWithAWarning() throws Exception {
        props = new ProfileProperties(5_242_880, 1000, null, false);
        when(repo.findText(id)).thenReturn(Optional.of(TestPdfs.CV_TEXT + "\n" + "x".repeat(5000) + " TAIL_MARKER"));
        service().run(id);
        assertThat(fake.lastUserText()).doesNotContain("TAIL_MARKER");
        ArgumentCaptor<String> warnings = ArgumentCaptor.forClass(String.class);
        verify(repo).succeed(eq(id), any(), any(), warnings.capture(), any());
        assertThat(warnings.getValue()).contains("first 1000 characters");
    }

    // ------------------------------------------------------------------ failures

    @Test
    void finishLengthIsInvalidOutput() {
        fake.respond(FakeExtractionModel.DEFAULT_JSON, FinishReason.LENGTH, new TokenUsage(100, 4096));
        service().run(id);
        verifyFailed(ExtractionFailure.INVALID_OUTPUT);
    }

    @Test
    void contentFilterIsRefused() {
        fake.respond(FakeExtractionModel.DEFAULT_JSON, FinishReason.CONTENT_FILTER, new TokenUsage(100, 10));
        service().run(id);
        verifyFailed(ExtractionFailure.REFUSED);
    }

    @Test
    void refusalWithEmptyTextIsRefusedNotInvalidOutput() {
        // Claude refusals come back as stop_reason=refusal -> FinishReason.OTHER with null text (ProviderWireTest)
        fake.respond(req -> dev.langchain4j.model.chat.response.ChatResponse.builder()
                .aiMessage(dev.langchain4j.data.message.AiMessage.builder().build())
                .finishReason(FinishReason.OTHER).tokenUsage(new TokenUsage(100, 0)).build());
        service().run(id);
        verifyFailed(ExtractionFailure.REFUSED);
    }

    @Test
    void contentFilterWithBlankTextIsRefused() {
        fake.respond(" ", FinishReason.CONTENT_FILTER, new TokenUsage(100, 0));
        service().run(id);
        verifyFailed(ExtractionFailure.REFUSED);
    }

    @Test
    void timeoutIsTimeout() {
        fake.fail(new RuntimeException(new HttpTimeoutException("request timed out")));
        service().run(id);
        verifyFailed(ExtractionFailure.TIMEOUT);
    }

    @Test
    void providerErrorIsProviderError() {
        fake.fail(new RuntimeException("connection refused"));
        service().run(id);
        verifyFailed(ExtractionFailure.PROVIDER_ERROR);
    }

    @Test
    void notJsonIsInvalidOutput() {
        fake.respondJson("I'm sorry, here is the CV: Ada");
        service().run(id);
        verifyFailed(ExtractionFailure.INVALID_OUTPUT);
    }

    @Test
    void emptyDocumentIsInvalidOutput() {
        fake.respondJson("{\"fullName\":null,\"experience\":[],\"skills\":[],\"education\":[],\"projects\":[]}");
        service().run(id);
        verifyFailed(ExtractionFailure.INVALID_OUTPUT);
    }

    @Test
    void blankAnswerIsInvalidOutput() {
        fake.respondJson("   ");
        service().run(id);
        verifyFailed(ExtractionFailure.INVALID_OUTPUT);
    }

    @Test
    void contextOverflowIsDetectedFromTokenUsage() {
        fake.respond(FakeExtractionModel.DEFAULT_JSON, FinishReason.STOP, new TokenUsage(12_000, 256));
        service(12288).run(id); // 12256 >= 12288 - 32
        verifyFailed(ExtractionFailure.CONTEXT_OVERFLOW);
    }

    @Test
    void justBelowTheContextMarginSucceeds() {
        fake.respond(FakeExtractionModel.DEFAULT_JSON, FinishReason.STOP, new TokenUsage(12_000, 255));
        service(12288).run(id); // 12255 < 12256
        verify(repo).succeed(eq(id), any(), any(), any(), any());
    }

    @Test
    void contextIsNotCheckedForProviderManagedModels() {
        fake.respond(FakeExtractionModel.DEFAULT_JSON, FinishReason.STOP, new TokenUsage(500_000, 4000));
        service((Integer) null).run(id);
        verify(repo).succeed(eq(id), any(), any(), any(), any());
    }

    // ------------------------------------------------------------------ claim, disabled, queue

    @Test
    void lostClaimNeverCallsTheModel() {
        when(repo.claim(eq(id), any())).thenReturn(false);
        service().run(id);
        assertThat(fake.calls()).isZero();
        verify(repo, never()).fail(any(), any(), any(), any());
        verify(repo, never()).succeed(any(), any(), any(), any(), any());
    }

    @Test
    void aiDisabledFailsWithAiDisabled() {
        service(null, null, Runnable::run).run(id);
        verify(repo).fail(eq(id), eq(ExtractionFailure.AI_DISABLED), isNull(), any());
    }

    @Test
    void remoteModelWithoutOptInNeverSeesTheCv() {
        service(ResumeExtractionModel.remote(fake, "claude/x"), null, Runnable::run).run(id);
        assertThat(fake.calls()).isZero();
        verify(repo).fail(eq(id), eq(ExtractionFailure.REMOTE_EXTRACTION_DISABLED), eq("claude/x"), any());
        verify(repo, never()).findText(any());
    }

    @Test
    void remoteModelWithOptInExtracts() {
        props = new ProfileProperties(5_242_880, 16_000, null, true);
        service(ResumeExtractionModel.remote(fake, "claude/x"), null, Runnable::run).run(id);
        assertThat(fake.calls()).isEqualTo(1);
        verify(repo).succeed(eq(id), eq("claude/x"), any(), any(), any());
    }

    @Test
    void deletedMeanwhileDoesNothing() {
        when(repo.findText(id)).thenReturn(Optional.empty());
        service().run(id);
        assertThat(fake.calls()).isZero();
        verify(repo, never()).fail(any(), any(), any(), any());
    }

    @Test
    void fullQueueFailsWithQueueFull() {
        ResumeExtractionService s = service(new ResumeExtractionModel(fake, "fake/m"), null, r -> {
            throw new RejectedExecutionException("full");
        });
        s.enqueue(id);
        verify(repo).fail(eq(id), eq(ExtractionFailure.QUEUE_FULL), isNull(), any());
        assertThat(fake.calls()).isZero();
    }

    @Test
    void enqueueRunsOnTheExecutor() {
        AtomicBoolean ran = new AtomicBoolean();
        ResumeExtractionService s = service(new ResumeExtractionModel(fake, "fake/m"), null, r -> {
            ran.set(true);
            r.run();
        });
        s.enqueue(id);
        assertThat(ran).isTrue();
        verify(repo).succeed(eq(id), any(), any(), any(), any());
    }

    @Test
    void repositoryFailureWhileStoringIsSwallowed() {
        when(repo.succeed(any(), any(), any(), any(), any()))
                .thenThrow(new DataIntegrityViolationException("Failing row contains (" + SECRET + ")"));
        service().run(id); // never throws
        verify(repo).fail(eq(id), eq(ExtractionFailure.PROVIDER_ERROR), any(), any());
    }

    // ------------------------------------------------------------------ S1 shutdown

    @Test
    void interruptedCallLeavesTheRowRunning() {
        fake.fail(new RuntimeException(new InterruptedIOException("interrupted")));
        service().run(id);
        verify(repo, never()).fail(any(), any(), any(), any());
        verify(repo, never()).succeed(any(), any(), any(), any(), any());
    }

    @Test
    void interruptFlagLeavesTheRowRunning() {
        fake.respond(req -> {
            Thread.currentThread().interrupt();
            throw new RuntimeException("I/O error");
        });
        try {
            service().run(id);
        } finally {
            Thread.interrupted(); // clear for the next test
        }
        verify(repo, never()).fail(any(), any(), any(), any());
    }

    @Test
    void socketTimeoutIsATimeoutNotAnInterrupt() {
        fake.fail(new RuntimeException(new java.net.SocketTimeoutException("read timed out")));
        service().run(id);
        verifyFailed(ExtractionFailure.TIMEOUT);
    }

    @Test
    void interruptedWhileWaitingForTheGateLeavesTheRowRunning() throws Exception {
        LocalModelGate gate = new LocalModelGate();
        assertThat(gate.tryAcquireShared()).isTrue(); // an explanation holds the model
        Thread.currentThread().interrupt();
        try {
            service(new ResumeExtractionModel(fake, "fake/m"), gate, Runnable::run).run(id);
        } finally {
            Thread.interrupted();
            gate.releaseShared();
        }
        assertThat(fake.calls()).isZero();
        verify(repo, never()).fail(any(), any(), any(), any());
    }

    // ------------------------------------------------------------------ S8 gate

    @Test
    void gateIsHeldExclusivelyDuringTheCallAndReleasedAfter() {
        LocalModelGate gate = new LocalModelGate();
        AtomicBoolean heldDuringCall = new AtomicBoolean();
        fake.respond(req -> {
            heldDuringCall.set(gate.exclusiveHeld());
            return FakeExtractionModel.response(FakeExtractionModel.DEFAULT_JSON, FinishReason.STOP,
                    new TokenUsage(1, 1));
        });
        service(new ResumeExtractionModel(fake, "fake/m"), gate, Runnable::run).run(id);
        assertThat(heldDuringCall).isTrue();
        assertThat(gate.exclusiveHeld()).isFalse();

        fake.fail(new RuntimeException("boom"));
        service(new ResumeExtractionModel(fake, "fake/m"), gate, Runnable::run).run(id);
        assertThat(gate.exclusiveHeld()).as("released after a failure").isFalse();
        assertThat(gate.tryAcquireShared()).isTrue();
        gate.releaseShared();
    }

    @Test
    void extractionWaitsForARunningExplanation() throws Exception {
        LocalModelGate gate = new LocalModelGate();
        assertThat(gate.tryAcquireShared()).isTrue();
        Thread worker = new Thread(() -> service(new ResumeExtractionModel(fake, "fake/m"), gate, Runnable::run).run(id));
        worker.start();
        TimeUnit.MILLISECONDS.sleep(300);
        assertThat(fake.calls()).as("waits while an explanation runs").isZero();
        assertThat(gate.tryAcquireShared()).as("no new explanation while an extraction waits").isFalse();
        gate.releaseShared();
        worker.join(Duration.ofSeconds(10).toMillis());
        assertThat(fake.calls()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ S6 logs

    @Test
    void logsNeverContainCvContentOrModelOutput(CapturedOutput output) {
        when(repo.findText(id)).thenReturn(Optional.of(TestPdfs.CV_TEXT + "\n" + SECRET));
        // success path
        fake.respondJson(FakeExtractionModel.DEFAULT_JSON.replace("London", "London " + SECRET));
        service().run(id);
        // parse failure carrying the content
        fake.respondJson("{\"fullName\": \"" + SECRET + "\", broken");
        service().run(id);
        // provider failure whose message quotes content
        fake.fail(new RuntimeException("bad request: " + SECRET, new SQLException("Detail: " + SECRET, "23514")));
        service().run(id);
        // storage failure whose message quotes the row
        fake.reset();
        when(repo.succeed(any(), any(), any(), any(), any()))
                .thenThrow(new DataIntegrityViolationException("Failing row contains (" + SECRET + ")"));
        service().run(id);

        assertThat(output.getAll()).contains("CV extraction").doesNotContain(SECRET)
                .doesNotContain("Ada Lovelace").doesNotContain("Acme Ltd");
    }

    @Test
    void describeListsClassNamesAndSqlStateOnly() {
        String d = ResumeExtractionService.describe(new RuntimeException(SECRET, new SQLException(SECRET, "23505")));
        assertThat(d).isEqualTo("RuntimeException <- SQLException[SQLState 23505]");
    }
}
