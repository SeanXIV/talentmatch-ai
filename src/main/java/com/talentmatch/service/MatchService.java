package com.talentmatch.service;

import com.talentmatch.ai.AiProperties;
import com.talentmatch.ai.ExplanationBatch;
import com.talentmatch.ai.ExplanationFallbackRenderer;
import com.talentmatch.ai.ExplanationRateLimiter;
import com.talentmatch.ai.ExplanationReason;
import com.talentmatch.ai.ExplanationResult;
import com.talentmatch.ai.ExplanationService;
import com.talentmatch.ai.ExplanationStatus;
import com.talentmatch.ai.JobContext;
import com.talentmatch.ai.MatchContext;
import com.talentmatch.domain.scoring.CandidateSkillFact;
import com.talentmatch.domain.scoring.JobRequirement;
import com.talentmatch.domain.scoring.MatchEvaluation;
import com.talentmatch.domain.scoring.ScoringEngine;
import com.talentmatch.repository.MatchJdbcRepository;
import com.talentmatch.repository.MatchJdbcRepository.JobHeader;
import com.talentmatch.repository.MatchJdbcRepository.RankedRow;
import com.talentmatch.service.exception.MatchesBusyException;
import com.talentmatch.service.exception.NotFoundException;
import com.talentmatch.service.exception.RegenerateRateLimitedException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Cached match scoring plus AI explanations. A job_match row is fresh while
 * {@code computed_at >= GREATEST(candidate.updated_at, job.updated_at)}; stale or missing rows
 * are recomputed under a per-job advisory lock and written with one set-based upsert.
 * The breakdown is recomputed at read time for the returned page only.
 *
 * <p>Scoring runs in one short transaction; it commits (releasing the advisory lock and the
 * connection) before any LLM work starts. Explanations are then attached outside any
 * transaction by {@link ExplanationService}, which never throws and never blocks longer than the
 * request budget.
 */
@Service
public class MatchService {

    private static final Logger log = LoggerFactory.getLogger(MatchService.class);
    private static final double SCORE_TOLERANCE = 1e-9;

    private final MatchJdbcRepository matchRepository;
    private final ScoringEngine scoringEngine;
    private final TransactionTemplate txTemplate;
    private final ExplanationService explanationService;
    private final ExplanationRateLimiter rateLimiter;
    private final AiProperties aiProperties;

    public MatchService(MatchJdbcRepository matchRepository, ScoringEngine scoringEngine,
                        TransactionTemplate txTemplate, ExplanationService explanationService,
                        ExplanationRateLimiter rateLimiter, AiProperties aiProperties) {
        this.matchRepository = matchRepository;
        this.scoringEngine = scoringEngine;
        this.txTemplate = txTemplate;
        this.explanationService = explanationService;
        this.rateLimiter = rateLimiter;
        this.aiProperties = aiProperties;
    }

    /**
     * Ranked matches for a job, refreshing stale scores first (or all scores if regenerate),
     * each with an explanation. With AI enabled, regenerate also regenerates the AI explanations
     * of the page's top-N rows and is rate-limited per job.
     *
     * @throws RegenerateRateLimitedException if regenerate was used for this job too recently
     * @throws NotFoundException              if the job does not exist
     * @throws MatchesBusyException           if another request holds the job's lock for too long
     */
    public MatchPageView getMatches(UUID jobId, int page, int limit, double minScore, boolean regenerate) {
        ExplanationRateLimiter.Lease lease = regenerate && aiProperties.enabled() ? rateLimiter.acquire(jobId) : null;
        ScoredPage scored;
        try {
            scored = Objects.requireNonNull(
                    txTemplate.execute(status -> scorePage(jobId, page, limit, minScore, regenerate)),
                    "scorePage returned null");
        } catch (RuntimeException | Error e) {
            // A failed request does not consume the job's regenerate slot.
            rateLimiter.release(lease);
            throw e;
        }

        JobHeader job = scored.job();
        if (!scored.matchable()) {
            return notMatchable(job, page, limit);
        }

        List<MatchView> matches = new ArrayList<>(scored.rows().size());
        int generated = 0;
        if (!scored.rows().isEmpty()) {
            JobContext jobContext = new JobContext(job.id(), job.title(), job.company(), job.description(),
                    job.updatedAt());
            List<MatchContext> contexts = scored.rows().stream().map(MatchService::toContext).toList();
            ExplanationBatch batch = explanationService.explain(jobContext, contexts, regenerate);
            generated = batch.generated();
            Map<UUID, ExplanationResult> results = batch.byCandidate();
            for (int i = 0; i < contexts.size(); i++) {
                MatchContext ctx = contexts.get(i);
                ExplanationResult result = results.get(ctx.candidateId());
                if (result == null) {
                    result = ExplanationResult.template(ExplanationStatus.UNAVAILABLE,
                            ExplanationFallbackRenderer.render(ctx.candidateName(), ctx.scorePercent(),
                                    ctx.evaluation(), ExplanationReason.GENERATION_FAILED, false,
                                    aiProperties.topN()));
                }
                matches.add(toView(ctx, scored.rows().get(i).row(), result));
            }
        }

        return new MatchPageView(job.id(), job.title(), job.company(), true, scored.reason(),
                scored.reason() == null ? null : scored.reason().message(), page, limit, scored.totalPages(),
                scored.total(), scored.recomputed(), generated, matches);
    }

    /**
     * Recomputes one job's matches in its own transaction (called once per job by the batch run).
     * Never generates explanations.
     *
     * @param force true to rescore every candidate, false to rescore only missing/stale rows
     */
    @Transactional
    public JobRecomputeOutcome recomputeJob(UUID jobId, boolean force) {
        lock(jobId);
        Optional<JobHeader> job = matchRepository.findJob(jobId);
        if (job.isEmpty()) {
            return new JobRecomputeOutcome.SkippedJobDeleted();
        }
        List<JobRequirement> reqs = matchRepository.findRequirements(jobId);
        if (!ScoringEngine.isMatchable(reqs)) {
            return new JobRecomputeOutcome.SkippedNoSkills();
        }
        List<UUID> targets = force ? matchRepository.findAllCandidateIds()
                : matchRepository.findStaleCandidateIds(jobId);
        return new JobRecomputeOutcome.Recomputed(scoreAndStore(jobId, reqs, targets, force));
    }

    // ------------------------------------------------------------------ scoring (in the tx)

    /** Phase 2 scoring and paging, unchanged; runs inside {@link #txTemplate}. */
    private ScoredPage scorePage(UUID jobId, int page, int limit, double minScore, boolean regenerate) {
        JobHeader job = matchRepository.findJob(jobId).orElseThrow(() -> NotFoundException.job(jobId));
        List<JobRequirement> reqs = matchRepository.findRequirements(jobId);
        if (!ScoringEngine.isMatchable(reqs)) {
            return ScoredPage.notMatchable(job);
        }

        int recomputed = 0;
        List<UUID> targets = regenerate ? matchRepository.findAllCandidateIds()
                : matchRepository.findStaleCandidateIds(jobId);
        if (!targets.isEmpty()) {
            lock(jobId);
            // Re-read everything under the lock: a concurrent request may have refreshed the
            // rows, or the job may have been edited or deleted, while we waited.
            job = matchRepository.findJob(jobId).orElseThrow(() -> NotFoundException.job(jobId));
            reqs = matchRepository.findRequirements(jobId);
            if (!ScoringEngine.isMatchable(reqs)) {
                return ScoredPage.notMatchable(job);
            }
            targets = regenerate ? matchRepository.findAllCandidateIds()
                    : matchRepository.findStaleCandidateIds(jobId);
            recomputed = scoreAndStore(jobId, reqs, targets, regenerate);
        }

        long total = matchRepository.countMatches(jobId, minScore);
        long offset = (long) page * limit;
        List<RankedRow> rows = total > offset
                ? matchRepository.findRankedPage(jobId, minScore, limit, offset)
                : List.of();

        List<ScoredRow> scoredRows = new ArrayList<>(rows.size());
        if (!rows.isEmpty()) {
            List<UUID> pageIds = rows.stream().map(RankedRow::candidateId).toList();
            List<UUID> reqIds = reqs.stream().map(JobRequirement::skillId).toList();
            Map<UUID, List<CandidateSkillFact>> facts = matchRepository.findFacts(reqIds, pageIds);
            for (int i = 0; i < rows.size(); i++) {
                RankedRow row = rows.get(i);
                MatchEvaluation eval = scoringEngine.evaluate(reqs, facts.getOrDefault(row.candidateId(), List.of()));
                if (Math.abs(eval.score() - row.score()) > SCORE_TOLERANCE) {
                    log.warn("Cached score {} for candidate {} / job {} differs from the engine's {} "
                                    + "(weights changed?). Run POST /api/matches/recompute to refresh.",
                            row.score(), row.candidateId(), jobId, eval.score());
                }
                scoredRows.add(new ScoredRow((int) (offset + i + 1), row, eval));
            }
        }

        MatchReason reason = null;
        if (total == 0 && !matchRepository.anyCandidateExists()) {
            reason = MatchReason.NO_CANDIDATES;
        }
        int totalPages = total == 0 ? 0 : (int) Math.min(Integer.MAX_VALUE, (total + limit - 1) / limit);
        return new ScoredPage(job, true, recomputed, total, totalPages, reason, scoredRows);
    }

    // ------------------------------------------------------------------ helpers

    private void lock(UUID jobId) {
        try {
            matchRepository.lockJob(jobId);
        } catch (PessimisticLockingFailureException e) {
            throw new MatchesBusyException(e);
        }
    }

    /** Scores the targets and upserts them; returns the number of rows written. */
    private int scoreAndStore(UUID jobId, List<JobRequirement> reqs, List<UUID> targets, boolean allCandidates) {
        if (targets.isEmpty()) {
            return 0;
        }
        List<UUID> reqIds = reqs.stream().map(JobRequirement::skillId).toList();
        Map<UUID, List<CandidateSkillFact>> facts =
                matchRepository.findFacts(reqIds, allCandidates ? null : targets);
        double[] scores = new double[targets.size()];
        for (int i = 0; i < targets.size(); i++) {
            scores[i] = scoringEngine.evaluate(reqs, facts.getOrDefault(targets.get(i), List.of())).score();
        }
        int written = matchRepository.upsertScores(jobId, targets, scores);
        log.debug("Recomputed {} match(es) for job {}", written, jobId);
        return written;
    }

    private static MatchPageView notMatchable(JobHeader job, int page, int limit) {
        MatchReason reason = MatchReason.JOB_HAS_NO_SKILLS;
        return new MatchPageView(job.id(), job.title(), job.company(), false, reason, reason.message(),
                page, limit, 0, 0L, 0, 0, List.of());
    }

    private static int percent(double score) {
        return (int) Math.round(score * 100d);
    }

    private static MatchContext toContext(ScoredRow s) {
        RankedRow row = s.row();
        return new MatchContext(s.rank(), row.candidateId(), row.candidateName(), row.candidateSummary(),
                row.candidateUpdatedAt(), row.score(), percent(row.score()), s.eval(), row.stored());
    }

    private static MatchView toView(MatchContext ctx, RankedRow row, ExplanationResult explanation) {
        MatchEvaluation eval = ctx.evaluation();
        double score = Math.round(row.score() * 10_000d) / 10_000d;
        MatchBreakdownView breakdown = new MatchBreakdownView(eval.earnedPoints(), eval.maxPoints(),
                eval.matchedRequired(), eval.matchedNiceToHave(), eval.missingRequired(), eval.missingNiceToHave());
        return new MatchView(ctx.rank(), row.candidateId(), row.candidateName(), score, ctx.scorePercent(),
                eval.summary(), breakdown, explanation.aiExplanation(), explanation.status(),
                explanation.explanation(), row.computedAt());
    }

    /** A ranked row with its rank and read-time evaluation. */
    private record ScoredRow(int rank, RankedRow row, MatchEvaluation eval) {
    }

    /** Result of the scoring transaction. */
    private record ScoredPage(JobHeader job, boolean matchable, int recomputed, long total, int totalPages,
                              MatchReason reason, List<ScoredRow> rows) {

        static ScoredPage notMatchable(JobHeader job) {
            return new ScoredPage(job, false, 0, 0L, 0, null, List.of());
        }
    }
}
