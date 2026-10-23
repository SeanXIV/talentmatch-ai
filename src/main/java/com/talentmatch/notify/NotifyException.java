package com.talentmatch.notify;

import java.util.Objects;

/**
 * A delivery failure, already classified ({@link SmtpFailure}). Deliberately carries <b>no cause</b>:
 * mail exceptions (Spring's {@code MailSendException} in particular) put recipient addresses and server
 * reply text in their messages, and a cause would let them reach a log line or an error response.
 * {@link SmtpFailure#causeClass()} keeps the class name for logs.
 */
public class NotifyException extends Exception {

    private static final long serialVersionUID = 1L;

    private final transient SmtpFailure failure;

    public NotifyException(SmtpFailure failure) {
        super(Objects.requireNonNull(failure, "failure").historyText(), null, false, false);
        this.failure = failure;
    }

    public SmtpFailure failure() {
        return failure;
    }
}
