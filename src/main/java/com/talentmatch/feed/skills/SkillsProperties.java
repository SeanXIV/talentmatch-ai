package com.talentmatch.feed.skills;

import jakarta.validation.Valid;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * {@code talentmatch.feed.skills.*} (§7): the description budget of the AI prompt and the
 * background enrichment (§4.7). {@code ambiguous-names} under the same prefix is read by
 * {@link SkillDictionary}.
 *
 * @param maxDescriptionChars the posting text sent to the model is cut to this many characters at a
 *                            word boundary (500..50000)
 * @param enrichment          the background AI stage
 */
@Validated
@ConfigurationProperties("talentmatch.feed.skills")
public record SkillsProperties(
        @DefaultValue("8000") int maxDescriptionChars,
        @DefaultValue @Valid Enrichment enrichment) {

    /** Characters per token used for the Ollama context budget (as for CV extraction). */
    public static final int CHARS_PER_TOKEN = 3;
    /** System prompt, title, company, fences and schema. */
    public static final int PROMPT_OVERHEAD_TOKENS = 800;

    public SkillsProperties {
        if (maxDescriptionChars < 500 || maxDescriptionChars > 50_000) {
            throw new IllegalArgumentException("talentmatch.feed.skills.max-description-chars is " + maxDescriptionChars
                    + " but must be between 500 and 50000");
        }
        enrichment = enrichment == null ? Enrichment.defaults() : enrichment;
    }

    public static SkillsProperties defaults() {
        return new SkillsProperties(8000, null);
    }

    /** {@code ceil(max-description-chars / 3) + 800 + max-output-tokens}: what the prompt and answer need. */
    public int requiredContextTokens() {
        return (maxDescriptionChars + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN + PROMPT_OVERHEAD_TOKENS
                + enrichment.maxOutputTokens();
    }

    /**
     * @param enabled         false: every job keeps its dictionary skills only (SKIPPED)
     * @param callTimeout     one model call (1s..30m)
     * @param maxOutputTokens answer budget (100..8000)
     * @param temperature     0..2 (ignored by Claude, which takes no temperature)
     * @param maxAttempts     calls per job before FAILED (1..10)
     * @param failureBackoff  after a timeout or provider error the job, and the whole stage, wait this long (1s..1h)
     * @param busyRetry       the local model is busy with a CV extraction: retry after this (1s..1h)
     * @param maxPerHour      model calls per sliding hour (1..1000)
     * @param sweep           how often the stage looks for work without being woken (1s..1h)
     */
    public record Enrichment(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("5m") Duration callTimeout,
            @DefaultValue("600") int maxOutputTokens,
            @DefaultValue("0.0") double temperature,
            @DefaultValue("3") int maxAttempts,
            @DefaultValue("5m") Duration failureBackoff,
            @DefaultValue("60s") Duration busyRetry,
            @DefaultValue("30") int maxPerHour,
            @DefaultValue("60s") Duration sweep) {

        public Enrichment {
            callTimeout = between("call-timeout", callTimeout, Duration.ofSeconds(1), Duration.ofMinutes(30));
            range("max-output-tokens", maxOutputTokens, 100, 8000);
            if (Double.isNaN(temperature) || temperature < 0 || temperature > 2) {
                throw new IllegalArgumentException("talentmatch.feed.skills.enrichment.temperature is " + temperature
                        + " but must be between 0 and 2");
            }
            range("max-attempts", maxAttempts, 1, 10);
            failureBackoff = between("failure-backoff", failureBackoff, Duration.ofSeconds(1), Duration.ofHours(1));
            busyRetry = between("busy-retry", busyRetry, Duration.ofSeconds(1), Duration.ofHours(1));
            range("max-per-hour", maxPerHour, 1, 1000);
            sweep = between("sweep", sweep, Duration.ofSeconds(1), Duration.ofHours(1));
        }

        public static Enrichment defaults() {
            return new Enrichment(true, Duration.ofMinutes(5), 600, 0.0, 3, Duration.ofMinutes(5),
                    Duration.ofSeconds(60), 30, Duration.ofSeconds(60));
        }

        private static void range(String name, int value, int min, int max) {
            if (value < min || value > max) {
                throw new IllegalArgumentException("talentmatch.feed.skills.enrichment." + name + " is " + value
                        + " but must be between " + min + " and " + max);
            }
        }

        private static Duration between(String name, Duration value, Duration min, Duration max) {
            if (value == null || value.compareTo(min) < 0 || value.compareTo(max) > 0) {
                throw new IllegalArgumentException("talentmatch.feed.skills.enrichment." + name + " is " + value
                        + " but must be between " + min + " and " + max);
            }
            return value;
        }
    }
}
