package com.talentmatch.ai.config;

import com.talentmatch.ai.AiProperties;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
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
        AiProperties.OpenAi o = ai.openai();
        if (o.apiKey() == null || o.apiKey().isBlank()) {
            throw AiConfigurationException.missingKey("openai", "OPENAI_API_KEY");
        }
        var builder = OpenAiChatModel.builder()
                .apiKey(o.apiKey().strip())
                .modelName(o.model())
                .temperature(o.temperature())
                .maxCompletionTokens(o.maxOutputTokens())
                .timeout(ai.callTimeout())
                .maxRetries(0)
                .supportedCapabilities(Set.of(Capability.RESPONSE_FORMAT_JSON_SCHEMA))
                .strictJsonSchema(true)
                .logRequests(false)
                .logResponses(false);
        if (o.baseUrl() != null && !o.baseUrl().isBlank()) {
            builder.baseUrl(o.baseUrl().strip());
        }
        return builder.build();
    }
}
