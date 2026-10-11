package com.talentmatch.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.talentmatch.support.AbstractNotifyIT;
import com.talentmatch.support.Api.Res;
import jakarta.mail.internet.MimeMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** §5.4 endpoints with email configured (GreenMail): settings, test send (200/429/502), history, status. */
@ExtendWith(OutputCaptureExtension.class)
class NotificationEndpointsIT extends AbstractNotifyIT {

    private static void assertNoSecrets(Res r) {
        assertThat(r.body()).doesNotContain(SMTP_PASSWORD).doesNotContain(SMTP_LOGIN).doesNotContain(TO)
                .doesNotContain(FROM_ADDRESS).doesNotContain("zqowner").doesNotContain("zqalerts");
    }

    // ------------------------------------------------------------------ GET / PUT settings

    @Test
    void getSettingsReturnsDefaultsAndTheMaskedChannel() {
        Res r = api.get("/api/notifications/settings");
        assertThat(r.status()).isEqualTo(200);
        JsonNode b = r.json();
        assertThat(b.get("enabled").asBoolean()).isFalse();
        assertThat(b.get("channel").asText()).isEqualTo("EMAIL");
        assertThat(b.get("minScore").asDouble()).isEqualTo(0.6);
        assertThat(b.get("maxPerHour").asInt()).isEqualTo(20);
        JsonNode e = b.get("email");
        assertThat(e.get("configured").asBoolean()).isTrue();
        assertThat(e.get("host").asText()).isEqualTo("127.0.0.1");
        assertThat(e.get("port").asInt()).isEqualTo(SMTP_PORT);
        assertThat(e.get("security").asText()).isEqualTo("NONE");
        assertThat(e.get("to").asText()).isEqualTo("z•••@tm-owner.example");
        assertThat(e.get("from").asText()).as("D5: from masked too").isEqualTo("z•••@tm-mail.example");
        assertThat(e.get("authenticated").asBoolean()).isTrue();
        assertThat(e.has("missing")).isFalse();
        List<String> keys = new ArrayList<>();
        e.fieldNames().forEachRemaining(keys::add);
        assertThat(keys).containsExactlyInAnyOrder("configured", "host", "port", "security", "from", "to",
                "authenticated");
        assertNoSecrets(r);
    }

    @Test
    void putValidSettingsPersistsAndReturnsTheSameShape() {
        Res r = api.put("/api/notifications/settings",
                "{\"enabled\": true, \"minScore\": 0.75, \"maxPerHour\": 5, \"channel\": \"EMAIL\"}");
        assertThat(r.status()).as("%s", r).isEqualTo(200);
        assertThat(r.json().get("enabled").asBoolean()).isTrue();
        assertThat(r.json().get("minScore").asDouble()).isEqualTo(0.75);
        assertThat(r.json().get("maxPerHour").asInt()).isEqualTo(5);
        assertThat(r.json().get("email").get("configured").asBoolean()).isTrue();
        assertNoSecrets(r);
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM notification_settings");
        assertThat(row.get("enabled")).isEqualTo(true);
        assertThat(row.get("min_score")).isEqualTo(0.75);
        assertThat(row.get("max_per_hour")).isEqualTo(5);
        assertThat(api.get("/api/notifications/settings").json().get("maxPerHour").asInt()).isEqualTo(5);

        // channel is optional; a second PUT replaces the row
        Res again = api.put("/api/notifications/settings", "{\"enabled\": false, \"minScore\": 0, \"maxPerHour\": 200}");
        assertThat(again.status()).isEqualTo(200);
        assertThat(again.json().get("channel").asText()).isEqualTo("EMAIL");
        assertThat(countRows("notification_settings")).isOne();
    }

    @Test
    void putValidation() {
        JsonNode b = assertError(api.put("/api/notifications/settings",
                "{\"enabled\": true, \"minScore\": 1.5, \"maxPerHour\": 0}"), 400, "VALIDATION_FAILED");
        assertThat(fieldMessages(b)).containsOnly(
                Map.entry("maxPerHour", "maxPerHour must be a whole number between 1 and 200."),
                Map.entry("minScore", "minScore must be a number between 0 and 1."));
        JsonNode missing = assertError(api.put("/api/notifications/settings", "{}"), 400, "VALIDATION_FAILED");
        assertThat(fields(missing)).containsExactly("enabled", "maxPerHour", "minScore");
        assertThat(fieldMessages(missing).get("enabled")).isEqualTo("enabled is required.");
        assertThat(fields(assertError(api.put("/api/notifications/settings",
                "{\"enabled\": false, \"minScore\": 0.5, \"maxPerHour\": 201}"), 400, "VALIDATION_FAILED")))
                .containsExactly("maxPerHour");

        JsonNode sms = assertError(api.put("/api/notifications/settings",
                "{\"enabled\": true, \"minScore\": 0.6, \"maxPerHour\": 20, \"channel\": \"SMS\"}"), 400,
                "MALFORMED_REQUEST");
        // same handling as every bad enum in the API (InvalidFormatException is a MismatchedInputException)
        assertThat(sms.get("message").asText()).isEqualTo("Field 'channel' has the wrong type.");
        assertThat(fields(sms)).containsExactly("channel");
        assertError(api.put("/api/notifications/settings",
                "{\"enabled\": true, \"minScore\": 0.6, \"maxPerHour\": 20, \"password\": \"x\"}"), 400,
                "MALFORMED_REQUEST");
        assertError(api.put("/api/notifications/settings", "{\"enabled\": true, \"minScore\": \"high\"}"), 400,
                "MALFORMED_REQUEST");
        assertThat(countRows("notification_settings")).isZero();
    }

    // ------------------------------------------------------------------ POST test

    @Test
    void testSendDeliversThenRateLimits(CapturedOutput output) throws Exception {
        Res r = api.post("/api/notifications/test", "");
        assertThat(r.status()).as("%s", r).isEqualTo(200);
        JsonNode b = r.json();
        assertThat(b.get("channel").asText()).isEqualTo("EMAIL");
        assertThat(b.get("delivered").asBoolean()).isTrue();
        assertThat(b.get("to").asText()).isEqualTo("z•••@tm-owner.example");
        assertThat(b.get("messageId").asText()).matches("<test-[0-9a-f-]{36}@tm-mail\\.example>");
        assertNoSecrets(r);
        MimeMessage[] got = received();
        assertThat(got).hasSize(1);
        assertThat(got[0].getSubject()).isEqualTo("[TalentMatch] Test notification");
        assertThat(got[0].getHeader("Message-ID", null)).isEqualTo(b.get("messageId").asText());
        assertThat(got[0].getHeader("Auto-Submitted", null)).isEqualTo("auto-generated");
        assertThat(String.valueOf(got[0].getContent())).contains("Notifications: disabled");
        assertThat(countRows("feed_notification")).as("no row for a test").isZero();

        Res again = api.post("/api/notifications/test", "");
        JsonNode err = assertError(again, 429, "NOTIFICATION_TEST_RATE_LIMITED");
        int retryAfter = Integer.parseInt(again.header("Retry-After"));
        assertThat(retryAfter).isBetween(1, 30);
        assertThat(err.get("message").asText()).isEqualTo("A test email was sent less than 30 seconds ago. "
                + "Try again in " + retryAfter + (retryAfter == 1 ? " second." : " seconds."));
        assertThat(received()).hasSize(1);

        assertThat(output.getAll()).containsPattern("Email test notification outcome=sent smtpCode=none latencyMs=\\d+");
        assertThat(output.getAll()).doesNotContain(SMTP_PASSWORD).doesNotContain(TO).doesNotContain("Test notification");
    }

    @Test
    void testSendWithTheServerDownIs502NamingHostAndPort() {
        greenMailDown();
        Res r = api.post("/api/notifications/test", "");
        JsonNode b = assertError(r, 502, "NOTIFICATION_DELIVERY_FAILED");
        assertThat(b.get("message").asText()).isEqualTo("Couldn't connect to 127.0.0.1:" + SMTP_PORT
                + ": the connection was refused. Check SMTP_HOST and SMTP_PORT.");
        assertThat(b.get("error").asText()).isEqualTo("Bad Gateway");
        assertNoSecrets(r);
        // D12: the failed attempt used the slot
        greenMailUp();
        assertError(api.post("/api/notifications/test", ""), 429, "NOTIFICATION_TEST_RATE_LIMITED");
    }

    @Test
    void testSendWithAWrongPasswordIs502WithTheLoginText(CapturedOutput output) {
        wrongSmtpPassword();
        Res r = api.post("/api/notifications/test", "");
        JsonNode b = assertError(r, 502, "NOTIFICATION_DELIVERY_FAILED");
        assertThat(b.get("message").asText())
                .isEqualTo("The mail server rejected the login (535). Check SMTP_USERNAME and SMTP_PASSWORD.");
        assertNoSecrets(r);
        assertThat(output.getAll()).contains("Email test notification outcome=config smtpCode=535")
                .doesNotContain(SMTP_PASSWORD).doesNotContain("Zq-Some-Other-Pw").doesNotContain(SMTP_LOGIN);
        assertThat(dispatcher.state().paused()).as("a failed test doesn't pause the dispatcher").isFalse();
    }

    // ------------------------------------------------------------------ history and status

    @Test
    void historyIsPagedNewestFirstWithTitleAndCompany() {
        owner(20);
        queue("acme", 3);
        jdbc.update("UPDATE feed_notification SET created_at = created_at - (interval '1 minute' * "
                + "(SELECT count(*) FROM feed_notification f2 WHERE f2.id < feed_notification.id))");
        dispatcher.dispatchDue();
        List<String> idsNewestFirst = jdbc.queryForList(
                "SELECT id::text FROM feed_notification ORDER BY created_at DESC, id", String.class);

        Res all = api.get("/api/notifications");
        assertThat(all.status()).isEqualTo(200);
        JsonNode body = all.json();
        assertThat(body.get("totalElements").asInt()).isEqualTo(3);
        List<String> ids = new ArrayList<>();
        body.get("content").forEach(n -> ids.add(n.get("id").asText()));
        assertThat(ids).isEqualTo(idsNewestFirst);
        JsonNode first = body.get("content").get(0);
        assertThat(first.get("title").asText()).startsWith("Backend Engineer");
        assertThat(first.get("company").asText()).isEqualTo("Acme");
        assertThat(first.get("channel").asText()).isEqualTo("EMAIL");
        assertThat(first.get("status").asText()).isEqualTo("SENT");
        assertThat(first.get("attempts").asInt()).isOne();
        assertThat(first.get("score").asDouble()).isEqualTo(1.0);
        assertThat(first.has("lastError")).isTrue();
        assertThat(first.get("sentAt").isNull()).isFalse();
        assertThat(first.get("jobId").asText()).isNotBlank();

        Res page = api.get("/api/notifications?page=1&size=2");
        assertThat(page.json().get("content").size()).isOne();
        assertThat(page.json().get("totalPages").asInt()).isEqualTo(2);
        assertThat(page.json().get("content").get(0).get("id").asText()).isEqualTo(idsNewestFirst.get(2));
        assertThat(api.get("/api/notifications?page=9").json().get("content").size()).isZero();

        assertError(api.get("/api/notifications?page=-1"), 400, "INVALID_PARAMETER");
        assertError(api.get("/api/notifications?size=0"), 400, "INVALID_PARAMETER");
        assertError(api.get("/api/notifications?size=101"), 400, "INVALID_PARAMETER");
        assertNoSecrets(all);
    }

    @Test
    void feedStatusHasTheNotificationsSection() {
        owner(20);
        queue("acme", 2);
        JsonNode n = api.get("/api/feed/status").json().get("notifications");
        assertThat(n.get("enabled").asBoolean()).isTrue();
        assertThat(n.get("channel").asText()).isEqualTo("EMAIL");
        assertThat(n.get("configured").asBoolean()).isTrue();
        assertThat(n.get("minScore").asDouble()).isEqualTo(0.6);
        assertThat(n.get("pending").asInt()).isEqualTo(2);
        assertThat(n.get("failedLast24h").asInt()).isZero();
        assertThat(n.get("paused").asBoolean()).isFalse();
        assertThat(n.get("pausedUntil").isNull()).isTrue();
        assertThat(n.get("lastError").isNull()).isTrue();
        dispatcher.dispatchDue();
        assertThat(api.get("/api/feed/status").json().at("/notifications/pending").asInt()).isZero();
    }
}
