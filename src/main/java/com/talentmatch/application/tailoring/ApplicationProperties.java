package com.talentmatch.application.tailoring;

import jakarta.validation.Valid;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Tailored applications and the tracker ({@code talentmatch.applications.*}, Phase 7 §7). Lives in
 * {@code application.tailoring} so {@code ai.config} can read it without importing the root
 * {@code application} package (§1.1).
 *
 * <p>The constructors normalize and range-check every value, so a hand-built instance (tests) is checked
 * the same way as a bound one. With the Ollama provider the context window must hold
 * {@link #requiredContextTokens()} (checked at startup by {@code requireTailoringBudget}).
 *
 * @param allowRemoteGeneration send the profile (roles, highlights; never contact data) to a hosted provider
 *                              (claude / openai profiles) for tailoring. Default false: with a hosted provider
 *                              a generation then fails with {@code REMOTE_GENERATION_DISABLED}. Ollama is
 *                              always allowed (decision c).
 * @param zone                  time zone of the {@code appliedOn} default and the cover-letter date (default
 *                              Africa/Johannesburg)
 * @param generation            the tailoring model calls
 * @param coverLetter           the cover-letter length
 */
@Validated
@ConfigurationProperties("talentmatch.applications")
public record ApplicationProperties(
        @DefaultValue("false") boolean allowRemoteGeneration,
        @DefaultValue("Africa/Johannesburg") String zone,
        @DefaultValue @Valid Generation generation,
        @DefaultValue @Valid CoverLetter coverLetter) {

    public static final String DEFAULT_ZONE = "Africa/Johannesburg";
    /** Rough worst case for profile and posting text: about 3 characters per token. */
    public static final int CHARS_PER_TOKEN = 3;
    /** System prompt, user-message labels, refs and the JSON schema / grammar overhead. */
    public static final int PROMPT_OVERHEAD_TOKENS = 1200;

    public ApplicationProperties {
        zone = zone == null || zone.isBlank() ? DEFAULT_ZONE : zone.strip();
        try {
            ZoneId.of(zone);
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("talentmatch.applications.zone is " + zone
                    + " but must be a time zone id such as Africa/Johannesburg or UTC");
        }
        generation = generation == null ? Generation.defaults() : generation;
        coverLetter = coverLetter == null ? CoverLetter.defaults() : coverLetter;
    }

    /** All defaults. */
    public static ApplicationProperties defaults() {
        return new ApplicationProperties(false, null, null, null);
    }

    public ZoneId zoneId() {
        return ZoneId.of(zone);
    }

    /**
     * Context window one tailoring call needs: {@code ceil((maxProfileChars + maxJobChars) / 3) + 1200 +
     * maxOutputTokens} (8582 with the defaults). Below this, Ollama silently cuts the prompt.
     */
    public int requiredContextTokens() {
        int chars = generation.maxProfileChars() + generation.maxJobChars();
        return (chars + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN + PROMPT_OVERHEAD_TOKENS + generation.maxOutputTokens();
    }

    /**
     * {@code talentmatch.applications.generation.*}.
     *
     * @param callTimeout     HTTP timeout of one model call (1s..2h). CPU-only Ollama: CV ~15-20 min,
     *                        letter ~5-10 min.
     * @param maxOutputTokens output token limit per document (512..16384); Claude gets at least 16000
     *                        (adaptive thinking)
     * @param temperature     sampling temperature (0..2; Ollama/OpenAI, ignored for Claude)
     * @param maxProfileChars PROFILE FACTS budget of the prompt (1000..60000)
     * @param maxJobChars     job description budget of the prompt (500..20000)
     */
    public record Generation(
            @DefaultValue("45m") Duration callTimeout,
            @DefaultValue("2048") int maxOutputTokens,
            @DefaultValue("0.2") double temperature,
            @DefaultValue("12000") int maxProfileChars,
            @DefaultValue("4000") int maxJobChars) {

        private static final String PREFIX = "talentmatch.applications.generation.";

        public Generation {
            if (callTimeout == null || callTimeout.compareTo(Duration.ofSeconds(1)) < 0
                    || callTimeout.compareTo(Duration.ofHours(2)) > 0) {
                throw new IllegalArgumentException(PREFIX + "call-timeout is " + callTimeout
                        + " but must be between PT1S and PT2H");
            }
            requireBetween("max-output-tokens", maxOutputTokens, 512, 16384);
            if (Double.isNaN(temperature) || temperature < 0.0 || temperature > 2.0) {
                throw new IllegalArgumentException(PREFIX + "temperature is " + temperature
                        + " but must be between 0.0 and 2.0");
            }
            requireBetween("max-profile-chars", maxProfileChars, 1000, 60000);
            requireBetween("max-job-chars", maxJobChars, 500, 20000);
        }

        public static Generation defaults() {
            return new Generation(Duration.ofMinutes(45), 2048, 0.2, 12000, 4000);
        }

        private static void requireBetween(String name, int value, int min, int max) {
            if (value < min || value > max) {
                throw new IllegalArgumentException(PREFIX + name + " is " + value + " but must be between "
                        + min + " and " + max);
            }
        }
    }

    /**
     * {@code talentmatch.applications.cover-letter.*}: the body length the model is asked for (decision p).
     *
     * @param minWords 50..600, below {@code maxWords}
     * @param maxWords 50..600, above {@code minWords}
     */
    public record CoverLetter(
            @DefaultValue("180") int minWords,
            @DefaultValue("350") int maxWords) {

        private static final String PREFIX = "talentmatch.applications.cover-letter.";

        public CoverLetter {
            if (minWords < 50 || minWords > 600) {
                throw new IllegalArgumentException(PREFIX + "min-words is " + minWords
                        + " but must be between 50 and 600");
            }
            if (maxWords < 50 || maxWords > 600) {
                throw new IllegalArgumentException(PREFIX + "max-words is " + maxWords
                        + " but must be between 50 and 600");
            }
            if (minWords >= maxWords) {
                throw new IllegalArgumentException(PREFIX + "min-words (" + minWords
                        + ") must be less than " + PREFIX + "max-words (" + maxWords + ")");
            }
        }

        public static CoverLetter defaults() {
            return new CoverLetter(180, 350);
        }
    }
}
