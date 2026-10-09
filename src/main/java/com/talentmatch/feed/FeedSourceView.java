package com.talentmatch.feed;

import java.util.List;
import java.util.Objects;

/**
 * A feed source as the API shows it.
 *
 * @param effectivePollIntervalSeconds the interval actually used (the configured one or the kind's
 *                                     default, never below the kind's minimum)
 * @param warnings                     notes from adding the source (e.g. the board has no open
 *                                     postings, or couldn't be checked); empty otherwise
 */
public record FeedSourceView(FeedSource source, int effectivePollIntervalSeconds, List<String> warnings) {

    public FeedSourceView {
        Objects.requireNonNull(source, "source");
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
