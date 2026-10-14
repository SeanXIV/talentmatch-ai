package com.talentmatch.notify;

import java.time.Duration;
import java.util.Locale;
import java.util.Objects;

/**
 * A classified delivery failure (§4.9). Every text it produces is built from fixed phrases, the SMTP
 * reply code and (for the API message) the configured host and port. Never the exception message, the
 * server's reply text, an address, the username or the password.
 *
 * @param kind       what the dispatcher does with it
 * @param smtpCode   the SMTP reply code, when one was found
 * @param reason     the finer cause, used for the texts
 * @param causeClass the class name of the most specific exception (logs only)
 */
public record SmtpFailure(Kind kind, Integer smtpCode, Reason reason, String causeClass) {

    /** {@code last_error} is a varchar(300). */
    public static final int MAX_TEXT = 300;

    public enum Kind {
        /** Try again later with backoff. */
        TRANSIENT,
        /** This message can never be delivered: FAILED at once. */
        PERMANENT,
        /** The channel is broken until the owner fixes the configuration: pause, count no attempt. */
        CONFIG
    }

    public enum Reason {
        AUTH_FAILED,
        AUTH_OR_TLS_REQUIRED,
        STARTTLS_UNAVAILABLE,
        TLS_HANDSHAKE,
        UNKNOWN_HOST,
        CONNECT_REFUSED,
        CONNECT_TIMEOUT,
        READ_TIMEOUT,
        IO,
        SMTP_TRANSIENT,
        RECIPIENT_REJECTED,
        SENDER_REJECTED,
        TOO_LARGE,
        REJECTED,
        INVALID_ADDRESS,
        UNKNOWN
    }

    public SmtpFailure {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(reason, "reason");
        causeClass = causeClass == null || causeClass.isBlank() ? "unknown" : causeClass;
    }

    public static SmtpFailure of(Kind kind, Reason reason, Integer smtpCode, Class<?> cause) {
        return new SmtpFailure(kind, smtpCode, reason, cause == null ? null : cause.getName());
    }

    /** The outcome word of the per-send log line: {@code transient}, {@code permanent} or {@code config}. */
    public String outcome() {
        return kind.name().toLowerCase(Locale.ROOT);
    }

    /**
     * Short sanitized text for {@code feed_notification.last_error}, the history and the status
     * ("SMTP 421 (transient)", "connect timeout", "SMTP login failed (535). Check SMTP_USERNAME and
     * SMTP_PASSWORD."). At most {@link #MAX_TEXT} characters.
     */
    public String historyText() {
        String text = switch (reason) {
            case AUTH_FAILED -> "SMTP login failed" + code() + ". Check SMTP_USERNAME and SMTP_PASSWORD.";
            case AUTH_OR_TLS_REQUIRED -> "SMTP" + bareCode() + " login or encryption required. "
                    + "Check SMTP_USERNAME, SMTP_PASSWORD and SMTP_SECURITY.";
            case STARTTLS_UNAVAILABLE -> "The server doesn't offer STARTTLS. Use SMTP_SECURITY=SSL on port 465.";
            case TLS_HANDSHAKE -> "TLS handshake failed. Check SMTP_HOST, SMTP_PORT and SMTP_SECURITY.";
            case UNKNOWN_HOST -> "SMTP host not found. Check SMTP_HOST.";
            case CONNECT_REFUSED -> "connection refused";
            case CONNECT_TIMEOUT -> "connect timeout";
            case READ_TIMEOUT -> "read timeout";
            case IO -> "I/O error";
            case SMTP_TRANSIENT -> smtpCode == null ? "SMTP temporary failure (transient)"
                    : "SMTP " + smtpCode + " (transient)";
            case RECIPIENT_REJECTED, SENDER_REJECTED, REJECTED -> smtpCode == null ? "rejected by the mail server"
                    : "SMTP " + smtpCode + " (rejected)";
            case TOO_LARGE -> "SMTP " + (smtpCode == null ? 552 : smtpCode) + " (too large)";
            case INVALID_ADDRESS -> "invalid address";
            case UNKNOWN -> "send failed (" + simpleName() + ")";
        };
        return text.length() <= MAX_TEXT ? text : text.substring(0, MAX_TEXT);
    }

    /**
     * The message of a 502 {@code NOTIFICATION_DELIVERY_FAILED} (§5.4): what went wrong and what to do,
     * with the SMTP code and host:port, never the server reply text or a credential.
     */
    public String userMessage(String host, int port, Duration connectTimeout) {
        String where = (host == null || host.isBlank() ? "the mail server" : host) + ":" + port;
        long seconds = connectTimeout == null ? 10 : Math.max(1, connectTimeout.toSeconds());
        return switch (reason) {
            case AUTH_FAILED -> "The mail server rejected the login" + code()
                    + ". Check SMTP_USERNAME and SMTP_PASSWORD.";
            case AUTH_OR_TLS_REQUIRED -> "The mail server requires a login or encryption" + code()
                    + ". Check SMTP_USERNAME, SMTP_PASSWORD and SMTP_SECURITY.";
            case STARTTLS_UNAVAILABLE -> "The mail server doesn't offer STARTTLS on port " + port
                    + ". Use port 465 with SMTP_SECURITY=SSL.";
            case TLS_HANDSHAKE -> "The TLS handshake with " + where
                    + " failed (certificate, host name or protocol). Check SMTP_HOST, SMTP_PORT and SMTP_SECURITY.";
            case UNKNOWN_HOST -> "The mail server host " + (host == null ? "" : host)
                    + " couldn't be found. Check SMTP_HOST.";
            case CONNECT_REFUSED -> "Couldn't connect to " + where
                    + ": the connection was refused. Check SMTP_HOST and SMTP_PORT.";
            case CONNECT_TIMEOUT -> "Couldn't connect to " + where + " within " + seconds
                    + (seconds == 1 ? " second." : " seconds.");
            case READ_TIMEOUT -> "The mail server at " + where + " stopped responding. Try again later.";
            case IO -> "The connection to " + where + " failed while sending. Try again later.";
            case SMTP_TRANSIENT -> "The mail server is temporarily unable to accept the message" + code()
                    + ". Try again later.";
            case RECIPIENT_REJECTED -> "The mail server refused the recipient address" + code()
                    + ". Check NOTIFY_EMAIL_TO.";
            case SENDER_REJECTED -> "The mail server refused the sender address" + code()
                    + ". Check NOTIFY_EMAIL_FROM; many servers only accept the account's own address.";
            case TOO_LARGE -> "The mail server rejected the message as too large" + code() + ".";
            case REJECTED -> "The mail server rejected the message" + code()
                    + ". Check the server's sending policy for this account.";
            case INVALID_ADDRESS -> "An email address was rejected as invalid. "
                    + "Check NOTIFY_EMAIL_FROM and NOTIFY_EMAIL_TO.";
            case UNKNOWN -> "The email couldn't be sent through " + where
                    + ". Try again later; the server log has the error class.";
        };
    }

    /** " (535)" or "". */
    private String code() {
        return smtpCode == null ? "" : " (" + smtpCode + ")";
    }

    /** " 530:" or ":". */
    private String bareCode() {
        return smtpCode == null ? ":" : " " + smtpCode + ":";
    }

    private String simpleName() {
        int dot = causeClass.lastIndexOf('.');
        return dot < 0 ? causeClass : causeClass.substring(dot + 1);
    }
}
