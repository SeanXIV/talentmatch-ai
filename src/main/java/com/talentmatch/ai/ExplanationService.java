package com.talentmatch.ai;

import com.talentmatch.repository.MatchJdbcRepository.StoredExplanation;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Attaches an explanation to every match on a page, written for one {@link Audience}. Never throws
 * for AI or database faults and never blocks longer than {@code request-budget} (plus small overhead).
 *
 * <p>Per row: a fresh stored explanation (same prompt hash) is returned as READY; rows ranked
 * within the top N with no fresh explanation get one generated on the AI executor (single-flight
 * per (audience, job, candidate, prompt hash)); everything else gets a deterministic template with a reason.
 * Generations that miss the budget keep running and persist themselves (PENDING). Failed
 * (pair, inputs) are not retried for {@code failure-backoff}; an open circuit skips the model.
 * {@code regenerate} bypasses both and ignores fresh/recent results for the top N.
 *
 * <p>Single-flight, backoff and circuit state are per instance (PRODUCTION_READINESS.md).
 */
@Service
public class ExplanationService {

    private static final Logger log = LoggerFactory.getLogger(ExplanationService.class);

    static final String FALLBACK_COUNTER = "talentmatch.ai.fallbacks";
    /** Successful flights are kept this long so a request that read the DB just before the persist reuses them. */
    static final Duration RECENT_SUCCESS_TTL = Duration.ofMinutes(2);
    static final int MAX_FLIGHTS = 1000;
    static final int MAX_BACKOFF_ENTRIES = 10_000;

    private final AiProperties properties;
    private final ExplanationGenerator generator; // null when AI is disabled
    private final AiCircuitBreaker circuit;
    private final Clock clock;
    private final MeterRegistry meterRegistry;
    private final ExplanationPromptBuilder promptBuilder;

    private final ConcurrentHashMap<FlightKey, Flight> flights = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<FlightKey, Backoff> backoffs = new ConcurrentHashMap<>();

    /** Spring constructor (parameter order differs from the plain one so null arguments never clash). */
    @Autowired
    public ExplanationService(AiProperties properties, AiCircuitBreaker circuit, Clock clock,
                              ObjectProvider<ExplanationGenerator> generator,
                              ObjectProvider<MeterRegistry> meterRegistry) {
        this(properties, generator.getIfAvailable(), circuit, clock, meterRegistry.getIfAvailable());
    }

    /**
     * @param generator     null when AI is disabled
     * @param meterRegistry null to use {@link Metrics#globalRegistry}
     */
    public ExplanationService(AiProperties properties, ExplanationGenerator generator, AiCircuitBreaker circuit,
                              Clock clock, MeterRegistry meterRegistry) {
        this.properties = properties;
        this.generator = generator;
        this.circuit = circuit;
        this.clock = clock;
        this.meterRegistry = meterRegistry == null ? Metrics.globalRegistry : meterRegistry;
        this.promptBuilder = new ExplanationPromptBuilder(properties.maxContextChars());
    }

    /** True if the AI layer is on (enabled and a generator exists). */
    public boolean aiActive() {
        return properties.enabled() && generator != null;
    }

    /**
     * Recruiter explanations for one page of a job's matches (rank order). Never throws. Same as
     * {@link #explainAll} with {@link Audience#RECRUITER} and one request per row, keyed by candidate.
     *
     * @param regenerate force new AI explanations for rows within the top N
     */
    public ExplanationBatch explain(JobContext job, List<MatchContext> rows, boolean regenerate) {
        try {
            Map<UUID, ExplanationResult> out = new LinkedHashMap<>();
            if (!aiActive()) {
                for (MatchContext row : rows) {
                    out.put(row.candidateId(), template(Audience.RECRUITER, row, ExplanationStatus.UNAVAILABLE,
                            ExplanationReason.AI_DISABLED, false));
                }
                return new ExplanationBatch(out, 0);
            }
            List<ExplanationRequest> requests = new ArrayList<>(rows.size());
            for (MatchContext row : rows) {
                requests.add(new ExplanationRequest(job, row));
            }
            ExplanationResults results = explainWithAi(Audience.RECRUITER, requests, regenerate);
            for (int i = 0; i < rows.size(); i++) {
                out.put(rows.get(i).candidateId(), results.results().get(i));
            }
            return new ExplanationBatch(out, results.generated());
        } catch (RuntimeException e) {
            log.error("Unexpected error while preparing explanations for job {}; using templates",
                    job == null ? null : job.jobId(), e);
            return allFailed(rows);
        }
    }

    /**
     * Explanations for a list of ranked (job, candidate) pairs written for one audience. The requests
     * may span many jobs and many candidates; each row's {@code rank} decides top-N eligibility.
     * AI, executor and database faults never throw: they yield templates with a reason.
     *
     * @param regenerate force new AI explanations for rows within the top N
     * @return one result per request, in input order
     * @throws IllegalStateException for SEEKER until its prompt and template are wired (Phase 6 step 3)
     */
    public ExplanationResults explainAll(Audience audience, List<ExplanationRequest> requests, boolean regenerate) {
        requireWired(Objects.requireNonNull(audience, "audience"));
        Objects.requireNonNull(requests, "requests");
        try {
            if (!aiActive()) {
                List<ExplanationResult> out = new ArrayList<>(requests.size());
                for (ExplanationRequest request : requests) {
                    out.add(template(audience, request.row(), ExplanationStatus.UNAVAILABLE,
                            ExplanationReason.AI_DISABLED, false));
                }
                return new ExplanationResults(out, 0);
            }
            return explainWithAi(audience, requests, regenerate);
        } catch (RuntimeException e) {
            log.error("Unexpected error while preparing {} {} explanations; using templates",
                    requests.size(), audience.tag(), e);
            return allFailed(audience, requests);
        }
    }

    private ExplanationResults explainWithAi(Audience audience, List<ExplanationRequest> requests,
                                             boolean regenerate) {
        long deadline = System.nanoTime() + properties.requestBudget().toNanos();
        int topN = properties.topN();
        evictExpiredFlights();

        ExplanationResult[] out = new ExplanationResult[requests.size()];
        List<Waiting> waiting = new ArrayList<>();
        for (int i = 0; i < requests.size(); i++) {
            ExplanationRequest request = requests.get(i);
            JobContext job = request.job();
            MatchContext row = request.row();
            String prompt = prompt(audience, job, row);
            String hash = ExplanationInputHasher.hash(audience, prompt);
            ExplanationView stored = storedView(row);
            boolean hasStored = stored != null;
            boolean fresh = hasStored && hash.equals(row.stored().inputHash());
            boolean eligible = row.rank() <= topN;

            if (fresh && !(regenerate && eligible)) {
                out[i] = ExplanationResult.ready(stored);
                continue;
            }
            if (!eligible) {
                out[i] = templateFor(audience, row, hasStored, ExplanationReason.NOT_IN_TOP_N);
                continue;
            }
            FlightKey key = new FlightKey(audience, job.jobId(), row.candidateId(), hash);
            if (!regenerate) {
                Backoff backoff = activeBackoff(key);
                if (backoff != null) {
                    out[i] = templateFor(audience, row, hasStored, backoff.reason());
                    continue;
                }
                if (!circuit.allowRequest()) {
                    out[i] = templateFor(audience, row, hasStored, ExplanationReason.PROVIDER_UNAVAILABLE);
                    continue;
                }
            }
            if (generator.localModelBusy()) {
                // A CV extraction holds the local model for many minutes: don't queue behind it.
                out[i] = fresh ? ExplanationResult.ready(stored)
                        : templateFor(audience, row, hasStored, ExplanationReason.AI_BUSY);
                continue;
            }
            Joined joined = join(key,
                    new ExplanationGenerator.GenerationTask(job, row, prompt, hash, audience), regenerate);
            waiting.add(new Waiting(i, row, joined, fresh, hasStored, stored));
        }

        int generated = 0;
        for (Waiting w : waiting) {
            GenerationOutcome outcome = await(w.joined().future(), deadline - System.nanoTime());
            MatchContext row = w.row();
            ExplanationResult result;
            if (outcome instanceof GenerationOutcome.Success s) {
                if (w.joined().started()) {
                    generated++;
                }
                result = ExplanationResult.ready(aiView(s));
            } else if (w.fresh()) {
                // A failed or unfinished regeneration never downgrades a still-valid explanation.
                result = ExplanationResult.ready(w.stored());
            } else if (outcome instanceof GenerationOutcome.Failure f) {
                ExplanationReason reason = f.kind().providerFault()
                        ? ExplanationReason.PROVIDER_UNAVAILABLE : ExplanationReason.GENERATION_FAILED;
                result = templateFor(audience, row, w.hasStored(), reason);
            } else if (outcome instanceof GenerationOutcome.Rejected) {
                result = templateFor(audience, row, w.hasStored(), ExplanationReason.AI_BUSY);
            } else {
                result = template(audience, row, ExplanationStatus.PENDING, ExplanationReason.GENERATING, false);
            }
            out[w.index()] = result;
        }
        return new ExplanationResults(Arrays.asList(out), generated);
    }

    // ------------------------------------------------------------------ audience dispatch

    /** Fails fast for an audience whose prompt and template are not wired yet. */
    private static void requireWired(Audience audience) {
        if (audience == Audience.SEEKER) {
            throw new IllegalStateException("SEEKER is wired in step 3");
        }
    }

    /** The exact user message for the audience. */
    private String prompt(Audience audience, JobContext job, MatchContext row) {
        return switch (audience) {
            case RECRUITER -> promptBuilder.build(job, row);
            case SEEKER -> throw new IllegalStateException("SEEKER is wired in step 3");
        };
    }

    // ------------------------------------------------------------------ single-flight

    /** Joins an in-flight generation for the key, reuses a recent success, or starts a new one. */
    private Joined join(FlightKey key, ExplanationGenerator.GenerationTask task, boolean regenerate) {
        Instant now = clock.instant();
        while (true) {
            Flight existing = flights.get(key);
            if (existing != null) {
                if (!existing.future.isDone()) {
                    return new Joined(existing.future, false);
                }
                boolean recentSuccess = existing.success
                        && existing.completedAt != null
                        && now.isBefore(existing.completedAt.plus(RECENT_SUCCESS_TTL));
                if (recentSuccess && !regenerate) {
                    return new Joined(existing.future, false);
                }
                Flight mine = new Flight();
                if (flights.replace(key, existing, mine)) {
                    start(key, mine, task);
                    return new Joined(mine.future, true);
                }
                continue; // lost a race: look again
            }
            Flight mine = new Flight();
            if (flights.putIfAbsent(key, mine) == null) {
                start(key, mine, task);
                return new Joined(mine.future, true);
            }
        }
    }

    /** Submits outside any map lambda; pipes the generator's outcome into the placeholder flight. */
    private void start(FlightKey key, Flight flight, ExplanationGenerator.GenerationTask task) {
        CompletableFuture<GenerationOutcome> submitted;
        try {
            submitted = generator.submit(task);
        } catch (RejectedExecutionException e) {
            complete(key, flight, new GenerationOutcome.Rejected());
            return;
        } catch (RuntimeException e) {
            log.warn("Could not submit an AI explanation task: {}", e.getClass().getSimpleName());
            complete(key, flight, new GenerationOutcome.Failure(FailureKind.PROVIDER_ERROR, 0L));
            return;
        }
        if (submitted == null) {
            complete(key, flight, new GenerationOutcome.Failure(FailureKind.PROVIDER_ERROR, 0L));
            return;
        }
        submitted.whenComplete((outcome, error) -> {
            GenerationOutcome o = outcome;
            if (error != null || o == null) {
                o = new GenerationOutcome.Failure(FailureKind.PROVIDER_ERROR, 0L);
            }
            complete(key, flight, o);
        });
    }

    private void complete(FlightKey key, Flight flight, GenerationOutcome outcome) {
        try {
            if (outcome instanceof GenerationOutcome.Success) {
                flight.completedAt = clock.instant();
                flight.success = true;
            } else {
                flights.remove(key, flight);
                if (outcome instanceof GenerationOutcome.Failure f) {
                    recordBackoff(key, f.kind().providerFault()
                            ? ExplanationReason.PROVIDER_UNAVAILABLE : ExplanationReason.GENERATION_FAILED);
                }
            }
        } finally {
            flight.future.complete(outcome);
        }
    }

    private void evictExpiredFlights() {
        if (flights.isEmpty()) {
            return;
        }
        Instant now = clock.instant();
        flights.entrySet().removeIf(e -> {
            Flight f = e.getValue();
            return f.future.isDone() && (!f.success || f.completedAt == null
                    || !now.isBefore(f.completedAt.plus(RECENT_SUCCESS_TTL)));
        });
        if (flights.size() > MAX_FLIGHTS) {
            // Over the cap: drop every completed entry (in-flight ones are bounded by the executor queue).
            flights.entrySet().removeIf(e -> e.getValue().future.isDone());
        }
    }

    private static GenerationOutcome await(CompletableFuture<GenerationOutcome> future, long remainingNanos) {
        try {
            if (future.isDone()) {
                return future.getNow(null);
            }
            if (remainingNanos <= 0 || Thread.currentThread().isInterrupted()) {
                return null;
            }
            return future.get(remainingNanos, TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException | CancellationException | java.util.concurrent.CompletionException e) {
            return new GenerationOutcome.Failure(FailureKind.PROVIDER_ERROR, 0L);
        }
    }

    // ------------------------------------------------------------------ failure backoff

    private Backoff activeBackoff(FlightKey key) {
        Backoff b = backoffs.get(key);
        if (b == null) {
            return null;
        }
        if (clock.instant().isBefore(b.retryAt())) {
            return b;
        }
        backoffs.remove(key, b);
        return null;
    }

    private void recordBackoff(FlightKey key, ExplanationReason reason) {
        Duration ttl = properties.failureBackoff();
        if (ttl.isZero()) {
            return;
        }
        Instant now = clock.instant();
        if (backoffs.size() >= MAX_BACKOFF_ENTRIES) {
            backoffs.entrySet().removeIf(e -> !now.isBefore(e.getValue().retryAt()));
            if (backoffs.size() >= MAX_BACKOFF_ENTRIES) {
                return;
            }
        }
        backoffs.put(key, new Backoff(now.plus(ttl), reason));
    }

    // ------------------------------------------------------------------ views

    /** The stored explanation as an AI view, or null if absent or unreadable. */
    private static ExplanationView storedView(MatchContext row) {
        StoredExplanation stored = row.stored();
        if (stored == null || stored.text() == null || stored.text().isBlank() || stored.inputHash() == null) {
            return null;
        }
        Optional<MatchExplanation> payload = ExplanationPayloadCodec.fromJson(stored.payloadJson());
        if (payload.isEmpty()) {
            log.warn("Stored explanation for candidate {} has an unreadable payload; treating it as absent",
                    row.candidateId());
            return null;
        }
        MatchExplanation p = payload.get();
        return new ExplanationView(ExplanationSource.AI, p.headline(), stored.text(), p.strengths(), p.gaps(),
                stored.model(), stored.generatedAt(), null, null);
    }

    private static ExplanationView aiView(GenerationOutcome.Success s) {
        MatchExplanation v = s.value();
        return new ExplanationView(ExplanationSource.AI, v.headline(), v.explanation(), v.strengths(), v.gaps(),
                s.model(), s.generatedAt(), null, null);
    }

    /** STALE if an older AI explanation exists, else UNAVAILABLE. */
    private ExplanationResult templateFor(Audience audience, MatchContext row, boolean hasStored,
                                          ExplanationReason reason) {
        return template(audience, row, hasStored ? ExplanationStatus.STALE : ExplanationStatus.UNAVAILABLE,
                reason, hasStored);
    }

    private ExplanationResult template(Audience audience, MatchContext row, ExplanationStatus status,
                                       ExplanationReason reason, boolean stale) {
        countFallback(audience, reason);
        ExplanationView view = switch (audience) {
            case RECRUITER -> ExplanationFallbackRenderer.render(row.candidateName(), row.scorePercent(),
                    row.evaluation(), reason, stale, properties.topN());
            case SEEKER -> throw new IllegalStateException("SEEKER is wired in step 3");
        };
        return ExplanationResult.template(status, view);
    }

    /** Recruiter wrapper fallback: one template per row that can be rendered (Phase 3 behaviour). */
    private ExplanationBatch allFailed(List<MatchContext> rows) {
        Map<UUID, ExplanationResult> out = new LinkedHashMap<>();
        if (rows != null) {
            for (MatchContext row : rows) {
                try {
                    out.put(row.candidateId(), templateFor(Audience.RECRUITER, row, row.stored() != null,
                            ExplanationReason.GENERATION_FAILED));
                } catch (RuntimeException e) {
                    log.error("Could not render a template explanation for candidate {}", row.candidateId(), e);
                }
            }
        }
        return new ExplanationBatch(out, 0);
    }

    /**
     * {@link #explainAll} fallback: one GENERATION_FAILED template per request, in input order. Template
     * rendering is pure; if it fails for a row, that programming error propagates rather than
     * breaking the one-result-per-request contract.
     */
    private ExplanationResults allFailed(Audience audience, List<ExplanationRequest> requests) {
        List<ExplanationResult> out = new ArrayList<>(requests.size());
        for (ExplanationRequest request : requests) {
            MatchContext row = request.row();
            out.add(templateFor(audience, row, row.stored() != null, ExplanationReason.GENERATION_FAILED));
        }
        return new ExplanationResults(out, 0);
    }

    private void countFallback(Audience audience, ExplanationReason reason) {
        try {
            Counter.builder(FALLBACK_COUNTER)
                    .description("Matches served with a template explanation")
                    .tag("audience", audience.tag())
                    .tag("reason", reason.name())
                    .register(meterRegistry)
                    .increment();
        } catch (RuntimeException e) {
            log.debug("Could not record the fallback metric", e);
        }
    }

    // ------------------------------------------------------------------ types

    /** Single-flight and backoff key: the same pair explained for two audiences is two flights. */
    private record FlightKey(Audience audience, UUID jobId, UUID candidateId, String inputHash) {
    }

    private static final class Flight {
        final CompletableFuture<GenerationOutcome> future = new CompletableFuture<>();
        volatile Instant completedAt;
        volatile boolean success;
    }

    private record Joined(CompletableFuture<GenerationOutcome> future, boolean started) {
    }

    private record Backoff(Instant retryAt, ExplanationReason reason) {
    }

    private record Waiting(int index, MatchContext row, Joined joined, boolean fresh, boolean hasStored,
                           ExplanationView stored) {
    }
}
