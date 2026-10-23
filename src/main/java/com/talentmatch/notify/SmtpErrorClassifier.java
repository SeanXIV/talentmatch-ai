package com.talentmatch.notify;

import com.talentmatch.notify.SmtpFailure.Kind;
import com.talentmatch.notify.SmtpFailure.Reason;
import jakarta.mail.Address;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.AddressException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.SSLHandshakeException;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.eclipse.angus.mail.smtp.SMTPSenderFailedException;
import org.eclipse.angus.mail.util.MailConnectException;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;

/**
 * Classifies a mail exception into TRANSIENT, PERMANENT or CONFIG (§4.9), with the SMTP reply code.
 * Pure. It walks the whole exception graph: the cause chain, {@link MessagingException#getNextException()}
 * and the exceptions inside Spring's {@link MailSendException#getFailedMessages()}, with an identity
 * cycle guard and at most {@value #MAX_NODES} exceptions. The reply code comes from Angus Mail's
 * {@code SMTPSendFailedException}, {@code SMTPAddressFailedException} and {@code SMTPSenderFailedException}
 * ({@code getReturnCode()}).
 *
 * <p>First match wins, in this order: CONFIG, then PERMANENT, then TRANSIENT (which is also the
 * fallback, so an unknown failure is retried with backoff and bounded by max-attempts).
 *
 * <p>Exception messages are only inspected for two fixed facts (the Angus "STARTTLS is required"
 * text and a leading 3-digit reply code on an authentication failure); they are never copied into the
 * result.
 */
public final class SmtpErrorClassifier {

    static final int MAX_NODES = 32;

    /** Angus Mail's message when {@code mail.smtp.starttls.required} is set and the server has no STARTTLS. */
    static final String STARTTLS_REQUIRED_MESSAGE = "STARTTLS is required but host does not support STARTTLS";

    private static final Set<Integer> CONFIG_CODES = Set.of(530, 534, 535, 538);
    private static final Pattern LEADING_CODE = Pattern.compile("^\\s*([2-5]\\d\\d)\\b");

    private SmtpErrorClassifier() {
    }

    public static SmtpFailure classify(Throwable failure) {
        if (failure == null) {
            return SmtpFailure.of(Kind.TRANSIENT, Reason.UNKNOWN, null, null);
        }
        List<Throwable> all = flatten(failure);

        // ---- CONFIG: the channel is broken until the configuration changes
        for (Throwable t : all) {
            if (t instanceof AuthenticationFailedException || t instanceof MailAuthenticationException) {
                return SmtpFailure.of(Kind.CONFIG, Reason.AUTH_FAILED, authCode(all), t.getClass());
            }
        }
        for (Throwable t : all) {
            Integer code = replyCode(t);
            if (code != null && CONFIG_CODES.contains(code)) {
                return SmtpFailure.of(Kind.CONFIG, code == 535 ? Reason.AUTH_FAILED : Reason.AUTH_OR_TLS_REQUIRED,
                        code, t.getClass());
            }
        }
        for (Throwable t : all) {
            String message = t.getMessage();
            if (t instanceof MessagingException && message != null && message.contains(STARTTLS_REQUIRED_MESSAGE)) {
                return SmtpFailure.of(Kind.CONFIG, Reason.STARTTLS_UNAVAILABLE, null, t.getClass());
            }
            if (t instanceof SSLHandshakeException) {
                return SmtpFailure.of(Kind.CONFIG, Reason.TLS_HANDSHAKE, null, t.getClass());
            }
            if (t instanceof UnknownHostException) {
                return SmtpFailure.of(Kind.CONFIG, Reason.UNKNOWN_HOST, null, t.getClass());
            }
        }

        // ---- PERMANENT: this message can never be delivered
        for (Throwable t : all) {
            Integer code = replyCode(t);
            if (code != null && code >= 500 && code <= 599) {
                return SmtpFailure.of(Kind.PERMANENT, permanentReason(t, code), code, t.getClass());
            }
        }
        for (Throwable t : all) {
            if (t instanceof SendFailedException sfe && hasAddresses(sfe.getInvalidAddresses())) {
                return SmtpFailure.of(Kind.PERMANENT, Reason.RECIPIENT_REJECTED, null, t.getClass());
            }
            if (t instanceof AddressException) {
                return SmtpFailure.of(Kind.PERMANENT, Reason.INVALID_ADDRESS, null, t.getClass());
            }
        }

        // ---- TRANSIENT: everything else
        for (Throwable t : all) {
            Integer code = replyCode(t);
            if (code != null && code >= 400 && code <= 499) {
                return SmtpFailure.of(Kind.TRANSIENT, Reason.SMTP_TRANSIENT, code, t.getClass());
            }
        }
        for (Throwable t : all) {
            if (t instanceof MailConnectException) {
                boolean timeout = all.stream().anyMatch(SocketTimeoutException.class::isInstance);
                return SmtpFailure.of(Kind.TRANSIENT, timeout ? Reason.CONNECT_TIMEOUT : Reason.CONNECT_REFUSED,
                        null, t.getClass());
            }
        }
        for (Throwable t : all) {
            if (t instanceof SocketTimeoutException) {
                return SmtpFailure.of(Kind.TRANSIENT, Reason.READ_TIMEOUT, null, t.getClass());
            }
        }
        for (Throwable t : all) {
            if (t instanceof ConnectException) {
                return SmtpFailure.of(Kind.TRANSIENT, Reason.CONNECT_REFUSED, null, t.getClass());
            }
        }
        for (Throwable t : all) {
            if (t instanceof IOException) {
                return SmtpFailure.of(Kind.TRANSIENT, Reason.IO, null, t.getClass());
            }
        }
        return SmtpFailure.of(Kind.TRANSIENT, Reason.UNKNOWN, null, deepest(all).getClass());
    }

    /** The reply code of an Angus SMTP exception, or null. */
    static Integer replyCode(Throwable t) {
        int code = -1;
        if (t instanceof SMTPAddressFailedException e) {
            code = e.getReturnCode();
        } else if (t instanceof SMTPSenderFailedException e) {
            code = e.getReturnCode();
        } else if (t instanceof SMTPSendFailedException e) {
            code = e.getReturnCode();
        }
        return code >= 200 && code <= 599 ? code : null;
    }

    private static Reason permanentReason(Throwable t, int code) {
        if (code == 552) {
            return Reason.TOO_LARGE;
        }
        if (t instanceof SMTPSenderFailedException) {
            return Reason.SENDER_REJECTED;
        }
        if (t instanceof SMTPAddressFailedException) {
            return Reason.RECIPIENT_REJECTED;
        }
        return code == 550 || code == 551 || code == 553 ? Reason.RECIPIENT_REJECTED : Reason.REJECTED;
    }

    /**
     * The reply code of an authentication failure: Angus puts the server's last reply ("535 5.7.8 …")
     * in the message of {@link AuthenticationFailedException}; only its leading three digits are read.
     */
    private static Integer authCode(List<Throwable> all) {
        for (Throwable t : all) {
            Integer code = replyCode(t);
            if (code != null) {
                return code;
            }
        }
        for (Throwable t : all) {
            if (t instanceof AuthenticationFailedException && t.getMessage() != null) {
                Matcher m = LEADING_CODE.matcher(t.getMessage());
                if (m.find()) {
                    return Integer.valueOf(m.group(1));
                }
            }
        }
        return null;
    }

    private static boolean hasAddresses(Address[] addresses) {
        return addresses != null && addresses.length > 0;
    }

    private static Throwable deepest(List<Throwable> all) {
        return all.get(all.size() - 1);
    }

    /** Breadth-first over causes, next exceptions and failed-message exceptions; cycle-safe and bounded. */
    static List<Throwable> flatten(Throwable root) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Throwable> out = new ArrayList<>();
        Deque<Throwable> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty() && out.size() < MAX_NODES) {
            Throwable t = queue.poll();
            if (t == null || !seen.add(t)) {
                continue;
            }
            out.add(t);
            if (t instanceof MailSendException mse) {
                for (Exception e : mse.getFailedMessages().values()) {
                    if (e != null) {
                        queue.add(e);
                    }
                }
            }
            if (t instanceof MessagingException me && me.getNextException() != null) {
                queue.add(me.getNextException());
            }
            if (t.getCause() != null) {
                queue.add(t.getCause());
            }
        }
        return out;
    }
}
