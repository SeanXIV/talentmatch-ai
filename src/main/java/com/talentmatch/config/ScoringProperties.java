package com.talentmatch.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Match scoring weights ({@code talentmatch.scoring.*}). Invalid values fail startup.
 *
 * @param requiredWeight   points for a required skill (default 10)
 * @param niceToHaveWeight points for a nice-to-have skill (default 5)
 */
@Validated
@ConfigurationProperties("talentmatch.scoring")
public record ScoringProperties(
        @Min(1) @Max(1000) int requiredWeight,
        @Min(1) @Max(1000) int niceToHaveWeight) {
}
