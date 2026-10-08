package com.talentmatch.web.dto;

import com.talentmatch.feed.FeedSource;
import com.talentmatch.feed.FeedSourceState;
import com.talentmatch.feed.FeedSourceView;
import com.talentmatch.feed.SourceManagedBy;
import com.talentmatch.feed.source.SourceKind;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A watched job source (§5.1). Never includes the ETag, body hash or any API key.
 *
 * @param effectivePollIntervalSeconds the interval actually used
 * @param warnings                     notes from adding the source; empty on every other response
 */
public record FeedSourceResponse(
        UUID id,
        SourceKind kind,
        SourceManagedBy managedBy,
        FeedSourceState state,
        String companyName,
        String boardToken,
        Map<String, String> options,
        Integer pollIntervalSeconds,
        int effectivePollIntervalSeconds,
        Instant nextPollAt,
        Instant lastPolledAt,
        Instant lastSuccessAt,
        String lastStatus,
        String lastError,
        int consecutiveFailures,
        int openPostings,
        Instant baselineAt,
        Instant createdAt,
        List<String> warnings) {

    public static FeedSourceResponse of(FeedSourceView view) {
        FeedSource s = view.source();
        return new FeedSourceResponse(s.id(), s.kind(), s.managedBy(), s.state(), s.companyName(), s.boardToken(),
                s.options(), s.pollIntervalSeconds(), view.effectivePollIntervalSeconds(), s.nextPollAt(),
                s.lastPolledAt(), s.lastSuccessAt(), s.lastStatus(), s.lastError(), s.consecutiveFailures(),
                s.openPostings(), s.baselineAt(), s.createdAt(), view.warnings());
    }
}
