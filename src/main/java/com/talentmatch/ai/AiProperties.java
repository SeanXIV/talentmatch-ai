package com.talentmatch.ai;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * AI explanation settings ({@code talentmatch.ai.*}). API keys carry no validation annotations
 * because this record is bound even when OpenAI/Claude are not in use; the provider bean methods
 * check them. {@code toString()} of the provider records masks the keys.
 *
 * @param enabled          master switch; false = template explanations only, no LLM beans
 * @param provider         ollama | openai | claude (set by profiles)
 * @param topN             only matches ranked 1..topN get AI explanations
 * @param callTimeout      per model call (HTTP timeout), 1s..300s
 * @param requestBudget    max time a GET waits for explanations, 0..60s (0 = never wait)
 * @param maxConcurrency   parallel model calls
 * @param queueCapacity    queued model calls before AI_BUSY
 * @param maxContextChars  truncation limit for job description / candidate summary
 * @param failureBackoff   how long a failed (pair, inputs) is not retried, &gt;= 0
 * @param regenerateWindow minimum time between regenerate=true requests per job, &gt;= 1s
 */
@Validated
@ConfigurationProperties("talentmatch.ai")
public record AiProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("ollama") @NotNull AiProvider provider,
        @DefaultValue("5") @Min(1) @Max(20) int topN,
        @DefaultValue("60s") @NotNull Duration callTimeout,
        @DefaultValue("8s") @NotNull Duration requestBudget,
        @DefaultValue("2") @Min(1) @Max(16) int maxConcurrency,
        @DefaultValue("20") @Min(0) @Max(1000) int queueCapacity,
        @DefaultValue("2000") @Min(200) @Max(20000) int maxContextChars,
        @DefaultValue("60s") @NotNull Duration failureBackoff,
        @DefaultValue("60s") @NotNull Duration regenerateWindow,
        @DefaultValue @Valid Circuit circuit,
        @DefaultValue @Valid Ollama ollama,
        @DefaultValue @Valid OpenAi openai,
        @DefaultValue @Valid Claude claude) {

    private static final String PREFIX = "talentmatch.ai.";

    public AiProperties {
        if (provider == null) {
            throw new IllegalArgumentException(PREFIX + "provider must be set (ollama, openai or claude)");
        }
        requireBetween("call-timeout", callTimeout, Duration.ofSeconds(1), Duration.ofSeconds(300));
        requireBetween("request-budget", requestBudget, Duration.ZERO, Duration.ofSeconds(60));
        requireBetween("failure-backoff", failureBackoff, Duration.ZERO, null);
        requireBetween("regenerate-window", regenerateWindow, Duration.ofSeconds(1), null);
        circuit = circuit == null ? new Circuit(3, Duration.ofSeconds(30)) : circuit;
        ollama = ollama == null ? new Ollama(null, null, 0.2, 400) : ollama;
        openai = openai == null ? new OpenAi(null, null, null, 0.2, 500) : openai;
        claude = claude == null ? new Claude(null, null, null, 3000, "", false) : claude;
    }

    /** Model name of the active provider. */
    public String activeModel() {
        return switch (provider) {
            case OLLAMA -> ollama.model();
            case OPENAI -> openai.model();
            case CLAUDE -> claude.model();
        };
    }

    private static void requireBetween(String name, Duration value, Duration min, Duration max) {
        if (value == null) {
            throw new IllegalArgumentException(PREFIX + name + " must be set");
        }
        if (value.compareTo(min) < 0 || (max != null && value.compareTo(max) > 0)) {
            throw new IllegalArgumentException(PREFIX + name + " is " + value + " but must be "
                    + (max == null ? "at least " + min : "between " + min + " and " + max));
        }
    }

    private static String mask(String apiKey) {
        return apiKey == null || apiKey.isBlank() ? "(unset)" : "****";
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * Circuit breaker around the model provider.
     *
     * @param failureThreshold consecutive provider failures that open the circuit
     * @param openDuration     how long the circuit stays open before one probe is allowed
     */
    public record Circuit(
            @DefaultValue("3") @Min(1) @Max(100) int failureThreshold,
            @DefaultValue("30s") Duration openDuration) {

        public Circuit {
            if (openDuration == null) {
                openDuration = Duration.ofSeconds(30);
            }
            if (openDuration.isNegative()) {
                throw new IllegalArgumentException(PREFIX + "circuit.open-duration must not be negative");
            }
        }
    }

    /** Local Ollama (default provider). */
    public record Ollama(
            @DefaultValue("http://localhost:11434") String baseUrl,
            @DefaultValue("qwen2.5:7b-instruct") String model,
            @DefaultValue("0.2") double temperature,
            @DefaultValue("400") int maxOutputTokens) {

        public Ollama {
            baseUrl = isBlank(baseUrl) ? "http://localhost:11434" : baseUrl.strip();
            model = isBlank(model) ? "qwen2.5:7b-instruct" : model.strip();
        }
    }

    /** OpenAI (profile {@code openai}). */
    public record OpenAi(
            String apiKey,
            String baseUrl,
            @DefaultValue("gpt-4.1-mini") String model,
            @DefaultValue("0.2") double temperature,
            @DefaultValue("500") int maxOutputTokens) {

        public OpenAi {
            model = isBlank(model) ? "gpt-4.1-mini" : model.strip();
        }

        @Override
        public String toString() {
            return "OpenAi[apiKey=" + mask(apiKey) + ", baseUrl=" + baseUrl + ", model=" + model
                    + ", temperature=" + temperature + ", maxOutputTokens=" + maxOutputTokens + "]";
        }
    }

    /**
     * Anthropic Claude (profile {@code claude}). Never configure temperature, top-p, top-k or
     * thinking for this model (the API rejects them).
     *
     * @param effort            must stay blank (API default) until LangChain4j can merge
     *                          output_config.effort with structured output
     * @param cacheSystemPrompt cache the system prompt (only worth it above the minimum prefix size)
     */
    public record Claude(
            String apiKey,
            String baseUrl,
            @DefaultValue("claude-sonnet-5-5") String model,
            @DefaultValue("3000") int maxTokens,
            @DefaultValue("") String effort,
            @DefaultValue("false") boolean cacheSystemPrompt) {

        public Claude {
            model = isBlank(model) ? "claude-sonnet-5-5" : model.strip();
            effort = effort == null ? "" : effort.strip();
        }

        @Override
        public String toString() {
            return "Claude[apiKey=" + mask(apiKey) + ", baseUrl=" + baseUrl + ", model=" + model
                    + ", maxTokens=" + maxTokens + ", effort=" + effort
                    + ", cacheSystemPrompt=" + cacheSystemPrompt + "]";
        }
    }
}
