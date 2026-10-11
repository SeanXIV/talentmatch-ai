package com.talentmatch.web.dto;

import com.talentmatch.feed.FeedStatusView;
import java.time.Instant;

/**
 * {@code GET /api/feed/status} (§5.2). Step 6 returned the poller's part; step 7 adds
 * {@code profile} and {@code preferences}. The notifications, aggregator, enrichment and detection
 * parts are added (additively) by later steps.
 */
public record FeedStatusResponse(boolean enabled, boolean schedulerEnabled, Profile profile, Preferences preferences,
                                 Sources sources, Processing processing) {

    public record Profile(boolean present, Integer version, Integer appliedVersion) {
    }

    public record Preferences(boolean present, Integer version) {
    }

    public record Sources(long total, long active, long failing, Instant lastSuccessAt) {
    }

    public record Processing(long pending) {
    }

    public static FeedStatusResponse of(FeedStatusView view) {
        FeedStatusView.Sources s = view.sources();
        FeedStatusView.Profile p = view.profile();
        return new FeedStatusResponse(view.enabled(), view.schedulerEnabled(),
                new Profile(p.present(), p.version(), p.appliedVersion()),
                new Preferences(view.preferences().present(), view.preferences().version()),
                new Sources(s.total(), s.active(), s.failing(), s.lastSuccessAt()),
                new Processing(view.processing().pending()));
    }
}
