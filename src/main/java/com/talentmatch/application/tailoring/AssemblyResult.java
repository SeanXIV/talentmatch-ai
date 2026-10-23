package com.talentmatch.application.tailoring;

import com.talentmatch.application.content.Finding;
import java.util.List;

/**
 * What an assembler made of the model's answer: a grounded document with its findings, or a rejection
 * (the kind fails with {@code INVALID_OUTPUT}; nothing is stored).
 */
public sealed interface AssemblyResult<T> {

    /** A grounded document and its findings (stored with the version). */
    record Assembled<T>(T content, List<Finding> findings) implements AssemblyResult<T> {

        public Assembled {
            findings = List.copyOf(findings);
        }
    }

    /**
     * The answer was rejected.
     *
     * @param reason a fixed description with counts only (no document text), safe to log
     */
    record Rejected<T>(String reason) implements AssemblyResult<T> {
    }
}
