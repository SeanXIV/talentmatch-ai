package com.talentmatch.notify;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * The {@link Notifier} beans by channel. A channel without a notifier is not configured.
 *
 * <p>Two notifiers for one channel fail at construction. Lookups resolve the beans on each call (there is
 * one per channel, so this is cheap), and a notifier that reports no channel is ignored: a test double
 * replacing a notifier bean can then be stubbed after the context has started.
 */
@Component
public class NotificationChannels {

    private final ObjectProvider<Notifier> notifiers;

    public NotificationChannels(ObjectProvider<Notifier> notifiers) {
        this.notifiers = notifiers;
        Map<Channel, Notifier> seen = new EnumMap<>(Channel.class);
        notifiers.orderedStream().forEach(n -> {
            Channel channel = n.channel();
            if (channel == null) {
                return;
            }
            Notifier previous = seen.putIfAbsent(channel, n);
            if (previous != null) {
                throw new IllegalStateException("Two notifiers for channel " + channel + ": "
                        + previous.getClass().getName() + " and " + n.getClass().getName());
            }
        });
    }

    public boolean configured(Channel channel) {
        return notifier(channel).map(Notifier::configured).orElse(false);
    }

    /** The notifier of a channel, configured or not; empty when there is none. */
    public Optional<Notifier> notifier(Channel channel) {
        if (channel == null) {
            return Optional.empty();
        }
        return notifiers.orderedStream().filter(n -> n.channel() == channel).findFirst();
    }
}
