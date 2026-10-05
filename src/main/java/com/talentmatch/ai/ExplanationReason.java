package com.talentmatch.ai;

/** Why a match carries a template explanation instead of an AI one. */
public enum ExplanationReason {
    AI_DISABLED,
    NOT_IN_TOP_N,
    GENERATING,
    AI_BUSY,
    PROVIDER_UNAVAILABLE,
    GENERATION_FAILED;

    /** Prefix added to the note when an older AI explanation exists but is out of date. */
    public static final String STALE_PREFIX =
            "The previous AI explanation is out of date because the candidate or job changed. ";

    /**
     * User-facing note for this reason.
     *
     * @param topN number of top matches that get AI explanations (used by {@link #NOT_IN_TOP_N})
     */
    public String note(int topN) {
        return switch (this) {
            case AI_DISABLED ->
                    "AI explanations are turned off, so this summary was built from the skill breakdown.";
            case NOT_IN_TOP_N -> "AI explanations are generated for the top " + topN
                    + " matches only, so this summary was built from the skill breakdown.";
            case GENERATING -> "An AI explanation is being written. Reload in a few seconds; "
                    + "until then this summary was built from the skill breakdown.";
            case AI_BUSY -> "The AI service is busy right now, so this summary was built from the skill "
                    + "breakdown. Reload later for an AI explanation.";
            case PROVIDER_UNAVAILABLE -> "The AI explanation service is unavailable right now, so this "
                    + "summary was built from the skill breakdown.";
            case GENERATION_FAILED -> "An AI explanation couldn't be produced for this match, so this "
                    + "summary was built from the skill breakdown.";
        };
    }

    /** {@link #note(int)}, prefixed with {@link #STALE_PREFIX} when {@code stale}. */
    public String note(int topN, boolean stale) {
        return stale ? STALE_PREFIX + note(topN) : note(topN);
    }
}
