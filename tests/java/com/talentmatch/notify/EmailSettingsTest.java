package com.talentmatch.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatNoException;

import com.talentmatch.notify.NotifyProperties.Email;
import com.talentmatch.notify.NotifyProperties.Security;
import java.time.Duration;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/** §4.9 / §9.1 #14: startup checks, JavaMail properties, loopback literals, masking. */
class EmailSettingsTest {

    static Email email(String host, int port, Security security, String user, String password, String from,
                       String to) {
        return new Email(host, port, security, user, password, from, to, Duration.ofSeconds(7),
                Duration.ofSeconds(8), Duration.ofSeconds(9));
    }

    // ------------------------------------------------------------------ EmailSenderFactory

    @Test
    void starttlsProperties() {
        Properties p = EmailSenderFactory.javaMailProperties(email("smtp.example.com", 587, Security.STARTTLS,
                "user", "pw", "Alerts <alerts@example.com>", "owner@example.com"));
        assertThat(p.getProperty("mail.smtp.host")).isEqualTo("smtp.example.com");
        assertThat(p.getProperty("mail.smtp.port")).isEqualTo("587");
        assertThat(p.getProperty("mail.smtp.starttls.enable")).isEqualTo("true");
        assertThat(p.getProperty("mail.smtp.starttls.required")).isEqualTo("true");
        assertThat(p.getProperty("mail.smtp.ssl.enable")).isNull();
        assertThat(p.getProperty("mail.smtp.ssl.checkserveridentity")).isEqualTo("true");
        assertThat(p.getProperty("mail.smtp.ssl.protocols")).isEqualTo("TLSv1.2 TLSv1.3");
        assertThat(p.getProperty("mail.smtp.connectiontimeout")).isEqualTo("7000");
        assertThat(p.getProperty("mail.smtp.timeout")).isEqualTo("8000");
        assertThat(p.getProperty("mail.smtp.writetimeout")).isEqualTo("9000");
        assertThat(p.getProperty("mail.debug")).isEqualTo("false");
        assertThat(p.getProperty("mail.smtp.auth")).isEqualTo("true");
        assertThat(p.getProperty("mail.smtp.from")).as("bare envelope sender").isEqualTo("alerts@example.com");
        assertThat(p.values()).as("no credential in the session properties").doesNotContain("pw");
    }

    @Test
    void sslProperties() {
        Properties p = EmailSenderFactory.javaMailProperties(email("smtp.example.com", 465, Security.SSL,
                null, null, "a@example.com", "b@example.com"));
        assertThat(p.getProperty("mail.smtp.ssl.enable")).isEqualTo("true");
        assertThat(p.getProperty("mail.smtp.starttls.enable")).isNull();
        assertThat(p.getProperty("mail.smtp.ssl.checkserveridentity")).isEqualTo("true");
        assertThat(p.getProperty("mail.smtp.ssl.protocols")).isEqualTo("TLSv1.2 TLSv1.3");
        assertThat(p.getProperty("mail.smtp.auth")).as("auth only with a username").isEqualTo("false");
        assertThat(p.getProperty("mail.debug")).isEqualTo("false");
    }

    @Test
    void noneProperties() {
        Properties p = EmailSenderFactory.javaMailProperties(email("127.0.0.1", 2525, Security.NONE,
                null, null, "a@example.com", "b@example.com"));
        assertThat(p.getProperty("mail.smtp.starttls.enable")).isEqualTo("false");
        assertThat(p.getProperty("mail.smtp.starttls.required")).isNull();
        assertThat(p.getProperty("mail.smtp.ssl.enable")).isNull();
    }

    @Test
    void senderGetsCredentialsOnlyWithAUsername() {
        JavaMailSenderImpl s = EmailSenderFactory.create(email("h.example.com", 587, Security.STARTTLS, "u", "p",
                "a@example.com", "b@example.com"));
        assertThat(s.getUsername()).isEqualTo("u");
        assertThat(s.getPassword()).isEqualTo("p");
        assertThat(s.getDefaultEncoding()).isEqualTo("UTF-8");
        assertThat(s.getProtocol()).isEqualTo("smtp");
        JavaMailSenderImpl anon = EmailSenderFactory.create(email("h.example.com", 587, Security.STARTTLS, null,
                null, "a@example.com", "b@example.com"));
        assertThat(anon.getUsername()).isNull();
        assertThat(anon.getPassword()).isNull();
    }

    // ------------------------------------------------------------------ EmailSettings.validate

    @Test
    void usernameWithoutPasswordFailsNamingTheVariableOnly() {
        assertThatThrownBy(() -> EmailSettings.validate(email(null, 587, Security.STARTTLS, "zq-user-77", null,
                null, null)))
                .isInstanceOf(NotifyConfigurationException.class)
                .hasMessageContaining("SMTP_PASSWORD")
                .hasMessageNotContaining("zq-user-77");
        assertThatThrownBy(() -> EmailSettings.validate(email(null, 587, Security.STARTTLS, null, "Zq-Pw-77",
                null, null)))
                .isInstanceOf(NotifyConfigurationException.class)
                .hasMessageContaining("SMTP_USERNAME")
                .hasMessageNotContaining("Zq-Pw-77");
    }

    @Test
    void blankPasswordCountsAsAbsent() {
        Email e = email(null, 587, Security.STARTTLS, "user", "   ", null, null);
        assertThat(e.password()).isNull();
        assertThatThrownBy(() -> EmailSettings.validate(e)).isInstanceOf(NotifyConfigurationException.class);
    }

    @Test
    void invalidAddressesFailOnlyWhenConfigured() {
        for (String bad : new String[]{"owner", "owner@", "@example.com", "a@example.com, b@example.com",
                "a@example.com\r\nBcc: x@example.com", "not an address"}) {
            assertThatThrownBy(() -> EmailSettings.validate(email("smtp.example.com", 587, Security.STARTTLS, null,
                    null, "alerts@example.com", bad)))
                    .as(bad).isInstanceOf(NotifyConfigurationException.class)
                    .hasMessageContaining("NOTIFY_EMAIL_TO")
                    .satisfies(e -> {
                        if (!"@example.com".equals(bad)) {      // the fixed action text names alerts@example.com
                            assertThat(e.getMessage()).doesNotContain(bad);
                        }
                    });
            assertThatThrownBy(() -> EmailSettings.validate(email("smtp.example.com", 587, Security.STARTTLS, null,
                    null, bad, "owner@example.com")))
                    .as(bad).isInstanceOf(NotifyConfigurationException.class)
                    .hasMessageContaining("NOTIFY_EMAIL_FROM");
        }
        // partially configured: not a failure
        assertThatNoException().isThrownBy(() -> EmailSettings.validate(email(null, 587, Security.STARTTLS, null,
                null, "nonsense", null)));
        assertThatNoException().isThrownBy(() -> EmailSettings.validate(email("smtp.example.com", 587,
                Security.STARTTLS, "u", "p", "\"Alerts\" <alerts@example.com>", "owner@example.com")));
    }

    @Test
    void bareLocalPartIsRejectedExplicitly() throws Exception {
        // §11 item 5 assumption: InternetAddress accepts a bare local part even in strict mode.
        var parsed = jakarta.mail.internet.InternetAddress.parse("owner", true);
        assertThat(parsed).hasSize(1);
        assertThat(EmailSettings.parse("owner")).isNull();
        assertThat(EmailSettings.parse("owner@example.com")).isNotNull();
    }

    @Test
    void noneOnlyForLoopbackHosts() {
        assertThatThrownBy(() -> EmailSettings.validate(email("smtp.example.com", 25, Security.NONE, null, null,
                "a@example.com", "b@example.com")))
                .isInstanceOf(NotifyConfigurationException.class)
                .hasMessageContaining("SMTP_SECURITY=NONE")
                .hasMessageNotContaining("smtp.example.com");
        for (String h : new String[]{"localhost", "127.0.0.1", "127.1.2.3", "::1", "[::1]", "LOCALHOST"}) {
            assertThatNoException().as(h).isThrownBy(() -> EmailSettings.validate(email(h, 2525, Security.NONE, null,
                    null, "a@example.com", "b@example.com")));
        }
        // NONE while unconfigured is fine (nothing is sent)
        assertThatNoException().isThrownBy(() -> EmailSettings.validate(email(null, 25, Security.NONE, null, null,
                null, null)));
    }

    @Test
    void loopbackIsALiteralCheckWithoutDns() {
        assertThat(EmailSettings.isLoopback("localhost")).isTrue();
        assertThat(EmailSettings.isLoopback("127.0.0.1")).isTrue();
        assertThat(EmailSettings.isLoopback("127.255.255.254")).isTrue();
        assertThat(EmailSettings.isLoopback("0:0:0:0:0:0:0:1")).isTrue();
        assertThat(EmailSettings.isLoopback("127.0.0.300")).isFalse();
        assertThat(EmailSettings.isLoopback("128.0.0.1")).isFalse();
        assertThat(EmailSettings.isLoopback("localhost.example.com")).isFalse();
        assertThat(EmailSettings.isLoopback("my-localhost")).isFalse();
        assertThat(EmailSettings.isLoopback("127.0.0.1.nip.io")).isFalse();
        assertThat(EmailSettings.isLoopback(null)).isFalse();
        // a name that would need DNS is never resolved: fast and false
        long t0 = System.nanoTime();
        assertThat(EmailSettings.isLoopback("zq-unresolvable-host.invalid")).isFalse();
        assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofMillis(200));
    }

    @Test
    void missingListsBlankVariables() {
        assertThat(EmailSettings.missing(Email.unconfigured()))
                .containsExactly("SMTP_HOST", "NOTIFY_EMAIL_FROM", "NOTIFY_EMAIL_TO");
        assertThat(EmailSettings.missing(email("h", 587, Security.STARTTLS, null, null, "a@b.example", null)))
                .containsExactly("NOTIFY_EMAIL_TO");
        assertThat(EmailSettings.missing(email("h", 587, Security.STARTTLS, null, null, "a@b.example", "c@d.example")))
                .isEmpty();
    }

    @Test
    void fromDomain() {
        assertThat(EmailSettings.fromDomain(email("h", 587, Security.STARTTLS, null, null,
                "Alerts <alerts@Mail.Example.COM>", "x@y.example"))).isEqualTo("mail.example.com");
        assertThat(EmailSettings.fromDomain(Email.unconfigured())).isEqualTo("localhost");
    }

    // ------------------------------------------------------------------ EmailAddresses

    @Test
    void masking() {
        assertThat(EmailAddresses.mask("owner@example.com")).isEqualTo("o•••@example.com");
        assertThat(EmailAddresses.mask("o@example.com")).isEqualTo("o•••@example.com");
        assertThat(EmailAddresses.mask("\"Owner Name\" <owner@example.com>")).isEqualTo("o•••@example.com");
        assertThat(EmailAddresses.mask("no-at-sign")).isEqualTo("•••");
        assertThat(EmailAddresses.mask("@example.com")).isEqualTo("•••@example.com");
        assertThat(EmailAddresses.mask("  ")).isNull();
        assertThat(EmailAddresses.mask(null)).isNull();
        assertThat(EmailAddresses.mask("😀x@example.com")).isEqualTo("😀•••@example.com");
    }

    // ------------------------------------------------------------------ EmailNotifier basics (no network)

    @Test
    void notifierIsUnconfiguredWithoutHostFromOrTo() {
        EmailNotifier n = new EmailNotifier(NotifyProperties.withEmail(email("smtp.example.com", 587,
                Security.STARTTLS, null, null, "a@example.com", null)), java.time.Clock.systemUTC());
        assertThat(n.channel()).isEqualTo(Channel.EMAIL);
        assertThat(n.configured()).isFalse();
        assertThat(n.info().configured()).isFalse();
        assertThat(n.info().missing()).containsExactly("NOTIFY_EMAIL_TO");
        assertThat(n.info().host()).isNull();
        assertThatThrownBy(() -> n.send(new NotificationMessage("s", "b", "k")))
                .isInstanceOf(NotifyException.class);
    }

    @Test
    void notifierInfoMasksBothAddressesAndHidesCredentials() {
        EmailNotifier n = new EmailNotifier(NotifyProperties.withEmail(email("smtp.example.com", 587,
                Security.STARTTLS, "zq-login", "Zq-Pw", "Alerts <alerts@example.com>", "owner@example.com")),
                java.time.Clock.systemUTC());
        assertThat(n.configured()).isTrue();
        EmailNotifier.EmailInfo info = n.info();
        assertThat(info.from()).isEqualTo("a•••@example.com");
        assertThat(info.to()).isEqualTo("o•••@example.com");
        assertThat(info.authenticated()).isTrue();
        assertThat(info.security()).isEqualTo("STARTTLS");
        assertThat(info.toString()).doesNotContain("zq-login").doesNotContain("Zq-Pw").doesNotContain("owner@")
                .doesNotContain("alerts@");
        assertThat(n.rfcMessageId("feed-1234")).isEqualTo("<feed-1234@example.com>");
        assertThat(n.rfcMessageId("feed-<x>\r\n@y")).isEqualTo("<feed-xy@example.com>");
    }
}
