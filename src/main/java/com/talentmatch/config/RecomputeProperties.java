package com.talentmatch.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Batch recompute settings ({@code talentmatch.recompute.*}).
 *
 * @param historySize          number of runs kept in memory for status lookups
 * @param maxFailuresReported  cap on per-job failures listed in a run resource
 */
@Validated
@ConfigurationProperties("talentmatch.recompute")
public record RecomputeProperties(
        @DefaultValue("20") @Min(1) @Max(1000) int historySize,
        @DefaultValue("50") @Min(0) @Max(10000) int maxFailuresReported) {
}
