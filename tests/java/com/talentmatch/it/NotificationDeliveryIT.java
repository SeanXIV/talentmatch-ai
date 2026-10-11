package com.talentmatch.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.talentmatch.notify.NotificationDispatcher.DispatchReport;
import com.talentmatch.support.AbstractNotifyIT;
import com.talentmatch.support.Api.Res;
import jakarta.mail.internet.MimeMessage;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * §9.2 items 3 and 14 (GreenMail): delivery, backoff, CONFIG pause, expiry, cap, D2, recovery, health,
 * status, bounded lock waits, and no secrets or job text in the logs.
 */
@ExtendWith(OutputCaptureExtension.class)
class NotificationDeliveryIT extends AbstractNotifyIT {

    private static final String BACKEND_DESC =
            "You will build Java services and write SQL every day. Kubernetes is advantageous.";
    private static final String LOGIN_FAILED = "SMTP login failed (535). Check SMTP_USERNAME and SMTP_PASSWORD.";

    @Autowired
    private ApplicationContext context;

    /** No line of our own logging may contain a credential, an address, the subject or job text. */
    private static void assertNoLeaks(CapturedOutput output, String... more) {
        List<String> forbidden = new ArrayList<>(List.of(SMTP_PASSWORD, SMTP_LOGIN, TO, FROM_ADDRESS, "zqalerts",
                "zqowner", "% match:", "Kubernetes is advantageous", "You will build", "Ada Lovelace",
                "ada@example.com", "Zq-Some-Other-Pw"));
        forbidden.addAll(List.of(more));
        output.getAll().lines()
                .filter(l -> !l.toLowerCase().contains("greenmail") && !l.contains("c.i.g."))
                .forEach(line -> {
                    for (String f : forbidden) {
                        assertThat(line).as("log line leaks '%s'", f).doesNotContain(f);
                    }
                });
    }

    private JsonNode health() {
        Res r = api.get("/actuator/health");
        assertThat(r.status()).isEqualTo(200);
        return r.json();
    }

    private JsonNode statusNotifications() {
        Res r = api.get("/api/feed/status");
        assertThat(r.status()).isEqualTo(200);
        return r.json().get("notifications");
    }

    // ------------------------------------------------------------------ 3. happy path

    @Test
    void happyPathSendsExactlyOneMessageWithTheExactHeaders(CapturedOutput output) throws Exception {
        AtomicBoolean txDuringSend = new AtomicBoolean();
        doAnswer(i -> {
            txDuringSend.set(TransactionSynchronizationManager.isActualTransactionActive());
            return i.callRealMethod();
        }).when(emailNotifier).send(any());

        skills("Java", "SQL", "Kubernetes", "Python");
        profile("Java", "SQL");
        preferences("""
                {"targetTitles": ["Backend Engineer"], "excludedTitleKeywords": ["Sales"],
                 "regions": {"countries": ["ZA"], "includeRemote": true, "remoteScope": "ELIGIBLE_FROM_COUNTRIES"}}""");
        notifications(true, 0.6);
        UUID s = lever("acme", "Acme");
        serve("acme", old("a", "Backend Engineer", JAVA_SQL), old("b", "Data Engineer", JAVA_SQL),
                old("c", "Platform Engineer", JAVA_SQL));
        pollOk(s);
        processor.processDue();
        assertThat(dispatcher.dispatchDue().sent()).as("baseline jobs never notify").isZero();

        serve("acme", old("a", "Backend Engineer", JAVA_SQL), old("b", "Data Engineer", JAVA_SQL),
                old("c", "Platform Engineer", JAVA_SQL), fresh("n", "Backend Engineer", BACKEND_DESC));
        jdbc.update("UPDATE feed_source SET last_polled_at = NULL WHERE id = ?", s);
        pollOk(s);
        processor.processDue();
        UUID job = jobOf(s, "n");
        assertThat(ownerScore(job)).isCloseTo(0.8, org.assertj.core.api.Assertions.within(1e-9));
        Map<String, Object> pending = notificationForJob(job);
        assertThat(pending.get("status")).isEqualTo("PENDING");
        UUID id = (UUID) pending.get("id");

        Instant before = Instant.now();
        DispatchReport r = dispatcher.dispatchDue();
        assertThat(r.sent()).isOne();
        assertThat(txDuringSend.get()).as("SMTP never runs inside a DB transaction").isFalse();

        Map<String, Object> row = notification(id);
        assertThat(row.get("status")).isEqualTo("SENT");
        assertThat(row.get("attempts")).isEqualTo(1);
        assertThat(row.get("last_error")).isNull();
        assertThat(instant(row.get("sent_at"))).isBetween(before.minusSeconds(1), Instant.now().plusSeconds(1));

        MimeMessage[] got = received();
        assertThat(got).hasSize(1);
        MimeMessage m = got[0];
        assertThat(m.getSubject()).isEqualTo("[TalentMatch] 80% match: Backend Engineer at Acme");
        assertThat(m.getContentType()).isEqualToIgnoringCase("text/plain; charset=UTF-8");
        assertThat(m.getHeader("Message-ID", null)).isEqualTo("<feed-" + id + "@" + FROM_DOMAIN + ">");
        assertThat(m.getHeader("Auto-Submitted", null)).isEqualTo("auto-generated");
        assertThat(m.getHeader("X-Auto-Response-Suppress", null)).isEqualTo("All");
        assertThat(m.getFrom()[0].toString()).contains(FROM_ADDRESS);
        assertThat(m.getAllRecipients()).extracting(Object::toString).containsExactly(TO);
        String body = String.valueOf(m.getContent());
        assertThat(body).contains("Matched: Java, SQL").contains("Apply: https://jobs.lever.co/acme/n")
                .contains("Details: http://127.0.0.1:8080/api/feed/jobs/" + job)
                .doesNotContain("Kubernetes is advantageous").doesNotContain("You will build")
                .doesNotContain("Ada Lovelace").doesNotContain("ada@example.com");

        // a second run sends nothing more
        assertThat(dispatcher.dispatchDue().sent()).isZero();
        assertThat(received()).hasSize(1);

        assertThat(output.getAll()).containsPattern("Email notification id=" + id + " job=" + job
                + " outcome=sent smtpCode=none latencyMs=\\d+");
        assertNoLeaks(output);
    }

    @Test
    void noJavaMailSenderBeanAndNoMailHealthDespiteSpringMailHost() {
        assertThat(context.getBeansOfType(JavaMailSender.class)).isEmpty();
        JsonNode h = health();
        assertThat(h.at("/components/mail").isMissingNode()).isTrue();
        assertThat(h.at("/components/feed/details/notifications/configured").asBoolean()).isTrue();
    }

    // ------------------------------------------------------------------ connect failure → backoff → recovery

    @Test
    void connectFailureBacksOffOneMinuteThenSendsWhenTheServerIsBack(CapturedOutput output) throws Exception {
        owner(20);
        queue("acme", 1);
        UUID id = notificationIds().get(0);
        greenMailDown();
        Instant t0 = Instant.now();
        DispatchReport r = dispatcher.dispatchDue();
        assertThat(r.retried()).isOne();
        Map<String, Object> row = notification(id);
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("attempts")).isEqualTo(1);
        assertThat(row.get("last_error")).isEqualTo("connection refused");
        Duration delay = Duration.between(t0, instant(row.get("next_attempt_at")));
        assertThat(delay).isBetween(Duration.ofSeconds(53), Duration.ofSeconds(68));
        assertThat(dispatcher.state().paused()).as("a transient failure doesn't pause").isFalse();
        assertThat(statusNotifications().get("lastError").asText()).isEqualTo("connection refused");
        assertThat(health().at("/components/feed/details/reasons").toString()).doesNotContain("EMAIL_CONFIG");

        // not due yet: nothing is claimed
        greenMailUp();
        dispatcher.dispatchDue();
        assertThat(received()).isEmpty();

        makeAllDue();
        assertThat(dispatcher.dispatchDue().sent()).isOne();
        row = notification(id);
        assertThat(row.get("status")).isEqualTo("SENT");
        assertThat(row.get("attempts")).isEqualTo(2);
        assertThat(row.get("last_error")).isNull();
        assertThat(received()).hasSize(1);
        assertThat(dispatcher.state().lastError()).isNull();
        assertThat(output.getAll()).contains("outcome=transient smtpCode=none");
        assertNoLeaks(output);
    }

    // ------------------------------------------------------------------ wrong password → CONFIG pause

    @Test
    void wrongPasswordPausesWithoutCountingAndAGoodTestSendResumes(CapturedOutput output) throws Exception {
        owner(20);
        queue("acme", 1);
        UUID id = notificationIds().get(0);
        wrongSmtpPassword();

        Instant t0 = Instant.now();
        DispatchReport r = dispatcher.dispatchDue();
        assertThat(r.paused()).isTrue();
        Map<String, Object> row = notification(id);
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("attempts")).isEqualTo(0);
        assertThat(row.get("last_error")).isEqualTo(LOGIN_FAILED);
        assertThat(instant(row.get("next_attempt_at"))).isBetween(t0.minusSeconds(1), Instant.now().plusSeconds(1));
        assertThat(dispatcher.state().paused()).isTrue();
        assertThat(dispatcher.state().pausedUntil()).isAfter(t0.plus(Duration.ofMinutes(14)));
        verify(emailNotifier, times(1)).send(any());

        // paused: a second run opens no SMTP connection although the row is due
        assertThat(dispatcher.dispatchDue().paused()).isTrue();
        verify(emailNotifier, times(1)).send(any());
        assertThat(received()).isEmpty();

        // health: feed DEGRADED with EMAIL_CONFIG, overall UP
        JsonNode h = health();
        assertThat(h.get("status").asText()).isEqualTo("UP");
        assertThat(h.at("/components/feed/status").asText()).isEqualTo("DEGRADED");
        assertThat(h.at("/components/feed/details/reasons").toString()).contains("EMAIL_CONFIG")
                .doesNotContain("NOTIFICATIONS_FAILED");
        assertThat(h.at("/components/feed/details/notifications/paused").asBoolean()).isTrue();
        assertThat(h.toString()).doesNotContain(SMTP_PASSWORD).doesNotContain(SMTP_LOGIN).doesNotContain(TO);

        // status shows the sanitized error
        JsonNode n = statusNotifications();
        assertThat(n.get("paused").asBoolean()).isTrue();
        assertThat(n.get("pausedUntil").isNull()).isFalse();
        assertThat(n.get("lastError").asText()).isEqualTo(LOGIN_FAILED);
        assertThat(n.get("pending").asInt()).isOne();

        // the owner fixes the password; a test send works while paused and lifts the pause (D11)
        GREENMAIL.getUserManager().getUserByEmail(TO).setPassword(SMTP_PASSWORD);
        Res test = api.post("/api/notifications/test", "");
        assertThat(test.status()).as("%s", test).isEqualTo(200);
        assertThat(dispatcher.state().paused()).isFalse();
        assertThat(dispatcher.state().lastError()).isNull();
        assertThat(dispatcher.dispatchDue().sent()).as("the released row is due at once").isOne();
        assertThat(notification(id).get("attempts")).isEqualTo(1);
        assertThat(received()).hasSize(2);
        assertThat(health().at("/components/feed/details/reasons").toString()).doesNotContain("EMAIL_CONFIG");

        assertThat(output.getAll()).contains("outcome=config smtpCode=535");
        assertNoLeaks(output);
    }

    // ------------------------------------------------------------------ expiry (D4, D8)

    @Test
    void rowsOlderThanMaxAgeExpireWithoutSmtpAndDoNotDegradeHealth() throws Exception {
        owner(20);
        queue("acme", 2);
        List<UUID> ids = notificationIds();
        jdbc.update("UPDATE feed_notification SET created_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minus(Duration.ofHours(25))), ids.get(0));
        DispatchReport r = dispatcher.dispatchDue();
        assertThat(r.expired()).isOne();
        assertThat(r.sent()).isOne();
        assertThat(notification(ids.get(0)).get("status")).isEqualTo("FAILED");
        assertThat(notification(ids.get(0)).get("last_error")).isEqualTo("expired");
        assertThat(notification(ids.get(0)).get("attempts")).isEqualTo(0);
        verify(emailNotifier, times(1)).send(any());
        assertThat(received()).hasSize(1);

        assertThat(health().at("/components/feed/details/reasons").toString()).doesNotContain("NOTIFICATIONS_FAILED");
        assertThat(statusNotifications().get("failedLast24h").asInt()).isOne();
    }

    @Test
    void expiryRunsEvenWhileDisabled() {
        owner(20);
        queue("acme", 1);
        notifications(false, 0.6);
        jdbc.update("UPDATE feed_notification SET created_at = ?",
                Timestamp.from(Instant.now().minus(Duration.ofHours(25))));
        assertThat(dispatcher.dispatchDue().expired()).isOne();
        assertThat(received()).isEmpty();
    }

    @Test
    void aRecentFailureDegradesHealthWithNotificationsFailed() {
        owner(20);
        queue("acme", 1);
        jdbc.update("UPDATE feed_notification SET status = 'FAILED', attempts = 1, last_error = 'SMTP 550 (rejected)'");
        JsonNode h = health();
        assertThat(h.get("status").asText()).isEqualTo("UP");
        assertThat(h.at("/components/feed/status").asText()).isEqualTo("DEGRADED");
        assertThat(h.at("/components/feed/details/reasons").toString()).contains("NOTIFICATIONS_FAILED");
        assertThat(h.at("/components/feed/details/notifications/failedLastHour").asInt()).isOne();
    }

    // ------------------------------------------------------------------ hourly cap (D3)

    @Test
    void hourlyCapHoldsTheSecondUntilTheWindowEnds() {
        owner(1);
        queue("acme", 2);
        DispatchReport r = dispatcher.dispatchDue();
        assertThat(r.sent()).isOne();
        assertThat(r.deferredUntil()).isAfter(Instant.now().plus(Duration.ofMinutes(59)));
        assertThat(pendingNotifications()).isOne();
        assertThat(jdbc.queryForObject("SELECT attempts FROM feed_notification WHERE status = 'PENDING'",
                Integer.class)).as("nothing claimed, nothing counted").isZero();
        assertThat(dispatcher.dispatchDue().sent()).isZero();

        jdbc.update("UPDATE feed_notification SET sent_at = ? WHERE status = 'SENT'",
                Timestamp.from(Instant.now().minus(Duration.ofMinutes(61))));
        assertThat(dispatcher.dispatchDue().sent()).isOne();
        assertThat(received()).hasSize(2);
    }

    // ------------------------------------------------------------------ D2 and per-row settings

    @Test
    void disablingAfterQueueingSendsNothing() {
        owner(20);
        queue("acme", 1);
        Res put = api.put("/api/notifications/settings", "{\"enabled\": false, \"minScore\": 0.6, \"maxPerHour\": 20}");
        assertThat(put.status()).isEqualTo(200);
        dispatcher.dispatchDue();
        assertThat(received()).isEmpty();
        assertThat(pendingNotifications()).isOne();
        assertThat(jdbc.queryForObject("SELECT attempts FROM feed_notification", Integer.class)).isZero();
    }

    @Test
    void settingsAreReReadPerRowWithinOneRun() throws Exception {
        owner(20);
        queue("acme", 3);
        AtomicBoolean first = new AtomicBoolean(true);
        doAnswer(i -> {
            if (first.getAndSet(false)) {
                notifications(false, 0.6);                    // the owner turns it off mid-run
            }
            return i.callRealMethod();
        }).when(emailNotifier).send(any());
        assertThat(dispatcher.dispatchDue().sent()).isOne();
        assertThat(pendingNotifications()).isEqualTo(2);
        assertThat(received()).hasSize(1);
    }

    // ------------------------------------------------------------------ recovery (at least once, same Message-ID)

    @Test
    void recoveryResendsAnInterruptedRowWithTheSameMessageId() throws Exception {
        owner(20);
        queue("acme", 1);
        UUID id = notificationIds().get(0);
        assertThat(dispatcher.dispatchDue().sent()).isOne();
        // simulate a crash between the server's 250 and the SENT update
        jdbc.update("UPDATE feed_notification SET status = 'SENDING', sent_at = NULL WHERE id = ?", id);
        recovery.recover();
        assertThat(notification(id).get("status")).isEqualTo("PENDING");
        assertThat(dispatcher.dispatchDue().sent()).isOne();
        MimeMessage[] got = received();
        assertThat(got).hasSize(2);
        String expected = "<feed-" + id + "@" + FROM_DOMAIN + ">";
        assertThat(got[0].getHeader("Message-ID", null)).isEqualTo(expected);
        assertThat(got[1].getHeader("Message-ID", null)).isEqualTo(expected);
    }

    // ------------------------------------------------------------------ bounded lock waits

    @Test
    void aLockedRowBoundsTheFinishWaitAndTheResultIsWrittenLaterWithoutResending() throws Exception {
        owner(20);
        queue("acme", 1);
        UUID id = notificationIds().get(0);
        Connection holder = rawConnection();
        try {
            holder.setAutoCommit(false);
            doAnswer(i -> {
                try (PreparedStatement ps = holder.prepareStatement(
                        "SELECT id FROM feed_notification WHERE id = ? FOR UPDATE")) {
                    ps.setObject(1, id);
                    ps.executeQuery().close();
                }
                return i.callRealMethod();
            }).when(emailNotifier).send(any());

            long t0 = System.nanoTime();
            DispatchReport r = dispatcher.dispatchDue();
            Duration took = Duration.ofNanos(System.nanoTime() - t0);
            assertThat(r.sent()).isOne();
            assertThat(took).as("the finish waits at most ~2s for the lock").isLessThan(Duration.ofSeconds(10));
            assertThat(notification(id).get("status")).as("result not yet written").isEqualTo("SENDING");
            assertThat(received()).hasSize(1);
        } finally {
            holder.rollback();
            holder.close();
        }
        dispatcher.dispatchDue();
        assertThat(notification(id).get("status")).isEqualTo("SENT");
        assertThat(notification(id).get("attempts")).isEqualTo(1);
        verify(emailNotifier, times(1)).send(any());
        assertThat(received()).hasSize(1);

        // recovery after the flush resets nothing
        recovery.recover();
        assertThat(notification(id).get("status")).isEqualTo("SENT");
    }

    // ------------------------------------------------------------------ source deleted while sending

    @Test
    void deletingTheSourceWhileSendingIsHarmless() throws Exception {
        owner(20);
        UUID s = queue("acme", 2);
        AtomicBoolean first = new AtomicBoolean(true);
        doAnswer(i -> {
            Object result = i.callRealMethod();
            if (first.getAndSet(false)) {
                Res del = api.delete("/api/feed/sources/" + s);
                assertThat(del.status()).as("%s", del).isBetween(200, 204);
            }
            return result;
        }).when(emailNotifier).send(any());
        DispatchReport r = dispatcher.dispatchDue();
        assertThat(r.sent()).isOne();
        assertThat(countRows("feed_notification")).isZero();
        assertThat(received()).hasSize(1);
        assertThat(dispatcher.dispatchDue().sent()).isZero();
    }
}
