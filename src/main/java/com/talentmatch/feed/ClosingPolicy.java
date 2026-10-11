package com.talentmatch.feed;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Which postings a complete listing closes, and which postings of a first poll are baseline (§4.5,
 * pure).
 * <ul>
 *   <li>{@code missing = open − fetched}.</li>
 *   <li><b>Suspicious</b>: {@code fetched == 0 && open ≥ 3}, or {@code missing / open > ratio && open ≥ 6}
 *       (a provider hiccup or a format change, not a mass closing).</li>
 *   <li>The first suspicious poll closes nothing ({@code SUSPICIOUS_EMPTY}, {@code suspicious_since}
 *       set). A second suspicious poll in a row closes the missing postings. A poll that isn't
 *       suspicious closes them as usual and clears {@code suspicious_since}.</li>
 * </ul>
 */
public final class ClosingPolicy {

    static final int MIN_OPEN_FOR_EMPTY = 3;
    static final int MIN_OPEN_FOR_RATIO = 6;

    private ClosingPolicy() {
    }

    /**
     * @param toClose     external ids to close now (empty while closing is held)
     * @param suspicious  this poll looked suspicious
     * @param closingHeld the first suspicious poll: nothing closed, status {@code SUSPICIOUS_EMPTY},
     *                    {@code suspicious_since} set; otherwise {@code suspicious_since} is cleared
     */
    public record Decision(Set<String> toClose, boolean suspicious, boolean closingHeld) {

        public Decision {
            toClose = toClose == null ? Set.of() : Set.copyOf(toClose);
        }
    }

    /**
     * @param openIds               external ids of the source's open postings before this poll
     * @param fetchedIds            external ids in the listing just fetched
     * @param previouslySuspicious  the source's {@code suspicious_since} is set
     * @param ratio                 {@code talentmatch.feed.closing.suspicious-drop-ratio}
     */
    public static Decision decide(Collection<String> openIds, Collection<String> fetchedIds,
                                  boolean previouslySuspicious, double ratio) {
        Set<String> open = new LinkedHashSet<>(openIds);
        Set<String> fetched = new HashSet<>(fetchedIds);
        Set<String> missing = new LinkedHashSet<>(open);
        missing.removeAll(fetched);
        boolean suspicious = isSuspicious(fetched.size(), open.size(), missing.size(), ratio);
        if (suspicious && !previouslySuspicious) {
            return new Decision(Set.of(), true, true);
        }
        return new Decision(missing, suspicious, false);
    }

    public static boolean isSuspicious(int fetched, int open, int missing, double ratio) {
        if (open <= 0) {
            return false;
        }
        if (fetched == 0 && open >= MIN_OPEN_FOR_EMPTY) {
            return true;
        }
        return open >= MIN_OPEN_FOR_RATIO && (double) missing / open > ratio;
    }

    /**
     * Baseline (seen on the source's first successful poll, so not "new"): every posting except one
     * whose publish time is known and inside the fresh window. A posting with no publish time is
     * baseline.
     */
    public static boolean isBaselinePosting(Instant postedAt, Instant now, Duration freshWindow) {
        return !(postedAt != null && postedAt.isAfter(now.minus(freshWindow)));
    }
}
