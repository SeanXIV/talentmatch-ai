package com.talentmatch.ai.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.talentmatch.ai.AiCircuitBreaker;
import com.talentmatch.ai.AiProperties;
import com.talentmatch.ai.AiProvider;
import com.talentmatch.ai.ExplanationAssistant;
import com.talentmatch.ai.ExplanationGenerator;
import com.talentmatch.ai.ExplanationPrompts;
import com.talentmatch.ai.FailureKind;
import com.talentmatch.ai.GenerationOutcome;
import com.talentmatch.ai.JobContext;
import com.talentmatch.ai.MatchContext;
import com.talentmatch.ai.MatchExplanation;
import com.talentmatch.ai.MatchExplanationValidator;
import com.talentmatch.domain.scoring.MatchEvaluation;
import com.talentmatch.domain.scoring.SkillHit;
import com.talentmatch.repository.MatchJdbcRepository;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.service.Result;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Spec §10 unit 9: wire-level tests of the three provider builders against a local stub server.
 * Captures the exact request body each LangChain4j model sends; no external host is contacted.
 */
class ProviderWireTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ANSWER = "{\"headline\":\"Strong fit with every required skill\","
            + "\"explanation\":\"Ada covers both required skills, Java and SQL, and also brings Docker.\","
            + "\"strengths\":[\"Java\",\"SQL\"],\"gaps\":[\"Kubernetes\"]}";
    private static final String USER = "Explain this match.\n\nMATCH FACTS (authoritative, computed by the scoring engine)";

    private StubHttpServer stub;

    @BeforeEach
    void start() throws Exception {
        stub = new StubHttpServer();
    }

    @AfterEach
    void stop() {
        stub.close();
    }

    private AiProperties props(AiProvider provider, AiProperties.Claude claude) {
        return new AiProperties(true, provider, 5, Duration.ofSeconds(5), Duration.ofSeconds(8), 2, 20, 2000,
                Duration.ofSeconds(60), Duration.ofSeconds(60), null,
                new AiProperties.Ollama(stub.baseUrl(), null, 0.2, 400),
                new AiProperties.OpenAi("sk-openai-fake", stub.baseUrl() + "/v1", null, 0.2, 500),
                claude);
    }

    private AiProperties claudeProps(String effort) {
        return props(AiProvider.CLAUDE,
                new AiProperties.Claude("sk-ant-fake-key", stub.baseUrl() + "/v1/", null, 3000, effort, false));
    }

    private static ExplanationAssistant assistant(ChatModel model) {
        return new AiConfiguration().explanationAssistant(model);
    }

    private static String claudeResponse(String text, String stopReason) throws Exception {
        return "{\"id\":\"msg_01\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-sonnet-5-5\","
                + "\"content\":[{\"type\":\"text\",\"text\":" + JSON.writeValueAsString(text) + "}],"
                + "\"stop_reason\":\"" + stopReason + "\",\"stop_sequence\":null,"
                + "\"usage\":{\"input_tokens\":321,\"output_tokens\":54}}";
    }

    // ------------------------------------------------------------------ Claude

    @Test
    void claudeRequestBody() throws Exception {
        stub.respond(200, claudeResponse(ANSWER, "end_turn"));
        ChatModel model = new ClaudeChatModelConfig().claudeChatModel(claudeProps(""));
        Result<MatchExplanation> r = assistant(model).explain(USER);

        StubHttpServer.Captured req = stub.last();
        System.out.println("CLAUDE_REQUEST_PATH " + req.path());
        System.out.println("CLAUDE_REQUEST_BODY " + req.body());
        assertThat(duplicateKeys(req.body()))
                .as("duplicate JSON keys in the Claude request; body=%s", req.body())
                .isEmpty();
        JsonNode body = JSON.readTree(req.body());

        assertThat(req.path()).isEqualTo("/v1/messages");
        assertThat(req.header("x-api-key")).isEqualTo("sk-ant-fake-key");
        assertThat(body.get("model").asText()).isEqualTo("claude-sonnet-5-5");
        assertThat(body.get("max_tokens").asInt()).isEqualTo(3000);
        for (String forbidden : List.of("temperature", "top_p", "top_k")) {
            assertThat(body.has(forbidden)).as(forbidden).isFalse();
        }
        assertThat(req.body()).doesNotContain("budget_tokens");
        if (body.has("thinking")) {
            assertThat(body.at("/thinking/type").asText()).isNotEqualTo("disabled");
        }
        // system prompt is the first system block
        JsonNode system = body.get("system");
        String firstSystem = system.isArray() ? system.get(0).get("text").asText() : system.asText();
        assertThat(firstSystem).startsWith(ExplanationPrompts.SYSTEM);
        // user message carries the prompt; no assistant prefill
        JsonNode messages = body.get("messages");
        assertThat(messages.get(messages.size() - 1).get("role").asText()).isEqualTo("user");
        assertThat(messages.findValuesAsText("role")).doesNotContain("assistant");
        assertThat(req.body()).contains("MATCH FACTS (authoritative");

        // effort is never sent: LangChain4j 1.20.2 would emit it as a second output_config key
        assertThat(body.at("/output_config/effort").isMissingNode()).as("effort not sent").isTrue();
        assertThat(body.at("/output_config/format/type").asText()).as("structured output; body=%s", req.body())
                .isEqualTo("json_schema");

        assertThat(r.content().headline()).isEqualTo("Strong fit with every required skill");
        assertThat(r.finishReason()).isEqualTo(FinishReason.STOP);
        assertThat(r.tokenUsage().inputTokenCount()).isEqualTo(321);
        assertThat(r.tokenUsage().outputTokenCount()).isEqualTo(54);
    }

    @Test
    void claudeRefusalBecomesAFailureOutcome() throws Exception {
        stub.respond(200, claudeResponse("", "refusal"));
        ChatModel model = new ClaudeChatModelConfig().claudeChatModel(claudeProps(""));
        AiProperties p = claudeProps("");
        AiCircuitBreaker circuit = new AiCircuitBreaker(p, Clock.systemUTC());
        MatchJdbcRepository repo = mock(MatchJdbcRepository.class);
        ExplanationGenerator gen = new ExplanationGenerator(assistant(model), new MatchExplanationValidator(), repo,
                circuit, ModelInfo.of(p), Runnable::run, Clock.systemUTC(), null);

        MatchEvaluation eval = new MatchEvaluation(1.0, 10, 10,
                List.of(new SkillHit(UUID.randomUUID(), "Java", 5)), List.of(), List.of(), List.of(), "s");
        MatchContext row = new MatchContext(1, UUID.randomUUID(), "Ada", "s", Instant.EPOCH, 1.0, 100, eval, null);
        JobContext job = new JobContext(UUID.randomUUID(), "t", "c", "d", Instant.EPOCH);
        GenerationOutcome o = gen.submit(new ExplanationGenerator.GenerationTask(job, row, USER, "a".repeat(64))).join();

        System.out.println("CLAUDE_REFUSAL_OUTCOME " + o);
        assertThat(o).isInstanceOf(GenerationOutcome.Failure.class);
        assertThat(((GenerationOutcome.Failure) o).kind()).isIn(FailureKind.REFUSED, FailureKind.INVALID_OUTPUT);
        org.mockito.Mockito.verifyNoInteractions(repo);
        assertThat(circuit.consecutiveFailures()).as("a refusal is not a provider fault").isZero();
    }

    @Test
    void claudeHttpErrorIsAProviderError() {
        stub.respond(529, "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}");
        ChatModel model = new ClaudeChatModelConfig().claudeChatModel(claudeProps(""));
        Throwable t = org.assertj.core.api.Assertions.catchThrowable(() -> assistant(model).explain(USER));
        assertThat(t).isNotNull();
        assertThat(FailureKind.classify(t)).isEqualTo(FailureKind.PROVIDER_ERROR);
        assertThat(stub.requests()).as("maxRetries(0)").hasSize(1);
    }

    /** Object keys that occur more than once in the same JSON object (any depth). */
    static List<String> duplicateKeys(String json) throws Exception {
        List<String> dups = new java.util.ArrayList<>();
        try (com.fasterxml.jackson.core.JsonParser p = JSON.getFactory().createParser(json)) {
            java.util.Deque<java.util.Set<String>> stack = new java.util.ArrayDeque<>();
            for (com.fasterxml.jackson.core.JsonToken t = p.nextToken(); t != null; t = p.nextToken()) {
                switch (t) {
                    case START_OBJECT -> stack.push(new java.util.HashSet<>());
                    case END_OBJECT -> stack.pop();
                    case FIELD_NAME -> {
                        if (!stack.peek().add(p.currentName())) {
                            dups.add(p.currentName());
                        }
                    }
                    default -> { }
                }
            }
        }
        return dups;
    }

    // ------------------------------------------------------------------ Ollama

    @Test
    void ollamaRequestBody() throws Exception {
        stub.respond(200, "{\"model\":\"qwen2.5:7b-instruct\",\"created_at\":\"2026-10-02T09:00:00Z\","
                + "\"message\":{\"role\":\"assistant\",\"content\":" + JSON.writeValueAsString(ANSWER) + "},"
                + "\"done\":true,\"done_reason\":\"stop\",\"prompt_eval_count\":111,\"eval_count\":22}");
        ChatModel model = new OllamaChatModelConfig().ollamaChatModel(props(AiProvider.OLLAMA, null));
        Result<MatchExplanation> r = assistant(model).explain(USER);

        StubHttpServer.Captured req = stub.last();
        System.out.println("OLLAMA_REQUEST_BODY " + req.body());
        JsonNode body = JSON.readTree(req.body());
        assertThat(req.path()).isEqualTo("/api/chat");
        assertThat(body.get("model").asText()).isEqualTo("qwen2.5:7b-instruct");
        assertThat(body.at("/options/num_predict").asInt()).isEqualTo(400);
        assertThat(body.at("/options/temperature").asDouble()).isEqualTo(0.2);
        assertThat(body.get("format").isObject()).as("format = JSON schema: %s", req.body()).isTrue();
        assertThat(body.at("/format/properties/headline").isMissingNode()).isFalse();
        assertThat(body.at("/messages/0/role").asText()).isEqualTo("system");
        assertThat(body.at("/messages/0/content").asText()).isEqualTo(ExplanationPrompts.SYSTEM);
        assertThat(body.at("/messages/1/content").asText()).startsWith(USER);
        assertThat(r.content().strengths()).containsExactly("Java", "SQL");
        assertThat(r.finishReason()).isEqualTo(FinishReason.STOP);
        assertThat(r.tokenUsage().inputTokenCount()).isEqualTo(111);
        assertThat(r.tokenUsage().outputTokenCount()).isEqualTo(22);
    }

    // ------------------------------------------------------------------ OpenAI

    @Test
    void openAiRequestBody() throws Exception {
        stub.respond(200, "{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion\",\"created\":1,"
                + "\"model\":\"gpt-4.1-mini\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                + "\"content\":" + JSON.writeValueAsString(ANSWER) + "},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}}");
        ChatModel model = new OpenAiChatModelConfig().openAiChatModel(props(AiProvider.OPENAI, null));
        Result<MatchExplanation> r = assistant(model).explain(USER);

        StubHttpServer.Captured req = stub.last();
        System.out.println("OPENAI_REQUEST_BODY " + req.body());
        JsonNode body = JSON.readTree(req.body());
        assertThat(req.path()).isEqualTo("/v1/chat/completions");
        assertThat(req.header("Authorization")).isEqualTo("Bearer sk-openai-fake");
        assertThat(body.get("model").asText()).isEqualTo("gpt-4.1-mini");
        assertThat(body.get("max_completion_tokens").asInt()).isEqualTo(500);
        assertThat(body.has("max_tokens")).isFalse();
        assertThat(body.at("/response_format/type").asText()).isEqualTo("json_schema");
        assertThat(body.at("/response_format/json_schema/strict").asBoolean()).isTrue();
        assertThat(r.content().headline()).isEqualTo("Strong fit with every required skill");
        assertThat(r.finishReason()).isEqualTo(FinishReason.STOP);
        assertThat(r.tokenUsage().totalTokenCount()).isEqualTo(15);
    }
}
