package com.talentmatch.notify;

import java.util.EnumMap;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** The {@link Notifier} beans by channel. A channel without a notifier is not configured. */
@Component
public class NotificationChannels {

    private final Map<Channel, Notifier> notifiers = new EnumMap<>(Channel.class);

    public NotificationChannels(ObjectProvider<Notifier> notifiers) {
        notifiers.orderedStream().forEach(n -> {
            Notifier previous = this.notifiers.putIfAbsent(n.channel(), n);
            if (previous != null) {
                throw new IllegalStateException("Two notifiers for channel " + n.channel() + ": "
                        + previous.getClass().getName() + " and " + n.getClass().getName());
            }
        });
    }

    public boolean configured(Channel channel) {
        Notifier n = channel == null ? null : notifiers.get(channel);
        return n != null && n.configured();
    }
}
