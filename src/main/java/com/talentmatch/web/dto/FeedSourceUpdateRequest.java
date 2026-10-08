package com.talentmatch.web.dto;

import com.talentmatch.feed.FeedSourceService;
import com.talentmatch.feed.FeedSourceState;

/**
 * Body of {@code PUT /api/feed/sources/{id}}: a full replace of the owner-editable fields. The kind,
 * board token and options can't be changed (sending them is an unknown field, 400 MALFORMED_REQUEST);
 * delete the source and add it again instead.
 *
 * @param companyName         null clears it
 * @param state               ACTIVE or PAUSED (required)
 * @param pollIntervalSeconds null restores the kind's default
 */
public record FeedSourceUpdateRequest(String companyName, FeedSourceState state, Integer pollIntervalSeconds) {

    public FeedSourceService.SourceChanges toChanges() {
        return new FeedSourceService.SourceChanges(companyName, state, pollIntervalSeconds);
    }
}
