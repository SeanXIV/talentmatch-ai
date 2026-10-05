package com.talentmatch.ai.config;

import com.talentmatch.ai.AiProperties;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Anthropic Claude chat model (profile {@code claude}); needs ANTHROPIC_API_KEY.
 *
 * <p>For this model family, never set temperature, top-p, top-k, thinking type or thinking budget
 * (the API answers 400), and never prefill. Thinking stays unset (adaptive).
 *
 * <p>Effort is not sent: LangChain4j 1.20.2 writes {@code customParameters} as extra top-level
 * keys, so {@code output_config.effort} would appear next to the structured-output
 * {@code output_config.format} as a duplicate key instead of being merged. Structured output
 * wins; a non-blank {@code talentmatch.ai.claude.effort} fails startup until that is fixed.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty(name = "talentmatch.ai.enabled", matchIfMissing = true)
@ConditionalOnProperty(prefix = "talentmatch.ai", name = "provider", havingValue = "claude")
public class ClaudeChatModelConfig {

    @Bean
    public ChatModel claudeChatModel(AiProperties ai) {
        AiProperties.Claude c = ai.claude();
        if (c.apiKey() == null || c.apiKey().isBlank()) {
            throw AiConfigurationException.missingKey("claude", "ANTHROPIC_API_KEY");
        }
        requireBlankEffort(c.effort());

        var builder = AnthropicChatModel.builder()
                .apiKey(c.apiKey().strip())
                .modelName(c.model())
                .maxTokens(c.maxTokens())
                .timeout(ai.callTimeout())
                .maxRetries(0)
                .logRequests(false)
                .logResponses(false)
                // Structured output: AiServices sends the MatchExplanation JSON schema.
                .supportedCapabilities(Set.of(Capability.RESPONSE_FORMAT_JSON_SCHEMA));
        if (c.baseUrl() != null && !c.baseUrl().isBlank()) {
            builder.baseUrl(c.baseUrl().strip());
        }
        if (c.cacheSystemPrompt()) {
            builder.cacheSystemMessages(true);
        }
        return builder.build();
    }

    /** Effort cannot be combined with structured output in LangChain4j 1.20.2 (see class doc). */
    static void requireBlankEffort(String effort) {
        if (effort == null || effort.isBlank()) {
            return;
        }
        throw new AiConfigurationException(
                "talentmatch.ai.claude.effort is '" + effort + "', but effort is not supported yet: LangChain4j "
                        + "1.20.2 cannot send output_config.effort alongside the structured-output "
                        + "output_config.format.",
                "Leave talentmatch.ai.claude.effort blank to use the API default effort.");
    }
}
