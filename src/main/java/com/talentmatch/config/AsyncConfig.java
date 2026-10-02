package com.talentmatch.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** Single-thread executor for batch match recomputes: at most one run at a time, no queue. */
@Configuration(proxyBeanMethods = false)
public class AsyncConfig {

    public static final String RECOMPUTE_EXECUTOR = "recomputeExecutor";

    @Bean(RECOMPUTE_EXECUTOR)
    public ThreadPoolTaskExecutor recomputeExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("recompute-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        return executor;
    }
}
