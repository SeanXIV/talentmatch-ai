package com.talentmatch.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

/** §4.9: a channel without a notifier is not configured; one notifier per channel. */
class NotificationChannelsTest {

    private record StubNotifier(Channel channel, boolean configured) implements Notifier {
    }

    private static NotificationChannels channels(Notifier... notifiers) {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        for (int i = 0; i < notifiers.length; i++) {
            factory.registerSingleton("notifier" + i, notifiers[i]);
        }
        return new NotificationChannels(factory.getBeanProvider(Notifier.class));
    }

    @Test
    void noNotifierMeansNotConfigured() {
        NotificationChannels c = channels();
        assertThat(c.configured(Channel.EMAIL)).isFalse();
        assertThat(c.configured(null)).isFalse();
    }

    @Test
    void notifierDecides() {
        assertThat(channels(new StubNotifier(Channel.EMAIL, true)).configured(Channel.EMAIL)).isTrue();
        assertThat(channels(new StubNotifier(Channel.EMAIL, false)).configured(Channel.EMAIL)).isFalse();
    }

    @Test
    void twoNotifiersOnOneChannelThrow() {
        assertThatThrownBy(() -> channels(new StubNotifier(Channel.EMAIL, true), new StubNotifier(Channel.EMAIL, false)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("EMAIL");
    }
}
