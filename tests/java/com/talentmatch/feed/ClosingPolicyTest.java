package com.talentmatch.feed;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** §4.5 closing and baseline. */
class ClosingPolicyTest {

    private static final double RATIO = 0.5;

    private static List<String> ids(int n) {
        return IntStream.range(0, n).mapToObj(i -> "p" + i).toList();
    }

    @Test
    void normalCloseOfMissingPostings() {
        ClosingPolicy.Decision d = ClosingPolicy.decide(List.of("a", "b", "c"), List.of("a", "b", "x"), false, RATIO);
        assertThat(d.toClose()).containsExactly("c");
        assertThat(d.suspicious()).isFalse();
        assertThat(d.closingHeld()).isFalse();
    }

    @Test
    void emptyListingWithThreeOpenIsHeldThenClosed() {
        ClosingPolicy.Decision first = ClosingPolicy.decide(ids(3), List.of(), false, RATIO);
        assertThat(first.closingHeld()).isTrue();
        assertThat(first.suspicious()).isTrue();
        assertThat(first.toClose()).isEmpty();

        ClosingPolicy.Decision second = ClosingPolicy.decide(ids(3), List.of(), true, RATIO);
        assertThat(second.closingHeld()).isFalse();
        assertThat(second.suspicious()).isTrue();
        assertThat(second.toClose()).containsExactlyInAnyOrderElementsOf(ids(3));
    }

    @Test
    void emptyListingWithTwoOpenClosesAtOnce() {
        ClosingPolicy.Decision d = ClosingPolicy.decide(ids(2), List.of(), false, RATIO);
        assertThat(d.closingHeld()).isFalse();
        assertThat(d.suspicious()).isFalse();
        assertThat(d.toClose()).containsExactlyInAnyOrderElementsOf(ids(2));
    }

    @Test
    void sixtyPercentDropHeldWithSixOpenButNotWithFive() {
        // 6 open, 4 missing (66%) / 5 open, 3 missing (60%)
        ClosingPolicy.Decision six = ClosingPolicy.decide(ids(6), ids(2), false, RATIO);
        assertThat(six.closingHeld()).isTrue();
        assertThat(six.toClose()).isEmpty();

        ClosingPolicy.Decision five = ClosingPolicy.decide(ids(5), ids(2), false, RATIO);
        assertThat(five.closingHeld()).isFalse();
        assertThat(five.suspicious()).isFalse();
        assertThat(five.toClose()).containsExactlyInAnyOrder("p2", "p3", "p4");
    }

    @Test
    void ratioIsStrictlyGreaterThan() {
        // 6 open, 3 missing = exactly 50%: not suspicious
        assertThat(ClosingPolicy.isSuspicious(3, 6, 3, RATIO)).isFalse();
        assertThat(ClosingPolicy.isSuspicious(2, 6, 4, RATIO)).isTrue();
        assertThat(ClosingPolicy.isSuspicious(0, 0, 0, RATIO)).isFalse();
    }

    @Test
    void nonSuspiciousPollAfterSuspiciousOneClearsTheHold() {
        ClosingPolicy.Decision d = ClosingPolicy.decide(ids(6), ids(5), true, RATIO);
        assertThat(d.suspicious()).isFalse();
        assertThat(d.closingHeld()).isFalse();
        assertThat(d.toClose()).containsExactly("p5");
    }

    @Test
    void baselinePostingEdges() {
        Instant now = Instant.parse("2026-10-08T12:00:00Z");
        Duration window = Duration.ofHours(24);
        assertThat(ClosingPolicy.isBaselinePosting(null, now, window)).isTrue();
        assertThat(ClosingPolicy.isBaselinePosting(now.minus(Duration.ofDays(3)), now, window)).isTrue();
        assertThat(ClosingPolicy.isBaselinePosting(now.minus(window), now, window)).as("exactly 24h old").isTrue();
        assertThat(ClosingPolicy.isBaselinePosting(now.minus(window).plusSeconds(1), now, window)).isFalse();
        assertThat(ClosingPolicy.isBaselinePosting(now.minus(Duration.ofHours(1)), now, window)).isFalse();
        assertThat(ClosingPolicy.isBaselinePosting(now.plus(Duration.ofHours(1)), now, window))
                .as("future publish time").isFalse();
    }
}
