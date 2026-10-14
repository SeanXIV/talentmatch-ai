package com.talentmatch.notify;

import java.util.Optional;
import java.util.UUID;

/**
 * Where the dispatcher gets the job facts of a notification. Owned by {@code notify} and implemented by
 * {@code feed} ({@code FeedNotificationContent}), so the package direction stays {@code feed → notify}.
 * Implementations must only read (no locks): the dispatcher calls it between claiming a row and sending.
 */
public interface NotificationContentSource {

    /** The job's facts now; empty when the job no longer exists. */
    Optional<NotificationContent> load(UUID jobId);
}
