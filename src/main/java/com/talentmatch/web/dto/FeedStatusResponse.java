package com.talentmatch.web.dto;

import com.talentmatch.feed.FeedStatusView;
import com.talentmatch.notify.Channel;
import java.time.Instant;

/**
 * {@code GET /api/feed/status} (§5.2). Step 6 returned the poller's part; step 7 added
 * {@code profile} and {@code preferences}; step 8 adds {@code notifications} (with {@code paused},
 * {@code pausedUntil} and {@code lastError} beyond §5.2, D20); step 9 adds {@code aggregator}. The
 * enrichment and detection parts are added (additively) by later steps.
 */
public record FeedStatusResponse(boolean enabled, boolean schedulerEnabled, Profile profile, Preferences preferences,
                                 Notifications notifications, Sources sources, Aggregator aggregator,
                                 Processing processing) {

    public record Profile(boolean present, Integer version, Integer appliedVersion) {
    }

    public record Preferences(boolean present, Integer version) {
    }

    public record Notifications(boolean enabled, Channel channel, boolean configured, double minScore, long pending,
                                long failedLast24h, boolean paused, Instant pausedUntil, String lastError) {
    }

    public record Sources(long total, long active, long failing, Instant lastSuccessAt) {
    }

    public record Aggregator(boolean configured, int requestsToday, int dailyBudget) {
    }

    public record Processing(long pending) {
    }

    public static FeedStatusResponse of(FeedStatusView view) {
        FeedStatusView.Sources s = view.sources();
        FeedStatusView.Profile p = view.profile();
        FeedStatusView.Notifications n = view.notifications();
        return new FeedStatusResponse(view.enabled(), view.schedulerEnabled(),
                new Profile(p.present(), p.version(), p.appliedVersion()),
                new Preferences(view.preferences().present(), view.preferences().version()),
                new Notifications(n.enabled(), n.channel(), n.configured(), n.minScore(), n.pending(),
                        n.failedLast24h(), n.paused(), n.pausedUntil(), n.lastError()),
                new Sources(s.total(), s.active(), s.failing(), s.lastSuccessAt()),
                new Aggregator(view.aggregator().configured(), view.aggregator().requestsToday(),
                        view.aggregator().dailyBudget()),
                new Processing(view.processing().pending()));
    }
}
