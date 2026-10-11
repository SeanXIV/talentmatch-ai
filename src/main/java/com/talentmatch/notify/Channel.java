package com.talentmatch.notify;

/**
 * A notification channel. Must match the V5 CHECKs on {@code notification_settings.channel} and
 * {@code feed_notification.channel}. Phase 5 has email only; a push channel ("Later") adds a value
 * here and a migration that widens the CHECKs.
 */
public enum Channel {
    EMAIL
}
