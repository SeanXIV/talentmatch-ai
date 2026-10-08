package com.talentmatch.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.talentmatch.feed.FeedProperties.Intervals;
import com.talentmatch.feed.source.SourceKind;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** §7 feed-level properties used by step 5: intervals and the probe timeout. */
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
}
