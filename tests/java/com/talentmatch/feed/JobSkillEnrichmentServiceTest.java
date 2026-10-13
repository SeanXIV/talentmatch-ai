package com.talentmatch.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.talentmatch.ai.LocalModelGate;
import com.talentmatch.feed.skills.DictionarySkillMatcher;
import com.talentmatch.feed.skills.JobSkillExtractionModel;
import com.talentmatch.feed.skills.SkillDictionary;
import com.talentmatch.feed.skills.SkillsProperties;
import com.talentmatch.support.FakeExtractionModel;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** §4.7 run rules that are awkward in an IT: the hourly cap, AI off, the busy gate. Repository mocked. */
class JobSkillEnrichmentServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-12T10:00:00Z");

    private FeedEnrichmentRepository repo;
    private FakeExtractionModel fake;
    private LocalModelGate gate;
    private SkillDictionary dictionary;

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(value);
        return p;
    }

    private JobSkillEnrichmentService service(int maxPerHour, boolean withModel) {
        SkillsProperties.Enrichment e = new SkillsProperties.Enrichment(true, Duration.ofMinutes(5), 600, 0, 3,
                Duration.ofMinutes(5), Duration.ofSeconds(60), maxPerHour, Duration.ofSeconds(60));
        return new JobSkillEnrichmentService(repo,
                provider(withModel ? new JobSkillExtractionModel(fake, "fake/m", null, true) : null),
                provider(gate), dictionary, new SkillsProperties(8000, e), FeedProperties.defaults(),
                provider(null), new ThreadPoolTaskExecutor(), Clock.fixed(NOW, ZoneOffset.UTC),
                provider((MeterRegistry) new SimpleMeterRegistry()));
    }

    private static FeedEnrichmentRepository.Task task(int n) {
        return new FeedEnrichmentRepository.Task(UUID.randomUUID(), "Engineer " + n, "Acme", "We use Java.",
                "a".repeat(64), 1);
    }

    @BeforeEach
    void setUp() {
        repo = mock(FeedEnrichmentRepository.class);
        fake = new FakeExtractionModel();
        fake.respondJson("{\"skills\":[]}");
        gate = new LocalModelGate();
        dictionary = mock(SkillDictionary.class);
        when(dictionary.refresh()).thenReturn(new SkillDictionary.Snapshot("fp", Map.of(), Map.of(),
                new DictionarySkillMatcher(List.of(), List.of())));
        when(repo.succeed(any(), anyString(), anyString(), anyString(), any())).thenReturn(true);
    }

    @Test
    void hourlyCapStopsClaimingAndSaysWhenItResumes() {
        when(repo.claim(anyInt(), any())).thenReturn(Optional.of(task(1)), Optional.of(task(2)),
                Optional.of(task(3)));
        JobSkillEnrichmentService s = service(2, true);

        JobSkillEnrichmentService.Report r = s.enrichDue();
        assertThat(r.succeeded()).isEqualTo(2);
        assertThat(r.deferredUntil()).isEqualTo(NOW.plus(Duration.ofHours(1)));
        assertThat(fake.calls()).isEqualTo(2);
        verify(repo, times(2)).claim(anyInt(), any());           // nothing claimed once the cap is reached

        assertThat(s.enrichDue().deferredUntil()).as("same hour: still capped").isNotNull();
        verify(repo, times(2)).claim(anyInt(), any());
    }

    @Test
    void withoutAModelEverythingPendingIsSkipped() {
        when(repo.skipPending()).thenReturn(4);
        JobSkillEnrichmentService.Report r = service(30, false).enrichDue();
        assertThat(r.skipped()).isEqualTo(4);
        verify(repo, never()).claim(anyInt(), any());
        assertThat(service(30, false).aiEnabled()).isFalse();
    }

    @Test
    void busyGateGivesTheAttemptBack() throws Exception {
        FeedEnrichmentRepository.Task t = task(1);
        when(repo.claim(anyInt(), any())).thenReturn(Optional.of(t));
        Thread holder = new Thread(() -> {
            try {
                gate.acquireExclusive();
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                if (gate.exclusiveHeld()) {
                    gate.releaseExclusive();
                }
            }
        });
        holder.start();
        while (!gate.exclusiveHeld()) {
            Thread.onSpinWait();
        }
        JobSkillEnrichmentService.Report r = service(30, true).enrichDue();
        assertThat(r.modelBusy()).isTrue();
        verify(repo).release(eq(t.jobId()), eq(NOW.plusSeconds(60)), eq(true));
        assertThat(fake.calls()).isZero();
        holder.interrupt();
        holder.join(5000);
    }

    @Test
    void finalFailureIsFailedEarlierOnesWait() {
        FeedEnrichmentRepository.Task last = new FeedEnrichmentRepository.Task(UUID.randomUUID(), "T", "C", "D",
                "a".repeat(64), 3);
        when(repo.claim(anyInt(), any())).thenReturn(Optional.of(task(1)), Optional.of(last), Optional.empty());
        fake.respondJson("nope");
        JobSkillEnrichmentService.Report r = service(30, true).enrichDue();
        assertThat(r.retried()).isEqualTo(1);
        assertThat(r.failed()).isEqualTo(1);
        verify(repo).release(any(), eq(NOW.plus(Duration.ofMinutes(5))), eq(false));
        verify(repo).fail(eq(last.jobId()), eq(com.talentmatch.feed.skills.EnrichmentFailure.INVALID_OUTPUT),
                eq("fake/m"));
        verify(repo, never()).release(eq(last.jobId()), any(), anyBoolean());
    }
}
