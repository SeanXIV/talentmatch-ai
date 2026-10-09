package com.talentmatch.notify;

/**
 * One notification channel (§4.9). The feed processor only asks whether a channel is configured
 * before it queues a {@code feed_notification} row; sending ({@code send(NotificationMessage)}) and
 * the email implementation arrive with step 8. With no {@code Notifier} bean, every channel counts as
 * not configured and no notification row is ever written.
 */
public interface Notifier {

    Channel channel();

    /** True when the channel can deliver (for email: host, from and to are set). Never opens a connection. */
    boolean configured();
}
