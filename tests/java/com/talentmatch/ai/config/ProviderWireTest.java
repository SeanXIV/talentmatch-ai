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
                new AiProperties.Ollama(stub.baseUrl(), null, 0.2, 400, 12288),
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
        // N8: pinned to the observed value. AiServices parses the (empty) refusal text before it looks at
        // stop_reason, so a Claude refusal surfaces as INVALID_OUTPUT; neither counts as a provider fault.
        assertThat(((GenerationOutcome.Failure) o).kind()).isEqualTo(FailureKind.INVALID_OUTPUT);
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

    // ------------------------------------------------------------------ Ollama CV extraction (Phase 4, B3)

    static final String CV_JSON = "{\"fullName\":\"Ada Lovelace\",\"email\":null,\"phone\":null,\"location\":null,"
            + "\"headline\":null,\"summary\":null,\"links\":[],\"experience\":[{\"title\":\"Engineer\","
            + "\"company\":\"Acme\",\"location\":null,\"startDate\":\"2020-01\",\"endDate\":null,\"current\":true,"
            + "\"highlights\":[]}],\"projects\":[],\"skills\":[{\"name\":\"Java\",\"years\":null}],"
            + "\"certifications\":[],\"education\":[],\"languages\":[]}";

    private static com.talentmatch.profile.ProfileProperties profileProps() {
        return new com.talentmatch.profile.ProfileProperties(5_242_880, 16_000, null, false);
    }

    private String ollamaCvResponse() throws Exception {
        return "{\"model\":\"qwen2.5:7b-instruct\",\"created_at\":\"2026-10-05T09:00:00Z\","
                + "\"message\":{\"role\":\"assistant\",\"content\":" + JSON.writeValueAsString(CV_JSON) + "},"
                + "\"done\":true,\"done_reason\":\"stop\",\"prompt_eval_count\":1500,\"eval_count\":300}";
    }

    /** Captures the Ollama request of one extraction through the production extraction wiring. */
    private JsonNode ollamaExtractionRequest() throws Exception {
        stub.respond(200, ollamaCvResponse());
        com.talentmatch.profile.ResumeExtractionModel model = new OllamaChatModelConfig()
                .ollamaResumeExtractionModel(props(AiProvider.OLLAMA, null), profileProps());
        dev.langchain4j.model.chat.response.ChatResponse r = model.chatModel().chat(extractionRequest());
        assertThat(r.aiMessage().text()).isEqualTo(CV_JSON);
        assertThat(model.contextTokens()).isEqualTo(12288);
        String body = stub.last().body();
        System.out.println("OLLAMA_EXTRACTION_REQUEST_BODY " + body);
        return JSON.readTree(body);
    }

    /** Same shape as ResumeExtractionService.request (package-private): system + fenced CV + schema. */
    static dev.langchain4j.model.chat.request.ChatRequest extractionRequest() {
        return dev.langchain4j.model.chat.request.ChatRequest.builder()
                .messages(dev.langchain4j.data.message.SystemMessage.from(
                                com.talentmatch.profile.ResumeExtractionPrompts.SYSTEM),
                        dev.langchain4j.data.message.UserMessage.from(
                                com.talentmatch.profile.ResumeExtractionPrompts.userMessage("Ada Lovelace, Engineer at Acme")))
                .responseFormat(com.talentmatch.profile.ProfileJsonSchema.responseFormat())
                .build();
    }

    /** True if the schema node allows JSON null (type includes "null", or an anyOf/oneOf branch is null). */
    static boolean allowsNull(JsonNode schema) {
        if (schema == null || schema.isMissingNode()) {
            return false;
        }
        JsonNode type = schema.get("type");
        if (type != null && (type.asText().equals("null")
                || (type.isArray() && java.util.stream.StreamSupport.stream(type.spliterator(), false)
                        .anyMatch(t -> t.asText().equals("null"))))) {
            return true;
        }
        for (String combo : List.of("anyOf", "oneOf")) {
            if (schema.has(combo)) {
                for (JsonNode branch : schema.get(combo)) {
                    if (allowsNull(branch)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** An optional field may be omitted (not in required) or set to null (nullable). */
    static boolean optional(JsonNode objectSchema, String field) {
        JsonNode required = objectSchema.get("required");
        boolean isRequired = required != null && java.util.stream.StreamSupport.stream(required.spliterator(), false)
                .anyMatch(r -> r.asText().equals(field));
        return !isRequired || allowsNull(objectSchema.at("/properties/" + field));
    }

    /** Resolves "items" of an array property, following a local $ref/$defs if used. */
    static JsonNode items(JsonNode root, JsonNode objectSchema, String arrayField) {
        JsonNode prop = objectSchema.at("/properties/" + arrayField);
        JsonNode items = prop.has("items") ? prop.get("items") : firstNonNullBranch(prop).get("items");
        return deref(root, items);
    }

    private static JsonNode firstNonNullBranch(JsonNode n) {
        for (String combo : List.of("anyOf", "oneOf")) {
            if (n.has(combo)) {
                for (JsonNode b : n.get(combo)) {
                    if (!"null".equals(b.path("type").asText())) {
                        return b;
                    }
                }
            }
        }
        return n;
    }

    private static JsonNode deref(JsonNode root, JsonNode n) {
        if (n != null && n.has("$ref")) {
            String ref = n.get("$ref").asText();
            return root.at(ref.substring(1));
        }
        return n;
    }

    /** DECIDES B3: optional CV fields must be nullable or not required, or the grammar forces invented values. */
    @Test
    void ollamaExtractionSchemaLetsOptionalFieldsBeNull() throws Exception {
        JsonNode body = ollamaExtractionRequest();
        JsonNode format = body.get("format");
        assertThat(format != null && format.isObject()).as("format = JSON schema: %s", body).isTrue();
        System.out.println("B3_FORMAT_SCHEMA " + format);

        List<String> notOptional = new java.util.ArrayList<>();
        for (String f : List.of("fullName", "email", "phone", "location", "headline", "summary")) {
            if (!optional(format, f)) {
                notOptional.add(f);
            }
        }
        JsonNode skill = items(format, format, "skills");
        if (!optional(skill, "years")) {
            notOptional.add("skills[].years");
        }
        JsonNode exp = items(format, format, "experience");
        for (String f : List.of("title", "company", "endDate", "startDate", "location", "current")) {
            if (!optional(exp, f)) {
                notOptional.add("experience[]." + f);
            }
        }
        JsonNode cert = items(format, format, "certifications");
        for (String f : List.of("issuer", "expires", "credentialId", "url")) {
            if (!optional(cert, f)) {
                notOptional.add("certifications[]." + f);
            }
        }
        assertThat(notOptional).as("fields forced to a non-null value by the Ollama grammar; schema=%s", format)
                .isEmpty();
        // the hand-built schema: optional fields are nullable AND required (keys are never omitted)
        for (JsonNode[] objField : new JsonNode[][] {{format, JSON.valueToTree("email")},
                {skill, JSON.valueToTree("years")}, {exp, JSON.valueToTree("endDate")}}) {
            String f = objField[1].asText();
            assertThat(allowsNull(objField[0].at("/properties/" + f))).as("%s nullable", f).isTrue();
            assertThat(objField[0].get("required")).as("%s required", f).anyMatch(r -> r.asText().equals(f));
        }
        assertThat(allowsNull(skill.at("/properties/name"))).as("skills[].name is never null").isFalse();
    }

    @Test
    void ollamaExtractionOptions() throws Exception {
        JsonNode body = ollamaExtractionRequest();
        com.talentmatch.profile.ProfileProperties p = profileProps();
        assertThat(body.at("/options/num_ctx").asInt()).as("shared num_ctx (B2/S8)").isEqualTo(12288);
        assertThat(body.at("/options/num_predict").asInt()).isEqualTo(p.extraction().maxOutputTokens());
        assertThat(body.at("/options/temperature").isMissingNode()).as("temperature sent: %s", body).isFalse();
        assertThat(body.at("/options/temperature").asDouble()).as("N6: extraction temperature 0").isEqualTo(0.0);
    }

    @Test
    void ollamaExplanationUsesTheSameNumCtxAsExtraction() throws Exception {
        stub.respond(200, "{\"model\":\"qwen2.5:7b-instruct\",\"created_at\":\"2026-10-02T09:00:00Z\","
                + "\"message\":{\"role\":\"assistant\",\"content\":" + JSON.writeValueAsString(ANSWER) + "},"
                + "\"done\":true,\"done_reason\":\"stop\",\"prompt_eval_count\":1,\"eval_count\":1}");
        assistant(new OllamaChatModelConfig().ollamaChatModel(props(AiProvider.OLLAMA, null))).explain(USER);
        int explanationCtx = JSON.readTree(stub.last().body()).at("/options/num_ctx").asInt();
        int extractionCtx = ollamaExtractionRequest().at("/options/num_ctx").asInt();
        assertThat(explanationCtx).as("S8: a different num_ctx makes Ollama reload the model").isEqualTo(12288)
                .isEqualTo(extractionCtx);
    }

    @Test
    void ollamaContextBudgetIsCheckedAtStartup() {
        AiProperties ai = new AiProperties(true, AiProvider.OLLAMA, 5, Duration.ofSeconds(5), Duration.ofSeconds(8), 2,
                20, 2000, Duration.ofSeconds(60), Duration.ofSeconds(60), null,
                new AiProperties.Ollama(stub.baseUrl(), null, 0.2, 400, 8192), null, null);
        com.talentmatch.profile.ProfileProperties big = new com.talentmatch.profile.ProfileProperties(5_242_880, 24_000,
                null, false);
        Throwable t = org.assertj.core.api.Assertions.catchThrowable(() ->
                new OllamaChatModelConfig().ollamaResumeExtractionModel(ai, big));
        assertThat(t).isInstanceOf(AiConfigurationException.class)
                .hasMessageContaining("context-tokens is 8192").hasMessageContaining("13096");
        // the defaults fit exactly: 16000/3 -> 5334 + 1000 + 4096 = 10430 <= 12288
        assertThat(profileProps().requiredContextTokens()).isEqualTo(10430);
    }

    @Test
    void openAiExtractionRequestBody() throws Exception {
        stub.respond(200, "{\"id\":\"chatcmpl-2\",\"object\":\"chat.completion\",\"created\":1,"
                + "\"model\":\"gpt-4.1-mini\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                + "\"content\":" + JSON.writeValueAsString(CV_JSON) + "},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}}");
        com.talentmatch.profile.ResumeExtractionModel model = new OpenAiChatModelConfig()
                .openAiResumeExtractionModel(props(AiProvider.OPENAI, null), profileProps());
        model.chatModel().chat(extractionRequest());
        JsonNode body = JSON.readTree(stub.last().body());
        System.out.println("OPENAI_EXTRACTION_REQUEST_BODY " + body);
        assertThat(body.get("max_completion_tokens").asInt()).isEqualTo(4096);
        assertThat(body.at("/temperature").asDouble()).isEqualTo(0.0);
        assertThat(body.at("/response_format/type").asText()).isEqualTo("json_schema");
        assertThat(body.at("/response_format/json_schema/strict").asBoolean()).as("non-strict for extraction").isFalse();
        JsonNode schema = body.at("/response_format/json_schema/schema");
        assertThat(allowsNull(schema.at("/properties/email"))).as("schema=%s", schema).isTrue();
        assertThat(model.contextTokens()).as("provider-managed context").isNull();
    }

    @Test
    void claudeExtractionRequestBody() throws Exception {
        stub.respond(200, claudeResponse(CV_JSON, "end_turn"));
        com.talentmatch.profile.ResumeExtractionModel model = new ClaudeChatModelConfig()
                .claudeResumeExtractionModel(claudeProps(""), profileProps());
        dev.langchain4j.model.chat.response.ChatResponse r = model.chatModel().chat(extractionRequest());
        String raw = stub.last().body();
        System.out.println("CLAUDE_EXTRACTION_REQUEST_BODY " + raw);
        assertThat(duplicateKeys(raw)).as("duplicate keys; body=%s", raw).isEmpty();
        JsonNode body = JSON.readTree(raw);
        assertThat(body.get("max_tokens").asInt()).isGreaterThanOrEqualTo(16000);
        assertThat(body.at("/output_config/effort").isMissingNode()).isTrue();
        assertThat(body.at("/output_config/format/type").asText()).isEqualTo("json_schema");
        for (String forbidden : List.of("temperature", "top_p", "top_k")) {
            assertThat(body.has(forbidden)).as(forbidden).isFalse();
        }
        assertThat(raw).contains("<cv>").doesNotContain("budget_tokens");
        assertThat(r.aiMessage().text()).isEqualTo(CV_JSON);
    }

    @Test
    void claudeExtractionRefusalMapsToANonStopFinishReason() throws Exception {
        stub.respond(200, claudeResponse("", "refusal"));
        com.talentmatch.profile.ResumeExtractionModel model = new ClaudeChatModelConfig()
                .claudeResumeExtractionModel(claudeProps(""), profileProps());
        dev.langchain4j.model.chat.response.ChatResponse r = model.chatModel().chat(extractionRequest());
        System.out.println("CLAUDE_EXTRACTION_REFUSAL finish=" + r.finishReason() + " text='" + r.aiMessage().text() + "'");
        assertThat(r.finishReason()).as("ResumeExtractionService maps any non-STOP finish to REFUSED")
                .isNotNull().isNotEqualTo(FinishReason.STOP).isNotEqualTo(FinishReason.LENGTH);
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
