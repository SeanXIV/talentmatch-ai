package com.talentmatch.feed;

import com.talentmatch.preferences.PreferencesRepository;
import com.talentmatch.profile.OwnerProfileRepository;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code GET /api/feed/status} (§5.2): switches, profile and preferences versions, sources and the
 * processing backlog.
 */
@Service
public class FeedStatusService {

    private final FeedSourceRepository sources;
    private final FeedJobRepository jobs;
    private final FeedStateRepository state;
    private final OwnerProfileRepository profiles;
    private final PreferencesRepository preferences;
    private final FeedProperties properties;

    public FeedStatusService(FeedSourceRepository sources, FeedJobRepository jobs, FeedStateRepository state,
                             OwnerProfileRepository profiles, PreferencesRepository preferences,
                             FeedProperties properties) {
        this.sources = sources;
        this.jobs = jobs;
        this.state = state;
        this.profiles = profiles;
        this.preferences = preferences;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public FeedStatusView status() {
        FeedSourceRepository.SourceCounts counts = sources.counts();
        Optional<OwnerProfileRepository.OwnerRef> owner = profiles.findRef();
        Optional<Integer> preferencesVersion = preferences.findVersion();
        FeedStateRepository.State applied = state.find();
        return new FeedStatusView(properties.enabled(), properties.schedulerRunning(),
                new FeedStatusView.Profile(owner.isPresent(),
                        owner.map(OwnerProfileRepository.OwnerRef::version).orElse(null),
                        applied.appliedProfileVersion()),
                new FeedStatusView.Preferences(preferencesVersion.isPresent(), preferencesVersion.orElse(null)),
                new FeedStatusView.Sources(counts.total(), counts.active(), counts.failing(), counts.lastSuccessAt()),
                new FeedStatusView.Processing(jobs.countPendingProcessing()));
    }
}
