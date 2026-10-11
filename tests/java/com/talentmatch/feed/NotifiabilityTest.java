package com.talentmatch.feed;

import static org.assertj.core.api.Assertions.assertThat;

import com.talentmatch.feed.Notifiability.Decision;
import com.talentmatch.feed.Notifiability.Input;
import com.talentmatch.feed.Notifiability.Reason;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** §4.8 notifiable rule (decision g): every reason, in order; boundaries. */
class NotifiabilityTest {

    private static final Instant NOW = Instant.parse("2026-10-08T12:00:00Z");
    private static final Duration WINDOW = Duration.ofHours(24);
    private static final Instant FRESH = NOW.minus(Duration.ofHours(1));

    /** Everything holds. */
    private static Input ok() {
        return new Input(null, false, FRESH, true, 0.8, true, 0.6, true, false);
    }

    private static Decision decide(Input in) {
        return Notifiability.decide(in, NOW, WINDOW);
    }

    @Test
    void allRulesHoldNotifies() {
        Decision d = decide(ok());
        assertThat(d.shouldNotify()).isTrue();
        assertThat(d.reason()).isNull();
    }

    @Test
    void eachReasonOnItsOwn() {
        assertThat(decide(new Input(NOW, false, FRESH, true, 0.8, true, 0.6, true, false)).reason())
                .isEqualTo(Reason.CLOSED);
        assertThat(decide(new Input(null, true, FRESH, true, 0.8, true, 0.6, true, false)).reason())
                .isEqualTo(Reason.BASELINE);
        assertThat(decide(new Input(null, false, NOW.minus(Duration.ofHours(25)), true, 0.8, true, 0.6, true, false))
                .reason()).isEqualTo(Reason.NOT_FRESH);
        assertThat(decide(new Input(null, false, null, true, 0.8, true, 0.6, true, false)).reason())
                .as("unknown first-seen time").isEqualTo(Reason.NOT_FRESH);
        assertThat(decide(new Input(null, false, FRESH, false, 0.8, true, 0.6, true, false)).reason())
                .isEqualTo(Reason.FILTERED);
        assertThat(decide(new Input(null, false, FRESH, true, null, true, 0.6, true, false)).reason())
                .isEqualTo(Reason.NOT_SCORED);
        assertThat(decide(new Input(null, false, FRESH, true, Double.NaN, true, 0.6, true, false)).reason())
                .isEqualTo(Reason.NOT_SCORED);
        assertThat(decide(new Input(null, false, FRESH, true, 0.59, true, 0.6, true, false)).reason())
                .isEqualTo(Reason.BELOW_THRESHOLD);
        assertThat(decide(new Input(null, false, FRESH, true, 0.8, false, 0.6, true, false)).reason())
                .isEqualTo(Reason.DISABLED);
        assertThat(decide(new Input(null, false, FRESH, true, 0.8, true, 0.6, true, true)).reason())
                .isEqualTo(Reason.ALREADY_NOTIFIED);
        assertThat(decide(new Input(null, false, FRESH, true, 0.8, true, 0.6, false, false)).reason())
                .isEqualTo(Reason.CHANNEL_NOT_CONFIGURED);
    }

    @Test
    void reasonsAreCheckedInOrder() {
        // everything fails: each step removes the first failing rule and expects the next one
        Instant closed = NOW;
        Instant stale = NOW.minus(Duration.ofDays(2));
        assertThat(decide(new Input(closed, true, stale, false, null, false, 0.6, false, true)).reason())
                .isEqualTo(Reason.CLOSED);
        assertThat(decide(new Input(null, true, stale, false, null, false, 0.6, false, true)).reason())
                .isEqualTo(Reason.BASELINE);
        assertThat(decide(new Input(null, false, stale, false, null, false, 0.6, false, true)).reason())
                .isEqualTo(Reason.NOT_FRESH);
        assertThat(decide(new Input(null, false, FRESH, false, null, false, 0.6, false, true)).reason())
                .isEqualTo(Reason.FILTERED);
        assertThat(decide(new Input(null, false, FRESH, true, null, false, 0.6, false, true)).reason())
                .isEqualTo(Reason.NOT_SCORED);
        assertThat(decide(new Input(null, false, FRESH, true, 0.1, false, 0.6, false, true)).reason())
                .isEqualTo(Reason.BELOW_THRESHOLD);
        assertThat(decide(new Input(null, false, FRESH, true, 0.9, false, 0.6, false, true)).reason())
                .isEqualTo(Reason.DISABLED);
        assertThat(decide(new Input(null, false, FRESH, true, 0.9, true, 0.6, false, true)).reason())
                .isEqualTo(Reason.ALREADY_NOTIFIED);
        assertThat(decide(new Input(null, false, FRESH, true, 0.9, true, 0.6, false, false)).reason())
                .isEqualTo(Reason.CHANNEL_NOT_CONFIGURED);
        assertThat(Reason.values()).containsExactly(Reason.CLOSED, Reason.BASELINE, Reason.NOT_FRESH,
                Reason.FILTERED, Reason.NOT_SCORED, Reason.BELOW_THRESHOLD, Reason.DISABLED,
                Reason.ALREADY_NOTIFIED, Reason.CHANNEL_NOT_CONFIGURED);
    }

    @Test
    void scoreEqualToTheThresholdNotifies() {
        assertThat(decide(new Input(null, false, FRESH, true, 0.6, true, 0.6, true, false)).shouldNotify()).isTrue();
        // 3/5 computed in floating point
        double threeFifths = 3.0 / 5.0;
        assertThat(decide(new Input(null, false, FRESH, true, threeFifths, true, 0.6, true, false)).shouldNotify())
                .isTrue();
        assertThat(decide(new Input(null, false, FRESH, true, 0.6 - 1e-9 * 0.5, true, 0.6, true, false))
                .shouldNotify()).as("within epsilon").isTrue();
        assertThat(decide(new Input(null, false, FRESH, true, 0.5999, true, 0.6, true, false)).reason())
                .isEqualTo(Reason.BELOW_THRESHOLD);
        assertThat(decide(new Input(null, false, FRESH, true, 0.0, true, 0.0, true, false)).shouldNotify())
                .as("min 0 accepts a zero score").isTrue();
    }

    @Test
    void freshnessBoundary() {
        Instant exactlyWindowAgo = NOW.minus(WINDOW);
        assertThat(decide(new Input(null, false, exactlyWindowAgo, true, 0.8, true, 0.6, true, false)).reason())
                .as("first seen exactly now - 24h is not fresh").isEqualTo(Reason.NOT_FRESH);
        assertThat(decide(new Input(null, false, exactlyWindowAgo.plusMillis(1), true, 0.8, true, 0.6, true, false))
                .shouldNotify()).isTrue();
        assertThat(decide(new Input(null, false, NOW, true, 0.8, true, 0.6, true, false)).shouldNotify()).isTrue();
    }

    @Test
    void channelNotConfiguredOnlyWhenEverythingElsePasses() {
        // with the channel off, any other failing rule wins
        assertThat(decide(new Input(null, false, FRESH, true, 0.8, false, 0.6, false, false)).reason())
                .isEqualTo(Reason.DISABLED);
        assertThat(decide(new Input(null, false, FRESH, true, 0.8, true, 0.6, false, true)).reason())
                .isEqualTo(Reason.ALREADY_NOTIFIED);
        assertThat(decide(new Input(null, false, FRESH, true, 0.2, true, 0.6, false, false)).reason())
                .isEqualTo(Reason.BELOW_THRESHOLD);
        assertThat(decide(new Input(null, true, FRESH, true, 0.8, true, 0.6, false, false)).reason())
                .isEqualTo(Reason.BASELINE);
        Decision d = decide(new Input(null, false, FRESH, true, 0.8, true, 0.6, false, false));
        assertThat(d.shouldNotify()).isFalse();
        assertThat(d.reason()).isEqualTo(Reason.CHANNEL_NOT_CONFIGURED);
    }
}
