package com.talentmatch.ai.config;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/**
 * Prints AI configuration errors (e.g. "Profile 'claude' is active but ANTHROPIC_API_KEY is not
 * set.") as one actionable startup message. Registered in {@code META-INF/spring.factories}.
 */
public class AiStartupFailureAnalyzer extends AbstractFailureAnalyzer<AiConfigurationException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, AiConfigurationException cause) {
        return new FailureAnalysis(cause.getDescription(), cause.getAction(), cause);
    }
}
