package com.talentmatch.notify;

/**
 * Invalid notification configuration detected at startup (an SMTP username without a password, an
 * invalid address, plaintext SMTP to a remote host). Turned into a readable startup message by
 * {@link NotifyStartupFailureAnalyzer}. Names the environment variable to fix, <b>never its value</b>.
 * Deliberately not a binding exception, so Boot's {@code BindFailureAnalyzer} (which prints bound values)
 * never handles it.
 */
public class NotifyConfigurationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String description;
    private final String action;

    public NotifyConfigurationException(String description, String action) {
        super(description + " " + action, null, false, false);
        this.description = description;
        this.action = action;
    }

    public String getDescription() {
        return description;
    }

    public String getAction() {
        return action;
    }
}
