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

    public PollWriter(FeedSourceRepository sources, JobPostingRepository postings, FeedJobRepository jobs,
                      FeedProperties properties, PlatformTransactionManager transactionManager) {
        this.sources = sources;
        this.postings = postings;
        this.jobs = jobs;
        this.properties = properties;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /**
     * @param source     the claimed row ({@code leaseUntil} is the lease token)
     * @param nextPollAt when the source is due again
     * @return the poll's statistics and status
     * @throws LeaseLostException when the lease is no longer ours (nothing written)
     */
    PollOutcome.Ok write(FeedSource source, PollPlan plan, Instant now, Instant nextPollAt) {
        return tx.execute(status -> doWrite(source, plan, now, nextPollAt));
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
        Set<UUID> touched = new LinkedHashSet<>();
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
                UUID jobId = attachOrCreate(posting, baseline, now);
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
        if (plan.complete()) {
            List<String> openIds = stored.values().stream().filter(StoredPosting::open)
                    .map(StoredPosting::externalId).toList();
            ClosingPolicy.Decision decision = ClosingPolicy.decide(openIds, plan.fetchedIds(),
                    source.suspiciousSince() != null, properties.closing().suspiciousDropRatio());
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

        for (UUID jobId : touched) {
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

    /** The open job with this posting's key, or a new one (§4.4). */
    private UUID attachOrCreate(NormalizedPosting posting, boolean baseline, Instant now) {
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
            jobs.clearBaseline(open.get());
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
