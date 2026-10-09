package com.talentmatch.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.talentmatch.feed.source.SourceProperties;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.config.IntervalTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

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

    // ------------------------------------------------------------------ step 7: processor

    @Test
    void processorExecutorIsOneThreadWithQueueOneAndDiscards() {
        runner.withPropertyValues("talentmatch.feed.scheduler.enabled=false").run(ctx -> {
            ThreadPoolTaskExecutor ex = ctx.getBean(FeedConfig.PROCESSOR_EXECUTOR, ThreadPoolTaskExecutor.class);
            assertThat(ex.getCorePoolSize()).isOne();
            assertThat(ex.getMaxPoolSize()).isOne();
            assertThat(ex.getQueueCapacity()).isOne();
            assertThat(ex.getThreadNamePrefix()).isEqualTo("feed-proc-");
            assertThat(ex.getThreadPoolExecutor().getRejectedExecutionHandler())
                    .isInstanceOf(ThreadPoolExecutor.DiscardPolicy.class);
        });
    }

    @Test
    void processorSweepIsRegisteredWhenSchedulingIsOn() {
        FeedProcessor processor = mock(FeedProcessor.class);
        runner.withBean(FeedProcessor.class, () -> processor)
                .withPropertyValues("talentmatch.feed.enabled=true", "talentmatch.feed.scheduler.enabled=true")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();
                    ctx.getBean(FeedConfig.FeedSchedulingConfig.class).configureTasks(registrar);
                    List<IntervalTask> tasks = registrar.getFixedDelayTaskList();
                    assertThat(tasks).hasSize(2);
                    assertThat(tasks.get(0).getIntervalDuration()).isEqualTo(Duration.ofSeconds(15));
                    IntervalTask sweep = tasks.get(1);
                    assertThat(sweep.getIntervalDuration()).isEqualTo(Duration.ofSeconds(30));
                    assertThat(sweep.getInitialDelayDuration()).isEqualTo(Duration.ofSeconds(20));
                    sweep.getRunnable().run();
                    verify(processor).wake();
                });
    }

    @Test
    void noSweepWhenSchedulingIsOff() {
        FeedProcessor processor = mock(FeedProcessor.class);
        runner.withBean(FeedProcessor.class, () -> processor)
                .withPropertyValues("talentmatch.feed.scheduler.enabled=false").run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).doesNotHaveBean(FeedConfig.FeedSchedulingConfig.class);
                    assertThat(ctx).doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class);
                    assertThat(ctx).hasBean(FeedConfig.PROCESSOR_EXECUTOR);
                });
        runner.withBean(FeedProcessor.class, () -> processor)
                .withPropertyValues("talentmatch.feed.enabled=false", "talentmatch.feed.scheduler.enabled=true")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(FeedConfig.FeedSchedulingConfig.class));
    }
}
