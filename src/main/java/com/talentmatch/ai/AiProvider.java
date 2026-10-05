package com.talentmatch.ai;

import java.util.Locale;

/** LLM provider behind the explanation layer ({@code talentmatch.ai.provider}). */
public enum AiProvider {
    OLLAMA,
    OPENAI,
    CLAUDE;

    /** Lower-case id used in labels and logs, e.g. {@code ollama}. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }
}
