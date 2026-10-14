package com.talentmatch.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import com.talentmatch.notify.NotificationDispatcher;
import com.talentmatch.notify.NotificationTestService;
import jakarta.mail.internet.MimeMessage;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Base for the step-8 email ITs: {@link AbstractFeedProcessingIT} with the <b>real</b> email notifier
 * (still a spy, so tests can count or intercept sends) wired to an in-process GreenMail SMTP server on a
 * fixed loopback port (fixed so it can be stopped and restarted under the same cached context),
 * {@code security: NONE}, with a login. A stray {@code spring.mail.host} is set too, to prove the
 * auto-configured sender stays excluded.
 *
 * <p>The dispatcher's pause and the test-send rate limit are in memory and the context is cached, so
 * both are reset before each test.
 */
public abstract class AbstractNotifyIT extends AbstractFeedProcessingIT {

    protected static final String SMTP_LOGIN = "zq-gm-login-7";
    protected static final String SMTP_PASSWORD = "Zq-Gm-Pw-31337";
    protected static final String FROM = "TalentMatch Alerts <zqalerts@tm-mail.example>";
    protected static final String FROM_ADDRESS = "zqalerts@tm-mail.example";
    protected static final String TO = "zqowner@tm-owner.example";
    protected static final String FROM_DOMAIN = "tm-mail.example";

    protected static final int SMTP_PORT = freePort();
    protected static final GreenMail GREENMAIL = new GreenMail(new ServerSetup(SMTP_PORT, "127.0.0.1",
            ServerSetup.PROTOCOL_SMTP))
            .withConfiguration(GreenMailConfiguration.aConfig().withUser(TO, SMTP_LOGIN, SMTP_PASSWORD));

    static {
        GREENMAIL.start();
    }

    private static int freePort() {
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return s.getLocalPort();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void smtpProperties(DynamicPropertyRegistry registry) {
        registry.add("talentmatch.notify.email.host", () -> "127.0.0.1");
        registry.add("talentmatch.notify.email.port", () -> SMTP_PORT);
        registry.add("talentmatch.notify.email.security", () -> "NONE");
        registry.add("talentmatch.notify.email.username", () -> SMTP_LOGIN);
        registry.add("talentmatch.notify.email.password", () -> SMTP_PASSWORD);
        registry.add("talentmatch.notify.email.from", () -> FROM);
        registry.add("talentmatch.notify.email.to", () -> TO);
        registry.add("talentmatch.notify.email.connection-timeout", () -> "2s");
        registry.add("talentmatch.notify.email.read-timeout", () -> "5s");
        // a stray Spring mail setting must not create a JavaMailSender (the auto-config is excluded)
        registry.add("spring.mail.host", () -> "127.0.0.1");
    }

    @Autowired
    protected NotificationDispatcher dispatcher;
    @Autowired
    protected NotificationTestService testService;

    @Override
    protected boolean stubEmailNotifier() {
        return false;
    }

    @BeforeEach
    void resetMail() throws Exception {
        greenMailUp();
        GREENMAIL.getUserManager().getUserByEmail(TO).setPassword(SMTP_PASSWORD);
        GREENMAIL.purgeEmailFromAllMailboxes();
        dispatcher.resume();
        ReflectionTestUtils.setField(testService, "lastTest", new AtomicReference<Instant>());
    }

    @AfterEach
    void leaveMailUp() {
        greenMailUp();
        dispatcher.resume();
    }

    protected static void greenMailUp() {
        if (!GREENMAIL.isRunning()) {
            GREENMAIL.start();
        }
    }

    protected static void greenMailDown() {
        GREENMAIL.stop();
    }

    protected static void wrongSmtpPassword() {
        GREENMAIL.getUserManager().getUserByEmail(TO).setPassword("Zq-Some-Other-Pw");
    }

    protected static MimeMessage[] received() {
        return GREENMAIL.getReceivedMessages();
    }

    // ------------------------------------------------------------------ queueing notifications through the feed

    protected static final String JAVA_SQL = "We use Java and SQL.";
    protected static final String PREFS = """
            {"targetTitles": ["Engineer"],
             "regions": {"countries": ["ZA"], "includeRemote": true, "remoteScope": "ELIGIBLE_FROM_COUNTRIES"}}""";

    /** Owner, preferences, notifications enabled (min 0.6, cap {@code maxPerHour}). */
    protected void owner(int maxPerHour) {
        skills("Java", "SQL", "Kubernetes");
        profile("Java", "SQL");
        preferences(PREFS);
        notifications(true, 0.6);
        jdbc.update("UPDATE notification_settings SET max_per_hour = ?", maxPerHour);
    }

    /**
     * A Lever source with a baseline poll, then a second poll with {@code n} fresh "Engineer N" postings,
     * processed: {@code n} PENDING notification rows. Returns the source id.
     */
    protected UUID queue(String token, int n) {
        UUID s = lever(token, "Acme");
        serve(token, old("base", "Platform Engineer", JAVA_SQL));
        pollOk(s);
        processor.processDue();
        List<Post> posts = new ArrayList<>();
        posts.add(old("base", "Platform Engineer", JAVA_SQL));
        for (int i = 0; i < n; i++) {
            posts.add(fresh("p" + i, "Backend Engineer " + i, JAVA_SQL));
        }
        serve(token, posts);
        jdbc.update("UPDATE feed_source SET last_polled_at = NULL, content_hash = NULL, etag = NULL WHERE id = ?", s);
        pollOk(s);
        processor.processDue();
        assertThat(pendingNotifications()).as("queued notifications").isEqualTo(n);
        return s;
    }

    protected int pendingNotifications() {
        return jdbc.queryForObject("SELECT count(*) FROM feed_notification WHERE status = 'PENDING'", Integer.class);
    }

    protected Map<String, Object> notification(UUID id) {
        return jdbc.queryForMap("SELECT * FROM feed_notification WHERE id = ?", id);
    }

    protected Map<String, Object> notificationForJob(UUID jobId) {
        return jdbc.queryForMap("SELECT * FROM feed_notification WHERE job_id = ?", jobId);
    }

    protected List<UUID> notificationIds() {
        return jdbc.queryForList("SELECT id FROM feed_notification ORDER BY created_at, id", UUID.class);
    }

    protected void makeAllDue() {
        jdbc.update("UPDATE feed_notification SET next_attempt_at = ? WHERE status = 'PENDING'",
                Timestamp.from(Instant.now().minusSeconds(1)));
    }
}
