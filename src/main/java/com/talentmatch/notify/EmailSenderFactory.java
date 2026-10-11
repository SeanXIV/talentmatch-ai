package com.talentmatch.notify;

import java.nio.charset.StandardCharsets;
import java.util.Properties;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * Builds the {@link JavaMailSenderImpl} of the email channel and its JavaMail properties (§4.9). Pure.
 * There is no {@code JavaMailSender} bean: the notifier owns the one it builds here.
 */
public final class EmailSenderFactory {

    static final String TLS_PROTOCOLS = "TLSv1.2 TLSv1.3";

    private EmailSenderFactory() {
    }

    /**
     * The JavaMail session properties:
     * <ul>
     *   <li>{@code mail.smtp.host}, {@code mail.smtp.port}; {@code mail.smtp.auth=true} only with a username;</li>
     *   <li>the envelope sender {@code mail.smtp.from} = {@code from};</li>
     *   <li>connect, read and write timeouts in milliseconds;</li>
     *   <li>STARTTLS: {@code starttls.enable} and {@code starttls.required} (never a plaintext fallback);
     *       SSL: {@code ssl.enable}; both: server identity checked, TLS 1.2/1.3 only;</li>
     *   <li>{@code mail.debug=false} always (debug output prints the AUTH exchange).</li>
     * </ul>
     */
    public static Properties javaMailProperties(NotifyProperties.Email e) {
        Properties p = new Properties();
        p.setProperty("mail.transport.protocol", "smtp");
        if (e.host() != null) {
            p.setProperty("mail.smtp.host", e.host());
        }
        p.setProperty("mail.smtp.port", String.valueOf(e.port()));
        p.setProperty("mail.smtp.auth", String.valueOf(e.authenticated()));
        String envelopeFrom = envelopeSender(e.from());
        if (envelopeFrom != null) {
            p.setProperty("mail.smtp.from", envelopeFrom);
        }
        p.setProperty("mail.smtp.connectiontimeout", String.valueOf(e.connectionTimeout().toMillis()));
        p.setProperty("mail.smtp.timeout", String.valueOf(e.readTimeout().toMillis()));
        p.setProperty("mail.smtp.writetimeout", String.valueOf(e.writeTimeout().toMillis()));
        switch (e.security()) {
            case STARTTLS -> {
                p.setProperty("mail.smtp.starttls.enable", "true");
                p.setProperty("mail.smtp.starttls.required", "true");
                tls(p);
            }
            case SSL -> {
                p.setProperty("mail.smtp.ssl.enable", "true");
                tls(p);
            }
            case NONE -> p.setProperty("mail.smtp.starttls.enable", "false");
        }
        p.setProperty("mail.debug", "false");
        return p;
    }

    /** A sender for these settings; call only when {@link NotifyProperties.Email#configured()}. */
    public static JavaMailSenderImpl create(NotifyProperties.Email e) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(e.host());
        sender.setPort(e.port());
        sender.setProtocol("smtp");
        if (e.username() != null) {
            sender.setUsername(e.username());
            sender.setPassword(e.password());
        }
        sender.setDefaultEncoding(StandardCharsets.UTF_8.name());
        sender.setJavaMailProperties(javaMailProperties(e));
        return sender;
    }

    private static void tls(Properties p) {
        p.setProperty("mail.smtp.ssl.checkserveridentity", "true");
        p.setProperty("mail.smtp.ssl.protocols", TLS_PROTOCOLS);
    }

    /** The bare address of {@code from} (a personal-name form is reduced to its address). */
    private static String envelopeSender(String from) {
        if (from == null) {
            return null;
        }
        var parsed = EmailSettings.parse(from);
        return parsed == null ? from : parsed.getAddress();
    }
}
