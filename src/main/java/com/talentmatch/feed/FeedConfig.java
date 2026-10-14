package com.talentmatch.feed;

import com.talentmatch.ai.config.MdcTaskDecorator;
import com.talentmatch.feed.skills.SkillsProperties;
import com.talentmatch.feed.source.SourceProperties;
import com.talentmatch.notify.NotificationDispatcher;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * Feed threads (§4.1).
 * <ul>
 *   <li>{@code feedPollExecutor}: {@code poll-threads} (2) threads, no queue, AbortPolicy, prefix
 *       {@code feed-poll-}, MDC copied, no waiting on shutdown. A rejected poll gives its lease back,
 *       so the source stays due and is claimed on a later tick.</li>
 *   <li>Scheduling is switched on only when {@code talentmatch.feed.enabled} and
 *       {@code talentmatch.feed.scheduler.enabled} are both true (both default to true; tests set the
 *       scheduler off and call {@link FeedScheduler#tick()} themselves). The tick delays come from
 *       {@link FeedProperties.Scheduler}.</li>
 *   <li>{@code feedAiExecutor}: the single {@code feed-ai-} thread of {@link JobSkillEnrichmentService}
 *       (one follow-up run queued, further wake-ups dropped); its sweep ({@code skills.enrichment.sweep},
 *       60s) is registered with the scheduler tick, like the processor's.</li>
 *   <li>{@code feedProcessorExecutor}: the single {@code feed-proc-} thread of {@link FeedProcessor}. Its
 *       sweep ({@code processor.sweep}, 30s) is registered with the scheduler tick, so the processor runs
 *       in the background exactly when the poller does; tests call {@link FeedProcessor#processDue()}.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class FeedConfig {

    public static final String POLL_EXECUTOR = "feedPollExecutor";
    public static final String PROCESSOR_EXECUTOR = "feedProcessorExecutor";
    public static final String AI_EXECUTOR = "feedAiExecutor";

    /** One {@code feed-ai-} thread; a queued follow-up run covers further wake-ups (as the processor). */
    @Bean(AI_EXECUTOR)
    public ThreadPoolTaskExecutor feedAiExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1);
        executor.setThreadNamePrefix("feed-ai-");
        executor.setTaskDecorator(new MdcTaskDecorator());
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
        // A shutdown interrupts a running call; the job is left RUNNING and FeedRecovery resets it.
        executor.setWaitForTasksToCompleteOnShutdown(false);
        return executor;
    }

    /**
     * One {@code feed-proc-} thread. The queue holds a single follow-up run: a wake-up while a run is in
     * progress queues one more run (which sees everything that became due meanwhile); further wake-ups
     * are dropped because that queued run covers them.
     */
    @Bean(PROCESSOR_EXECUTOR)
    public ThreadPoolTaskExecutor feedProcessorExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1);
        executor.setThreadNamePrefix("feed-proc-");
        executor.setTaskDecorator(new MdcTaskDecorator());
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(false);
        return executor;
    }

    @Bean(POLL_EXECUTOR)
    public ThreadPoolTaskExecutor feedPollExecutor(SourceProperties sourceProperties) {
        int threads = sourceProperties.http().pollThreads();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(threads);
        executor.setMaxPoolSize(threads);
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("feed-poll-");
        executor.setTaskDecorator(new MdcTaskDecorator());
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(false);
        return executor;
    }

    /**
     * Registers the scheduler tick and the processor sweep; absent (no scheduling at all) when the feed
     * or its scheduler is off.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnBooleanProperty(name = {"talentmatch.feed.enabled", "talentmatch.feed.scheduler.enabled"},
            matchIfMissing = true)
    static class FeedSchedulingConfig implements SchedulingConfigurer {

        private final FeedScheduler scheduler;
        private final FeedProperties properties;
        private final ObjectProvider<FeedProcessor> processor;
        private final ObjectProvider<NotificationDispatcher> dispatcher;
        private final ObjectProvider<JobSkillEnrichmentService> enrichment;
        private final SkillsProperties skills;

        FeedSchedulingConfig(FeedScheduler scheduler, FeedProperties properties,
                             ObjectProvider<FeedProcessor> processor, ObjectProvider<NotificationDispatcher> dispatcher,
                             ObjectProvider<JobSkillEnrichmentService> enrichment, SkillsProperties skills) {
            this.scheduler = scheduler;
            this.properties = properties;
            this.processor = processor;
            this.dispatcher = dispatcher;
            this.enrichment = enrichment;
            this.skills = skills;
        }

        @Override
        public void configureTasks(ScheduledTaskRegistrar registrar) {
            FeedProperties.Scheduler s = properties.scheduler();
            registrar.addFixedDelayTask(new FixedDelayTask(scheduler::scheduledTick, s.tick(), s.initialDelay()));
            FeedProcessor p = processor.getIfAvailable();
            if (p != null) {
                registrar.addFixedDelayTask(new FixedDelayTask(p::wake, properties.processor().sweep(),
                        s.initialDelay()));
            }
            // The notification dispatcher's sweep reuses processor.sweep (D9); it also picks up rows whose
            // next_attempt_at has come, and rows waiting for the hourly cap or a pause to end.
            NotificationDispatcher d = dispatcher.getIfAvailable();
            if (d != null) {
                registrar.addFixedDelayTask(new FixedDelayTask(d::wake, properties.processor().sweep(),
                        s.initialDelay()));
            }
            JobSkillEnrichmentService e = enrichment.getIfAvailable();
            if (e != null) {
                registrar.addFixedDelayTask(new FixedDelayTask(e::wake, skills.enrichment().sweep(),
                        s.initialDelay()));
            }
        }
    }
}
