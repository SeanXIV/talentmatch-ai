package com.talentmatch.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.talentmatch.notify.NotificationChannels;
import com.talentmatch.notify.NotificationContentSource;
import com.talentmatch.notify.NotificationDispatcher;
import com.talentmatch.notify.NotificationRepository;
import com.talentmatch.notify.NotificationSettingsRepository;
import com.talentmatch.notify.NotifyProperties;
import com.talentmatch.support.AbstractNotifyIT;
import jakarta.mail.internet.MimeMessage;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;

/** §9.2 item 14 / plan IT 8: concurrent dispatchers and processor → exactly one message per notification. */
class NotificationConcurrencyIT extends AbstractNotifyIT {

    @Autowired
    private NotificationRepository notificationRepository;
    @Autowired
    private NotificationSettingsRepository settingsRepository;
    @Autowired
    private NotificationChannels channels;
    @Autowired
    private NotificationContentSource contentSource;
    @Autowired
    private NotifyProperties notifyProperties;
    @Autowired
    private PlatformTransactionManager transactionManager;

    /** A second dispatcher over the same beans: its own run lock, like a second instance. */
    private NotificationDispatcher secondInstance() {
        return new NotificationDispatcher(notificationRepository, settingsRepository, channels, contentSource,
                notifyProperties, transactionManager, new SyncTaskExecutor(), Clock.systemUTC(), false, false);
    }

    private int openRows() {
        return jdbc.queryForObject("SELECT count(*) FROM feed_notification WHERE status IN ('PENDING','SENDING')",
                Integer.class);
    }

    private static Set<String> messageIds(MimeMessage[] messages) throws Exception {
        Set<String> ids = new HashSet<>();
        for (MimeMessage m : messages) {
            ids.add(m.getHeader("Message-ID", null));
        }
        return ids;
    }

    @Test
    void twoDispatchersOverTenRowsSendEachExactlyOnce() throws Exception {
        owner(200);
        queue("acme", 10);
        List<UUID> ids = notificationIds();
        NotificationDispatcher other = secondInstance();
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            List<Callable<Integer>> loops = new ArrayList<>();
            for (NotificationDispatcher d : List.of(dispatcher, other, dispatcher)) {
                loops.add(() -> {
                    int sent = 0;
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
                    while (openRows() > 0 && System.nanoTime() < deadline) {
                        sent += d.dispatchDue().sent();
                    }
                    return sent;
                });
            }
            int total = 0;
            for (Future<Integer> f : pool.invokeAll(loops, 90, TimeUnit.SECONDS)) {
                total += f.get();
            }
            assertThat(total).isEqualTo(10);
        } finally {
            pool.shutdownNow();
        }
        MimeMessage[] got = received();
        assertThat(got).hasSize(10);
        Set<String> expected = new HashSet<>();
        ids.forEach(id -> expected.add("<feed-" + id + "@" + FROM_DOMAIN + ">"));
        assertThat(messageIds(got)).isEqualTo(expected);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM feed_notification WHERE status = 'SENT' AND attempts = 1",
                Integer.class)).isEqualTo(10);
    }

    @Test
    void processorAndDispatcherConcurrentlyGiveOneMessagePerJob() throws Exception {
        owner(200);
        UUID s = lever("acme", "Acme");
        serve("acme", old("base", "Platform Engineer", JAVA_SQL));
        pollOk(s);
        processor.processDue();
        List<Post> posts = new ArrayList<>();
        posts.add(old("base", "Platform Engineer", JAVA_SQL));
        int n = 8;
        for (int i = 0; i < n; i++) {
            posts.add(fresh("c" + i, "Backend Engineer " + i, JAVA_SQL));
        }
        serve("acme", posts);
        jdbc.update("UPDATE feed_source SET last_polled_at = NULL, content_hash = NULL, etag = NULL WHERE id = ?", s);
        pollOk(s);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> processing = pool.submit(() -> {
                while (pendingProcessing() > 0) {
                    processor.processDue();
                }
            });
            Future<?> sending = pool.submit(() -> {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
                while (System.nanoTime() < deadline
                        && (pendingProcessing() > 0 || openRows() > 0 || countRows("feed_notification") < n)) {
                    dispatcher.dispatchDue();
                }
            });
            processing.get(90, TimeUnit.SECONDS);
            sending.get(90, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(countRows("feed_notification")).isEqualTo(n);
        assertThat(received()).hasSize(n);
        assertThat(messageIds(received())).hasSize(n);

        // re-processing SENT jobs (a profile change) creates no row and sends nothing
        profile("Java", "SQL", "Kubernetes");
        while (pendingProcessing() > 0) {
            processor.processDue();
        }
        dispatcher.dispatchDue();
        assertThat(countRows("feed_notification")).isEqualTo(n);
        assertThat(received()).hasSize(n);
    }

    @Test
    void recoveryNeverResetsARowThisJvmIsSending() throws Exception {
        owner(20);
        queue("acme", 1);
        UUID id = notificationIds().get(0);
        java.util.concurrent.CountDownLatch inSend = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.doAnswer(i -> {
            inSend.countDown();
            release.await(30, TimeUnit.SECONDS);
            return i.callRealMethod();
        }).when(emailNotifier).send(org.mockito.ArgumentMatchers.any());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> run = pool.submit(dispatcher::dispatchDue);
            assertThat(inSend.await(30, TimeUnit.SECONDS)).isTrue();
            Future<Integer> recovered = pool.submit(dispatcher::recoverInterrupted);
            Thread.sleep(500);
            assertThat(recovered.isDone()).as("recovery waits for the run").isFalse();
            release.countDown();
            run.get(30, TimeUnit.SECONDS);
            assertThat(recovered.get(Duration.ofSeconds(70).toSeconds(), TimeUnit.SECONDS)).isZero();
        } finally {
            pool.shutdownNow();
        }
        assertThat(notification(id).get("status")).isEqualTo("SENT");
        assertThat(received()).hasSize(1);
    }
}
