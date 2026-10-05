package com.talentmatch.ai.config;

/**
 * Invalid AI configuration detected at startup (missing API key, bad effort value). Turned into
 * a readable startup message by {@link AiStartupFailureAnalyzer}. Never contains key values.
 */
public class AiConfigurationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String description;
    private final String action;

    public AiConfigurationException(String description, String action) {
        super(description + " " + action);
        this.description = description;
        this.action = action;
    }

    public String getDescription() {
        return description;
    }

    public String getAction() {
        return action;
    }

    static AiConfigurationException missingKey(String profile, String envVar) {
        return new AiConfigurationException(
                "Profile '" + profile + "' is active but " + envVar + " is not set.",
                "Export it, or drop the profile to use local Ollama (default) or set AI_ENABLED=false.");
    }
}
