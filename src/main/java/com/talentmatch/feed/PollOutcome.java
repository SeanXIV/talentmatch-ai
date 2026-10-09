package com.talentmatch.feed;

import com.talentmatch.feed.source.SourceFailure;
import java.util.Objects;

/** The result of one {@link SourcePoller#poll} (the poller never throws). */
public sealed interface PollOutcome permits PollOutcome.Ok, PollOutcome.NotModified, PollOutcome.Failed,
        PollOutcome.Deferred, PollOutcome.LeaseLost {

    /** The value stored in {@code feed_source.last_status}; null when nothing was recorded. */
    String status();

    /**
     * Postings written. {@code status} is OK, or SUSPICIOUS_EMPTY when closing was held back.
     *
     * @param fetched     postings in the listing (after the adapter's own skips)
     * @param created     new postings (attached to an existing job or creating one)
     * @param updated     stored postings whose content changed
     * @param reopened    closed postings seen again
     * @param closed      postings closed because they left a complete listing
     * @param jobsClosed  feed jobs closed because their last open posting closed
     * @param skipped     postings that couldn't be read (adapter) or stored (normalizer)
     * @param detailCalls Greenhouse detail requests made
     */
    record Stats(int fetched, int created, int updated, int reopened, int closed, int jobsClosed, int skipped,
                 int detailCalls) {
    }

    record Ok(String status, Stats stats) implements PollOutcome {

        public Ok {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(stats, "stats");
        }
    }

    /** 304, or a body identical to the last one: nothing written but the source's schedule. */
    record NotModified() implements PollOutcome {

        @Override
        public String status() {
            return "NOT_MODIFIED";
        }
    }

    /**
     * The poll failed and was recorded with backoff.
     *
     * @param message sanitized (no body, query or key); also stored in {@code last_error}
     */
    record Failed(SourceFailure failure, String status, String message) implements PollOutcome {
    }

    /**
     * Saving lost a lock conflict (a deadlock or lock timeout against a concurrent writer of the same
     * feed jobs): nothing written, and it isn't a source failure. The lease was released and the
     * source is due again at {@code retryAt}; {@code consecutive_failures}, {@code last_status} and
     * {@code last_error} are unchanged.
     */
    record Deferred(java.time.Instant retryAt) implements PollOutcome {

        @Override
        public String status() {
            return null;
        }
    }

    /** The lease was taken over (the poll overran it) or the source was deleted: nothing written. */
    record LeaseLost() implements PollOutcome {

        @Override
        public String status() {
            return null;
        }
    }
}
