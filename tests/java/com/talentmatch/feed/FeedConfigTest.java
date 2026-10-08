package com.talentmatch.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.talentmatch.feed.source.SourceProperties;
import java.util.concurrent.ThreadPoolExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** §4.1 scheduler wiring: scheduling exists only when the feed and its scheduler are both enabled. */
class FeedConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(FeedConfig.class)
            .withBean(SourceProperties.class, SourceProperties::defaults)
            .withBean(FeedProperties.class, FeedProperties::defaults)
            .withBean(FeedScheduler.class, () -> mock(FeedScheduler.class));

    @Test
    void schedulerDisabledMeansNoSchedulingBeans() {
        runner.withPropertyValues("talentmatch.feed.scheduler.enabled=false").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).doesNotHaveBean(FeedConfig.FeedSchedulingConfig.class);
            assertThat(ctx).doesNotHaveBean(SchedulingConfigurer.class);
            assertThat(ctx).doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class);
            assertThat(ctx).hasBean(FeedConfig.POLL_EXECUTOR);
        });
    }

    @Test
    void feedDisabledMeansNoSchedulingBeans() {
        runner.withPropertyValues("talentmatch.feed.enabled=false", "talentmatch.feed.scheduler.enabled=true")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).doesNotHaveBean(FeedConfig.FeedSchedulingConfig.class);
                    assertThat(ctx).doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class);
                });
    }

    @Test
    void bothEnabledOrMissingRegistersScheduling() {
        runner.withPropertyValues("talentmatch.feed.enabled=true", "talentmatch.feed.scheduler.enabled=true")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasSingleBean(FeedConfig.FeedSchedulingConfig.class);
                    assertThat(ctx).hasSingleBean(ScheduledAnnotationBeanPostProcessor.class);
                });
        runner.run(ctx -> assertThat(ctx).hasSingleBean(FeedConfig.FeedSchedulingConfig.class));
    }

    @Test
    void pollExecutorHasNoQueueAndAborts() {
        runner.withPropertyValues("talentmatch.feed.scheduler.enabled=false").run(ctx -> {
            ThreadPoolTaskExecutor ex = ctx.getBean(FeedConfig.POLL_EXECUTOR, ThreadPoolTaskExecutor.class);
            assertThat(ex.getCorePoolSize()).isEqualTo(2);
            assertThat(ex.getMaxPoolSize()).isEqualTo(2);
            assertThat(ex.getQueueCapacity()).isZero();
            assertThat(ex.getThreadNamePrefix()).isEqualTo("feed-poll-");
            assertThat(ex.getThreadPoolExecutor().getRejectedExecutionHandler())
                    .isInstanceOf(ThreadPoolExecutor.AbortPolicy.class);
        });
    }
}
