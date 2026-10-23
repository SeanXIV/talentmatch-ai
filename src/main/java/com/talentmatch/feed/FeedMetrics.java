package com.talentmatch.feed;

import com.talentmatch.feed.source.SourceKind;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The poller's meters (§6.6). Tags are lower case:
 * <ul>
 *   <li>{@value #POLLS} (timer): {@code kind} (greenhouse, lever, ashby, adzuna) and {@code outcome}, the
 *       lower-cased {@code last_status} (ok, suspicious_empty, not_modified, rate_limited, not_found,
 *       unauthorized, error, invalid_response, too_large, budget_exhausted), or deferred / lease_lost;</li>
 *   <li>{@value #NEW_POSTINGS} (counter): {@code kind}; a source's baseline poll is not counted.</li>
 * </ul>
 * Recording never throws: a meter problem is logged at debug by class name.
 */
@Component
public class FeedMetrics {

    private static final Logger log = LoggerFactory.getLogger(FeedMetrics.class);

    public static final String POLLS = "talentmatch.feed.polls";
    public static final String NEW_POSTINGS = "talentmatch.feed.postings.new";

    private final MeterRegistry registry;

    @Autowired
    public FeedMetrics(ObjectProvider<MeterRegistry> registry) {
        this(registry.getIfAvailable());
    }

    /** {@code null} records into {@link Metrics#globalRegistry}. */
    public FeedMetrics(MeterRegistry registry) {
        this.registry = registry == null ? Metrics.globalRegistry : registry;
    }

    /** Records into the global registry (callers built without Spring). */
    public static FeedMetrics global() {
        return new FeedMetrics((MeterRegistry) null);
    }

    public void poll(SourceKind kind, PollOutcome outcome, long latencyMs) {
        try {
            Timer.builder(POLLS).description("Feed source polls")
                    .tag("kind", kindTag(kind)).tag("outcome", outcomeTag(outcome))
                    .register(registry).record(Math.max(0, latencyMs), TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            log.debug("Could not record poll metrics ({})", e.getClass().getSimpleName());
        }
    }

    public void newPostings(SourceKind kind, int count) {
        if (count <= 0) {
            return;
        }
        try {
            Counter.builder(NEW_POSTINGS).description("New postings found by feed polls")
                    .tag("kind", kindTag(kind)).register(registry).increment(count);
        } catch (RuntimeException e) {
            log.debug("Could not record new-posting metrics ({})", e.getClass().getSimpleName());
        }
    }

    static String kindTag(SourceKind kind) {
        return kind == null ? "unknown" : kind.name().toLowerCase(Locale.ROOT);
    }

    static String outcomeTag(PollOutcome outcome) {
        if (outcome instanceof PollOutcome.Deferred) {
            return "deferred";
        }
        if (outcome instanceof PollOutcome.LeaseLost) {
            return "lease_lost";
        }
        String status = outcome == null ? null : outcome.status();
        return status == null ? "error" : status.toLowerCase(Locale.ROOT);
    }
}
