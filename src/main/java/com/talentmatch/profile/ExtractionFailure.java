package com.talentmatch.profile;

import com.talentmatch.ai.FailureKind;

/** Why a CV could not be turned into a draft profile, with a user-facing hint. */
public enum ExtractionFailure {
    AI_DISABLED("AI is turned off (talentmatch.ai.enabled=false). Enter your profile by hand with PUT /api/profile, "
            + "or turn AI on and retry."),
    TIMEOUT("The model did not finish in time. Retry, or raise talentmatch.profile.extraction.call-timeout "
            + "(on a CPU-only machine a CV can take 20-40 minutes; keep the computer awake)."),
    PROVIDER_ERROR("The AI provider could not be reached or returned an error. Check that it is running "
            + "(for Ollama: ollama serve) and retry."),
    REFUSED("The model stopped without finishing the profile. Retry; if it keeps happening, try another model."),
    INVALID_OUTPUT("The model's answer was not a usable profile (malformed or cut off). Retry, or raise "
            + "talentmatch.profile.extraction.max-output-tokens."),
    QUEUE_FULL("Too many CVs are waiting to be read. Retry in a few minutes."),
    CONTEXT_OVERFLOW("Your CV was too long for the model's context window, so part of it could have been missed. "
            + "Raise talentmatch.ai.ollama.context-tokens (uses more memory) or upload a shorter CV, then retry."),
    REMOTE_EXTRACTION_DISABLED("The AI provider is a hosted service (claude or openai profile), and sending your CV "
            + "to it is turned off. Set talentmatch.profile.allow-remote-extraction=true to allow it, or use the default "
            + "local Ollama provider, which keeps your CV on this machine. Then retry."),
    TOO_MANY_ATTEMPTS("Reading this CV was interrupted " + ResumeRepository.MAX_ATTEMPTS + " times (for example the app "
            + "stopped or crashed). Retry with POST /api/profile/resume/{id}/extract, or enter your profile by hand.");

    private final String hint;

    ExtractionFailure(String hint) {
        this.hint = hint;
    }

    public String hint() {
        return hint;
    }

    static ExtractionFailure of(FailureKind kind) {
        return switch (kind) {
            case TIMEOUT -> TIMEOUT;
            case PROVIDER_ERROR -> PROVIDER_ERROR;
            case REFUSED -> REFUSED;
            case INVALID_OUTPUT -> INVALID_OUTPUT;
        };
    }
}
