package com.talentmatch.notify;

import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Date;
import java.util.List;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * The email {@link Notifier} (§4.9): Jakarta Mail through a {@link JavaMailSenderImpl} it builds itself
 * (there is no {@code JavaMailSender} bean). Always a bean (created by {@link NotifyConfig}); not
 * configured, and without a sender, when host, from or to is blank.
 *
 * <p>Each message is {@code text/plain; charset=UTF-8} with a UTF-8 subject, a stable
 * {@code Message-ID <key@from-domain>} (Spring's {@code JavaMailSenderImpl} keeps an explicitly set
 * Message-ID across {@code saveChanges()}), {@code Auto-Submitted: auto-generated} and
 * {@code X-Auto-Response-Suppress: All}. Every failure becomes a {@link NotifyException} carrying only
 * the classification; this class never logs (the dispatcher and the test service log one line per send).
 */
public class EmailNotifier implements Notifier {

    private final NotifyProperties.Email email;
    private final Clock clock;
    private final JavaMailSenderImpl sender;
    private final InternetAddress from;
    private final InternetAddress to;
    private final String fromDomain;

    /**
     * @param properties validated with {@link EmailSettings#validate} beforehand (by {@link NotifyConfig})
     */
    public EmailNotifier(NotifyProperties properties, Clock clock) {
        this.email = properties.email();
        this.clock = clock;
        boolean configured = email.configured();
        this.from = configured ? EmailSettings.parse(email.from()) : null;
        this.to = configured ? EmailSettings.parse(email.to()) : null;
        this.sender = configured && from != null && to != null ? EmailSenderFactory.create(email) : null;
        this.fromDomain = EmailSettings.fromDomain(email);
    }

    @Override
    public Channel channel() {
        return Channel.EMAIL;
    }

    @Override
    public boolean configured() {
        return sender != null;
    }

    @Override
    public void send(NotificationMessage message) throws NotifyException {
        if (sender == null) {
            // Callers check configured() first; this only guards misuse.
            throw new NotifyException(SmtpFailure.of(SmtpFailure.Kind.CONFIG, SmtpFailure.Reason.UNKNOWN,
                    null, IllegalStateException.class));
        }
        try {
            MimeMessage mime = sender.createMimeMessage();
            mime.setFrom(from);
            mime.setRecipient(Message.RecipientType.TO, to);
            mime.setSubject(message.subject(), StandardCharsets.UTF_8.name());
            mime.setText(message.textBody(), StandardCharsets.UTF_8.name());   // text/plain; charset=UTF-8
            mime.setSentDate(Date.from(clock.instant()));
            mime.setHeader("Auto-Submitted", "auto-generated");
            mime.setHeader("X-Auto-Response-Suppress", "All");
            mime.setHeader("Message-ID", rfcMessageId(message.messageId()));
            sender.send(mime);
        } catch (MessagingException | RuntimeException e) {          // RuntimeException covers Spring's MailException
            throw new NotifyException(SmtpErrorClassifier.classify(e));
        }
    }

    /** {@code <key@from-domain>}; the key is reduced to safe characters. */
    public String rfcMessageId(String key) {
        String k = key == null ? "" : key.replaceAll("[^A-Za-z0-9.\\-_]", "");
        return "<" + (k.isEmpty() ? "notification" : k) + "@" + fromDomain + ">";
    }

    /** The recipient, masked ({@code o•••@example.com}); null when not configured. */
    public String maskedTo() {
        return EmailAddresses.mask(email.to());
    }

    /** What the settings endpoint may show about the channel: no username, no password, masked addresses. */
    public EmailInfo info() {
        if (!configured()) {
            return EmailInfo.notConfigured(EmailSettings.missing(email));
        }
        return new EmailInfo(true, email.host(), email.port(), email.security().name(),
                EmailAddresses.mask(email.from()), EmailAddresses.mask(email.to()), email.authenticated(), null);
    }

    public String host() {
        return email.host();
    }

    public int port() {
        return email.port();
    }

    /**
     * The email channel as shown by {@code GET /api/notifications/settings} (§5.4). Both addresses are
     * masked (D5: the spec masks only {@code to}; {@code from} is usually the owner's own address too).
     *
     * @param missing the blank environment variables when not configured, otherwise null
     */
    public record EmailInfo(boolean configured, String host, Integer port, String security, String from, String to,
                            Boolean authenticated, List<String> missing) {

        static EmailInfo notConfigured(List<String> missing) {
            return new EmailInfo(false, null, null, null, null, null, null, List.copyOf(missing));
        }
    }
}
