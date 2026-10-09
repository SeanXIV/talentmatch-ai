package com.talentmatch.web.dto;

import com.talentmatch.feed.FeedPollService;
import java.util.UUID;

/**
 * {@code 202} for {@code POST /api/feed/sources/{id}/poll} (§5.1). The poll runs in the background;
 * its result appears in the source's {@code lastStatus}.
 *
 * @param queued always true: the poll started, or (every poll thread busy) the source was made due
 *               for the next scheduler tick
 */
public record FeedPollResponse(UUID sourceId, boolean queued) {

    public static FeedPollResponse of(FeedPollService.Queued queued) {
        return new FeedPollResponse(queued.sourceId(), true);
    }
}
