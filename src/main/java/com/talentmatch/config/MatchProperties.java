package com.talentmatch.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Paging limits for {@code GET /api/jobs/{id}/matches} and {@code GET /api/candidates/{id}/matches},
 * and the candidate-side rescore chunk ({@code talentmatch.matches.*}).
 *
 * @param defaultLimit limit used when the request has none
 * @param maxLimit     largest accepted limit
 * @param rescoreChunk jobs scored (and advisory-locked) per transaction on the candidate side
 */
@Validated
@ConfigurationProperties("talentmatch.matches")
public record MatchProperties(
        @DefaultValue("10") @Min(1) @Max(1000) int defaultLimit,
        @DefaultValue("100") @Min(1) @Max(1000) int maxLimit,
        @DefaultValue("200") @Min(1) @Max(1000) int rescoreChunk) {

    public MatchProperties {
        if (defaultLimit > maxLimit) {
            throw new IllegalArgumentException(
                    "talentmatch.matches.default-limit (" + defaultLimit
                            + ") must not exceed talentmatch.matches.max-limit (" + maxLimit + ")");
        }
    }
}
