package com.talentmatch.support;

import com.talentmatch.notify.Channel;
import com.talentmatch.notify.Notifier;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * An EMAIL {@link Notifier} for step-7 ITs (the real email notifier arrives with step 8). Whether it
 * counts as configured is switched per test through {@link #CONFIGURED} (reset to true by
 * {@link AbstractFeedProcessingIT}).
 */
public class StubNotifier implements Notifier {

    public static final AtomicBoolean CONFIGURED = new AtomicBoolean(true);

    @Override
    public Channel channel() {
        return Channel.EMAIL;
    }

    @Override
    public boolean configured() {
        return CONFIGURED.get();
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class Config {

        @Bean
        StubNotifier stubNotifier() {
            return new StubNotifier();
        }
    }
}
