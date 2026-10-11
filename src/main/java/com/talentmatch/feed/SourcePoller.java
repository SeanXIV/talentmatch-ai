package com.talentmatch.feed;

import com.talentmatch.feed.JobPostingRepository.StoredPosting;
import com.talentmatch.feed.source.FetchRequest;
import com.talentmatch.feed.source.FetchResult;
import com.talentmatch.feed.source.RawPosting;
import com.talentmatch.feed.source.SourceAdapter;
import com.talentmatch.feed.source.SourceException;
import com.talentmatch.feed.source.SourceFailure;
import com.talentmatch.feed.source.SourceKind;
import com.talentmatch.feed.source.SourceProperties;
import com.talentmatch.feed.source.SourceTarget;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Polls one claimed source (§1.2, §4.4, §6.1, §6.2). Runs on a {@code feed-poll-N} thread; never
 * throws, every outcome is recorded on the source under the poller's lease.
 * <ol>
 *   <li>Fetch the listing with the stored ETag and body hash (no transaction during HTTP). 304 or an
 *       identical body → NOT_MODIFIED.</li>
 *   <li>Greenhouse: fetch details (description, salary) for new, changed ({@code updated_at}) and
 *       pending postings, new ones first, at most {@code greenhouse.max-detail-calls-per-poll}, and
 *       only while the lease leaves room for another request. Postings still waiting keep their old
 *       content (or go in without a description) and the next fetch is unconditional.</li>
 *   <li>Normalize. One bad posting is skipped; more than half skipped → INVALID_RESPONSE and nothing
 *       is written (likely a provider format change).</li>
 *   <li>{@link PollWriter}: one transaction for the postings and the source state.</li>
 * </ol>
 * Failures back off per {@link Backoff}. Logs never hold posting text or URL queries: the source id,
 * kind, counts and the sanitized {@link SourceException} message only.
 */
@Component
public class SourcePoller {

    private static final Logger log = LoggerFactory.getLogger(SourcePoller.class);

    /** Headroom kept before the lease ends: one more detail request must finish inside the lease. */
    private static final Duration LEASE_MARGIN = Duration.ofSeconds(30);
    private static final int MAX_ERROR_LENGTH = 300;
    /** A save that lost a lock conflict is retried after this (plus up to {@link #LOCK_CONFLICT_JITTER}). */
    private static final Duration LOCK_CONFLICT_RETRY = Duration.ofSeconds(5);
    private static final Duration LOCK_CONFLICT_JITTER = Duration.ofSeconds(5);

    private final SourceAdapters adapters;
    private final PollWriter writer;
    private final FeedSourceRepository sources;
    private final JobPostingRepository postings;
    private final FeedProperties properties;
    private final int maxDetailCalls;
    private final Duration requestDeadline;
    private final Clock clock;

    public SourcePoller(SourceAdapters adapters, PollWriter writer, FeedSourceRepository sources,
                        JobPostingRepository postings, FeedProperties properties, SourceProperties sourceProperties,
                        Clock clock) {
        this.adapters = adapters;
        this.writer = writer;
        this.sources = sources;
        this.postings = postings;
        this.properties = properties;
        this.maxDetailCalls = sourceProperties.greenhouse().maxDetailCallsPerPoll();
        this.requestDeadline = sourceProperties.http().connectTimeout().plus(sourceProperties.http().readTimeout());
        this.clock = clock;
        if (properties.lease().compareTo(requestDeadline.plus(LEASE_MARGIN)) <= 0) {
            throw new IllegalArgumentException("talentmatch.feed.lease (" + properties.lease()
                    + ") must be longer than talentmatch.feed.http.connect-timeout + read-timeout (" + requestDeadline
                    + ") plus " + LEASE_MARGIN.toSeconds() + "s");
        }
    }

    /**
     * Polls a source claimed by {@code FeedSourceRepository.claimDue} or {@code claim}; its
     * {@code leaseUntil} is the lease token. Never throws.
     */
    public PollOutcome poll(FeedSource source) {
        long started = System.nanoTime();
        PollOutcome outcome;
        try {
            outcome = doPoll(source);
        } catch (RuntimeException e) {
            outcome = unexpected(source, e);
        }
        logOutcome(source, outcome, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        return outcome;
    }

    private PollOutcome doPoll(FeedSource source) {
        Instant now = clock.instant();
        Duration interval = interval(source);
        Optional<SourceAdapter> found = adapters.find(source.kind());
        if (found.isEmpty() || source.boardToken() == null) {
            // Adzuna arrives with step 9; such a source can't be added yet.
            return fail(source, SourceFailure.of(SourceFailure.Kind.UNAUTHORIZED), "ERROR",
                    FeedSourceService.display(source.kind()) + " sources can't be polled yet.", now, interval);
        }
        SourceAdapter adapter = found.get();
        SourceTarget target = source.target();

        FetchResult result;
        try {
            result = adapter.fetch(target, new FetchRequest(source.etag(), source.lastModified(), source.contentHash()));
        } catch (SourceException e) {
            return fail(source, e.failure(), statusOf(e.kind()), e.getMessage(), now, interval);
        }
        if (result.notModified()) {
            Instant next = Backoff.afterSuccess(now, interval, ThreadLocalRandom.current());
            return sources.recordNotModified(source.id(), source.leaseUntil(), now, next, result.etag())
                    ? new PollOutcome.NotModified() : new PollOutcome.LeaseLost();
        }

        PollPlan plan = plan(source, adapter, target, result);
        int total = plan.fetched() + result.skipped();
        if (total > 0 && 2L * plan.skipped() > total) {
            return fail(source, SourceFailure.of(SourceFailure.Kind.INVALID_RESPONSE), "INVALID_RESPONSE",
                    plan.skipped() + " of " + total + " postings couldn't be read, so nothing was saved (the provider "
                            + "may have changed its format).", now, interval);
        }

        Instant next = Backoff.afterSuccess(now, interval, ThreadLocalRandom.current());
        try {
            return writer.write(source, plan, now, next);
        } catch (PollWriter.LeaseLostException e) {
            return new PollOutcome.LeaseLost();
        } catch (RuntimeException e) {
            if (LockRetry.isLockConflict(e)) {
                return deferAfterLockConflict(source, e);
            }
            // Rolled back: the next poll fetches and writes the same data again.
            log.warn("Feed poll source={} kind={}: saving the postings failed ({})", source.id(), source.kind(),
                    describe(e));
            return fail(source, SourceFailure.of(SourceFailure.Kind.SERVER_ERROR), "ERROR",
                    "Saving the postings failed (" + e.getClass().getSimpleName() + "); the next poll retries.",
                    clock.instant(), interval);
        }
    }

    /** Details (Greenhouse), normalization and the entry list; HTTP only, no database writes. */
    private PollPlan plan(FeedSource source, SourceAdapter adapter, SourceTarget target, FetchResult result) {
        List<RawPosting> raws = result.postings();
        Map<String, StoredPosting> stored = new HashMap<>();
        for (StoredPosting p : postings.findBySource(source.id())) {
            stored.put(p.externalId(), p);
        }

        boolean detailsEnabled = source.kind() == SourceKind.GREENHOUSE && maxDetailCalls > 0;
        Map<String, Want> wanted = new HashMap<>();
        if (detailsEnabled) {
            for (RawPosting raw : raws) {
                Want want = want(raw, stored.get(raw.externalId()));
                if (want != Want.NONE) {
                    wanted.put(raw.externalId(), want);
                }
            }
        }
        DetailResult details = fetchDetails(source, adapter, target, raws, wanted);

        List<PollPlan.Entry> entries = new ArrayList<>(raws.size());
        Set<String> fetchedIds = new LinkedHashSet<>();
        boolean pending = false;
        int skipped = result.skipped();
        for (RawPosting raw : raws) {
            String id = raw.externalId();
            fetchedIds.add(id);
            StoredPosting existing = stored.get(id);
            Want want = wanted.getOrDefault(id, Want.NONE);
            RawPosting content = raw;
            if (want != Want.NONE) {
                RawPosting detail = details.postings().get(id);
                if (detail != null) {
                    content = detail;
                } else {
                    pending = true;
                    if (want != Want.NEW) {
                        entries.add(PollPlan.Entry.touch(id));      // keep the stored content for now
                        continue;
                    }
                }
            } else if (detailsEnabled && raw.descriptionHtmlOrText() == null && existing != null) {
                entries.add(PollPlan.Entry.touch(id));          // unchanged since its detail was fetched
                continue;
            }
            try {
                entries.add(new PollPlan.Entry(id, PostingNormalizer.normalize(content)));
            } catch (RuntimeException e) {
                skipped++;
                // Normalizer messages name the problem only; anything else is logged by class name.
                log.debug("Feed poll source={} skipped posting id={}: {}", source.id(), safeId(id),
                        e instanceof PostingNormalizer.InvalidPostingException ? e.getMessage() : e.getClass().getName());
                if (existing != null) {
                    entries.add(PollPlan.Entry.touch(id));
                }
            }
        }
        int fetched = raws.size();
        int plannedSkipped = skipped;
        return new PollPlan(entries, fetchedIds, result.complete(), fetched, plannedSkipped, pending,
                result.etag(), result.lastModified(), result.bodyHash(), details.calls());
    }

    /** Why a Greenhouse listing posting needs its detail. */
    private enum Want {
        NEW, CHANGED, PENDING, NONE
    }

    private static Want want(RawPosting raw, StoredPosting existing) {
        if (raw.descriptionHtmlOrText() != null) {
            return Want.NONE;
        }
        if (existing == null) {
            return Want.NEW;
        }
        if (!existing.hasDescription()) {
            return Want.PENDING;
        }
        if (raw.contentVersion() != null && !raw.contentVersion().equals(existing.sourceUpdatedAt())) {
            return Want.CHANGED;
        }
        return Want.NONE;
    }

    private record DetailResult(Map<String, RawPosting> postings, int calls) {
    }

    /**
     * Fetches details one at a time (the HTTP client spaces requests per host), new postings first,
     * within the per-poll budget and the lease. A provider failure other than NOT_FOUND stops the
     * detail calls for this poll; the postings simply stay pending.
     */
    private DetailResult fetchDetails(FeedSource source, SourceAdapter adapter, SourceTarget target,
                                      List<RawPosting> raws, Map<String, Want> wanted) {
        if (wanted.isEmpty()) {
            return new DetailResult(Map.of(), 0);
        }
        List<RawPosting> order = new ArrayList<>();
        for (RawPosting raw : raws) {
            if (wanted.containsKey(raw.externalId())) {
                order.add(raw);
            }
        }
        order.sort(Comparator.comparing((RawPosting r) -> wanted.get(r.externalId())));   // NEW, CHANGED, PENDING
        Instant stopAt = source.leaseUntil() == null ? null
                : source.leaseUntil().minus(requestDeadline).minus(LEASE_MARGIN);
        Map<String, RawPosting> fetched = new HashMap<>();
        int calls = 0;
        for (RawPosting raw : order) {
            if (calls >= maxDetailCalls || Thread.currentThread().isInterrupted()
                    || (stopAt != null && !clock.instant().isBefore(stopAt))) {
                break;
            }
            calls++;
            try {
                adapter.fetchDetail(target, raw.externalId())
                        .filter(detail -> Objects.equals(detail.externalId(), raw.externalId()))
                        .ifPresent(detail -> fetched.put(raw.externalId(), detail));
            } catch (SourceException e) {
                log.info("Feed poll source={} kind={}: detail requests stopped after {} ({})", source.id(),
                        source.kind(), calls, e.getMessage());
                break;
            } catch (RuntimeException e) {
                log.warn("Feed poll source={} kind={}: detail request failed ({})", source.id(), source.kind(),
                        e.getClass().getName());
                break;
            }
        }
        return new DetailResult(fetched, calls);
    }

    // ------------------------------------------------------------------ failures

    /**
     * The save lost a lock conflict (deadlock or lock timeout) against a concurrent writer of the same
     * feed jobs, e.g. a refresh marker. Not a source failure (§6.1 backoff is for provider and
     * response problems): the lease is released and the source is due again in a few seconds, without
     * counting a failure or changing its status.
     */
    private PollOutcome deferAfterLockConflict(FeedSource source, RuntimeException e) {
        Instant retryAt = clock.instant().plus(LOCK_CONFLICT_RETRY)
                .plusMillis(ThreadLocalRandom.current().nextLong(LOCK_CONFLICT_JITTER.toMillis() + 1));
        log.info("Feed poll source={} kind={}: saving the postings lost a lock conflict ({}); retrying at {}",
                source.id(), source.kind(), describe(e), retryAt);
        try {
            return sources.releaseForRetry(source.id(), source.leaseUntil(), retryAt)
                    ? new PollOutcome.Deferred(retryAt) : new PollOutcome.LeaseLost();
        } catch (RuntimeException again) {
            // The lease expires on its own; the source is claimed again then.
            log.warn("Feed poll source={} kind={}: could not release the lease ({})", source.id(), source.kind(),
                    describe(again));
            return new PollOutcome.Deferred(retryAt);
        }
    }

    private PollOutcome fail(FeedSource source, SourceFailure failure, String status, String message, Instant now,
                             Duration interval) {
        String error = message == null ? status : cut(message);
        Instant next = Backoff.afterFailure(failure, source.consecutiveFailures() + 1, interval,
                properties.intervals().maxBackoff(), properties.intervals().notFoundBackoff(), now,
                ThreadLocalRandom.current());
        try {
            boolean recorded = sources.recordFailure(source.id(), source.leaseUntil(), now, next, status, error, true);
            return recorded ? new PollOutcome.Failed(failure, status, error) : new PollOutcome.LeaseLost();
        } catch (RuntimeException e) {
            // The lease expires on its own; the source is claimed again then.
            log.warn("Feed poll source={} kind={}: could not record the failure ({})", source.id(), source.kind(),
                    describe(e));
            return new PollOutcome.Failed(failure, status, error);
        }
    }

    private PollOutcome unexpected(FeedSource source, RuntimeException e) {
        log.warn("Feed poll source={} kind={} failed unexpectedly ({})", source.id(), source.kind(), describe(e));
        try {
            return fail(source, SourceFailure.of(SourceFailure.Kind.SERVER_ERROR), "ERROR",
                    "Unexpected error (" + e.getClass().getSimpleName() + "); the next poll retries.",
                    clock.instant(), interval(source));
        } catch (RuntimeException again) {
            return new PollOutcome.Failed(SourceFailure.of(SourceFailure.Kind.SERVER_ERROR), "ERROR", null);
        }
    }

    /** {@code feed_source.last_status} for a failure kind (V5 CHECK values). */
    static String statusOf(SourceFailure.Kind kind) {
        return switch (kind) {
            case RATE_LIMITED -> "RATE_LIMITED";
            case NOT_FOUND -> "NOT_FOUND";
            case UNAUTHORIZED -> "UNAUTHORIZED";
            case INVALID_RESPONSE -> "INVALID_RESPONSE";
            case TOO_LARGE -> "TOO_LARGE";
            case SERVER_ERROR, NETWORK, TIMEOUT -> "ERROR";
        };
    }

    private Duration interval(FeedSource source) {
        return Duration.ofSeconds(properties.intervals().effectiveSeconds(source.kind(), source.pollIntervalSeconds()));
    }

    // ------------------------------------------------------------------ logging

    private void logOutcome(FeedSource source, PollOutcome outcome, long latencyMs) {
        if (outcome instanceof PollOutcome.Ok ok) {
            PollOutcome.Stats s = ok.stats();
            log.info("Feed poll source={} kind={} outcome=ok status={} fetched={} new={} updated={} closed={} "
                            + "skipped={} latencyMs={}", source.id(), source.kind(), ok.status(), s.fetched(),
                    s.created(), s.updated(), s.closed(), s.skipped(), latencyMs);
        } else if (outcome instanceof PollOutcome.NotModified) {
            log.info("Feed poll source={} kind={} outcome=not_modified status=NOT_MODIFIED fetched=0 new=0 updated=0 "
                    + "closed=0 skipped=0 latencyMs={}", source.id(), source.kind(), latencyMs);
        } else if (outcome instanceof PollOutcome.Failed failed) {
            log.info("Feed poll source={} kind={} outcome=failed status={} error=\"{}\" latencyMs={}", source.id(),
                    source.kind(), failed.status(), failed.message(), latencyMs);
        } else if (outcome instanceof PollOutcome.Deferred deferred) {
            log.info("Feed poll source={} kind={} outcome=deferred: lock conflict while saving, nothing written, "
                    + "due again at {} (latencyMs={})", source.id(), source.kind(), deferred.retryAt(), latencyMs);
        } else {
            log.warn("Feed poll source={} kind={} outcome=lease_lost: the lease was taken over or the source was "
                    + "deleted; nothing was written (latencyMs={})", source.id(), source.kind(), latencyMs);
        }
    }

    /** Exception classes and SQLStates only: database messages can quote a posting's text. */
    static String describe(Throwable e) {
        StringBuilder sb = new StringBuilder();
        int depth = 0;
        for (Throwable t = e; t != null && depth < 8; t = t.getCause() == t ? null : t.getCause(), depth++) {
            if (sb.length() > 0) {
                sb.append(" <- ");
            }
            sb.append(t.getClass().getName());
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                sb.append("[SQLState ").append(sql.getSQLState()).append(']');
            }
        }
        return sb.toString();
    }

    private static String cut(String message) {
        return message.length() <= MAX_ERROR_LENGTH ? message : message.substring(0, MAX_ERROR_LENGTH);
    }

    /** External ids are provider data: printable characters only, at most 80. */
    private static String safeId(String id) {
        if (id == null) {
            return "?";
        }
        StringBuilder sb = new StringBuilder();
        id.codePoints().filter(c -> c >= 0x20 && c != 0x7f).limit(80).forEach(sb::appendCodePoint);
        return sb.length() == 0 ? "?" : sb.toString();
    }
}
