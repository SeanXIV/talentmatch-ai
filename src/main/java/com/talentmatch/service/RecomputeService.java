package com.talentmatch.service;

import com.talentmatch.config.AsyncConfig;
import com.talentmatch.config.RecomputeProperties;
import com.talentmatch.repository.MatchJdbcRepository;
import com.talentmatch.service.exception.MatchesBusyException;
import com.talentmatch.service.exception.RecomputeAlreadyRunningException;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.CannotCreateTransactionException;

/**
 * Batch recompute of all jobs' matches on a single background thread. One run at a time;
 * run status lives in memory only (last {@code historySize} runs, lost on restart).
 */
@Service
public class RecomputeService {

    private static final Logger log = LoggerFactory.getLogger(RecomputeService.class);

    private final MatchService matchService;
    private final MatchJdbcRepository matchRepository;
    private final TaskExecutor executor;
    private final RecomputeProperties properties;
    private final Clock clock = Clock.systemUTC();

    private final AtomicReference<RecomputeRun> active = new AtomicReference<>();
    private final Map<UUID, RecomputeRun> history;
    private volatile boolean shuttingDown;

    public RecomputeService(MatchService matchService,
                            MatchJdbcRepository matchRepository,
                            @Qualifier(AsyncConfig.RECOMPUTE_EXECUTOR) TaskExecutor executor,
                            RecomputeProperties properties) {
        this.matchService = matchService;
        this.matchRepository = matchRepository;
        this.executor = executor;
        this.properties = properties;
        int maxRuns = properties.historySize();
        this.history = Collections.synchronizedMap(new LinkedHashMap<UUID, RecomputeRun>(16, 0.75f, false) {
            private static final long serialVersionUID = 1L;

            @Override
            protected boolean removeEldestEntry(Map.Entry<UUID, RecomputeRun> eldest) {
                return size() > maxRuns;
            }
        });
    }

    /**
     * Starts a run in the background.
     *
     * @param onlyStale true to rescore only missing/stale rows, false to rescore everything
     * @throws RecomputeAlreadyRunningException if a run is already active
     */
    public RecomputeRunView start(boolean onlyStale) {
        RecomputeRun run = new RecomputeRun(UUID.randomUUID(), onlyStale, clock.instant(),
                properties.maxFailuresReported());
        while (!active.compareAndSet(null, run)) {
            RecomputeRun current = active.get();
            if (current != null) {
                throw new RecomputeAlreadyRunningException(current.id(), current.startedAt());
            }
            // The previous run finished between the CAS and get(): try again.
        }
        history.put(run.id(), run);
        try {
            executor.execute(() -> execute(run));
        } catch (TaskRejectedException e) {
            // The previous run's thread has cleared `active` but not yet returned to the pool.
            active.compareAndSet(run, null);
            history.remove(run.id());
            throw new RecomputeAlreadyRunningException(null, null);
        }
        log.info("Recompute run {} started (onlyStale={})", run.id(), onlyStale);
        return run.view();
    }

    public Optional<RecomputeRunView> find(UUID runId) {
        RecomputeRun run = history.get(runId);
        return Optional.ofNullable(run).map(RecomputeRun::view);
    }

    public int historySize() {
        return properties.historySize();
    }

    @PreDestroy
    void stop() {
        shuttingDown = true;
    }

    private void execute(RecomputeRun run) {
        try {
            List<UUID> jobIds = matchRepository.findAllJobIds();
            run.begin(jobIds.size());
            for (UUID jobId : jobIds) {
                if (shuttingDown) {
                    run.fail("the server is shutting down. Start a new run after it restarts.", clock.instant());
                    log.warn("Recompute run {} stopped: server shutting down", run.id());
                    return;
                }
                try {
                    JobRecomputeOutcome outcome = matchService.recomputeJob(jobId, !run.onlyStale());
                    if (outcome instanceof JobRecomputeOutcome.Recomputed r) {
                        run.recordRecomputed(r.candidates());
                    } else {
                        run.recordSkipped(jobId);
                    }
                } catch (RuntimeException e) {
                    log.warn("Recompute run {}: job {} failed", run.id(), jobId, e);
                    run.recordFailure(jobId, describe(e));
                }
            }
            run.complete(clock.instant());
            RecomputeRunView v = run.view();
            log.info("Recompute run {} finished: state={} processed={} skipped={} failed={} matchesWritten={}",
                    run.id(), v.state(), v.processed(), v.skipped(), v.failed(), v.matchesWritten());
        } catch (RuntimeException | Error e) {
            log.error("Recompute run {} failed", run.id(), e);
            run.fail(describe(e), clock.instant());
            if (e instanceof Error err) {
                throw err;
            }
        } finally {
            active.compareAndSet(run, null);
        }
    }

    /** User-safe failure reason (no SQL, class names or stack traces). */
    private static String describe(Throwable e) {
        if (e instanceof MatchesBusyException || e instanceof PessimisticLockingFailureException) {
            return "Matches for this job were locked by another request for too long. Run the recompute again.";
        }
        if (e instanceof DataAccessResourceFailureException || e instanceof CannotCreateTransactionException
                || e instanceof QueryTimeoutException) {
            return "The database was unavailable. Run the recompute again once it is back.";
        }
        return "Unexpected error; see the server logs for this job id.";
    }
}
