package com.talentmatch.feed;

import com.talentmatch.feed.source.SourceKind;
import com.talentmatch.feed.source.SourceProperties;
import com.talentmatch.notify.NotificationChannels;
import com.talentmatch.notify.NotificationDispatcher;
import com.talentmatch.notify.NotificationRepository;
import com.talentmatch.notify.NotificationSettingsRepository;
import com.talentmatch.preferences.PreferencesRepository;
import com.talentmatch.profile.OwnerProfileRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code GET /api/feed/status} (§5.2): switches, profile and preferences versions, notifications,
 * sources, the Adzuna budget, the processing backlog, AI enrichment and the detection latency.
 */
@Service
public class FeedStatusService {

    static final Duration FAILED_WINDOW = Duration.ofHours(24);
    static final Duration DETECTION_WINDOW = Duration.ofDays(7);

    private final FeedSourceRepository sources;
    private final FeedJobRepository jobs;
    private final FeedStateRepository state;
    private final OwnerProfileRepository profiles;
    private final PreferencesRepository preferences;
    private final NotificationSettingsRepository notificationSettings;
    private final NotificationRepository notifications;
    private final NotificationChannels channels;
    private final NotificationDispatcher dispatcher;
    private final FeedProperties properties;
    private final FeedApiUsage usage;
    private final SourceProperties.Adzuna adzuna;
    private final FeedEnrichmentRepository enrichmentRows;
    private final JobSkillEnrichmentService enrichment;
    private final Clock clock;

    public FeedStatusService(FeedSourceRepository sources, FeedJobRepository jobs, FeedStateRepository state,
                             OwnerProfileRepository profiles, PreferencesRepository preferences,
                             NotificationSettingsRepository notificationSettings, NotificationRepository notifications,
                             NotificationChannels channels, NotificationDispatcher dispatcher,
                             FeedProperties properties, FeedApiUsage usage, SourceProperties sourceProperties,
                             FeedEnrichmentRepository enrichmentRows, JobSkillEnrichmentService enrichment,
                             Clock clock) {
        this.clock = clock;
        this.enrichmentRows = enrichmentRows;
        this.enrichment = enrichment;
        this.sources = sources;
        this.jobs = jobs;
        this.state = state;
        this.profiles = profiles;
        this.preferences = preferences;
        this.notificationSettings = notificationSettings;
        this.notifications = notifications;
        this.channels = channels;
        this.dispatcher = dispatcher;
        this.properties = properties;
        this.usage = usage;
        this.adzuna = sourceProperties.adzuna();
    }

    @Transactional(readOnly = true)
    public FeedStatusView status() {
        FeedSourceRepository.SourceCounts counts = sources.counts();
        Optional<OwnerProfileRepository.OwnerRef> owner = profiles.findRef();
        Optional<Integer> preferencesVersion = preferences.findVersion();
        FeedStateRepository.State applied = state.find();
        NotificationSettingsRepository.Settings s = notificationSettings.find();
        NotificationDispatcher.ChannelState channel = dispatcher.state();
        return new FeedStatusView(properties.enabled(), properties.schedulerRunning(),
                new FeedStatusView.Profile(owner.isPresent(),
                        owner.map(OwnerProfileRepository.OwnerRef::version).orElse(null),
                        applied.appliedProfileVersion()),
                new FeedStatusView.Preferences(preferencesVersion.isPresent(), preferencesVersion.orElse(null)),
                new FeedStatusView.Notifications(s.enabled(), s.channel(), channels.configured(s.channel()),
                        s.minScore(), notifications.countPending(),
                        notifications.countFailedSince(FAILED_WINDOW, true), channel.paused(), channel.pausedUntil(),
                        channel.lastError()),
                new FeedStatusView.Sources(counts.total(), counts.active(), counts.failing(), counts.lastSuccessAt()),
                new FeedStatusView.Aggregator(adzuna.configured(), usage.requestsToday(SourceKind.ADZUNA),
                        adzuna.dailyRequestBudget()),
                new FeedStatusView.Processing(jobs.countPendingProcessing()),
                enrichment(enrichmentRows.counts()),
                detection());
    }

    private FeedStatusView.Enrichment enrichment(FeedEnrichmentRepository.Counts c) {
        return new FeedStatusView.Enrichment(c.pending(), c.running(), c.failed(), enrichment.modelBusy(),
                enrichment.aiEnabled(), enrichment.pausedUntil(), enrichment.capReachedUntil());
    }

    private FeedStatusView.Detection detection() {
        FeedJobRepository.DetectionStats stats = jobs.detectionStats(clock.instant().minus(DETECTION_WINDOW));
        Long p50 = stats.p50Seconds() == null ? null : Math.round(stats.p50Seconds());
        return new FeedStatusView.Detection(p50, stats.samples(), (int) DETECTION_WINDOW.toDays());
    }
}
