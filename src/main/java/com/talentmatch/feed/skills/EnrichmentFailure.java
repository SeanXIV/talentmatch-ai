package com.talentmatch.feed.skills;

import com.talentmatch.ai.FailureKind;

/** Why AI enrichment of a feed job failed ({@code feed_job.enrichment_failure}; must match the V5 CHECK). */
public enum EnrichmentFailure {
    AI_DISABLED,
    TIMEOUT,
    PROVIDER_ERROR,
    REFUSED,
    INVALID_OUTPUT,
    TOO_MANY_ATTEMPTS;

    public static EnrichmentFailure of(FailureKind kind) {
        return switch (kind) {
            case TIMEOUT -> TIMEOUT;
            case PROVIDER_ERROR -> PROVIDER_ERROR;
            case REFUSED -> REFUSED;
            case INVALID_OUTPUT -> INVALID_OUTPUT;
        };
    }
}
