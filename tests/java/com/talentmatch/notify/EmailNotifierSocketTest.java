package com.talentmatch.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import com.talentmatch.notify.NotifyProperties.Email;
import com.talentmatch.notify.NotifyProperties.Security;
import jakarta.mail.internet.MimeMessage;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link EmailNotifier} against real sockets (no Spring): the Angus exception chains of a refused
 * connection, a silent server (read timeout) and a 535 login failure are classified as §4.9 says, and a
 * delivered message has the exact headers. Verifies the §11 item 5 assumptions on the real libraries.
 */
class EmailNotifierSocketTest {

    private static final String USER = "zq-gm-user";
    private static final String PASSWORD = "Zq-Gm-Secret-1";

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return s.getLocalPort();
        }
    }

    private static EmailNotifier notifier(int port, String user, String password, Duration readTimeout) {
        Email e = new Email("127.0.0.1", port, Security.NONE, user, password, "TalentMatch <alerts@tm.example.com>",
                "owner@example.com", Duration.ofSeconds(2), readTimeout, Duration.ofSeconds(2));
        EmailSettings.validate(e);
        return new EmailNotifier(NotifyProperties.withEmail(e), Clock.fixed(Instant.parse("2026-10-11T10:00:00Z"),
                ZoneOffset.UTC));
    }

    private static NotificationMessage message(UUID id) {
        return new NotificationMessage("[TalentMatch] 80% match: Bäckend Engineer at Acme", "Body line ✓\nApply: x",
                "feed-" + id);
    }

    @Test
    void connectionRefusedIsTransient() throws Exception {
        EmailNotifier n = notifier(freePort(), null, null, Duration.ofSeconds(2));
        assertThatThrownBy(() -> n.send(message(UUID.randomUUID()))).isInstanceOfSatisfying(NotifyException.class,
                e -> {
                    assertThat(e.failure().kind()).isEqualTo(SmtpFailure.Kind.TRANSIENT);
                    assertThat(e.failure().reason()).isEqualTo(SmtpFailure.Reason.CONNECT_REFUSED);
                    assertThat(e.failure().causeClass()).isEqualTo("org.eclipse.angus.mail.util.MailConnectException");
                    assertThat(e.getCause()).isNull();
                });
    }

    @Test
    void silentServerIsAReadTimeout() throws Exception {
        List<Socket> accepted = new ArrayList<>();
        try (ServerSocket silent = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
            Thread acceptor = new Thread(() -> {
                try {
                    while (!silent.isClosed()) {
                        accepted.add(silent.accept());          // accept, never send a greeting
                    }
                } catch (Exception ignored) {
                    // closed
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();
            EmailNotifier n = notifier(silent.getLocalPort(), null, null, Duration.ofSeconds(1));
            long t0 = System.nanoTime();
            assertThatThrownBy(() -> n.send(message(UUID.randomUUID()))).isInstanceOfSatisfying(NotifyException.class,
                    e -> {
                        assertThat(e.failure().kind()).isEqualTo(SmtpFailure.Kind.TRANSIENT);
                        assertThat(e.failure().reason()).isEqualTo(SmtpFailure.Reason.READ_TIMEOUT);
                    });
            assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(8));
        } finally {
            for (Socket s : accepted) {
                s.close();
            }
        }
    }

    @Test
    void greenMailDeliversExactHeadersAndRejectsAWrongPasswordWith535() throws Exception {
        int port = freePort();
        GreenMail gm = new GreenMail(new ServerSetup(port, "127.0.0.1", ServerSetup.PROTOCOL_SMTP))
                .withConfiguration(GreenMailConfiguration.aConfig().withUser("owner@example.com", USER, PASSWORD));
        gm.start();
        try {
            UUID id = UUID.randomUUID();
            notifier(port, USER, PASSWORD, Duration.ofSeconds(5)).send(message(id));
            MimeMessage[] got = gm.getReceivedMessages();
            assertThat(got).hasSize(1);
            MimeMessage m = got[0];
            assertThat(m.getHeader("Message-ID", null)).isEqualTo("<feed-" + id + "@tm.example.com>");
            assertThat(m.getContentType()).isEqualToIgnoringCase("text/plain; charset=UTF-8");
            assertThat(m.getSubject()).isEqualTo("[TalentMatch] 80% match: Bäckend Engineer at Acme");
            assertThat(m.getHeader("Auto-Submitted", null)).isEqualTo("auto-generated");
            assertThat(m.getHeader("X-Auto-Response-Suppress", null)).isEqualTo("All");
            assertThat(String.valueOf(m.getContent()).replace("\r\n", "\n")).isEqualTo("Body line ✓\nApply: x");
            assertThat(m.getFrom()[0].toString()).contains("alerts@tm.example.com");
            assertThat(m.getAllRecipients()[0].toString()).isEqualTo("owner@example.com");
            assertThat(m.getSentDate().toInstant()).isEqualTo(Instant.parse("2026-10-11T10:00:00Z"));

            EmailNotifier wrong = notifier(port, USER, "Zq-Wrong-Pw", Duration.ofSeconds(5));
            assertThatThrownBy(() -> wrong.send(message(UUID.randomUUID())))
                    .isInstanceOfSatisfying(NotifyException.class, e -> {
                        assertThat(e.failure().kind()).isEqualTo(SmtpFailure.Kind.CONFIG);
                        assertThat(e.failure().reason()).isEqualTo(SmtpFailure.Reason.AUTH_FAILED);
                        assertThat(e.failure().smtpCode()).as("GreenMail replies 535").isEqualTo(535);
                        assertThat(e.getMessage()).doesNotContain("Zq-Wrong-Pw").doesNotContain(USER);
                    });
            assertThat(gm.getReceivedMessages()).hasSize(1);
        } finally {
            gm.stop();
        }
    }
}
