package com.talentmatch.web.dto;

import com.talentmatch.feed.FeedStatusView;
import java.time.Instant;

/**
 * {@code GET /api/feed/status} (§5.2). Phase 5 step 6 returns the poller's part; the profile,
 * preferences, notifications, aggregator, enrichment and detection parts are added (additively) by
 * later steps.
 */
public record FeedStatusResponse(boolean enabled, boolean schedulerEnabled, Sources sources, Processing processing) {

    public record Sources(long total, long active, long failing, Instant lastSuccessAt) {
    }

    public record Processing(long pending) {
    }

    public static FeedStatusResponse of(FeedStatusView view) {
        FeedStatusView.Sources s = view.sources();
        return new FeedStatusResponse(view.enabled(), view.schedulerEnabled(),
                new Sources(s.total(), s.active(), s.failing(), s.lastSuccessAt()),
                new Processing(view.processing().pending()));
    }
}
