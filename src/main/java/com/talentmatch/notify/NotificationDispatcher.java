package com.talentmatch.notify;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sends queued notifications (§4.9): at-least-once delivery, backoff on transient failures, a channel
 * pause after a configuration error, expiry and the hourly cap.
 *
 * <p><b>One run</b> ({@link #dispatchDue()}):
 * <ol>
 *   <li>finish results whose status write failed earlier (never re-sent);</li>
 *   <li>expire PENDING rows older than {@code max-age} (always, even when disabled, unconfigured or
 *       paused);</li>
 *   <li>stop when notifications are disabled (rows wait and expire, D2), the channel isn't configured,
 *       or it is paused;</li>
 *   <li>then, one row at a time: check the hourly cap before claiming (nothing is claimed while the cap
 *       is reached, D3), claim the oldest due row (→ SENDING, {@code SKIP LOCKED}), load the job's facts,
 *       send <b>outside any transaction, holding no connection</b>, and write the result in one short
 *       statement:
 *       <ul>
 *         <li>ok → SENT, {@code sent_at}; a pause and the last error are cleared;</li>
 *         <li>TRANSIENT → attempts+1 and {@code next_attempt_at} by {@link NotificationBackoff}, or FAILED
 *             on the {@code max-attempts}-th failure (D1); the run stops (the server is struggling; the
 *             next sweep goes on);</li>
 *         <li>PERMANENT → FAILED at once;</li>
 *         <li>CONFIG → back to PENDING without counting an attempt, and the whole channel pauses for
 *             {@code config-error-pause}. The row stays due: the in-memory pause holds every claim, so a
 *             successful test send ({@link #resume()}, D11) or a restart sends it right away.</li>
 *       </ul></li>
 * </ol>
 *
 * <p><b>Locks.</b> Claims and expiry skip locked rows. Status writes run in a transaction bounded by a
 * {@value #FINISH_LOCK_WAIT_SECONDS}s {@code lock_timeout}; one that fails (a lock conflict, a database
 * blip) is kept in memory and written first thing on the next run, so the email is never sent again for
 * it. Nothing is retried in a loop and no wait is unbounded.
 *
 * <p><b>Threads.</b> One background thread ({@code notify-}), started by {@link #wake()}: after the feed
 * processor queued a row, by the feed sweep, at startup recovery, after the settings change and after a
 * successful test send. Background runs happen only while the feed scheduler runs (the
 * {@code talentmatch.feed.enabled} and {@code talentmatch.feed.scheduler.enabled} flags, read here as
 * plain properties so {@code notify} never imports {@code feed}, D10); tests call {@link #dispatchDue()}.
 * One run at a time per JVM (a concurrent call returns {@link DispatchReport#busy()}).
 *
 * <p><b>Pause</b> state is in memory (single instance): cleared by the first successful send, by
 * {@link #resume()} or by a restart. Logs hold ids, outcomes, SMTP codes and latencies only: never a
 * subject, body, address, username or password. Every outcome and the detection latency of each sent
 * email are recorded by {@link NotifyMetrics}.
 */
@Component
public class NotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);

    static final long FINISH_LOCK_WAIT_SECONDS = 2;
    static final Duration FINISH_LOCK_WAIT = Duration.ofSeconds(FINISH_LOCK_WAIT_SECONDS);
    static final Duration RECOVERY_RUN_WAIT = Duration.ofSeconds(60);
    static final Duration CAP_WINDOW = Duration.ofHours(1);
    static final int MAX_UNFINISHED = 1000;
    static final String NO_CONTENT = "job no longer available";
    static final String INTERNAL_ERROR = "internal error";

    private final NotificationRepository notifications;
    private final NotificationSettingsRepository settings;
    private final NotificationChannels channels;
    private final NotificationContentSource contentSource;
    private final NotifyProperties properties;
    private final TransactionTemplate tx;
    private final TaskExecutor executor;
    private final java.time.Clock clock;
    private final boolean backgroundEnabled;
    private final NotifyMetrics metrics;

    private final ReentrantLock runLock = new ReentrantLock();
    /** Results whose status write failed; guarded by {@link #runLock}. */
    private final Map<UUID, Finish> unfinished = new LinkedHashMap<>();

    private volatile boolean stopping;
    private volatile Instant pausedUntil;
    private volatile String lastError;
    private volatile Instant lastErrorAt;

    /** Records metrics into the global registry. */
    public NotificationDispatcher(NotificationRepository notifications, NotificationSettingsRepository settings,
                                  NotificationChannels channels, NotificationContentSource contentSource,
                                  NotifyProperties properties, PlatformTransactionManager transactionManager,
                                  TaskExecutor executor, java.time.Clock clock, boolean feedEnabled,
                                  boolean schedulerEnabled) {
        this(notifications, settings, channels, contentSource, properties, transactionManager, executor, clock,
                feedEnabled, schedulerEnabled, NotifyMetrics.global());
    }

    @Autowired
    public NotificationDispatcher(NotificationRepository notifications, NotificationSettingsRepository settings,
                                  NotificationChannels channels, NotificationContentSource contentSource,
                                  NotifyProperties properties, PlatformTransactionManager transactionManager,
                                  @Qualifier(NotifyConfig.DISPATCH_EXECUTOR) TaskExecutor executor,
                                  java.time.Clock clock,
                                  @Value("${talentmatch.feed.enabled:true}") boolean feedEnabled,
                                  @Value("${talentmatch.feed.scheduler.enabled:true}") boolean schedulerEnabled,
                                  NotifyMetrics metrics) {
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.notifications = notifications;
        this.settings = settings;
        this.channels = channels;
        this.contentSource = contentSource;
        this.properties = properties;
        this.tx = new TransactionTemplate(transactionManager);
        this.executor = executor;
        this.clock = clock;
        this.backgroundEnabled = feedEnabled && schedulerEnabled;
    }

    // ------------------------------------------------------------------ public API

    /**
     * The channel's in-memory state.
     *
     * @param paused      a configuration error paused sending and the pause hasn't run out
     * @param pausedUntil when the pause ends (null when not paused)
     * @param lastError   the sanitized text of the last failed send (cleared by a successful one)
     * @param lastErrorAt when it happened
     */
    public record ChannelState(boolean paused, Instant pausedUntil, String lastError, Instant lastErrorAt) {
    }

    /**
     * What one run did.
     *
     * @param busy          another run was in progress; this one did nothing
     * @param paused        sending is paused after a configuration error
     * @param deferredUntil the hourly cap stopped the run; sending resumes at this time
     */
    public record DispatchReport(boolean busy, int expired, int sent, int retried, int failed, boolean paused,
                                 Instant deferredUntil) {

        public static DispatchReport ofBusy() {
            return new DispatchReport(true, 0, 0, 0, 0, false, null);
        }

        static DispatchReport idle() {
            return new DispatchReport(false, 0, 0, 0, 0, false, null);
        }
    }

    /**
     * Starts a background run on the {@code notify-} thread, unless background runs are off (the feed
     * scheduler is off) or the application is stopping. A run already queued covers this wake-up.
     * Never throws, never blocks.
     */
    public void wake() {
        if (!backgroundEnabled || stopping) {
            return;
        }
        try {
            executor.execute(this::backgroundRun);
        } catch (TaskRejectedException e) {
            log.debug("Notification dispatcher wake-up rejected ({})", e.getClass().getName());
        }
    }

    /**
     * One run, on the calling thread (see the class comment). Must not be called inside a transaction:
     * SMTP never runs in one.
     *
     * @throws IllegalStateException when a transaction is active
     */
    public DispatchReport dispatchDue() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("NotificationDispatcher.dispatchDue() must not run inside a transaction");
        }
        if (stopping) {
            return DispatchReport.idle();
        }
        if (!runLock.tryLock()) {
            return DispatchReport.ofBusy();
        }
        try {
            return run();
        } finally {
            runLock.unlock();
        }
    }

    /**
     * Startup recovery (§6.3): rows left SENDING by a crash go back to PENDING and are sent again (at
     * least once, with the same Message-ID). Waits at most 60s for a run of this JVM to finish, so a row
     * being sent right now is never reset.
     *
     * @return the number of rows reset
     */
    public int recoverInterrupted() {
        boolean locked;
        try {
            locked = runLock.tryLock(RECOVERY_RUN_WAIT.toSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0;
        }
        if (!locked) {
            log.warn("Notification recovery skipped: a dispatcher run is still in progress");
            return 0;
        }
        try {
            flushUnfinished();
            Integer reset = tx.execute(status -> {
                notifications.limitLockWait(FINISH_LOCK_WAIT);
                return notifications.resetSending(new ArrayList<>(unfinished.keySet()));
            });
            return reset == null ? 0 : reset;
        } finally {
            runLock.unlock();
        }
    }

    public ChannelState state() {
        Instant until = pausedUntil;
        boolean paused = until != null && until.isAfter(clock.instant());
        return new ChannelState(paused, paused ? until : null, lastError, lastErrorAt);
    }

    /** Clears a configuration-error pause and the last error (after a successful test send, D11). */
    public void resume() {
        boolean wasPaused = pausedUntil != null;
        pausedUntil = null;
        lastError = null;
        lastErrorAt = null;
        if (wasPaused) {
            log.info("Email notifications resumed after a successful test send");
        }
    }

    @PreDestroy
    public void stop() {
        stopping = true;
    }

    // ------------------------------------------------------------------ one run

    private enum Result { SENT, RETRIED, FAILED, PAUSED }

    private DispatchReport run() {
        flushUnfinished();
        Instant now = clock.instant();
        int expired = notifications.expirePending(now, properties.maxAge());
        if (expired > 0) {
            log.info("Email notifications expired unsent: {}", expired);
            metrics.notification(Channel.EMAIL, "expired", expired);
        }
        int sent = 0;
        int retried = 0;
        int failed = 0;
        Instant deferredUntil = null;
        boolean paused = false;
        while (!stopping && !Thread.currentThread().isInterrupted()) {
            now = clock.instant();
            if (isPaused(now)) {
                paused = true;
                break;
            }
            NotificationSettingsRepository.Settings s = settings.find();
            if (!s.enabled()) {
                break;                                          // D2: rows wait (and expire)
            }
            Optional<Notifier> notifier = channels.notifier(s.channel());
            if (notifier.isEmpty() || !notifier.get().configured()) {
                break;                                          // §4.9 step 4: never claimed, expire after max-age
            }
            Instant windowStart = now.minus(CAP_WINDOW);
            if (notifications.countSentSince(windowStart) >= s.maxPerHour()) {
                Instant at = now;
                deferredUntil = notifications.oldestSentSince(windowStart).map(t -> t.plus(CAP_WINDOW))
                        .filter(t -> t.isAfter(at)).orElse(now.plus(Duration.ofMinutes(1)));
                log.debug("Email notifications: hourly cap {} reached; next send after {}", s.maxPerHour(),
                        deferredUntil);
                break;                                          // D3: checked before claiming, nothing written
            }
            Optional<NotificationRepository.Claimed> claimed = notifications.claimNext(now);
            if (claimed.isEmpty()) {
                break;
            }
            Result result = sendOne(claimed.get(), s, channels.notifier(claimed.get().channel()).orElse(notifier.get()));
            switch (result) {
                case SENT -> sent++;
                case FAILED -> failed++;
                case RETRIED -> retried++;
                case PAUSED -> paused = true;
            }
            if (result == Result.PAUSED || result == Result.RETRIED) {
                break;                                          // channel trouble: the next run goes on
            }
        }
        return new DispatchReport(false, expired, sent, retried, failed, paused, deferredUntil);
    }

    private Result sendOne(NotificationRepository.Claimed row, NotificationSettingsRepository.Settings s,
                           Notifier notifier) {
        UUID id = row.id();
        int attempts = row.attempts();
        NotificationMessage message;
        NotificationContent loaded;
        try {
            Optional<NotificationContent> content = contentSource.load(row.jobId());
            if (content.isEmpty()) {
                finish(id, Finish.fail(attempts, NO_CONTENT));
                log.info("Email notification id={} job={} outcome=permanent smtpCode=none latencyMs=0 ({})", id,
                        row.jobId(), NO_CONTENT);
                metrics.notification(row.channel(), "no_content", 1);
                return Result.FAILED;
            }
            loaded = content.get();
            message = NotificationFormatter.feed(id, loaded, row.score(), s.minScore(), properties.zoneId(),
                    properties.appBaseUrl(), clock.instant());
        } catch (RuntimeException e) {
            // A database blip or a bug: counted, so it is bounded by max-attempts.
            int a = attempts + 1;
            log.warn("Email notification id={} job={}: preparing the message failed ({})", id, row.jobId(),
                    NotifyErrors.describe(e));
            metrics.notification(row.channel(), "internal_error", 1);
            if (a >= properties.maxAttempts()) {
                finish(id, Finish.fail(a, INTERNAL_ERROR));
                return Result.FAILED;
            }
            finish(id, Finish.retry(a, backoff(clock.instant(), a), INTERNAL_ERROR));
            return Result.RETRIED;
        }

        long started = System.nanoTime();
        SmtpFailure failure = null;
        try {
            notifier.send(message);                             // no transaction, no connection held
        } catch (NotifyException e) {
            failure = e.failure();
        } catch (RuntimeException e) {
            failure = SmtpErrorClassifier.classify(e);
        }
        long latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        Instant done = clock.instant();

        if (failure == null) {
            finish(id, Finish.sent(attempts + 1, done));
            pausedUntil = null;
            lastError = null;
            lastErrorAt = null;
            log.info("Email notification id={} job={} outcome=sent smtpCode=none latencyMs={}", id, row.jobId(),
                    latencyMs);
            metrics.notification(row.channel(), "sent", 1);
            metrics.detectionLatency(done, loaded.postedAt(), loaded.firstSeenAt());
            return Result.SENT;
        }

        String text = failure.historyText();
        lastError = text;
        lastErrorAt = done;
        String code = failure.smtpCode() == null ? "none" : String.valueOf(failure.smtpCode());
        log.info("Email notification id={} job={} outcome={} smtpCode={} latencyMs={}", id, row.jobId(),
                failure.outcome(), code, latencyMs);
        metrics.notification(row.channel(), failure.outcome(), 1);
        switch (failure.kind()) {
            case CONFIG -> {
                Instant until = done.plus(properties.configErrorPause());
                pausedUntil = until;
                finish(id, Finish.release(done, text));
                log.warn("Email notifications paused until {} after a configuration error: {} (cause {})", until,
                        text, failure.causeClass());
                return Result.PAUSED;
            }
            case PERMANENT -> {
                finish(id, Finish.fail(attempts + 1, text));
                log.debug("Email notification id={} failed permanently (cause {})", id, failure.causeClass());
                return Result.FAILED;
            }
            default -> {
                int a = attempts + 1;
                if (a >= properties.maxAttempts()) {
                    finish(id, Finish.fail(a, text));
                    log.warn("Email notification id={} failed after {} attempts: {} (cause {})", id, a, text,
                            failure.causeClass());
                    return Result.FAILED;
                }
                finish(id, Finish.retry(a, backoff(done, a), text));
                return Result.RETRIED;
            }
        }
    }

    private boolean isPaused(Instant now) {
        Instant until = pausedUntil;
        return until != null && until.isAfter(now);
    }

    private Instant backoff(Instant now, int attemptsAfter) {
        double jitter = ThreadLocalRandom.current().nextDouble(-NotificationBackoff.MAX_JITTER,
                NotificationBackoff.MAX_JITTER);
        return NotificationBackoff.next(now, attemptsAfter, properties.retryInitial(), properties.retryMax(), jitter);
    }

    // ------------------------------------------------------------------ status writes

    /** A status write for a SENDING row. */
    private record Finish(Type type, int attempts, Instant at, String error) {

        enum Type { SENT, RETRY, FAIL, RELEASE }

        static Finish sent(int attempts, Instant sentAt) {
            return new Finish(Type.SENT, attempts, sentAt, null);
        }

        static Finish retry(int attempts, Instant nextAttemptAt, String error) {
            return new Finish(Type.RETRY, attempts, nextAttemptAt, error);
        }

        static Finish fail(int attempts, String error) {
            return new Finish(Type.FAIL, attempts, null, error);
        }

        static Finish release(Instant nextAttemptAt, String error) {
            return new Finish(Type.RELEASE, 0, nextAttemptAt, error);
        }
    }

    /** Writes the result; on failure keeps it for the next run (the email is never sent again for it). */
    private void finish(UUID id, Finish finish) {
        try {
            apply(id, finish);
        } catch (RuntimeException e) {
            if (unfinished.size() < MAX_UNFINISHED || unfinished.containsKey(id)) {
                unfinished.put(id, finish);
                log.warn("Email notification id={}: recording the result failed ({}); it is written on the next run",
                        id, NotifyErrors.describe(e));
            } else {
                log.warn("Email notification id={}: recording the result failed ({}) and too many results are "
                        + "pending; startup recovery will resend it", id, NotifyErrors.describe(e));
            }
        }
    }

    private void apply(UUID id, Finish f) {
        tx.executeWithoutResult(status -> {
            notifications.limitLockWait(FINISH_LOCK_WAIT);
            switch (f.type()) {
                case SENT -> notifications.markSent(id, f.attempts(), f.at());
                case RETRY -> notifications.retry(id, f.attempts(), f.at(), f.error());
                case FAIL -> notifications.fail(id, f.attempts(), f.error());
                case RELEASE -> notifications.release(id, f.at(), f.error());
            }
        });
    }

    /** Applies earlier results whose write failed; stops at the first failure (the database is still away). */
    private void flushUnfinished() {
        if (unfinished.isEmpty()) {
            return;
        }
        for (Map.Entry<UUID, Finish> entry : new ArrayList<>(unfinished.entrySet())) {
            try {
                apply(entry.getKey(), entry.getValue());
                unfinished.remove(entry.getKey());
            } catch (RuntimeException e) {
                log.warn("Email notifications: {} result(s) still unwritten ({})", unfinished.size(),
                        NotifyErrors.isLockConflict(e) ? "lock conflict" : NotifyErrors.describe(e));
                return;
            }
        }
    }

    private void backgroundRun() {
        try {
            dispatchDue();
        } catch (RuntimeException e) {
            log.warn("Notification dispatcher run failed ({}); the next sweep retries", NotifyErrors.describe(e));
        }
    }
}
