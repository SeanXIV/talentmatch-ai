package com.talentmatch.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Paging limits for {@code GET /api/jobs/{id}/matches} ({@code talentmatch.matches.*}).
 *
 * @param defaultLimit limit used when the request has none
 * @param maxLimit     largest accepted limit
 */
@Validated
@ConfigurationProperties("talentmatch.matches")
public record MatchProperties(
        @DefaultValue("10") @Min(1) @Max(1000) int defaultLimit,
        @DefaultValue("100") @Min(1) @Max(1000) int maxLimit) {

    public MatchProperties {
        if (defaultLimit > maxLimit) {
            throw new IllegalArgumentException(
                    "talentmatch.matches.default-limit (" + defaultLimit
                            + ") must not exceed talentmatch.matches.max-limit (" + maxLimit + ")");
        }
    }
}
