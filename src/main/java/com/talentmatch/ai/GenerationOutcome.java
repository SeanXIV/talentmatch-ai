package com.talentmatch.ai;

import java.time.Instant;
import java.util.Objects;

/** Result of one explanation generation (one model call, or none if rejected). */
public sealed interface GenerationOutcome
        permits GenerationOutcome.Success, GenerationOutcome.Failure, GenerationOutcome.Rejected {

    /**
     * A validated explanation.
     *
     * @param persisted false if the guarded write found the inputs changed, or the DB write failed
     */
    record Success(MatchExplanation value, String model, Instant generatedAt, long latencyMs, boolean persisted)
            implements GenerationOutcome {
        public Success {
            Objects.requireNonNull(value, "value");
        }
    }

    /** The model call failed, was refused, or produced invalid output. */
    record Failure(FailureKind kind, long latencyMs) implements GenerationOutcome {
        public Failure {
            Objects.requireNonNull(kind, "kind");
        }
    }

    /** The AI executor was full; no call was made (AI_BUSY). */
    record Rejected() implements GenerationOutcome {
    }
}
