package com.talentmatch.preferences;

/**
 * Published inside the transaction of a successful {@code PUT /api/preferences}. The feed listens
 * with {@code @TransactionalEventListener} (AFTER_COMMIT) and marks its open jobs for re-evaluation
 * in a transaction of its own (§4.11), so a rolled-back save is never seen and a feed failure never
 * fails the save. The preferences package never imports its listeners.
 *
 * @param version the preferences version just saved
 */
public record PreferencesSavedEvent(int version) {
}
