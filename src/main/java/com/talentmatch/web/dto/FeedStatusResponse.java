package com.talentmatch.web.dto;

import com.talentmatch.feed.FeedStatusView;
import com.talentmatch.notify.Channel;
import java.time.Instant;

/**
 * {@code GET /api/feed/status} (§5.2). Beyond §5.2: {@code notifications} adds {@code paused},
 * {@code pausedUntil} and {@code lastError} (step 8, D20); {@code enrichment} adds {@code pausedUntil}
 * and {@code capReachedUntil}, and {@code detection} adds {@code samples} and {@code windowDays}
 * (step 11).
 */
public record FeedStatusResponse(boolean enabled, boolean schedulerEnabled, Profile profile, Preferences preferences,
                                 Notifications notifications, Sources sources, Aggregator aggregator,
                                 Processing processing, Enrichment enrichment, Detection detection) {

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

    public record Enrichment(long pending, long running, long failed, boolean modelBusy, boolean aiEnabled,
                             Instant pausedUntil, Instant capReachedUntil) {
    }

    public record Detection(Long p50LatencySeconds, long samples, int windowDays) {
    }

    public static FeedStatusResponse of(FeedStatusView view) {
        FeedStatusView.Sources s = view.sources();
        FeedStatusView.Profile p = view.profile();
        FeedStatusView.Notifications n = view.notifications();
        FeedStatusView.Enrichment e = view.enrichment();
        FeedStatusView.Detection d = view.detection();
        return new FeedStatusResponse(view.enabled(), view.schedulerEnabled(),
                new Profile(p.present(), p.version(), p.appliedVersion()),
                new Preferences(view.preferences().present(), view.preferences().version()),
                new Notifications(n.enabled(), n.channel(), n.configured(), n.minScore(), n.pending(),
                        n.failedLast24h(), n.paused(), n.pausedUntil(), n.lastError()),
                new Sources(s.total(), s.active(), s.failing(), s.lastSuccessAt()),
                new Aggregator(view.aggregator().configured(), view.aggregator().requestsToday(),
                        view.aggregator().dailyBudget()),
                new Processing(view.processing().pending()),
                new Enrichment(e.pending(), e.running(), e.failed(), e.modelBusy(), e.aiEnabled(), e.pausedUntil(),
                        e.capReachedUntil()),
                new Detection(d.p50LatencySeconds(), d.samples(), d.windowDays()));
    }
}
