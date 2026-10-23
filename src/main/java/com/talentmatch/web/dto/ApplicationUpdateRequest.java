package com.talentmatch.web.dto;

import com.talentmatch.application.ApplicationService;
import java.util.UUID;

/**
 * Body of {@code PUT /api/applications/{id}}: a full replacement of the tracker fields. Unknown fields
 * are rejected by the web layer (400 MALFORMED_REQUEST). {@code status} and {@code appliedOn} are
 * strings so invalid values are field errors with the §5.3 messages.
 *
 * @param status            the target status (required)
 * @param appliedOn         yyyy-MM-dd, applied statuses only; default today when entering them
 * @param notes             null clears them
 * @param sentCvId          an approved CV version of this application, applied statuses only
 * @param sentCoverLetterId an approved cover letter version of this application, applied statuses only
 * @param force             allow a move outside the transition table (recorded in the history)
 */
public record ApplicationUpdateRequest(String status, String appliedOn, String notes, UUID sentCvId,
                                       UUID sentCoverLetterId, Boolean force) {

    public ApplicationService.Changes toChanges() {
        return new ApplicationService.Changes(status, appliedOn, notes, sentCvId, sentCoverLetterId, force);
    }
}
