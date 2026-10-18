package com.talentmatch.seeker;

import com.talentmatch.ai.AiProperties;
import com.talentmatch.ai.Audience;
import com.talentmatch.ai.ExplanationReason;
import com.talentmatch.ai.ExplanationRequest;
import com.talentmatch.ai.ExplanationResult;
import com.talentmatch.ai.ExplanationResults;
import com.talentmatch.ai.ExplanationService;
import com.talentmatch.ai.ExplanationStatus;
import com.talentmatch.ai.JobContext;
import com.talentmatch.ai.MatchContext;
import com.talentmatch.ai.SeekerFallbackRenderer;
import com.talentmatch.config.MatchProperties;
import com.talentmatch.domain.scoring.CandidateSkillFact;
import com.talentmatch.domain.scoring.JobRequirement;
import com.talentmatch.domain.scoring.MatchEvaluation;
import com.talentmatch.domain.scoring.ScoringEngine;
import com.talentmatch.profile.OwnerProfileRepository;
import com.talentmatch.repository.MatchJdbcRepository;
import com.talentmatch.repository.UuidOrder;
import com.talentmatch.seeker.CandidateMatchRepository.CandidateHeader;
import com.talentmatch.seeker.CandidateMatchRepository.RankedJobRow;
import com.talentmatch.seeker.CandidateMatchRepository.Scope;
import com.talentmatch.service.MatchBreakdownView;
import com.talentmatch.service.MatchReason;
import com.talentmatch.service.exception.MatchesBusyException;
import com.talentmatch.service.exception.NotFoundException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Ranked, explained jobs for one candidate (Phase 6 §1.2), the reverse of the job-side
 * {@code MatchService}. Uses the same {@code job_match} cache and staleness rule
 * ({@code computed_at < GREATEST(candidate.updated_at, job.updated_at)}).
 *
 * <ol>
 *   <li>Read (no locks): the candidate (404), whether it is the owner, and the in-scope matchable jobs
 *       to score (stale, or all with {@code regenerate}).</li>
 *   <li>Score them in chunks of {@code talentmatch.matches.rescore-chunk} jobs, one transaction each:
 *       the candidate's match lock first, then the jobs' match locks in uuid order (the same per-job
 *       locks as every other match writer, so a score is never written from old skills), a re-check of
 *       the targets under the locks, then one upsert. A lock timeout or deadlock is 503 MATCHES_BUSY;
 *       chunks already committed stay (idempotent).</li>
 *   <li>Read the page in one REPEATABLE READ snapshot: counts, rows, the read-time breakdown.</li>
 *   <li>Outside any transaction: seeker explanations ({@link ExplanationService#explainAll}).</li>
 * </ol>
 *
 * <p>No {@code @Transactional}: transactions are explicit. Logs carry ids and counts only.
 */
@Service
public class CandidateMatchService {

    private static final Logger log = LoggerFactory.getLogger(CandidateMatchService.class);

    /** Above this many scoring targets one request is slow: logged once (PRODUCTION_READINESS). */
    static final int LARGE_TARGET_WARN = 10_000;
    private static final double SCORE_TOLERANCE = 1e-9;

    private final CandidateMatchRepository repository;
    private final MatchJdbcRepository matchRepository;
    private final OwnerProfileRepository ownerProfiles;
    private final ScoringEngine scoringEngine;
    private final ExplanationService explanationService;
    private final AiProperties aiProperties;
    private final MatchProperties matchProperties;
    private final TransactionTemplate writeTx;
    private final TransactionTemplate readTx;
    private final TransactionTemplate snapshotTx;

    public CandidateMatchService(CandidateMatchRepository repository, MatchJdbcRepository matchRepository,
                                 OwnerProfileRepository ownerProfiles, ScoringEngine scoringEngine,
                                 ExplanationService explanationService, AiProperties aiProperties,
                                 MatchProperties matchProperties, PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.matchRepository = matchRepository;
        this.ownerProfiles = ownerProfiles;
        this.scoringEngine = scoringEngine;
        this.explanationService = explanationService;
        this.aiProperties = aiProperties;
        this.matchProperties = matchProperties;
        // Scoring chunks: READ COMMITTED, so the re-check after waiting for the locks sees what the
        // lock holders committed.
        this.writeTx = new TransactionTemplate(transactionManager);
        this.readTx = new TransactionTemplate(transactionManager);
        this.readTx.setReadOnly(true);
        // The page: one snapshot, so the counts, rows, candidate skills and job skills (and the
        // updated_at values that guard the explanation writes) all agree.
        this.snapshotTx = new TransactionTemplate(transactionManager);
        this.snapshotTx.setReadOnly(true);
        this.snapshotTx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    /**
     * Ranked jobs for a candidate, scoring missing or stale rows first (every in-scope job with
     * {@code regenerate}), each with a seeker explanation. With {@code regenerate}, the AI explanations
     * of the page's top-N rows are generated again.
     *
     * @param page  0-based page; {@code page * limit} must fit in an int (validated by the caller)
     * @param limit page size (validated by the caller)
     * @throws NotFoundException    if the candidate does not exist
     * @throws MatchesBusyException if a job's or the candidate's match lock is held too long, or on a deadlock
     */
    public CandidateMatchPageView getMatches(UUID candidateId, CandidateMatchQuery query, int page, int limit,
                                             boolean regenerate) {
        Objects.requireNonNull(candidateId, "candidateId");
        Objects.requireNonNull(query, "query");
        long started = System.nanoTime();

        // 1. Who, and what to score (no locks).
        Targeting targeting = Objects.requireNonNull(readTx.execute(status -> target(candidateId, query, regenerate)),
                "target returned null");
        List<UUID> targets = targeting.targets();
        if (targets.size() > LARGE_TARGET_WARN) {
            log.warn("Candidate {} has {} jobs to score in one request (over {}); this request will be slow",
                    candidateId, targets.size(), LARGE_TARGET_WARN);
        }

        // 2. Score in locked chunks.
        int recomputed = rescore(candidateId, targeting.scope(), targets, regenerate);

        // 3. The page, in one snapshot.
        RankedPage ranked = Objects.requireNonNull(
                snapshotTx.execute(status -> readPage(candidateId, targeting.scope(), page, limit)),
                "readPage returned null");

        // 4. Explanations, outside any transaction.
        List<CandidateMatchView> matches = new ArrayList<>(ranked.rows().size());
        int generated = 0;
        if (!ranked.rows().isEmpty()) {
            List<ExplanationRequest> requests = new ArrayList<>(ranked.rows().size());
            for (ScoredRow s : ranked.rows()) {
                requests.add(new ExplanationRequest(jobContext(s.row()), matchContext(ranked.candidate(), s)));
            }
            ExplanationResults results = explanationService.explainAll(Audience.SEEKER, requests, regenerate);
            generated = results.generated();
            for (int i = 0; i < ranked.rows().size(); i++) {
                ScoredRow s = ranked.rows().get(i);
                ExplanationResult result = i < results.results().size() ? results.results().get(i) : null;
                if (result == null) {
                    result = ExplanationResult.template(ExplanationStatus.UNAVAILABLE,
                            SeekerFallbackRenderer.render(percent(s.row().score()), s.eval(),
                                    ExplanationReason.GENERATION_FAILED, false, aiProperties.topN()));
                }
                matches.add(toView(s, result));
            }
        }

        // 5. The response.
        MatchReason reason = ranked.reason();
        long total = ranked.total();
        int totalPages = total == 0 ? 0 : (int) Math.min(Integer.MAX_VALUE, (total + limit - 1) / limit);
        log.info("Candidate matches candidate={} owner={} sort={} targets={} recomputed={} total={} generated={} "
                        + "latencyMs={}", candidateId, targeting.owner(), query.sort(), targets.size(), recomputed,
                total, generated, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        return new CandidateMatchPageView(candidateId, targeting.owner(), targeting.profileVersion(), query.sort(),
                page, limit, totalPages, total, recomputed, generated, ranked.unmatchable(), reason,
                reason == null ? null : reason.message(), matches);
    }

    // ------------------------------------------------------------------ 1. targeting

    private Targeting target(UUID candidateId, CandidateMatchQuery query, boolean regenerate) {
        repository.findCandidate(candidateId).orElseThrow(() -> NotFoundException.candidate(candidateId));
        Optional<OwnerProfileRepository.OwnerRef> ref = ownerProfiles.findRef();
        boolean owner = ref.isPresent() && candidateId.equals(ref.get().candidateId());
        Integer profileVersion = owner ? ref.get().version() : null;
        Scope scope = scopeFor(query, owner);
        List<UUID> targets = repository.findTargetJobIds(candidateId, scope, regenerate, null);
        return new Targeting(owner, profileVersion, scope, UuidOrder.sortedDistinct(targets));
    }

    /**
     * The request's scope. Owner preference verdicts (hiding FILTERED jobs unless
     * {@code includeFiltered}) are not applied here yet; {@link Scope#of}'s {@code hideFiltered} is the
     * switch for them.
     */
    static Scope scopeFor(CandidateMatchQuery query, boolean owner) {
        return Scope.of(query, false);
    }

    // ------------------------------------------------------------------ 2. scoring

    /** Scores the targets chunk by chunk, one transaction each; returns the rows written. */
    private int rescore(UUID candidateId, Scope scope, List<UUID> targets, boolean forced) {
        if (targets.isEmpty()) {
            return 0;
        }
        int chunkSize = matchProperties.rescoreChunk();
        int written = 0;
        for (int from = 0; from < targets.size(); from += chunkSize) {
            List<UUID> chunk = targets.subList(from, Math.min(from + chunkSize, targets.size()));
            try {
                Integer n = writeTx.execute(status -> rescoreChunk(candidateId, scope, chunk, forced));
                written += n == null ? 0 : n;
            } catch (PessimisticLockingFailureException e) {
                // Lock timeout or deadlock; the chunks committed so far stay (a retry has fewer targets).
                throw new MatchesBusyException(e);
            }
        }
        return written;
    }

    /**
     * One chunk, inside its transaction: the candidate lock, then the job locks (ascending uuid order),
     * a re-check under the locks, the candidate's current skills, the engine, one upsert.
     *
     * @param chunk strictly ascending in PostgreSQL uuid order
     */
    private int rescoreChunk(UUID candidateId, Scope scope, List<UUID> chunk, boolean forced) {
        matchRepository.lockCandidate(candidateId);
        matchRepository.lockJobs(chunk);
        // Re-read under the locks: another request may have scored these jobs, or a job may have been
        // edited, closed, deleted or lost its skills, while this one waited.
        List<UUID> jobIds = repository.findTargetJobIds(candidateId, scope, forced, chunk);
        if (jobIds.isEmpty()) {
            return 0;
        }
        Map<UUID, List<JobRequirement>> requirements = repository.requirementsFor(jobIds);
        // Read under the candidate lock, so these are the skills the candidate's updated_at stands for.
        List<CandidateSkillFact> facts = repository.candidateFacts(candidateId);
        List<UUID> scoredIds = new ArrayList<>(jobIds.size());
        double[] scores = new double[jobIds.size()];
        for (UUID jobId : jobIds) {
            List<JobRequirement> reqs = requirements.get(jobId);
            if (!ScoringEngine.isMatchable(reqs)) {
                continue;                                     // cannot happen under the job lock; be safe
            }
            scores[scoredIds.size()] = scoringEngine.evaluate(reqs, facts).score();
            scoredIds.add(jobId);
        }
        if (scoredIds.isEmpty()) {
            return 0;
        }
        double[] exact = scoredIds.size() == scores.length ? scores : Arrays.copyOf(scores, scoredIds.size());
        int written = matchRepository.upsertCandidateScores(candidateId, scoredIds, exact);
        log.debug("Recomputed {} match(es) for candidate {}", written, candidateId);
        return written;
    }

    // ------------------------------------------------------------------ 3. the page

    private RankedPage readPage(UUID candidateId, Scope scope, int page, int limit) {
        CandidateHeader candidate = repository.findCandidate(candidateId)
                .orElseThrow(() -> NotFoundException.candidate(candidateId));
        double minScore = scope.query().minScore();
        long total = repository.countRanked(candidateId, scope, minScore);
        long offset = (long) page * limit;
        List<RankedJobRow> rows = total > offset
                ? repository.findRankedPage(candidateId, scope, minScore, scope.query().sort(), limit, offset)
                : List.of();
        long unmatchable = repository.countUnmatchable(scope);
        List<CandidateSkillFact> facts = repository.candidateFacts(candidateId);

        List<ScoredRow> scored = new ArrayList<>(rows.size());
        if (!rows.isEmpty()) {
            Map<UUID, List<JobRequirement>> requirements =
                    repository.requirementsFor(rows.stream().map(RankedJobRow::jobId).toList());
            for (int i = 0; i < rows.size(); i++) {
                RankedJobRow row = rows.get(i);
                List<JobRequirement> reqs = requirements.get(row.jobId());
                if (!ScoringEngine.isMatchable(reqs)) {
                    // Impossible in one snapshot (the page query requires a skill); never fail the page.
                    log.warn("Job {} has no skills on candidate {}'s page; skipped", row.jobId(), candidateId);
                    continue;
                }
                MatchEvaluation eval = scoringEngine.evaluate(reqs, facts);
                if (Math.abs(eval.score() - row.score()) > SCORE_TOLERANCE) {
                    log.warn("Cached score {} for candidate {} / job {} differs from the engine's {} "
                                    + "(weights changed?). Run POST /api/matches/recompute to refresh.",
                            row.score(), candidateId, row.jobId(), eval.score());
                }
                scored.add(new ScoredRow((int) (offset + i + 1), row, eval));
            }
        }

        MatchReason reason = null;
        if (total == 0 && !repository.anyMatchable(scope)) {
            reason = MatchReason.NO_MATCHABLE_JOBS;
        } else if (facts.isEmpty()) {
            reason = MatchReason.CANDIDATE_HAS_NO_SKILLS;
        }
        return new RankedPage(candidate, total, unmatchable, reason, scored);
    }

    // ------------------------------------------------------------------ 4./5. explanations and views

    private static JobContext jobContext(RankedJobRow row) {
        return new JobContext(row.jobId(), row.title(), row.company(), row.description(), row.jobUpdatedAt());
    }

    /** The candidate's name is never part of a seeker explanation (decision h): it is not passed on. */
    private static MatchContext matchContext(CandidateHeader candidate, ScoredRow s) {
        RankedJobRow row = s.row();
        return new MatchContext(s.rank(), candidate.id(), null, candidate.summary(), candidate.updatedAt(),
                row.score(), percent(row.score()), s.eval(), row.stored());
    }

    private static CandidateMatchView toView(ScoredRow s, ExplanationResult explanation) {
        RankedJobRow row = s.row();
        MatchEvaluation eval = s.eval();
        double score = Math.round(row.score() * 10_000d) / 10_000d;
        MatchBreakdownView breakdown = new MatchBreakdownView(eval.earnedPoints(), eval.maxPoints(),
                eval.matchedRequired(), eval.matchedNiceToHave(), eval.missingRequired(), eval.missingNiceToHave());
        CandidateMatchView.Salary salary = row.salaryMin() == null && row.salaryMax() == null ? null
                : new CandidateMatchView.Salary(row.salaryMin(), row.salaryMax(), row.salaryCurrency(),
                        row.salaryPeriod(), Boolean.TRUE.equals(row.salaryEstimated()));
        // Owner-only preference fields (verdict, reasons, flags) are not filled in yet: null / empty.
        return new CandidateMatchView(s.rank(), row.jobId(), row.title(), row.company(), row.origin(),
                row.listedAt(), row.listedAtSource(), row.postedAt(), row.firstSeenAt(), row.closedAt(),
                row.baseline(), row.primaryUrl(), row.workplace(), row.locationText(), row.countryCodes(),
                row.seniority(), salary, row.enrichmentStatus(), null, List.of(), List.of(), score,
                percent(row.score()), eval.summary(), breakdown, explanation.aiExplanation(), explanation.status(),
                explanation.explanation(), row.computedAt());
    }

    private static int percent(double score) {
        return (int) Math.round(score * 100d);
    }

    // ------------------------------------------------------------------ types

    private record Targeting(boolean owner, Integer profileVersion, Scope scope, List<UUID> targets) {
    }

    private record ScoredRow(int rank, RankedJobRow row, MatchEvaluation eval) {
    }

    private record RankedPage(CandidateHeader candidate, long total, long unmatchable, MatchReason reason,
                              List<ScoredRow> rows) {
    }
}
