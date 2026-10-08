package com.talentmatch.feed;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@code GET /api/feed/status} (§5.2), the step-6 part: switches, sources and processing backlog. */
@Service
public class FeedStatusService {

    private final FeedSourceRepository sources;
    private final FeedJobRepository jobs;
    private final FeedProperties properties;

    public FeedStatusService(FeedSourceRepository sources, FeedJobRepository jobs, FeedProperties properties) {
        this.sources = sources;
        this.jobs = jobs;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public FeedStatusView status() {
        FeedSourceRepository.SourceCounts counts = sources.counts();
        return new FeedStatusView(properties.enabled(), properties.schedulerRunning(),
                new FeedStatusView.Sources(counts.total(), counts.active(), counts.failing(), counts.lastSuccessAt()),
                new FeedStatusView.Processing(jobs.countPendingProcessing()));
    }
}
