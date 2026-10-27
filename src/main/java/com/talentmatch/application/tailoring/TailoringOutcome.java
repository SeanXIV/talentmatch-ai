package com.talentmatch.application.tailoring;

import com.talentmatch.application.content.Finding;
import dev.langchain4j.model.output.TokenUsage;
import java.util.List;
import java.util.Objects;

/**
 * The result of one tailoring call for one document kind ({@link TailoringEngine}).
 *
 * @param <T> {@code TailoredCvContent} or {@code CoverLetterContent}
 */
public sealed interface TailoringOutcome<T> {

    /** Token usage reported by the provider; null when the call failed before a response or none was reported. */
    TokenUsage usage();

    /** Lower-case outcome tag for logs and metrics: {@code success} or the failure's tag. */
    String tag();

    /**
     * A grounded document, ready to store as an AI version.
     *
     * @param content   the assembled document (facts copied from the profile version)
     * @param findings  grounding findings (stored with the version; never logged)
     * @param inputHash sha256 of the prompt (stored on the version)
     */
    record Generated<T>(T content, List<Finding> findings, String inputHash, TokenUsage usage)
            implements TailoringOutcome<T> {

        public Generated {
            Objects.requireNonNull(content, "content");
            findings = List.copyOf(findings);
            Objects.requireNonNull(inputHash, "inputHash");
        }

        @Override
        public String tag() {
            return "success";
        }
    }

    /**
     * Nothing to store for this kind.
     *
     * @param failure a {@linkplain GenerationFailure#perKind() per-kind} failure
     * @param detail  a fixed description with counts only (an assembler's rejection reason), safe to log; may
     *                be null
     */
    record Failed<T>(GenerationFailure failure, String detail, TokenUsage usage) implements TailoringOutcome<T> {

        public Failed {
            Objects.requireNonNull(failure, "failure");
        }

        @Override
        public String tag() {
            return failure.tag();
        }
    }
}
