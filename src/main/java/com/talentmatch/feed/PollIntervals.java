package com.talentmatch.feed;

import com.talentmatch.feed.source.SourceKind;
import com.talentmatch.feed.source.SourceProperties;
import org.springframework.stereotype.Component;

/**
 * The interval a source is actually polled at (§4.3): {@link FeedProperties.Intervals#effectiveSeconds}
 * plus, for Adzuna, the share of the daily request budget ({@code 24h × active Adzuna sources /
 * budget}). With the default budget of 200 and four derived searches that is 29 minutes.
 */
@Component
public class PollIntervals {

    private final FeedSourceRepository sources;
    private final FeedProperties.Intervals intervals;
    private final int adzunaBudget;

    public PollIntervals(FeedSourceRepository sources, FeedProperties properties, SourceProperties sourceProperties) {
        this.sources = sources;
        this.intervals = properties.intervals();
        this.adzunaBudget = sourceProperties.adzuna().dailyRequestBudget();
    }

    /** Active Adzuna sources now (one query); pass it to {@link #seconds(FeedSource, int)} for a list. */
    public int activeAggregatorSources() {
        return sources.countActive(SourceKind.ADZUNA);
    }

    public int seconds(FeedSource source) {
        return seconds(source, source.kind().ats() ? 0 : activeAggregatorSources());
    }

    public int seconds(FeedSource source, int activeAggregatorSources) {
        // A paused source still shows the interval it would get when resumed.
        int sharing = source.kind().ats() || source.state() == FeedSourceState.ACTIVE
                ? activeAggregatorSources : activeAggregatorSources + 1;
        return intervals.effectiveSeconds(source.kind(), source.pollIntervalSeconds(), sharing, adzunaBudget);
    }
}
