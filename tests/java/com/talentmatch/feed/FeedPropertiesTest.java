package com.talentmatch.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.talentmatch.feed.FeedProperties.Intervals;
import com.talentmatch.feed.source.SourceKind;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** §7 feed-level properties: intervals and the probe timeout (step 5); switches, scheduler, window, lease, closing (step 6). */
class FeedPropertiesTest {

    private static final Duration H1 = Duration.ofHours(1);

    private static Intervals intervals(Duration ats, Duration atsMin, Duration agg, Duration aggMin) {
        return new Intervals(ats, atsMin, agg, aggMin, H1, Duration.ofHours(6));
    }

    @Test
    void defaults() {
        Intervals i = Intervals.defaults();
        assertThat(i.defaultSeconds(SourceKind.GREENHOUSE)).isEqualTo(300);
        assertThat(i.minSeconds(SourceKind.LEVER)).isEqualTo(120);
        assertThat(i.defaultSeconds(SourceKind.ADZUNA)).isEqualTo(900);
        assertThat(i.minSeconds(SourceKind.ADZUNA)).isEqualTo(600);
        FeedProperties p = FeedProperties.defaults();
        assertThat(p.probeTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(new FeedProperties(null, Duration.ofSeconds(5)).intervals()).isEqualTo(Intervals.defaults());
    }

    @Test
    void effectiveSecondsNullGivesDefault() {
        Intervals i = Intervals.defaults();
        assertThat(i.effectiveSeconds(SourceKind.ASHBY, null)).isEqualTo(300);
        assertThat(i.effectiveSeconds(SourceKind.ADZUNA, null)).isEqualTo(900);
    }

    @Test
    void effectiveSecondsKeepsValidAndRaisesBelowMinimum() {
        Intervals i = Intervals.defaults();
        assertThat(i.effectiveSeconds(SourceKind.LEVER, 3600)).isEqualTo(3600);
        assertThat(i.effectiveSeconds(SourceKind.LEVER, 120)).isEqualTo(120);
        assertThat(i.effectiveSeconds(SourceKind.LEVER, 60)).isEqualTo(120);
        assertThat(i.effectiveSeconds(SourceKind.ADZUNA, 120)).isEqualTo(600);
    }

    @Test
    void intervalRange() {
        assertThatThrownBy(() -> intervals(Duration.ofMinutes(5), Duration.ofSeconds(59), Duration.ofMinutes(15),
                Duration.ofMinutes(10))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ats-min");
        assertThatThrownBy(() -> intervals(Duration.ofHours(25), Duration.ofMinutes(2), Duration.ofMinutes(15),
                Duration.ofMinutes(10))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> intervals(null, Duration.ofMinutes(2), Duration.ofMinutes(15),
                Duration.ofMinutes(10))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Intervals(Duration.ofMinutes(5), Duration.ofMinutes(2), Duration.ofMinutes(15),
                Duration.ofMinutes(10), Duration.ofSeconds(30), Duration.ofHours(6)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("max-backoff");
        assertThatThrownBy(() -> new Intervals(Duration.ofMinutes(5), Duration.ofMinutes(2), Duration.ofMinutes(15),
                Duration.ofMinutes(10), H1, Duration.ofHours(48)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not-found-backoff");
        // bounds are inclusive
        Intervals edge = intervals(Duration.ofHours(24), Duration.ofMinutes(1), Duration.ofHours(24),
                Duration.ofMinutes(1));
        assertThat(edge.minSeconds(SourceKind.GREENHOUSE)).isEqualTo(60);
        assertThat(edge.defaultSeconds(SourceKind.GREENHOUSE)).isEqualTo(86_400);
    }

    @Test
    void intervalOrder() {
        assertThatThrownBy(() -> intervals(Duration.ofMinutes(1), Duration.ofMinutes(2), Duration.ofMinutes(15),
                Duration.ofMinutes(10))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ats-min");
        assertThatThrownBy(() -> intervals(Duration.ofMinutes(5), Duration.ofMinutes(2), Duration.ofMinutes(5),
                Duration.ofMinutes(10))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("aggregator-min");
        // equal is fine
        assertThat(intervals(Duration.ofMinutes(2), Duration.ofMinutes(2), Duration.ofMinutes(10),
                Duration.ofMinutes(10)).effectiveSeconds(SourceKind.GREENHOUSE, null)).isEqualTo(120);
    }

    @Test
    void probeTimeoutBounds() {
        assertThat(new FeedProperties(null, Duration.ofSeconds(1)).probeTimeout()).isEqualTo(Duration.ofSeconds(1));
        assertThat(new FeedProperties(null, Duration.ofSeconds(60)).probeTimeout()).isEqualTo(Duration.ofSeconds(60));
        assertThatThrownBy(() -> new FeedProperties(null, Duration.ofMillis(999)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("probe-timeout");
        assertThatThrownBy(() -> new FeedProperties(null, Duration.ofSeconds(61)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FeedProperties(null, null)).isInstanceOf(IllegalArgumentException.class);
    }
    // ------------------------------------------------------------------ step 6 keys

    private static FeedProperties full(Duration freshWindow, Duration lease, FeedProperties.Closing closing) {
        return new FeedProperties(true, null, null, Duration.ofSeconds(10), freshWindow, lease, closing);
    }

    @Test
    void stepSixDefaults() {
        FeedProperties p = FeedProperties.defaults();
        assertThat(p.enabled()).isTrue();
        assertThat(p.scheduler()).isEqualTo(FeedProperties.Scheduler.defaults());
        assertThat(p.scheduler().enabled()).isTrue();
        assertThat(p.scheduler().tick()).isEqualTo(Duration.ofSeconds(15));
        assertThat(p.scheduler().initialDelay()).isEqualTo(Duration.ofSeconds(20));
        assertThat(p.freshWindow()).isEqualTo(Duration.ofHours(24));
        assertThat(p.lease()).isEqualTo(Duration.ofMinutes(5));
        assertThat(p.closing().suspiciousDropRatio()).isEqualTo(0.5);
        assertThat(p.schedulerRunning()).isTrue();
        // nulls in the full constructor take the defaults
        FeedProperties nulls = new FeedProperties(false, null, null, Duration.ofSeconds(10), null, null, null);
        assertThat(nulls.freshWindow()).isEqualTo(Duration.ofHours(24));
        assertThat(nulls.lease()).isEqualTo(Duration.ofMinutes(5));
        assertThat(nulls.closing()).isEqualTo(FeedProperties.Closing.defaults());
        assertThat(nulls.intervals()).isEqualTo(Intervals.defaults());
    }

    @Test
    void twoArgumentConstructorKeepsStepFiveShape() {
        FeedProperties p = new FeedProperties(Intervals.defaults(), Duration.ofSeconds(7));
        assertThat(p.enabled()).isTrue();
        assertThat(p.probeTimeout()).isEqualTo(Duration.ofSeconds(7));
        assertThat(p.freshWindow()).isEqualTo(Duration.ofHours(24));
        assertThat(p.lease()).isEqualTo(Duration.ofMinutes(5));
        assertThat(p.scheduler().enabled()).isTrue();
    }

    @Test
    void schedulerRunningNeedsBothSwitches() {
        FeedProperties.Scheduler off = new FeedProperties.Scheduler(false, null, null);
        assertThat(new FeedProperties(true, off, null, Duration.ofSeconds(10), null, null, null).schedulerRunning())
                .isFalse();
        assertThat(new FeedProperties(false, null, null, Duration.ofSeconds(10), null, null, null).schedulerRunning())
                .isFalse();
    }

    @Test
    void freshWindowRange() {
        assertThat(full(Duration.ofHours(1), null, null).freshWindow()).isEqualTo(Duration.ofHours(1));
        assertThat(full(Duration.ofDays(7), null, null).freshWindow()).isEqualTo(Duration.ofDays(7));
        assertThatThrownBy(() -> full(Duration.ofMinutes(59), null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("fresh-window");
        assertThatThrownBy(() -> full(Duration.ofDays(7).plusSeconds(1), null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void leaseRange() {
        assertThat(full(null, Duration.ofMinutes(1), null).lease()).isEqualTo(Duration.ofMinutes(1));
        assertThat(full(null, Duration.ofHours(1), null).lease()).isEqualTo(Duration.ofHours(1));
        assertThatThrownBy(() -> full(null, Duration.ofSeconds(59), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("lease");
        assertThatThrownBy(() -> full(null, Duration.ofMinutes(61), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void closingRatioRange() {
        assertThat(new FeedProperties.Closing(0.1).suspiciousDropRatio()).isEqualTo(0.1);
        assertThat(new FeedProperties.Closing(0.95).suspiciousDropRatio()).isEqualTo(0.95);
        assertThatThrownBy(() -> new FeedProperties.Closing(0.09)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("suspicious-drop-ratio");
        assertThatThrownBy(() -> new FeedProperties.Closing(0.96)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FeedProperties.Closing(Double.NaN)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void schedulerRanges() {
        FeedProperties.Scheduler edge = new FeedProperties.Scheduler(true, Duration.ofSeconds(1), Duration.ZERO);
        assertThat(edge.tick()).isEqualTo(Duration.ofSeconds(1));
        assertThat(new FeedProperties.Scheduler(true, Duration.ofMinutes(10), Duration.ofMinutes(10)).tick())
                .isEqualTo(Duration.ofMinutes(10));
        assertThatThrownBy(() -> new FeedProperties.Scheduler(true, Duration.ofMillis(999), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("scheduler.tick");
        assertThatThrownBy(() -> new FeedProperties.Scheduler(true, Duration.ofMinutes(11), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FeedProperties.Scheduler(true, null, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("initial-delay");
        assertThatThrownBy(() -> new FeedProperties.Scheduler(true, null, Duration.ofMinutes(11)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------ step 7: processor

    @Test
    void processorDefaultsWithEveryConstructor() {
        FeedProperties.Processor d = FeedProperties.Processor.defaults();
        assertThat(d.sweep()).isEqualTo(Duration.ofSeconds(30));
        assertThat(d.batchSize()).isEqualTo(50);
        assertThat(d.retryDelay()).isEqualTo(Duration.ofMinutes(1));
        assertThat(FeedProperties.defaults().processor()).isEqualTo(d);
        assertThat(new FeedProperties(Intervals.defaults(), Duration.ofSeconds(10)).processor()).isEqualTo(d);
        assertThat(new FeedProperties(true, null, null, Duration.ofSeconds(10), null, null, null).processor())
                .as("step-6 constructor").isEqualTo(d);
        assertThat(new FeedProperties(true, null, null, Duration.ofSeconds(10), null, null, null, null).processor())
                .as("full constructor, null processor").isEqualTo(d);
        FeedProperties.Processor custom = new FeedProperties.Processor(Duration.ofSeconds(5), 10, Duration.ofSeconds(2));
        assertThat(new FeedProperties(true, null, null, Duration.ofSeconds(10), null, null, null, custom).processor())
                .isEqualTo(custom);
        // null durations in the record take their defaults
        FeedProperties.Processor nulls = new FeedProperties.Processor(null, 7, null);
        assertThat(nulls.sweep()).isEqualTo(Duration.ofSeconds(30));
        assertThat(nulls.retryDelay()).isEqualTo(Duration.ofMinutes(1));
        assertThat(nulls.batchSize()).isEqualTo(7);
    }

    @Test
    void processorRanges() {
        // inclusive bounds
        assertThat(new FeedProperties.Processor(Duration.ofSeconds(1), 1, Duration.ofSeconds(1)).batchSize()).isOne();
        assertThat(new FeedProperties.Processor(Duration.ofMinutes(10), 500, Duration.ofHours(1)).batchSize())
                .isEqualTo(500);
        assertThatThrownBy(() -> new FeedProperties.Processor(Duration.ofMillis(999), 50, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("processor.sweep");
        assertThatThrownBy(() -> new FeedProperties.Processor(Duration.ofMinutes(10).plusSeconds(1), 50, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("processor.sweep");
        assertThatThrownBy(() -> new FeedProperties.Processor(null, 0, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("processor.batch-size");
        assertThatThrownBy(() -> new FeedProperties.Processor(null, 501, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("processor.batch-size");
        assertThatThrownBy(() -> new FeedProperties.Processor(null, 50, Duration.ofMillis(999)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("processor.retry-delay");
        assertThatThrownBy(() -> new FeedProperties.Processor(null, 50, Duration.ofHours(1).plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("processor.retry-delay");
    }
}
