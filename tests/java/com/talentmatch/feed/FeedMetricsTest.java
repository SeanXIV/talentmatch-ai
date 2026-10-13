package com.talentmatch.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.talentmatch.feed.source.SourceFailure;
import com.talentmatch.feed.source.SourceKind;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** Step 11: {@link FeedMetrics} tags, the no-op for zero new postings and a broken registry. */
class FeedMetricsTest {

    private static final PollOutcome.Stats STATS = new PollOutcome.Stats(3, 1, 0, 0, 0, 0, 0, 0);

    private static PollOutcome.Failed failed(SourceFailure.Kind kind) {
        return new PollOutcome.Failed(SourceFailure.of(kind), SourcePoller.statusOf(kind), "msg");
    }

    @Test
    void outcomeTagIsTheLowerCasedStatusForEveryOutcome() {
        Map<PollOutcome, String> expected = new java.util.LinkedHashMap<>();
        expected.put(new PollOutcome.Ok("OK", STATS), "ok");
        expected.put(new PollOutcome.Ok("SUSPICIOUS_EMPTY", STATS), "suspicious_empty");
        expected.put(new PollOutcome.NotModified(), "not_modified");
        expected.put(failed(SourceFailure.Kind.RATE_LIMITED), "rate_limited");
        expected.put(failed(SourceFailure.Kind.NOT_FOUND), "not_found");
        expected.put(failed(SourceFailure.Kind.UNAUTHORIZED), "unauthorized");
        expected.put(failed(SourceFailure.Kind.SERVER_ERROR), "error");
        expected.put(failed(SourceFailure.Kind.NETWORK), "error");
        expected.put(failed(SourceFailure.Kind.TIMEOUT), "error");
        expected.put(failed(SourceFailure.Kind.INVALID_RESPONSE), "invalid_response");
        expected.put(failed(SourceFailure.Kind.TOO_LARGE), "too_large");
        expected.put(new PollOutcome.Failed(SourceFailure.of(SourceFailure.Kind.RATE_LIMITED), "BUDGET_EXHAUSTED",
                null), "budget_exhausted");
        expected.put(new PollOutcome.Deferred(Instant.parse("2026-10-13T10:00:00Z")), "deferred");
        expected.put(new PollOutcome.LeaseLost(), "lease_lost");
        expected.forEach((outcome, tag) -> assertThat(FeedMetrics.outcomeTag(outcome)).as("%s", outcome)
                .isEqualTo(tag));
    }

    @Test
    void everyFailureKindMapsToAStatusTagFromTheV5CheckList() {
        var allowed = java.util.Set.of("ok", "not_modified", "rate_limited", "not_found", "unauthorized", "error",
                "invalid_response", "too_large", "budget_exhausted", "suspicious_empty");
        for (SourceFailure.Kind k : SourceFailure.Kind.values()) {
            assertThat(FeedMetrics.outcomeTag(failed(k))).as("%s", k).isIn(allowed);
        }
    }

    @Test
    void aFailureWithoutStatusIsTaggedError() {
        assertThat(FeedMetrics.outcomeTag(new PollOutcome.Failed(SourceFailure.of(SourceFailure.Kind.NETWORK), null,
                null))).isEqualTo("error");
        assertThat(FeedMetrics.outcomeTag(null)).isEqualTo("error");
    }

    @Test
    void kindTagsAreLowerCase() {
        assertThat(Arrays.stream(SourceKind.values()).map(FeedMetrics::kindTag))
                .containsExactly("greenhouse", "lever", "ashby", "adzuna");
        assertThat(FeedMetrics.kindTag(null)).isEqualTo("unknown");
    }

    @Test
    void pollRecordsATimerWithKindAndOutcome() {
        SimpleMeterRegistry r = new SimpleMeterRegistry();
        FeedMetrics m = new FeedMetrics(r);
        m.poll(SourceKind.LEVER, new PollOutcome.Ok("OK", STATS), 120);
        m.poll(SourceKind.LEVER, new PollOutcome.Ok("OK", STATS), -5);       // clamped to 0
        m.poll(SourceKind.GREENHOUSE, new PollOutcome.NotModified(), 40);
        Timer t = r.find(FeedMetrics.POLLS).tags("kind", "lever", "outcome", "ok").timer();
        assertThat(t).isNotNull();
        assertThat(t.count()).isEqualTo(2);
        assertThat(t.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(120);
        assertThat(r.find(FeedMetrics.POLLS).tags("kind", "greenhouse", "outcome", "not_modified").timer().count())
                .isOne();
    }

    @Test
    void newPostingsCountsAndIgnoresZeroOrNegative() {
        SimpleMeterRegistry r = new SimpleMeterRegistry();
        FeedMetrics m = new FeedMetrics(r);
        m.newPostings(SourceKind.ASHBY, 0);
        m.newPostings(SourceKind.ASHBY, -1);
        assertThat(r.find(FeedMetrics.NEW_POSTINGS).meters()).as("nothing registered").isEmpty();
        m.newPostings(SourceKind.ASHBY, 3);
        m.newPostings(SourceKind.ASHBY, 2);
        assertThat(r.find(FeedMetrics.NEW_POSTINGS).tag("kind", "ashby").counter().count()).isEqualTo(5);
    }

    @Test
    void aBrokenRegistryNeverThrows() {
        MeterRegistry broken = mock(MeterRegistry.class);
        when(broken.config()).thenThrow(new IllegalStateException("broken"));
        FeedMetrics m = new FeedMetrics(broken);
        assertThatCode(() -> {
            m.poll(SourceKind.LEVER, new PollOutcome.NotModified(), 1);
            m.poll(null, null, 1);
            m.newPostings(SourceKind.LEVER, 4);
        }).doesNotThrowAnyException();
    }

    @Test
    void theSpringConstructorFallsBackToTheGlobalRegistry() {
        @SuppressWarnings("unchecked")
        ObjectProvider<MeterRegistry> none = mock(ObjectProvider.class);
        assertThatCode(() -> new FeedMetrics(none).newPostings(SourceKind.LEVER, 1)).doesNotThrowAnyException();
        assertThatCode(() -> FeedMetrics.global().poll(SourceKind.LEVER, new PollOutcome.LeaseLost(), 1))
                .doesNotThrowAnyException();
    }
}
