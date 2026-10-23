package com.talentmatch.application.tailoring;

import static com.talentmatch.application.tailoring.TailoringFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.talentmatch.application.content.CoverLetterContent;
import com.talentmatch.application.content.DocumentKind;
import com.talentmatch.application.content.ExperienceItem;
import com.talentmatch.application.content.FindingCode;
import com.talentmatch.application.content.TailoredCvContent;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.SocketTimeoutException;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** §4.6 / step 4: the engine with a mocked ChatModel (response checks, failures, interrupts, success). */
class TailoringEngineTest {

    private static final String CV_JSON = """
            {"headline": null, "summary": null, "skills": ["Java", "PostgreSQL"],
             "experience": [{"ref": "E1", "highlights": [{"ref": "E1.H1", "text": "%s"}]},
                            {"ref": "E2", "highlights": [{"ref": "E2.H1", "text": "%s"}]}],
             "projects": [], "certifications": ["C1"]}""".formatted(E1H1, E2H1);

    private static final String LETTER_JSON = "{\"paragraphs\": [\"%s\", \"%s\", \"%s\", \"%s\", \"%s\", \"%s\"]}"
            .formatted(CoverLetterAssemblerTest.G0, CoverLetterAssemblerTest.G1, CoverLetterAssemblerTest.G2,
                    CoverLetterAssemblerTest.G0, CoverLetterAssemblerTest.G1, CoverLetterAssemblerTest.G2);

    private final ChatModel chat = mock(ChatModel.class);
    private final TailoringModel model = new TailoringModel(chat, "fake/tailor", 12288, true);
    private final TailoringEngine engine = new TailoringEngine(ApplicationProperties.defaults());
    private final TailoringEngine.Options options = new TailoringEngine.Options(TODAY, false, null);

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    private static ChatResponse response(String text, FinishReason finish, TokenUsage usage) {
        return ChatResponse.builder().aiMessage(text == null ? AiMessage.builder().build() : AiMessage.from(text))
                .finishReason(finish).tokenUsage(usage).build();
    }

    private void answer(String text, FinishReason finish, TokenUsage usage) {
        when(chat.chat(any(ChatRequest.class))).thenReturn(response(text, finish, usage));
    }

    private TailoringOutcome<TailoredCvContent> cv() {
        return engine.generateCv(model, job(), facts(), check());
    }

    private static <T> TailoringOutcome.Failed<T> failed(TailoringOutcome<T> o, GenerationFailure failure) {
        assertThat(o).isInstanceOf(TailoringOutcome.Failed.class);
        TailoringOutcome.Failed<T> f = (TailoringOutcome.Failed<T>) o;
        assertThat(f.failure()).isEqualTo(failure);
        assertThat(f.tag()).isEqualTo(failure.tag());
        return f;
    }

    // ------------------------------------------------------------------ success

    @Test
    void aGroundedCvAnswerIsGeneratedWithThePromptsHash() {
        TokenUsage usage = new TokenUsage(3000, 400);
        answer(CV_JSON, FinishReason.STOP, usage);
        TailoringOutcome<TailoredCvContent> o = cv();
        assertThat(o).isInstanceOf(TailoringOutcome.Generated.class);
        var g = (TailoringOutcome.Generated<TailoredCvContent>) o;
        assertThat(g.tag()).isEqualTo("success");
        assertThat(g.usage()).isEqualTo(usage);
        assertThat(g.inputHash()).isEqualTo(engine.prompts().buildCv(job(), facts()).inputHash());
        assertThat(g.content().experience()).extracting(ExperienceItem::ref).containsExactly("E1", "E2");
        assertThat(g.content().header().email()).as("facts are copied from the profile").isEqualTo(EMAIL);
        assertThat(g.findings()).isEmpty();

        ArgumentCaptor<ChatRequest> req = ArgumentCaptor.forClass(ChatRequest.class);
        verify(chat).chat(req.capture());
        ChatRequest r = req.getValue();
        assertThat(r.messages()).hasSize(2);
        assertThat(((SystemMessage) r.messages().get(0)).text()).isEqualTo(TailoringPrompts.CV_SYSTEM);
        assertThat(((UserMessage) r.messages().get(1)).singleText())
                .isEqualTo(engine.prompts().buildCv(job(), facts()).user());
        assertThat(r.responseFormat()).isEqualTo(TailoredCvJsonSchema.responseFormat());
    }

    @Test
    void aGroundedLetterAnswerIsGenerated() {
        answer(LETTER_JSON, FinishReason.STOP, new TokenUsage(3000, 300));
        TailoringOutcome<?> o = engine.generate(DocumentKind.COVER_LETTER, model, job(), facts(), check(),
                new TailoringEngine.Options(TODAY, true, 30));
        assertThat(o).isInstanceOf(TailoringOutcome.Generated.class);
        var g = (TailoringOutcome.Generated<CoverLetterContent>) o;
        assertThat(g.content().date()).isEqualTo(TODAY);
        assertThat(g.content().paragraphs()).hasSize(7).last()
                .isEqualTo("I am available to start after a notice period of 30 days.");
        assertThat(g.findings()).as("98 words is below the default 180").extracting(f -> f.code())
                .containsExactly(FindingCode.LENGTH);
        assertThat(g.inputHash()).isEqualTo(engine.prompts().buildCoverLetter(job(), facts()).inputHash());

        ArgumentCaptor<ChatRequest> req = ArgumentCaptor.forClass(ChatRequest.class);
        verify(chat).chat(req.capture());
        assertThat(((SystemMessage) req.getValue().messages().get(0)).text()).contains("180 to 350 words");
        assertThat(req.getValue().responseFormat()).isEqualTo(CoverLetterJsonSchema.responseFormat());
    }

    @Test
    void generateDispatchesByKind() {
        answer(CV_JSON, FinishReason.STOP, null);
        assertThat(engine.generate(DocumentKind.CV, model, job(), facts(), check(), null))
                .isInstanceOf(TailoringOutcome.Generated.class);
        assertThatThrownBy(() -> engine.generate(DocumentKind.COVER_LETTER, model, job(), facts(), check(), null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> engine.generate(null, model, job(), facts(), check(), options))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new TailoringEngine.Options(null, false, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> engine.generateCv(null, job(), facts(), check())).isInstanceOf(NullPointerException.class);
    }

    // ------------------------------------------------------------------ model exceptions

    @Test
    void aProviderErrorIsClassifiedWithoutUsage() {
        when(chat.chat(any(ChatRequest.class))).thenThrow(new IllegalStateException("connection refused"));
        var f = failed(cv(), GenerationFailure.PROVIDER_ERROR);
        assertThat(f.usage()).isNull();
        assertThat(f.detail()).isNull();
    }

    @Test
    void aTimeoutIsClassified() {
        when(chat.chat(any(ChatRequest.class))).thenThrow(new RuntimeException(new SocketTimeoutException("read")));
        failed(cv(), GenerationFailure.TIMEOUT);
    }

    @Test
    void anInterruptInTheCauseChainIsRethrown() {
        RuntimeException e = new RuntimeException(new InterruptedException());
        when(chat.chat(any(ChatRequest.class))).thenThrow(e);
        assertThatThrownBy(this::cv).isSameAs(e);
    }

    @Test
    void aSetInterruptFlagIsRethrown() {
        UncheckedIOException e = new UncheckedIOException(new IOException("closed"));
        when(chat.chat(any(ChatRequest.class))).thenAnswer(inv -> {
            Thread.currentThread().interrupt();
            throw e;
        });
        assertThatThrownBy(this::cv).isSameAs(e);
        assertThat(Thread.interrupted()).as("the flag stays set for the caller").isTrue();
    }

    // ------------------------------------------------------------------ response checks

    @Test
    void finishReasons() {
        TokenUsage usage = new TokenUsage(3000, 2048);
        answer(CV_JSON, FinishReason.LENGTH, usage);
        assertThat(failed(cv(), GenerationFailure.INVALID_OUTPUT).usage()).isEqualTo(usage);
        answer(CV_JSON, FinishReason.CONTENT_FILTER, usage);
        failed(cv(), GenerationFailure.REFUSED);
        answer(null, FinishReason.OTHER, usage);
        failed(cv(), GenerationFailure.REFUSED);
        answer(CV_JSON, null, usage);
        assertThat(cv()).as("no finish reason is fine").isInstanceOf(TailoringOutcome.Generated.class);
    }

    @Test
    void contextOverflowIsCheckedFirst() {
        // 12288 - 32 = 12256
        answer(CV_JSON, FinishReason.STOP, new TokenUsage(12000, 256));
        failed(cv(), GenerationFailure.CONTEXT_OVERFLOW);
        answer(CV_JSON, FinishReason.LENGTH, new TokenUsage(12000, 300));
        failed(cv(), GenerationFailure.CONTEXT_OVERFLOW);
        answer(CV_JSON, FinishReason.STOP, new TokenUsage(12000, 255));
        assertThat(cv()).isInstanceOf(TailoringOutcome.Generated.class);
        answer(CV_JSON, FinishReason.STOP, new TokenUsage(12000, null));
        assertThat(cv()).as("unknown output tokens").isInstanceOf(TailoringOutcome.Generated.class);

        TailoringModel remote = TailoringModel.remote(chat, "claude/x");
        answer(CV_JSON, FinishReason.STOP, new TokenUsage(500_000, 4000));
        assertThat(engine.generateCv(remote, job(), facts(), check())).as("no num_ctx: no check")
                .isInstanceOf(TailoringOutcome.Generated.class);
        assertThat(remote.local()).isFalse();
        assertThat(remote.contextTokens()).isNull();
    }

    @Test
    void blankOrMissingAnswersAreInvalid() {
        answer("  ", FinishReason.STOP, null);
        assertThat(failed(cv(), GenerationFailure.INVALID_OUTPUT).detail()).isNull();
        answer(null, FinishReason.STOP, null);
        failed(cv(), GenerationFailure.INVALID_OUTPUT);
        when(chat.chat(any(ChatRequest.class))).thenReturn(null);
        failed(cv(), GenerationFailure.INVALID_OUTPUT);
    }

    @Test
    void garbageJsonIsUnparseable() {
        TokenUsage usage = new TokenUsage(3000, 20);
        answer("Sure! Here is your CV: {", FinishReason.STOP, usage);
        var f = failed(cv(), GenerationFailure.INVALID_OUTPUT);
        assertThat(f.detail()).isEqualTo("unparseable answer");
        assertThat(f.usage()).isEqualTo(usage);
        answer("null", FinishReason.STOP, usage);
        assertThat(failed(cv(), GenerationFailure.INVALID_OUTPUT).detail()).as("JSON null: the assembler rejects it")
                .isEqualTo("no answer");
    }

    @Test
    void anAssemblerRejectionIsInvalidOutputWithItsReason() {
        TokenUsage usage = new TokenUsage(3000, 20);
        answer("{\"headline\": null, \"summary\": null, \"skills\": [], \"experience\": [], \"projects\": [], "
                + "\"certifications\": []}", FinishReason.STOP, usage);
        var f = failed(cv(), GenerationFailure.INVALID_OUTPUT);
        assertThat(f.detail()).isEqualTo("no experience entries");
        assertThat(f.usage()).isEqualTo(usage);

        answer("{\"paragraphs\": [\"Too short.\"]}", FinishReason.STOP, usage);
        var letter = failed(engine.generateCoverLetter(model, job(), facts(), check(), options),
                GenerationFailure.INVALID_OUTPUT);
        assertThat(letter.detail()).isEqualTo("2 words");
    }

    @Test
    void anAssemblerExceptionIsInvalidOutput() {
        FactCheck broken = mock(FactCheck.class);
        when(broken.check(any(), any(), any(), any())).thenThrow(new IllegalStateException("bug"));
        answer(CV_JSON.replace(E1H1, "Cut latency by 40% for 12,000 users"), FinishReason.STOP, new TokenUsage(1, 1));
        var f = failed(engine.generateCv(model, job(), facts(), broken), GenerationFailure.INVALID_OUTPUT);
        assertThat(f.detail()).isEqualTo("assembly error");
        assertThat(f.usage()).isNotNull();
    }

    @Test
    void describeNamesClassesOnly() {
        RuntimeException e = new IllegalStateException("secret text", new IOException("more secret"));
        assertThat(TailoringEngine.describe(e)).isEqualTo("IllegalStateException <- IOException");
        assertThat(TailoringEngine.check(null, 100)).contains(GenerationFailure.INVALID_OUTPUT);
        assertThat(TailoringEngine.check(response("{}", FinishReason.STOP, null), 100)).isEmpty();
        assertThat(Set.of(TailoringEngine.CONTEXT_MARGIN_TOKENS)).containsExactly(32);
    }

    @Test
    void tailoringModelRequiresAModelAndLabel() {
        assertThatThrownBy(() -> new TailoringModel(null, "x")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new TailoringModel(chat, null)).isInstanceOf(NullPointerException.class);
        TailoringModel fake = new TailoringModel(chat, "fake/tailor");
        assertThat(fake.local()).isTrue();
        assertThat(fake.contextTokens()).isNull();
    }
}
