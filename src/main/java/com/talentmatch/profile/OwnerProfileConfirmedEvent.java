package com.talentmatch.profile;

import java.util.UUID;

/**
 * Published inside the transaction of a successful {@code PUT /api/profile}. Listeners that act on
 * it use {@code @TransactionalEventListener} (AFTER_COMMIT), so a rolled-back save is never seen.
 * The profile package never imports its listeners (the feed re-scores open jobs on it).
 *
 * @param version     the owner profile version just confirmed
 * @param candidateId the owner's candidate row
 */
public record OwnerProfileConfirmedEvent(int version, UUID candidateId) {
}
