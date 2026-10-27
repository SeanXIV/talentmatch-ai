package com.talentmatch.application;

import com.talentmatch.ai.config.MdcTaskDecorator;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Single-thread executor for document generation (Phase 7 §7, decision d): one generation at a time (a
 * local model uses the whole CPU), a short queue, abort when full (the generation is then marked
 * FAILED/QUEUE_FULL). Unfinished work is not awaited on shutdown: the interrupted generation stays RUNNING
 * and {@link GenerationRecovery} re-queues it on the next start. Exists with AI off too, so a queued
 * generation can still be marked FAILED/AI_DISABLED.
 */
@Configuration(proxyBeanMethods = false)
public class ApplicationConfig {

    public static final String APPLICATION_EXECUTOR = "applicationExecutor";

    @Bean(APPLICATION_EXECUTOR)
    public ThreadPoolTaskExecutor applicationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(10);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setThreadNamePrefix("application-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setAwaitTerminationSeconds(5);
        executor.setTaskDecorator(new MdcTaskDecorator());
        return executor;
    }
}
