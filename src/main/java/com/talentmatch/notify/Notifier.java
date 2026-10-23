package com.talentmatch.notify;

/**
 * One notification channel (§4.9). Phase 5 has one implementation, {@link EmailNotifier}. The
 * dispatcher, the settings and the history use only this interface, so a push channel later adds one
 * class plus a migration that widens the {@code channel} CHECKs.
 */
public interface Notifier {

    Channel channel();

    /** True when the channel can deliver (for email: host, from and to are set). Never opens a connection. */
    boolean configured();

    /**
     * Delivers one message, synchronously, bounded by the channel's own timeouts. Must never be called
     * inside a database transaction.
     *
     * @throws NotifyException with the classified, sanitized failure (no addresses, credentials or
     *                         server reply text)
     */
    void send(NotificationMessage message) throws NotifyException;
}
