package com.talentmatch.notify;

import com.talentmatch.service.exception.NotificationDeliveryException;
import com.talentmatch.service.exception.NotificationNotConfiguredException;
import com.talentmatch.service.exception.NotificationTestRateLimitedException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * {@code POST /api/notifications/test} (§5.4): sends the fixed test email synchronously on the request
 * thread, bounded by the SMTP timeouts, outside any transaction. It creates no {@code feed_notification}
 * row, doesn't count toward the hourly cap, and works while notifications are disabled or paused.
 *
 * <ul>
 *   <li>Not configured → 409 (does not use the rate-limit slot).</li>
 *   <li>At most one attempt per {@code test-interval}; failed attempts count too, so a bad password
 *       can't be hammered against the server (D12) → 429 with {@code Retry-After}.</li>
 *   <li>Failure → 502 with a message built from the classified error (host:port and the SMTP code,
 *       never the server's reply text or a credential).</li>
 *   <li>Success clears a configuration-error pause and wakes the dispatcher (D11).</li>
 * </ul>
 */
@Service
public class NotificationTestService {

    private static final Logger log = LoggerFactory.getLogger(NotificationTestService.class);

    private final EmailNotifier email;
    private final NotificationSettingsRepository settings;
    private final NotificationDispatcher dispatcher;
    private final NotifyProperties properties;
    private final Clock clock;
    private final AtomicReference<Instant> lastTest = new AtomicReference<>();

    public NotificationTestService(EmailNotifier email, NotificationSettingsRepository settings,
                                   NotificationDispatcher dispatcher, NotifyProperties properties, Clock clock) {
        this.email = email;
        this.settings = settings;
        this.dispatcher = dispatcher;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * @param to        the recipient, masked
     * @param messageId the Message-ID of the test email ({@code <test-…@domain>})
     */
    public record TestResult(Channel channel, boolean delivered, String to, String messageId) {
    }

    public TestResult sendTest() {
        if (!email.configured()) {
            throw new NotificationNotConfiguredException();
        }
        Instant now = clock.instant();
        reserveSlot(now);
        NotificationSettingsRepository.Settings s = settings.find();
        NotificationMessage message = NotificationFormatter.test(UUID.randomUUID(), s.enabled(), s.minScore(),
                s.maxPerHour(), properties.zoneId(), now);
        long started = System.nanoTime();
        try {
            email.send(message);
        } catch (NotifyException e) {
            SmtpFailure failure = e.failure();
            log.info("Email test notification outcome={} smtpCode={} latencyMs={} (cause {})", failure.outcome(),
                    failure.smtpCode() == null ? "none" : failure.smtpCode(), elapsedMs(started), failure.causeClass());
            throw new NotificationDeliveryException(failure.userMessage(email.host(), email.port(),
                    properties.email().connectionTimeout()));
        }
        log.info("Email test notification outcome=sent smtpCode=none latencyMs={}", elapsedMs(started));
        dispatcher.resume();
        dispatcher.wake();
        return new TestResult(Channel.EMAIL, true, email.maskedTo(), email.rfcMessageId(message.messageId()));
    }

    /** Takes the rate-limit slot before sending (compare-and-set), or throws 429. */
    private void reserveSlot(Instant now) {
        Duration interval = properties.testInterval();
        while (true) {
            Instant last = lastTest.get();
            if (last != null) {
                Instant allowedAt = last.plus(interval);
                if (now.isBefore(allowedAt)) {
                    long millis = Duration.between(now, allowedAt).toMillis();
                    int seconds = (int) Math.max(1, (millis + 999) / 1000);
                    throw new NotificationTestRateLimitedException(seconds, (int) Math.max(1, interval.toSeconds()));
                }
            }
            if (lastTest.compareAndSet(last, now)) {
                return;
            }
        }
    }

    private static long elapsedMs(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }
}
