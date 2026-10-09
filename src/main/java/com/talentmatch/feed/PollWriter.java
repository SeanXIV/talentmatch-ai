package com.talentmatch.feed;

import com.talentmatch.feed.JobPostingRepository.StoredPosting;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Stores one successful poll in <b>one</b> transaction together with the source's new state (§1.2,
 * §6.2). The transaction starts by locking the source row under the poller's lease; a lease that was
 * taken over (or a deleted source) writes nothing.
 * <ol>
 *   <li>New posting: dedup key → attach to the open feed job with that key, or create a FEED job;
 *       on the source's first successful poll it is baseline unless posted inside the fresh window.</li>
 *   <li>Changed posting (content hash differs): content replaced.</li>
 *   <li>Closed posting seen again: reopened; its job reopens unless another open job holds its key,
 *       in which case the posting moves to that job.</li>
 *   <li>Complete listing: {@link ClosingPolicy} closes missing postings (suspicious-drop guard); a job
 *       left without open postings closes.</li>
 *   <li>Every touched open job gets its canonical fields ({@link CanonicalJob}) and
 *       {@code process_after = now}, the hand-off to the processor.</li>
 *   <li>The source: status, ETag / body hash, baseline time, open count, next poll, lease released.</li>
 * </ol>
 * After the commit, a poll that touched jobs wakes the {@link FeedProcessor} (a no-op while the
 * scheduler is off).
 *
 * <p><b>Lock order.</b> Before writing, the open feed jobs of changed and closing postings are locked
 * in {@code job_id} order, and the canonical pass (with the deferred baseline clearing) also runs in
 * {@code job_id} order, the same order the refresh markers lock in. A lock conflict that still
 * happens rolls the poll back; {@link SourcePoller} treats it as transient (no failure counted, due
 * again in a few seconds).
 */
@Component
public class PollWriter {

    /** The lease was taken over or the source deleted; the transaction rolls back. */
    static final class LeaseLostException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        LeaseLostException() {
            super("lease lost", null, false, false);
        }
    }

    private final FeedSourceRepository sources;
    private final JobPostingRepository postings;
    private final FeedJobRepository jobs;
    private final FeedProperties properties;
    private final TransactionTemplate tx;
    private final FeedProcessor processor;

    public PollWriter(FeedSourceRepository sources, JobPostingRepository postings, FeedJobRepository jobs,
                      FeedProperties properties, PlatformTransactionManager transactionManager,
                      FeedProcessor processor) {
        this.sources = sources;
        this.postings = postings;
        this.jobs = jobs;
        this.properties = properties;
        this.tx = new TransactionTemplate(transactionManager);
        this.processor = processor;
    }

    /**
     * @param source     the claimed row ({@code leaseUntil} is the lease token)
     * @param nextPollAt when the source is due again
     * @return the poll's statistics and status
     * @throws LeaseLostException when the lease is no longer ours (nothing written)
     */
    PollOutcome.Ok write(FeedSource source, PollPlan plan, Instant now, Instant nextPollAt) {
        PollOutcome.Ok ok = tx.execute(status -> doWrite(source, plan, now, nextPollAt));
        if (ok != null && touchedJobs(ok.stats())) {
            processor.wake();                                   // after commit: the jobs are visible
        }
        return ok;
    }

    /** True when the poll handed jobs to the processor (new, changed, reopened or partly closed). */
    private static boolean touchedJobs(PollOutcome.Stats s) {
        return s.created() > 0 || s.updated() > 0 || s.reopened() > 0 || s.closed() > s.jobsClosed();
    }

    private PollOutcome.Ok doWrite(FeedSource source, PollPlan plan, Instant now, Instant nextPollAt) {
        if (!sources.lockLeased(source.id(), source.leaseUntil())) {
            throw new LeaseLostException();
        }
        Map<String, StoredPosting> stored = new HashMap<>();
        for (StoredPosting p : postings.findBySource(source.id())) {
            stored.put(p.externalId(), p);
        }
        boolean firstPoll = source.baselineAt() == null;

        // The closing decision depends only on what was stored and what was fetched: decided up front
        // so the jobs it closes can be locked together with the others below.
        ClosingPolicy.Decision decision = null;
        if (plan.complete()) {
            List<String> openIds = stored.values().stream().filter(StoredPosting::open)
                    .map(StoredPosting::externalId).toList();
            decision = ClosingPolicy.decide(openIds, plan.fetchedIds(),
                    source.suspiciousSince() != null, properties.closing().suspiciousDropRatio());
        }
        // Lock order: the open feed jobs this poll will write (changed or closing postings), in job_id
        // order, before any of them is written; the refresh markers lock in the same order
        // (FeedJobRepository.markOpenForProcessing), so the two can't deadlock on these rows.
        jobs.lockOpenInOrder(jobsToWrite(plan, stored, decision));

        Set<UUID> touched = new LinkedHashSet<>();
        Set<UUID> unbaseline = new LinkedHashSet<>();
        List<UUID> seen = new ArrayList<>();
        int created = 0;
        int updated = 0;
        int reopened = 0;

        for (PollPlan.Entry entry : plan.entries()) {
            StoredPosting existing = stored.get(entry.externalId());
            NormalizedPosting posting = entry.posting();
            if (existing == null) {
                if (posting == null) {
                    continue;                                   // nothing known about it yet
                }
                boolean baseline = firstPoll
                        && ClosingPolicy.isBaselinePosting(posting.postedAt(), now, properties.freshWindow());
                UUID jobId = attachOrCreate(posting, baseline, now, unbaseline);
                postings.insert(source.id(), jobId, posting, baseline, now);
                touched.add(jobId);
                created++;
                continue;
            }
            UUID jobId = existing.jobId();
            if (!existing.open()) {
                jobId = reopenJob(existing, now);
                postings.reopen(existing.id(), jobId, now);
                touched.add(jobId);
                reopened++;
            }
            if (posting != null && !posting.contentHash().equals(existing.contentHash())) {
                postings.update(existing.id(), posting, now);
                touched.add(jobId);
                updated++;
            } else if (existing.open()) {
                seen.add(existing.id());
            }
        }
        postings.touch(seen, now);

        int closed = 0;
        int jobsClosed = 0;
        Instant suspiciousSince = null;
        String status = "OK";
        if (decision != null) {
            if (decision.closingHeld()) {
                suspiciousSince = now;
                status = "SUSPICIOUS_EMPTY";
            } else if (!decision.toClose().isEmpty()) {
                List<UUID> affected = postings.close(source.id(), decision.toClose(), now);
                closed = affected.size();
                Set<UUID> closedJobs = jobs.closeWithoutOpenPostings(affected, now);
                jobsClosed = closedJobs.size();
                for (UUID jobId : affected) {
                    if (!closedJobs.contains(jobId)) {
                        touched.add(jobId);                     // still open via another posting
                    }
                }
            }
        }

        // In job_id order, like the lock pass above (jobs attached to from another source are locked here).
        for (UUID jobId : FeedJobRepository.sortedForDatabase(touched)) {
            if (unbaseline.contains(jobId)) {
                jobs.clearBaseline(jobId);
            }
            CanonicalJob.of(postings.findOpenCandidates(jobId)).ifPresent(c -> jobs.applyCanonical(jobId, c, now));
        }

        int open = postings.countOpen(source.id());
        // No validators when details are pending or closing was held (§4.5): the next fetch must be
        // processed in full, or a 304 / identical body would hold the closing forever.
        boolean forgetValidators = plan.detailsPending() || suspiciousSince != null;
        boolean recorded = sources.recordSuccess(source.id(), source.leaseUntil(), now, nextPollAt, status,
                forgetValidators ? null : plan.etag(),
                forgetValidators ? null : plan.lastModified(),
                forgetValidators ? null : plan.bodyHash(),
                suspiciousSince, open);
        if (!recorded) {
            throw new LeaseLostException();
        }
        return new PollOutcome.Ok(status, new PollOutcome.Stats(plan.fetched(), created, updated, reopened, closed,
                jobsClosed, plan.skipped(), plan.detailCalls()));
    }

    /**
     * The open feed jobs the poll writes before its ordered canonical pass: jobs of stored open
     * postings whose content changed, and jobs of postings the complete listing closes. (Jobs found by
     * dedup key and reopened jobs are only known while writing; closed jobs aren't marked anyway.)
     */
    private static Set<UUID> jobsToWrite(PollPlan plan, Map<String, StoredPosting> stored,
                                         ClosingPolicy.Decision decision) {
        Set<UUID> ids = new LinkedHashSet<>();
        for (PollPlan.Entry entry : plan.entries()) {
            StoredPosting existing = stored.get(entry.externalId());
            NormalizedPosting posting = entry.posting();
            if (existing != null && existing.open() && posting != null
                    && !posting.contentHash().equals(existing.contentHash())) {
                ids.add(existing.jobId());
            }
        }
        if (decision != null && !decision.closingHeld()) {
            for (String externalId : decision.toClose()) {
                StoredPosting p = stored.get(externalId);
                if (p != null) {
                    ids.add(p.jobId());
                }
            }
        }
        return ids;
    }

    /**
     * The open job with this posting's key, or a new one (§4.4). A non-baseline posting joining an
     * existing job adds it to {@code unbaseline}; the flag is cleared in the ordered canonical pass.
     */
    private UUID attachOrCreate(NormalizedPosting posting, boolean baseline, Instant now, Set<UUID> unbaseline) {
        String key = DedupKeys.of(posting.company(), posting.title(), posting.workplace());
        Optional<UUID> open = jobs.findOpenByDedupKey(key);
        if (open.isEmpty()) {
            Optional<UUID> created = jobs.create(posting, key, baseline, now);
            if (created.isPresent()) {
                return created.get();
            }
            // Another source's poll created it first (the partial unique index decided): attach.
            open = jobs.findOpenByDedupKey(key);
            if (open.isEmpty()) {
                throw new IllegalStateException("Feed job with a conflicting dedup key vanished");
            }
        }
        if (!baseline) {
            unbaseline.add(open.get());
        }
        return open.get();
    }

    /** The job a reappearing posting belongs to: its old job reopened, or the open job with the same key. */
    private UUID reopenJob(StoredPosting posting, Instant now) {
        FeedJobRepository.JobRef ref = jobs.findRef(posting.jobId())
                .orElseThrow(() -> new IllegalStateException("Posting without a feed job"));
        if (ref.closedAt() == null || jobs.reopen(ref.jobId(), now)) {
            return ref.jobId();
        }
        // A re-post created a new open job with this key meanwhile: the posting joins it.
        return jobs.findOpenByDedupKey(ref.dedupKey())
                .orElseThrow(() -> new IllegalStateException("Feed job could not be reopened"));
    }
}
