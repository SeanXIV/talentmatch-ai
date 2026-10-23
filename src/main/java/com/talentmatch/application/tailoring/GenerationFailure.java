package com.talentmatch.application.tailoring;

import com.talentmatch.ai.FailureKind;
import java.util.Locale;

/**
 * Why a generation, or one kind of it, failed (§6), with a user-facing hint. Must match V7's
 * {@code ck_generation_failure_values}: every value may be a generation's {@code failure_reason}; only the
 * {@linkplain #perKind() per-kind} values may be a {@code cv_failure} / {@code cover_letter_failure}.
 */
public enum GenerationFailure {
    AI_DISABLED("AI is turned off (talentmatch.ai.enabled=false), so documents can't be generated. Turn AI on "
            + "and generate again."),
    TIMEOUT("The model did not finish in time. Generate again, or raise "
            + "talentmatch.applications.generation.call-timeout; on a CPU-only machine both documents take "
            + "20–30 minutes; keep the computer awake."),
    PROVIDER_ERROR("The AI provider could not be reached or returned an error. Check that it is running "
            + "(for Ollama: ollama serve) and generate again."),
    REFUSED("The model stopped without finishing the document. Generate again; if it keeps happening, try "
            + "another model."),
    INVALID_OUTPUT("The model's answer couldn't be used (malformed, cut off, or too much of it couldn't be "
            + "checked against your profile). Generate again; if it keeps happening, try another model."),
    QUEUE_FULL("Too many generations are waiting. Try again in a few minutes."),
    CONTEXT_OVERFLOW("Your profile and the job posting were too long for the model's context window, so part "
            + "of them could have been missed. Raise talentmatch.ai.ollama.context-tokens or lower "
            + "talentmatch.applications.generation.max-profile-chars."),
    TOO_MANY_ATTEMPTS("This generation was interrupted " + GenerationFailure.MAX_ATTEMPTS + " times (for example "
            + "the app stopped or crashed). Start a new one with POST /api/applications/{id}/generations."),
    REMOTE_GENERATION_DISABLED("The AI provider is a hosted service (claude or openai profile), and sending your "
            + "profile to it is turned off. Set talentmatch.applications.allow-remote-generation=true to send "
            + "your profile to the hosted provider, or use local Ollama, which keeps it on this machine."),
    JOB_DELETED("The job for this application no longer exists, so documents can't be tailored to it. "
            + "Existing versions are kept.");

    /** Claims per generation before it is {@link #TOO_MANY_ATTEMPTS} (§4.6). */
    public static final int MAX_ATTEMPTS = 3;

    private final String hint;

    GenerationFailure(String hint) {
        this.hint = hint;
    }

    public String hint() {
        return hint;
    }

    /** Lower-case metric/log tag, e.g. {@code invalid_output}. */
    public String tag() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** True for the failures one document kind can have ({@code cv_failure}, {@code cover_letter_failure}). */
    public boolean perKind() {
        return switch (this) {
            case TIMEOUT, PROVIDER_ERROR, REFUSED, INVALID_OUTPUT, CONTEXT_OVERFLOW -> true;
            default -> false;
        };
    }

    /** The failure for a classified model-call exception. */
    public static GenerationFailure of(FailureKind kind) {
        return switch (kind) {
            case TIMEOUT -> TIMEOUT;
            case PROVIDER_ERROR -> PROVIDER_ERROR;
            case REFUSED -> REFUSED;
            case INVALID_OUTPUT -> INVALID_OUTPUT;
        };
    }
}
