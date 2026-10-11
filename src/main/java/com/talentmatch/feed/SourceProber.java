package com.talentmatch.feed;

import com.talentmatch.ai.config.MdcTaskDecorator;
import com.talentmatch.feed.source.BoardInfo;
import com.talentmatch.feed.source.SourceAdapter;
import com.talentmatch.feed.source.SourceException;
import com.talentmatch.feed.source.SourceFailure;
import com.talentmatch.feed.source.SourceKind;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

/**
 * Checks that a board exists when a source is added (§5.1 {@code verify=true}). The adapter's probe
 * runs on a small worker pool so the request waits at most {@code talentmatch.feed.probe-timeout}
 * (10s), shorter than the HTTP client's own connect + read deadline. On timeout the worker is
 * interrupted, which cancels its HTTP exchange.
 *
 * <p>Never throws for provider trouble: every outcome is a {@link Result}. Logs the kind, the outcome
 * and the {@link SourceException} message, which by contract holds no body, query or key.
 */
@Component
public class SourceProber implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(SourceProber.class);
    private static final int THREADS = 2;
    private static final int QUEUE = 8;

    /** The outcome of a board check. */
    public sealed interface Result permits Found, NotFound, Unchecked {
    }

    /** The board exists; {@code info.warning()} may say it has no open postings. */
    public record Found(BoardInfo info) implements Result {
    }

    /** The provider says there is no such board. */
    public record NotFound() implements Result {
    }

    /**
     * The board couldn't be checked (network, timeout, 5xx, rate limit, unexpected answer).
     *
     * @param failure the provider failure kind; null for our own deadline, a full pool or an unexpected error
     */
    public record Unchecked(SourceFailure.Kind failure) implements Result {
    }

    private final SourceAdapters adapters;
    private final Duration timeout;
    private final MdcTaskDecorator mdc = new MdcTaskDecorator();
    private final ThreadPoolExecutor executor;

    public SourceProber(SourceAdapters adapters, FeedProperties properties) {
        this.adapters = adapters;
        this.timeout = properties.probeTimeout();
        AtomicInteger counter = new AtomicInteger();
        ThreadFactory threads = runnable -> {
            Thread thread = new Thread(runnable, "feed-probe-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        this.executor = new ThreadPoolExecutor(THREADS, THREADS, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(QUEUE), threads, new ThreadPoolExecutor.AbortPolicy());
        this.executor.allowCoreThreadTimeOut(true);
    }

    /**
     * @param boardToken a token already checked against {@link SourceKeys#isValidToken}
     * @param options    normalized provider options
     * @throws IllegalArgumentException when no adapter supports the kind
     */
    public Result probe(SourceKind kind, String boardToken, Map<String, String> options) {
        SourceAdapter adapter = adapters.find(kind)
                .orElseThrow(() -> new IllegalArgumentException("No source adapter for " + kind));
        long started = System.nanoTime();
        Result result = run(adapter, kind, boardToken, options);
        log.info("Feed probe kind={} outcome={} latencyMs={}", kind, describe(result),
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        return result;
    }

    private Result run(SourceAdapter adapter, SourceKind kind, String boardToken, Map<String, String> options) {
        FutureTask<Optional<BoardInfo>> task = new FutureTask<>(() -> adapter.probe(boardToken, options));
        try {
            executor.execute(mdc.decorate(task));
        } catch (RejectedExecutionException e) {
            log.warn("Feed probe kind={} not run: every probe worker is busy", kind);
            return new Unchecked(null);
        }
        try {
            Optional<BoardInfo> info = task.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return info == null || info.isEmpty() ? new NotFound() : new Found(info.get());
        } catch (TimeoutException e) {
            task.cancel(true);
            log.info("Feed probe kind={} timed out after {} ms", kind, timeout.toMillis());
            return new Unchecked(SourceFailure.Kind.TIMEOUT);
        } catch (InterruptedException e) {
            task.cancel(true);
            Thread.currentThread().interrupt();
            return new Unchecked(null);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof SourceException se) {
                if (se.kind() == SourceFailure.Kind.NOT_FOUND) {
                    return new NotFound();
                }
                log.info("Feed probe kind={} failed: {}", kind, se.getMessage());
                return new Unchecked(se.kind());
            }
            // Class name only: an unexpected exception's message could hold anything.
            log.warn("Feed probe kind={} failed unexpectedly: {}", kind,
                    cause == null ? "unknown" : cause.getClass().getName());
            return new Unchecked(null);
        }
    }

    private static String describe(Result result) {
        if (result instanceof Found found) {
            return found.info().warning() == null ? "found" : "found_empty";
        }
        if (result instanceof Unchecked unchecked) {
            return "unchecked" + (unchecked.failure() == null ? ""
                    : "_" + unchecked.failure().name().toLowerCase(Locale.ROOT));
        }
        return "not_found";
    }

    @Override
    public void destroy() {
        executor.shutdownNow();
    }
}
