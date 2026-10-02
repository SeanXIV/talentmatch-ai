package com.talentmatch.service;

import com.talentmatch.domain.scoring.CandidateSkillFact;
import com.talentmatch.domain.scoring.JobRequirement;
import com.talentmatch.domain.scoring.MatchEvaluation;
import com.talentmatch.domain.scoring.ScoringEngine;
import com.talentmatch.repository.MatchJdbcRepository;
import com.talentmatch.repository.MatchJdbcRepository.JobHeader;
import com.talentmatch.repository.MatchJdbcRepository.RankedRow;
import com.talentmatch.service.exception.MatchesBusyException;
import com.talentmatch.service.exception.NotFoundException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cached match scoring. A job_match row is fresh while
 * {@code computed_at >= GREATEST(candidate.updated_at, job.updated_at)}; stale or missing rows
 * are recomputed under a per-job advisory lock and written with one set-based upsert.
 * The breakdown is recomputed at read time for the returned page only.
 */
@Service
public class MatchService {

    private static final Logger log = LoggerFactory.getLogger(MatchService.class);
    private static final double SCORE_TOLERANCE = 1e-9;

    private final MatchJdbcRepository matchRepository;
    private final ScoringEngine scoringEngine;

    public MatchService(MatchJdbcRepository matchRepository, ScoringEngine scoringEngine) {
        this.matchRepository = matchRepository;
        this.scoringEngine = scoringEngine;
    }

    /**
     * Ranked matches for a job, refreshing stale scores first (or all scores if regenerate).
     *
     * @throws NotFoundException    if the job does not exist
     * @throws MatchesBusyException if another request holds the job's lock for too long
     */
    @Transactional
    public MatchPageView getMatches(UUID jobId, int page, int limit, double minScore, boolean regenerate) {
        JobHeader job = matchRepository.findJob(jobId).orElseThrow(() -> NotFoundException.job(jobId));
        List<JobRequirement> reqs = matchRepository.findRequirements(jobId);
        if (!ScoringEngine.isMatchable(reqs)) {
            return notMatchable(job, page, limit);
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
                return notMatchable(job, page, limit);
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

        List<MatchView> matches = new ArrayList<>(rows.size());
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
                matches.add(toView((int) (offset + i + 1), row, eval));
            }
        }

        MatchReason reason = null;
        if (total == 0 && !matchRepository.anyCandidateExists()) {
            reason = MatchReason.NO_CANDIDATES;
        }
        int totalPages = total == 0 ? 0 : (int) Math.min(Integer.MAX_VALUE, (total + limit - 1) / limit);
        return new MatchPageView(job.id(), job.title(), job.company(), true, reason,
                reason == null ? null : reason.message(), page, limit, totalPages, total, recomputed, matches);
    }

    /**
     * Recomputes one job's matches in its own transaction (called once per job by the batch run).
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
                page, limit, 0, 0L, 0, List.of());
    }

    private static MatchView toView(int rank, RankedRow row, MatchEvaluation eval) {
        double score = Math.round(row.score() * 10_000d) / 10_000d;
        int percent = (int) Math.round(row.score() * 100d);
        MatchBreakdownView breakdown = new MatchBreakdownView(eval.earnedPoints(), eval.maxPoints(),
                eval.matchedRequired(), eval.matchedNiceToHave(), eval.missingRequired(), eval.missingNiceToHave());
        // Phase 2: no AI layer yet, so explanations are never available.
        return new MatchView(rank, row.candidateId(), row.candidateName(), score, percent, eval.summary(),
                breakdown, null, ExplanationStatus.UNAVAILABLE, row.computedAt());
    }
}
