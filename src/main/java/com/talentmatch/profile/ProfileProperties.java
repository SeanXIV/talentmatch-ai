package com.talentmatch.profile;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * CV upload and extraction settings ({@code talentmatch.profile.*}). The Ollama context window
 * is shared with explanations and lives in {@code talentmatch.ai.ollama.context-tokens}; it must
 * hold {@link #requiredContextTokens()} (checked at startup when the provider is Ollama).
 *
 * @param maxResumeBytes largest accepted PDF (keep below {@code spring.servlet.multipart.max-file-size})
 * @param maxTextChars   CV text sent to the model; longer text is truncated with a warning
 * @param extraction     the extraction model call
 * @param allowRemoteExtraction send the CV text to a hosted provider (claude / openai profiles)
 *                       for extraction. Default false: with a hosted provider the CV is then not
 *                       read (FAILED/REMOTE_EXTRACTION_DISABLED). Ollama is always allowed.
 */
@Validated
@ConfigurationProperties("talentmatch.profile")
public record ProfileProperties(
        @DefaultValue("5242880") @Min(1024) @Max(52428800) int maxResumeBytes,
        @DefaultValue("16000") @Min(1000) @Max(200000) int maxTextChars,
        @DefaultValue @Valid Extraction extraction,
        @DefaultValue("false") boolean allowRemoteExtraction) {

    /** Rough worst case for CV text: about 3 characters per token (names, dates, symbols). */
    public static final int CHARS_PER_TOKEN = 3;
    /** System prompt, user-message wrapper and the JSON schema / grammar overhead. */
    public static final int PROMPT_OVERHEAD_TOKENS = 1000;

    public ProfileProperties {
        extraction = extraction == null ? new Extraction(Duration.ofMinutes(60), 4096, 0.0) : extraction;
    }

    /**
     * Context window the extraction call needs: {@code ceil(maxTextChars / 3) + 1000 +
     * maxOutputTokens}. Below this, a long CV is silently cut by the model server.
     */
    public int requiredContextTokens() {
        return (maxTextChars + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN + PROMPT_OVERHEAD_TOKENS
                + extraction.maxOutputTokens();
    }

    /**
     * @param callTimeout     HTTP timeout of the single extraction call (1s..2h). On a CPU-only
     *                        machine (~1.7 tokens/s) a 2–3 page CV takes 20–40 minutes.
     * @param maxOutputTokens output token limit for the extracted JSON
     * @param temperature     sampling temperature for extraction (Ollama/OpenAI; ignored for Claude).
     *                        0 = deterministic copying, separate from the explanation temperature.
     */
    public record Extraction(
            @DefaultValue("60m") @NotNull Duration callTimeout,
            @DefaultValue("4096") @Min(512) @Max(32768) int maxOutputTokens,
            @DefaultValue("0.0") @DecimalMin("0.0") @DecimalMax("2.0") double temperature) {

        public Extraction {
            if (callTimeout == null || callTimeout.compareTo(Duration.ofSeconds(1)) < 0
                    || callTimeout.compareTo(Duration.ofHours(2)) > 0) {
                throw new IllegalArgumentException(
                        "talentmatch.profile.extraction.call-timeout is " + callTimeout + " but must be between PT1S and PT2H");
            }
        }
    }
}
