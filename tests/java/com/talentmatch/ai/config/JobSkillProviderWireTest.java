package com.talentmatch.ai.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.talentmatch.ai.AiProperties;
import com.talentmatch.ai.AiProvider;
import com.talentmatch.feed.skills.JobSkillExtractionModel;
import com.talentmatch.feed.skills.JobSkillJsonSchema;
import com.talentmatch.feed.skills.JobSkillPrompts;
import com.talentmatch.feed.skills.SkillsProperties;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Phase 5 step 10 (§4.7, deviation "Claude gets at least 4000 output tokens"): what each provider's
 * {@link JobSkillExtractionModel} actually sends, captured by a local stub. No external host.
 */
class JobSkillProviderWireTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ANSWER = "{\"skills\":[{\"name\":\"Java\",\"requirement\":\"REQUIRED\","
            + "\"evidence\":\"write Java\"}]}";

    private StubHttpServer stub;

    @BeforeEach
    void start() throws Exception {
        stub = new StubHttpServer();
    }

    @AfterEach
    void stop() {
        stub.close();
    }

    private AiProperties props(AiProvider provider) {
        return new AiProperties(true, provider, 5, Duration.ofSeconds(5), Duration.ofSeconds(8), 2, 20, 2000,
                Duration.ofSeconds(60), Duration.ofSeconds(60), null,
                new AiProperties.Ollama(stub.baseUrl(), null, 0.2, 400, 12288),
                new AiProperties.OpenAi("sk-openai-fake", stub.baseUrl() + "/v1", null, 0.2, 500),
                new AiProperties.Claude("sk-ant-fake-key", stub.baseUrl() + "/v1/", null, 3000, "", false));
    }

    private static SkillsProperties skills(int maxOutputTokens) {
        return new SkillsProperties(8000, new SkillsProperties.Enrichment(true, Duration.ofMinutes(5),
                maxOutputTokens, 0.0, 3, Duration.ofMinutes(5), Duration.ofSeconds(60), 30, Duration.ofSeconds(60)));
    }

    private static ChatRequest request() {
        String posting = JobSkillPrompts.postingText("Backend Engineer", "You will write Java.", 8000);
        return ChatRequest.builder()
                .messages(SystemMessage.from(JobSkillPrompts.SYSTEM),
                        UserMessage.from(JobSkillPrompts.userMessage("Acme", posting)))
                .responseFormat(JobSkillJsonSchema.responseFormat())
                .build();
    }

    private void claudeAnswers() throws Exception {
        stub.respond(200, "{\"id\":\"msg_01\",\"type\":\"message\",\"role\":\"assistant\","
                + "\"model\":\"claude-sonnet-5-5\",\"content\":[{\"type\":\"text\",\"text\":"
                + JSON.writeValueAsString(ANSWER) + "}],\"stop_reason\":\"end_turn\",\"stop_sequence\":null,"
                + "\"usage\":{\"input_tokens\":321,\"output_tokens\":54}}");
    }

    @Test
    void claudeEnrichmentGetsAtLeast4000OutputTokens() throws Exception {
        claudeAnswers();
        JobSkillExtractionModel m = new ClaudeChatModelConfig()
                .claudeJobSkillExtractionModel(props(AiProvider.CLAUDE), skills(600));
        m.chatModel().chat(request());
        String raw = stub.last().body();
        JsonNode body = JSON.readTree(raw);
        assertThat(body.get("max_tokens").asInt()).as("600 configured, floor 4000; body=%s", raw)
                .isEqualTo(ClaudeChatModelConfig.MIN_ENRICHMENT_TOKENS).isEqualTo(4000);
        for (String forbidden : List.of("temperature", "top_p", "top_k")) {
            assertThat(body.has(forbidden)).as(forbidden).isFalse();
        }
        assertThat(body.at("/output_config/format/type").asText()).isEqualTo("json_schema");
        assertThat(raw).contains("<job_posting>");
        assertThat(m.local()).isFalse();
        assertThat(m.contextTokens()).isNull();
    }

    @Test
    void claudeEnrichmentKeepsAHigherConfiguredBudget() throws Exception {
        claudeAnswers();
        new ClaudeChatModelConfig().claudeJobSkillExtractionModel(props(AiProvider.CLAUDE), skills(6000))
                .chatModel().chat(request());
        assertThat(JSON.readTree(stub.last().body()).get("max_tokens").asInt()).isEqualTo(6000);
    }

    @Test
    void ollamaEnrichmentUsesTheSharedNumCtxItsOwnBudgetAndTemperatureZero() throws Exception {
        stub.respond(200, "{\"model\":\"qwen2.5:7b-instruct\",\"created_at\":\"2026-10-02T09:00:00Z\","
                + "\"message\":{\"role\":\"assistant\",\"content\":" + JSON.writeValueAsString(ANSWER) + "},"
                + "\"done\":true,\"done_reason\":\"stop\",\"prompt_eval_count\":111,\"eval_count\":22}");
        JobSkillExtractionModel m = new OllamaChatModelConfig()
                .ollamaJobSkillExtractionModel(props(AiProvider.OLLAMA), skills(600));
        m.chatModel().chat(request());
        JsonNode body = JSON.readTree(stub.last().body());
        assertThat(body.at("/options/num_ctx").asInt()).as("same num_ctx: no model reload").isEqualTo(12288);
        assertThat(body.at("/options/num_predict").asInt()).isEqualTo(600);
        assertThat(body.at("/options/temperature").isMissingNode()).isFalse();
        assertThat(body.at("/options/temperature").asDouble()).isEqualTo(0.0);
        assertThat(body.at("/format/properties/skills/type").asText()).as("schema sent: %s", body)
                .isEqualTo("array");
        assertThat(m.local()).isTrue();
        assertThat(m.contextTokens()).isEqualTo(12288);
    }

    @Test
    void openAiEnrichmentUsesAStrictSchemaAndItsOwnBudget() throws Exception {
        stub.respond(200, "{\"id\":\"chatcmpl-3\",\"object\":\"chat.completion\",\"created\":1,"
                + "\"model\":\"gpt-4.1-mini\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                + "\"content\":" + JSON.writeValueAsString(ANSWER) + "},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}}");
        JobSkillExtractionModel m = new OpenAiChatModelConfig()
                .openAiJobSkillExtractionModel(props(AiProvider.OPENAI), skills(600));
        m.chatModel().chat(request());
        JsonNode body = JSON.readTree(stub.last().body());
        assertThat(body.get("max_completion_tokens").asInt()).isEqualTo(600);
        assertThat(body.at("/temperature").asDouble()).isEqualTo(0.0);
        assertThat(body.at("/response_format/type").asText()).isEqualTo("json_schema");
        assertThat(body.at("/response_format/json_schema/strict").asBoolean()).isTrue();
        assertThat(m.local()).isFalse();
    }
}
