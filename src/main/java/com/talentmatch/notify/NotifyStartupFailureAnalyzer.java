package com.talentmatch.notify;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/**
 * Prints notification configuration errors (e.g. "SMTP_USERNAME is set but SMTP_PASSWORD is empty.")
 * as one actionable startup message, with no configured values. Registered in
 * {@code META-INF/spring.factories}.
 */
public class NotifyStartupFailureAnalyzer extends AbstractFailureAnalyzer<NotifyConfigurationException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, NotifyConfigurationException cause) {
        return new FailureAnalysis(cause.getDescription(), cause.getAction(), cause);
    }
}
