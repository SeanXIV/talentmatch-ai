package com.talentmatch.config;

import com.talentmatch.ai.config.MdcTaskDecorator;
import com.talentmatch.profile.ProfileProperties;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Single-thread executor for CV extraction: one CV at a time (a local model uses the whole CPU),
 * a short queue, abort when full (the CV is then marked FAILED/QUEUE_FULL). Unfinished work is
 * not awaited on shutdown: the interrupted CV stays RUNNING and {@code ProfileRecovery} re-queues
 * it on the next start.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ProfileProperties.class)
public class ProfileConfig {

    public static final String PROFILE_EXECUTOR = "profileExecutor";
    public static final String RESUME_PARSER_EXECUTOR = "resumeParserExecutor";

    @Bean(PROFILE_EXECUTOR)
    public ThreadPoolTaskExecutor profileExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(10);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setThreadNamePrefix("profile-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setAwaitTerminationSeconds(5);
        executor.setTaskDecorator(new MdcTaskDecorator());
        return executor;
    }

    /**
     * PDF parsing during upload, off the request thread so it can be timed out. Two threads and a
     * short queue: a parse that ignores cancellation can hold a thread, but never the whole app
     * (further uploads then get 503 UPLOAD_BUSY until it finishes).
     */
    @Bean(RESUME_PARSER_EXECUTOR)
    public ThreadPoolTaskExecutor resumeParserExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(4);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setThreadNamePrefix("pdf-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setAwaitTerminationSeconds(1);
        executor.setTaskDecorator(new MdcTaskDecorator());
        return executor;
    }
}
