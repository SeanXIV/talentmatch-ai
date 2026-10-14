package com.talentmatch.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.talentmatch.notify.NotificationDispatcher;
import com.talentmatch.support.AbstractFeedProcessingIT;
import com.talentmatch.support.Api.Res;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * §4.9 "Dev without SMTP" and §9.2 item 14 in the base (unconfigured) context: the real email
 * notifier, not stubbed (the processing context's spy calls the real, unconfigured bean).
 */
@ExtendWith(OutputCaptureExtension.class)
class NotificationUnconfiguredIT extends AbstractFeedProcessingIT {

    private static final String NOT_CONFIGURED = "Email isn't configured: set SMTP_HOST, NOTIFY_EMAIL_FROM and "
            + "NOTIFY_EMAIL_TO (plus SMTP_USERNAME and SMTP_PASSWORD if your server needs a login), then restart.";

    @Autowired
    private ApplicationContext context;
    @Autowired
    private NotificationDispatcher dispatcher;

    @Override
    protected boolean stubEmailNotifier() {
        return false;
    }

    @Test
    void settingsShowWhatIsMissing() {
        Res r = api.get("/api/notifications/settings");
        assertThat(r.status()).isEqualTo(200);
        JsonNode e = r.json().get("email");
        List<String> keys = new ArrayList<>();
        e.fieldNames().forEachRemaining(keys::add);
        assertThat(keys).containsExactlyInAnyOrder("configured", "missing");
        assertThat(e.get("configured").asBoolean()).isFalse();
        List<String> missing = new ArrayList<>();
        e.get("missing").forEach(m -> missing.add(m.asText()));
        assertThat(missing).containsExactly("SMTP_HOST", "NOTIFY_EMAIL_FROM", "NOTIFY_EMAIL_TO");
    }

    @Test
    void enablingIsRefusedButDisablingWorks() {
        JsonNode b = assertError(api.put("/api/notifications/settings",
                "{\"enabled\": true, \"minScore\": 0.6, \"maxPerHour\": 20}"), 400, "VALIDATION_FAILED");
        assertThat(fields(b)).containsExactly("enabled");
        assertThat(fieldMessages(b).get("enabled")).isEqualTo(NOT_CONFIGURED);
        assertThat(api.put("/api/notifications/settings", "{\"enabled\": false, \"minScore\": 0.6, \"maxPerHour\": 20}")
                .status()).isEqualTo(200);
        assertError(api.put("/api/notifications/settings",
                "{\"enabled\": false, \"minScore\": 0.6, \"maxPerHour\": 20, \"channel\": \"SMS\"}"), 400,
                "MALFORMED_REQUEST");
    }

    @Test
    void testSendIs409() {
        JsonNode b = assertError(api.post("/api/notifications/test", ""), 409, "NOTIFICATION_NOT_CONFIGURED");
        assertThat(b.get("message").asText()).isEqualTo(NOT_CONFIGURED);
        // never rate limited: the 409 takes no slot
        assertError(api.post("/api/notifications/test", ""), 409, "NOTIFICATION_NOT_CONFIGURED");
    }

    @Test
    void statusHealthAndBeans() {
        JsonNode n = api.get("/api/feed/status").json().get("notifications");
        assertThat(n.get("configured").asBoolean()).isFalse();
        assertThat(n.get("enabled").asBoolean()).isFalse();
        assertThat(n.get("paused").asBoolean()).isFalse();
        assertThat(context.getBeansOfType(JavaMailSender.class)).isEmpty();
        JsonNode h = api.get("/actuator/health").json();
        assertThat(h.at("/components/mail").isMissingNode()).isTrue();
        assertThat(h.at("/components/feed/details/notifications/configured").asBoolean()).isFalse();
        assertThat(h.at("/components/feed/details/reasons").toString()).doesNotContain("EMAIL_CONFIG");
    }

    @Test
    void aNotifiableJobInsertsNoRowAndTheDispatcherClaimsNothing(CapturedOutput output) {
        skills("Java", "SQL");
        profile("Java", "SQL");
        notifications(true, 0.6);                              // written with JDBC (as if env changed later)
        UUID s = lever("acme", "Acme");
        serve("acme", old("base", "Platform Engineer", "We use Java and SQL."));
        pollOk(s);
        processor.processDue();
        serve("acme", old("base", "Platform Engineer", "We use Java and SQL."),
                fresh("z", "Zqxwing Backend Engineer", "Zqxdesc: Java and SQL."));
        jdbc.update("UPDATE feed_source SET last_polled_at = NULL, content_hash = NULL, etag = NULL WHERE id = ?", s);
        pollOk(s);
        processor.processDue();
        UUID job = jobOf(s, "z");
        assertThat(notificationCount()).isZero();
        assertThat(output.getAll()).contains("job=" + job).contains("reason=email_not_configured")
                .doesNotContain("Zqxwing").doesNotContain("Zqxdesc");

        // a row queued while email was configured is never claimed
        jdbc.update("INSERT INTO feed_notification (job_id, channel, score) VALUES (?, 'EMAIL', 0.9)", job);
        dispatcher.dispatchDue();
        assertThat(jdbc.queryForObject("SELECT status FROM feed_notification", String.class)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT attempts FROM feed_notification", Integer.class)).isZero();
    }
}
