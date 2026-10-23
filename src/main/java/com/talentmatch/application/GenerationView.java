package com.talentmatch.application;

import com.talentmatch.application.content.DocumentKind;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * An AI generation of an application's documents (§5.1 {@code Generation}), built by
 * {@link GenerationRepository.GenerationRow#view()}.
 *
 * @param status        PENDING, RUNNING, SUCCEEDED or FAILED
 * @param failureReason the generation's failure (FAILED only)
 * @param hint          what to do about the failure
 * @param results       one entry per wanted kind once it finished (stored document or per-kind failure)
 */
public record GenerationView(
        UUID id,
        UUID applicationId,
        String status,
        boolean wantCv,
        boolean wantCoverLetter,
        String failureReason,
        String hint,
        String model,
        Integer profileVersion,
        int attempts,
        Instant queuedAt,
        Instant startedAt,
        Instant finishedAt,
        List<Result> results) {

    /** The outcome for one kind: the stored version, or why it failed. */
    public record Result(DocumentKind kind, UUID documentId, String failureReason, String hint) {
    }
}
