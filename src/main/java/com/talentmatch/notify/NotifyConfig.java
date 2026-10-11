package com.talentmatch.notify;

import com.talentmatch.ai.config.MdcTaskDecorator;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Notification beans (§4.9).
 * <ul>
 *   <li>{@link EmailNotifier}: always present. The email settings are checked first
 *       ({@link EmailSettings#validate}); a bad combination fails startup with a
 *       {@link NotifyConfigurationException} that names the variable to fix and never its value. Exactly
 *       one INFO line says whether email is on.</li>
 *   <li>{@code notifyDispatchExecutor}: the single {@code notify-} thread of {@link NotificationDispatcher}.
 *       The queue holds one follow-up run; further wake-ups are dropped (the queued run covers them). On
 *       shutdown it waits up to 40s so an in-flight send (at most about 3 × 10s) can record its result.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(NotifyProperties.class)
public class NotifyConfig {

    public static final String DISPATCH_EXECUTOR = "notifyDispatchExecutor";

    private static final Logger log = LoggerFactory.getLogger(NotifyConfig.class);

    @Bean
    public EmailNotifier emailNotifier(NotifyProperties properties, Clock clock) {
        NotifyProperties.Email email = properties.email();
        EmailSettings.validate(email);
        EmailNotifier notifier = new EmailNotifier(properties, clock);
        if (notifier.configured()) {
            // The host is not a secret; no address, username or password is logged.
            log.info("Email notifications ready: host={} port={} security={}", email.host(), email.port(),
                    email.security());
        } else {
            List<String> missing = EmailSettings.missing(email);
            log.info("Email notifications are off: SMTP is not configured (set SMTP_HOST, NOTIFY_EMAIL_FROM and "
                    + "NOTIFY_EMAIL_TO).{}", missing.size() == 3 ? "" : " Missing: " + String.join(", ", missing) + ".");
        }
        return notifier;
    }

    @Bean(DISPATCH_EXECUTOR)
    public ThreadPoolTaskExecutor notifyDispatchExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1);
        executor.setThreadNamePrefix("notify-");
        executor.setTaskDecorator(new MdcTaskDecorator());
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(40);
        return executor;
    }
}
