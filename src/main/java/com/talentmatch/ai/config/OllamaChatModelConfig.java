package com.talentmatch.ai.config;

import com.talentmatch.ai.AiProperties;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Local Ollama chat model (default provider). No network access at construction: the app starts
 * and serves template explanations even when Ollama is not running.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty(name = "talentmatch.ai.enabled", matchIfMissing = true)
@ConditionalOnProperty(prefix = "talentmatch.ai", name = "provider", havingValue = "ollama", matchIfMissing = true)
public class OllamaChatModelConfig {

    @Bean
    public ChatModel ollamaChatModel(AiProperties ai) {
        AiProperties.Ollama o = ai.ollama();
        return OllamaChatModel.builder()
                .baseUrl(o.baseUrl())
                .modelName(o.model())
                .temperature(o.temperature())
                .numPredict(o.maxOutputTokens())
                .timeout(ai.callTimeout())
                .maxRetries(0)
                // Ollama's "format" accepts a JSON schema: AiServices then requests structured output.
                .supportedCapabilities(Set.of(Capability.RESPONSE_FORMAT_JSON_SCHEMA))
                .logRequests(false)
                .logResponses(false)
                .build();
    }
}
