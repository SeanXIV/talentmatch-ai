package com.talentmatch.ai.config;

import com.talentmatch.ai.AiProperties;
import com.talentmatch.profile.ProfileProperties;
import com.talentmatch.profile.ResumeExtractionModel;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import java.time.Duration;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** OpenAI chat model (profile {@code openai}); needs OPENAI_API_KEY. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty(name = "talentmatch.ai.enabled", matchIfMissing = true)
@ConditionalOnProperty(prefix = "talentmatch.ai", name = "provider", havingValue = "openai")
public class OpenAiChatModelConfig {

    @Bean
    public ChatModel openAiChatModel(AiProperties ai) {
        return build(ai, ai.callTimeout(), ai.openai().maxOutputTokens(), ai.openai().temperature(), true);
    }

    /**
     * Same model for CV extraction: long timeout, large output and the extraction temperature.
     * Non-strict JSON schema: ProfileJsonSchema already lists every field (optional ones as
     * nullable), and non-strict mode tolerates schema features strict mode rejects.
     */
    @Bean
    public ResumeExtractionModel openAiResumeExtractionModel(AiProperties ai, ProfileProperties profile) {
        ProfileProperties.Extraction x = profile.extraction();
        return ResumeExtractionModel.remote(build(ai, x.callTimeout(), x.maxOutputTokens(), x.temperature(), false),
                ModelInfo.of(ai).label());
    }

    private static ChatModel build(AiProperties ai, Duration timeout, int maxOutputTokens, double temperature,
                                   boolean strictSchema) {
        AiProperties.OpenAi o = ai.openai();
        if (o.apiKey() == null || o.apiKey().isBlank()) {
            throw AiConfigurationException.missingKey("openai", "OPENAI_API_KEY");
        }
        var builder = OpenAiChatModel.builder()
                .apiKey(o.apiKey().strip())
                .modelName(o.model())
                .temperature(temperature)
                .maxCompletionTokens(maxOutputTokens)
                .timeout(timeout)
                .maxRetries(0)
                .supportedCapabilities(Set.of(Capability.RESPONSE_FORMAT_JSON_SCHEMA))
                .strictJsonSchema(strictSchema)
                .logRequests(false)
                .logResponses(false);
        if (o.baseUrl() != null && !o.baseUrl().isBlank()) {
            builder.baseUrl(o.baseUrl().strip());
        }
        return builder.build();
    }
}
