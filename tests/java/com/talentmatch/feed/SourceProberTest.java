package com.talentmatch.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.talentmatch.feed.source.BoardInfo;
import com.talentmatch.feed.source.FetchRequest;
import com.talentmatch.feed.source.FetchResult;
import com.talentmatch.feed.source.SourceAdapter;
import com.talentmatch.feed.source.SourceException;
import com.talentmatch.feed.source.SourceFailure;
import com.talentmatch.feed.source.SourceKind;
import com.talentmatch.feed.source.SourceTarget;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** §5.1 verify=true: the board check, bounded by talentmatch.feed.probe-timeout. */
class SourceProberTest {

    @FunctionalInterface
    interface ProbeFn {
        Optional<BoardInfo> probe(String token, Map<String, String> options) throws Exception;
    }

    private static final class FakeAdapter implements SourceAdapter {
        private final SourceKind kind;
        private final ProbeFn fn;

        FakeAdapter(SourceKind kind, ProbeFn fn) {
            this.kind = kind;
            this.fn = fn;
        }

        @Override
        public SourceKind kind() {
            return kind;
        }

        @Override
        public FetchResult fetch(SourceTarget target, FetchRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<BoardInfo> probe(String boardToken, Map<String, String> options) {
            try {
                return fn.probe(boardToken, options);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private SourceProber prober;

    private SourceProber prober(Duration timeout, ProbeFn fn) {
        prober = new SourceProber(new SourceAdapters(List.of(new FakeAdapter(SourceKind.GREENHOUSE, fn))),
                new FeedProperties(null, timeout));
        return prober;
    }

    @AfterEach
    void tearDown() {
        if (prober != null) {
            prober.destroy();
        }
    }

    @Test
    void foundPassesInfoAndArguments() {
        AtomicReference<String> seen = new AtomicReference<>();
        BoardInfo info = new BoardInfo("Acme", 3, null);
        SourceProber.Result r = prober(Duration.ofSeconds(5), (t, o) -> {
            seen.set(t + "|" + o);
            return Optional.of(info);
        }).probe(SourceKind.GREENHOUSE, "acme", Map.of("k", "v"));
        assertThat(r).isEqualTo(new SourceProber.Found(info));
        assertThat(seen.get()).isEqualTo("acme|{k=v}");
    }

    @Test
    void emptyIsNotFound() {
        assertThat(prober(Duration.ofSeconds(5), (t, o) -> Optional.empty())
                .probe(SourceKind.GREENHOUSE, "acme", Map.of())).isInstanceOf(SourceProber.NotFound.class);
    }

    @Test
    void blockingAdapterPastTimeoutIsUncheckedTimeoutAndIsInterrupted() throws Exception {
        CountDownLatch interrupted = new CountDownLatch(1);
        SourceProber p = prober(Duration.ofSeconds(1), (t, o) -> {
            try {
                Thread.sleep(20_000);
            } catch (InterruptedException e) {
                interrupted.countDown();
                throw e;
            }
            return Optional.empty();
        });
        long start = System.nanoTime();
        SourceProber.Result r = p.probe(SourceKind.GREENHOUSE, "acme", Map.of());
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertThat(r).isEqualTo(new SourceProber.Unchecked(SourceFailure.Kind.TIMEOUT));
        assertThat(ms).isBetween(900L, 4_000L);
        assertThat(interrupted.await(5, TimeUnit.SECONDS)).as("worker interrupted on timeout").isTrue();
    }

    @Test
    void sourceNotFoundIsNotFound() {
        assertThat(prober(Duration.ofSeconds(5), (t, o) -> {
            throw new SourceException(SourceFailure.of(SourceFailure.Kind.NOT_FOUND, 404), "404 from x");
        }).probe(SourceKind.GREENHOUSE, "acme", Map.of())).isInstanceOf(SourceProber.NotFound.class);
    }

    @Test
    void otherFailureKindsAreUncheckedWithKind() {
        for (SourceFailure.Kind kind : SourceFailure.Kind.values()) {
            if (kind == SourceFailure.Kind.NOT_FOUND) {
                continue;
            }
            SourceProber p = prober(Duration.ofSeconds(5), (t, o) -> {
                throw new SourceException(SourceFailure.of(kind), "failed " + kind);
            });
            assertThat(p.probe(SourceKind.GREENHOUSE, "acme", Map.of())).as(kind.name())
                    .isEqualTo(new SourceProber.Unchecked(kind));
            p.destroy();
        }
    }

    @Test
    void runtimeExceptionIsUncheckedNull() {
        assertThat(prober(Duration.ofSeconds(5), (t, o) -> {
            throw new IllegalStateException("boom");
        }).probe(SourceKind.GREENHOUSE, "acme", Map.of())).isEqualTo(new SourceProber.Unchecked(null));
    }

    @Test
    void nullReturnIsNotFound() {
        assertThat(prober(Duration.ofSeconds(5), (t, o) -> null).probe(SourceKind.GREENHOUSE, "acme", Map.of()))
                .isInstanceOf(SourceProber.NotFound.class);
    }

    @Test
    void unsupportedKindThrows() {
        SourceProber p = prober(Duration.ofSeconds(5), (t, o) -> Optional.empty());
        assertThatThrownBy(() -> p.probe(SourceKind.LEVER, "acme", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fullPoolIsUncheckedNullInsteadOfWaiting() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        SourceProber p = prober(Duration.ofSeconds(1), (t, o) -> {
            // ignore interrupts so workers stay busy until released
            while (release.getCount() > 0) {
                try {
                    release.await();
                } catch (InterruptedException ignored) {
                    // keep blocking
                }
            }
            return Optional.empty();
        });
        try {
            // 2 workers + 8 queued fill the pool; each call times out after 1s, so fill it from threads.
            List<Thread> callers = new java.util.ArrayList<>();
            for (int i = 0; i < 10; i++) {
                Thread th = new Thread(() -> p.probe(SourceKind.GREENHOUSE, "acme", Map.of()));
                th.start();
                callers.add(th);
            }
            Thread.sleep(300);
            long start = System.nanoTime();
            SourceProber.Result r = p.probe(SourceKind.GREENHOUSE, "acme", Map.of());
            assertThat(r).isEqualTo(new SourceProber.Unchecked(null));
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(500);
            for (Thread th : callers) {
                th.join(5_000);
            }
        } finally {
            release.countDown();
        }
    }

    @Test
    void sourceAdaptersRejectsDuplicates() {
        FakeAdapter a = new FakeAdapter(SourceKind.LEVER, (t, o) -> Optional.empty());
        FakeAdapter b = new FakeAdapter(SourceKind.LEVER, (t, o) -> Optional.empty());
        assertThatThrownBy(() -> new SourceAdapters(List.of(a, b))).isInstanceOf(IllegalStateException.class);
        SourceAdapters adapters = new SourceAdapters(List.of(a));
        assertThat(adapters.supports(SourceKind.LEVER)).isTrue();
        assertThat(adapters.supports(SourceKind.ADZUNA)).isFalse();
        assertThat(adapters.find(SourceKind.LEVER)).containsSame(a);
    }
}
