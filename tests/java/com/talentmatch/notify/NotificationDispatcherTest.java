package com.talentmatch.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.talentmatch.notify.NotificationDispatcher.DispatchReport;
import com.talentmatch.notify.NotificationRepository.Claimed;
import com.talentmatch.notify.NotificationSettingsRepository.Settings;
import com.talentmatch.notify.SmtpFailure.Kind;
import com.talentmatch.notify.SmtpFailure.Reason;
import com.talentmatch.support.MutableClock;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.task.TaskExecutor;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** §4.9 / §9.1 #14 dispatcher with a mocked sender and repositories, {@link MutableClock}. */
class NotificationDispatcherTest {

    private static final Instant T0 = Instant.parse("2026-10-11T10:00:00Z");
    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID JOB = UUID.fromString("00000000-0000-0000-0000-0000000000b1");

    private final NotificationRepository repo = mock(NotificationRepository.class);
    private final NotificationSettingsRepository settingsRepo = mock(NotificationSettingsRepository.class);
    private final NotificationContentSource content = mock(NotificationContentSource.class);
    private final Notifier notifier = mock(Notifier.class);
    private final TaskExecutor executor = mock(TaskExecutor.class);
    private final MutableClock clock = MutableClock.fixedAt(T0);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private NotificationDispatcher dispatcher;

    private static Settings settings(boolean enabled, int maxPerHour) {
        return new Settings(enabled, Channel.EMAIL, 0.6, maxPerHour);
    }

    private static NotifyException failure(Kind kind, Reason reason, Integer code) {
        return new NotifyException(new SmtpFailure(kind, code, reason, "test.Cause"));
    }

    private static NotificationContent job() {
        return new NotificationContent(JOB, "Backend Engineer", "Acme", "REMOTE", null, List.of("ZA"), T0, T0,
                "https://jobs.example/1", "summary", List.of("Java"), List.of(), List.of(), List.of("Lever"), false);
    }

    private NotificationDispatcher dispatcher(boolean background) {
        DefaultListableBeanFactory f = new DefaultListableBeanFactory();
        f.registerSingleton("email", notifier);
        return new NotificationDispatcher(repo, settingsRepo, new NotificationChannels(f.getBeanProvider(Notifier.class)),
                content, NotifyProperties.defaults(), mock(PlatformTransactionManager.class), executor, clock,
                background, true, new NotifyMetrics(meters));
    }

    private double count(String outcome) {
        Counter c = meters.find(NotifyMetrics.NOTIFICATIONS).tags("channel", "email", "outcome", outcome).counter();
        return c == null ? 0 : c.count();
    }

    private long latencySamples() {
        Timer t = meters.find(NotifyMetrics.DETECTION_LATENCY).timer();
        return t == null ? 0 : t.count();
    }

    @BeforeEach
    void setUp() {
        when(notifier.channel()).thenReturn(Channel.EMAIL);
        when(notifier.configured()).thenReturn(true);
        when(settingsRepo.find()).thenReturn(settings(true, 20));
        when(content.load(any())).thenReturn(Optional.of(job()));
        when(repo.claimNext(any())).thenReturn(Optional.empty());
        dispatcher = dispatcher(false);
    }

    private void oneRow(int attempts) {
        when(repo.claimNext(any())).thenReturn(Optional.of(new Claimed(ID, JOB, Channel.EMAIL, 0.8, attempts, T0)),
                Optional.empty());
    }

    // ------------------------------------------------------------------ success

    @Test
    void successMarksSentWithTheClockAndTheFeedKey() throws Exception {
        oneRow(0);
        DispatchReport r = dispatcher.dispatchDue();
        assertThat(r.sent()).isOne();
        assertThat(r.busy()).isFalse();
        ArgumentCaptor<NotificationMessage> m = ArgumentCaptor.forClass(NotificationMessage.class);
        verify(notifier).send(m.capture());
        assertThat(m.getValue().messageId()).isEqualTo("feed-" + ID);
        assertThat(m.getValue().subject()).isEqualTo("[TalentMatch] 80% match: Backend Engineer at Acme");
        InOrder o = inOrder(repo);
        o.verify(repo).limitLockWait(Duration.ofSeconds(2));
        o.verify(repo).markSent(ID, 1, T0);
        verify(repo, times(2)).claimNext(T0);
        assertThat(count("sent")).isEqualTo(1);
        assertThat(latencySamples()).isOne();
    }

    // ------------------------------------------------------------------ D1: transient backoff, 5 sends in total

    @Test
    void transientSequenceRetriesAfter1_2_4_8MinutesAndFailsOnTheFifthFailure() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        java.util.concurrent.atomic.AtomicBoolean failed = new java.util.concurrent.atomic.AtomicBoolean();
        when(repo.claimNext(any())).thenAnswer(i -> failed.get() ? Optional.empty()
                : Optional.of(new Claimed(ID, JOB, Channel.EMAIL, 0.8, attempts.get(), T0)));
        doAnswer(i -> {
            failed.set(true);
            return true;
        }).when(repo).fail(eq(ID), anyInt(), anyString());
        doThrow(failure(Kind.TRANSIENT, Reason.SMTP_TRANSIENT, 421)).when(notifier).send(any());
        doAnswer(i -> {
            attempts.set(i.getArgument(1));
            return true;
        }).when(repo).retry(eq(ID), anyInt(), any(), anyString());

        long[] minutes = {1, 2, 4, 8};
        for (int n = 1; n <= 4; n++) {
            Instant now = clock.instant();
            DispatchReport r = dispatcher.dispatchDue();
            assertThat(r.retried()).isOne();
            ArgumentCaptor<Instant> next = ArgumentCaptor.forClass(Instant.class);
            verify(repo).retry(eq(ID), eq(n), next.capture(), eq("SMTP 421 (transient)"));
            long expected = Duration.ofMinutes(minutes[n - 1]).toMillis();
            assertThat(Duration.between(now, next.getValue()).toMillis()).as("delay after send %d", n)
                    .isBetween((long) (expected * 0.9), (long) (expected * 1.1));
            clock.advance(Duration.between(now, next.getValue()));
        }
        DispatchReport last = dispatcher.dispatchDue();
        assertThat(last.failed()).isOne();
        verify(repo).fail(ID, 5, "SMTP 421 (transient)");
        verify(notifier, times(5)).send(any());
        assertThat(dispatcher.state().lastError()).isEqualTo("SMTP 421 (transient)");
        assertThat(count("transient")).as("every failed send, the last one included").isEqualTo(5);
        assertThat(count("sent")).isZero();
        assertThat(latencySamples()).isZero();
    }

    @Test
    void aTransientFailureStopsTheRunAndLeavesTheRestForTheNextSweep() throws Exception {
        UUID other = UUID.randomUUID();
        when(repo.claimNext(any())).thenReturn(Optional.of(new Claimed(ID, JOB, Channel.EMAIL, 0.8, 0, T0)),
                Optional.of(new Claimed(other, JOB, Channel.EMAIL, 0.8, 0, T0)));
        doThrow(failure(Kind.TRANSIENT, Reason.CONNECT_REFUSED, null)).when(notifier).send(any());
        DispatchReport r = dispatcher.dispatchDue();
        assertThat(r.retried()).isOne();
        verify(repo, times(1)).claimNext(any());
        verify(repo).retry(eq(ID), eq(1), any(), eq("connection refused"));
    }

    // ------------------------------------------------------------------ PERMANENT

    @Test
    void permanentFailsAfterOneAttemptAndTheRunGoesOn() throws Exception {
        oneRow(0);
        doThrow(failure(Kind.PERMANENT, Reason.RECIPIENT_REJECTED, 550)).when(notifier).send(any());
        DispatchReport r = dispatcher.dispatchDue();
        assertThat(r.failed()).isOne();
        verify(repo).fail(ID, 1, "SMTP 550 (rejected)");
        verify(repo, times(2)).claimNext(any());
        verify(repo, never()).retry(any(), anyInt(), any(), any());
        assertThat(count("permanent")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ CONFIG

    @Test
    void configReleasesWithoutCountingPausesAndBlocksClaims() throws Exception {
        oneRow(2);
        doThrow(failure(Kind.CONFIG, Reason.AUTH_FAILED, 535)).when(notifier).send(any());
        DispatchReport r = dispatcher.dispatchDue();
        assertThat(r.paused()).isTrue();
        String text = "SMTP login failed (535). Check SMTP_USERNAME and SMTP_PASSWORD.";
        verify(repo).release(ID, T0, text);                       // next_attempt_at = now, attempts untouched
        verify(repo, never()).retry(any(), anyInt(), any(), any());
        verify(repo, never()).fail(any(), anyInt(), any());
        assertThat(count("config")).isEqualTo(1);
        assertThat(dispatcher.state().paused()).isTrue();
        assertThat(dispatcher.state().pausedUntil()).isEqualTo(T0.plus(Duration.ofMinutes(15)));
        assertThat(dispatcher.state().lastError()).isEqualTo(text);

        clock.advance(Duration.ofMinutes(14));
        DispatchReport paused = dispatcher.dispatchDue();
        assertThat(paused.paused()).isTrue();
        verify(repo, times(1)).claimNext(any());                  // no claim while paused
        verify(repo, times(2)).expirePending(any(), any());       // expiry still runs

        clock.advance(Duration.ofMinutes(1).plusSeconds(1));
        assertThat(dispatcher.state().paused()).isFalse();
        dispatcher.dispatchDue();
        verify(repo, times(2)).claimNext(any());
    }

    @Test
    void resumeClearsThePauseAndTheLastError() throws Exception {
        oneRow(0);
        doThrow(failure(Kind.CONFIG, Reason.UNKNOWN_HOST, null)).when(notifier).send(any());
        dispatcher.dispatchDue();
        assertThat(dispatcher.state().paused()).isTrue();
        dispatcher.resume();
        assertThat(dispatcher.state()).isEqualTo(new NotificationDispatcher.ChannelState(false, null, null, null));
        dispatcher.dispatchDue();
        verify(repo, times(2)).claimNext(any());
    }

    @Test
    void aSuccessfulSendClearsTheLastError() throws Exception {
        // the transient failure ends run 1 right after its claim
        when(repo.claimNext(any())).thenReturn(Optional.of(new Claimed(ID, JOB, Channel.EMAIL, 0.8, 0, T0)),
                Optional.of(new Claimed(ID, JOB, Channel.EMAIL, 0.8, 1, T0)), Optional.empty());
        doThrow(failure(Kind.TRANSIENT, Reason.READ_TIMEOUT, null)).doNothing().when(notifier).send(any());
        dispatcher.dispatchDue();
        assertThat(dispatcher.state().lastError()).isEqualTo("read timeout");
        dispatcher.dispatchDue();
        assertThat(dispatcher.state().lastError()).isNull();
        verify(repo).markSent(ID, 2, T0);
    }

    // ------------------------------------------------------------------ D2 / not configured / expiry

    @Test
    void disabledUnconfiguredAndPausedNeverClaimButAlwaysExpire() throws Exception {
        when(repo.expirePending(any(), any())).thenReturn(3);
        when(settingsRepo.find()).thenReturn(settings(false, 20));
        assertThat(dispatcher.dispatchDue().expired()).isEqualTo(3);

        when(settingsRepo.find()).thenReturn(settings(true, 20));
        when(notifier.configured()).thenReturn(false);
        dispatcher.dispatchDue();

        verify(repo, never()).claimNext(any());
        verify(notifier, never()).send(any());
        verify(repo, times(2)).expirePending(T0, Duration.ofHours(24));
        assertThat(count("expired")).as("each run adds the rows it expired").isEqualTo(6);
    }

    // ------------------------------------------------------------------ D3: hourly cap before claiming

    @Test
    void hourlyCapIsCheckedBeforeClaiming() {
        when(settingsRepo.find()).thenReturn(settings(true, 3));
        when(repo.countSentSince(T0.minus(Duration.ofHours(1)))).thenReturn(3L);
        when(repo.oldestSentSince(T0.minus(Duration.ofHours(1)))).thenReturn(Optional.of(T0.minus(Duration.ofMinutes(50))));
        DispatchReport r = dispatcher.dispatchDue();
        assertThat(r.deferredUntil()).isEqualTo(T0.plus(Duration.ofMinutes(10)));
        verify(repo, never()).claimNext(any());
        verify(repo, never()).release(any(), any(), any());
    }

    @Test
    void settingsAreReReadPerRow() throws Exception {
        when(repo.claimNext(any())).thenAnswer(i -> Optional.of(new Claimed(UUID.randomUUID(), JOB, Channel.EMAIL, 0.8,
                0, T0)));
        // disabled after the first send
        when(settingsRepo.find()).thenReturn(settings(true, 20), settings(false, 20));
        assertThat(dispatcher.dispatchDue().sent()).isOne();
        verify(repo, times(1)).claimNext(any());

        // cap lowered after the first send
        when(settingsRepo.find()).thenReturn(settings(true, 20), settings(true, 1));
        when(repo.countSentSince(any())).thenReturn(0L, 1L);
        when(repo.oldestSentSince(any())).thenReturn(Optional.of(T0));
        DispatchReport r = dispatcher.dispatchDue();
        assertThat(r.sent()).isOne();
        assertThat(r.deferredUntil()).isEqualTo(T0.plus(Duration.ofHours(1)));
        verify(repo, times(2)).claimNext(any());
    }

    // ------------------------------------------------------------------ content problems

    @Test
    void missingContentFailsTheRowWithoutSending() throws Exception {
        oneRow(0);
        when(content.load(JOB)).thenReturn(Optional.empty());
        dispatcher.dispatchDue();
        verify(repo).fail(ID, 0, "job no longer available");
        verify(notifier, never()).send(any());
        assertThat(count("no_content")).isEqualTo(1);
        assertThat(count("sent")).isZero();
    }

    @Test
    void contentErrorCountsAnAttemptAndIsBounded() throws Exception {
        oneRow(0);
        when(content.load(JOB)).thenThrow(new DataAccessResourceFailureException("db down"));
        assertThat(dispatcher.dispatchDue().retried()).isOne();
        verify(repo).retry(eq(ID), eq(1), any(), eq("internal error"));
        oneRow(4);
        assertThat(dispatcher.dispatchDue().failed()).isOne();
        verify(repo).fail(ID, 5, "internal error");
        verify(notifier, never()).send(any());
        assertThat(count("internal_error")).as("one per counted attempt").isEqualTo(2);
    }

    @Test
    void anUnexpectedRuntimeExceptionFromTheNotifierIsClassified() throws Exception {
        oneRow(0);
        doThrow(new IllegalStateException("boom owner@example.com")).when(notifier).send(any());
        dispatcher.dispatchDue();
        verify(repo).retry(eq(ID), eq(1), any(), eq("send failed (IllegalStateException)"));
    }

    // ------------------------------------------------------------------ unfinished results

    @Test
    void aFailedFinishIsAppliedOnTheNextRunAndNeverResent() throws Exception {
        oneRow(0);
        when(repo.markSent(ID, 1, T0)).thenThrow(new CannotAcquireLockException("lock timeout"))
                .thenThrow(new CannotAcquireLockException("still locked")).thenReturn(true);
        assertThat(dispatcher.dispatchDue().sent()).isOne();
        verify(notifier, times(1)).send(any());

        // still failing: recovery must not reset the row whose result is known
        dispatcher.recoverInterrupted();
        verify(repo).resetSending(List.of(ID));

        dispatcher.dispatchDue();                              // flush succeeds
        verify(repo, times(3)).markSent(ID, 1, T0);
        verify(notifier, times(1)).send(any());
        dispatcher.recoverInterrupted();
        verify(repo).resetSending(List.of());
    }

    // ------------------------------------------------------------------ step 11 metrics

    @Test
    void sentLatencyUsesPostedAtElseFirstSeenAt() throws Exception {
        clock.advance(Duration.ofMinutes(10));
        when(repo.claimNext(any())).thenReturn(Optional.of(new Claimed(ID, JOB, Channel.EMAIL, 0.8, 0, T0)),
                Optional.of(new Claimed(UUID.randomUUID(), JOB, Channel.EMAIL, 0.8, 0, T0)), Optional.empty());
        when(content.load(JOB)).thenReturn(
                Optional.of(new NotificationContent(JOB, "Backend Engineer", "Acme", "REMOTE", null, List.of("ZA"),
                        T0.minusSeconds(120), T0, "https://jobs.example/1", "s", List.of(), List.of(), List.of(),
                        List.of("Lever"), false)),
                Optional.of(new NotificationContent(JOB, "Backend Engineer", "Acme", "REMOTE", null, List.of("ZA"),
                        null, T0, "https://jobs.example/1", "s", List.of(), List.of(), List.of(),
                        List.of("Lever"), false)));
        assertThat(dispatcher.dispatchDue().sent()).isEqualTo(2);
        Timer t = meters.find(NotifyMetrics.DETECTION_LATENCY).timer();
        assertThat(t.count()).isEqualTo(2);
        // 12 minutes (posted 2 min before T0) + 10 minutes (first seen at T0), sent at T0 + 10 min
        assertThat(t.totalTime(TimeUnit.SECONDS)).isEqualTo(22 * 60);
        assertThat(count("sent")).isEqualTo(2);
    }

    @Test
    void theTenArgConstructorStillWorks() {
        DefaultListableBeanFactory f = new DefaultListableBeanFactory();
        f.registerSingleton("email", notifier);
        NotificationDispatcher d = new NotificationDispatcher(repo, settingsRepo,
                new NotificationChannels(f.getBeanProvider(Notifier.class)), content, NotifyProperties.defaults(),
                mock(PlatformTransactionManager.class), executor, clock, false, true);
        assertThat(d.dispatchDue().busy()).isFalse();
    }

    @Test
    void aBlockedSendIsNotCounted() {
        when(settingsRepo.find()).thenReturn(settings(false, 20));
        dispatcher.dispatchDue();
        assertThat(meters.find(NotifyMetrics.NOTIFICATIONS).meters()).isEmpty();
    }

    // ------------------------------------------------------------------ guards

    @Test
    void refusesToRunInsideATransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(dispatcher::dispatchDue).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("transaction");
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        verify(repo, never()).claimNext(any());
    }

    @Test
    void aConcurrentRunIsBusy() throws Exception {
        oneRow(0);
        List<DispatchReport> nested = new ArrayList<>();
        doAnswer(i -> {
            nested.add(CompletableFuture.supplyAsync(dispatcher::dispatchDue).get(10, TimeUnit.SECONDS));
            return null;
        }).when(notifier).send(any());
        dispatcher.dispatchDue();
        assertThat(nested).hasSize(1);
        assertThat(nested.get(0).busy()).isTrue();
    }

    @Test
    void stoppedDispatcherDoesNothing() {
        dispatcher.stop();
        dispatcher.dispatchDue();
        verify(repo, never()).expirePending(any(), any());
    }

    @Test
    void wakeIsANoOpWithoutBackgroundRuns() {
        dispatcher.wake();
        verify(executor, never()).execute(any());
        NotificationDispatcher bg = dispatcher(true);
        bg.wake();
        verify(executor).execute(any());
        bg.stop();
        bg.wake();
        verify(executor, times(1)).execute(any());
    }
}
