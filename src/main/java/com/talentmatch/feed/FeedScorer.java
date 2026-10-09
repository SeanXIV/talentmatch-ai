package com.talentmatch.feed;

import com.talentmatch.domain.scoring.CandidateSkillFact;
import com.talentmatch.domain.scoring.JobRequirement;
import com.talentmatch.domain.scoring.MatchEvaluation;
import com.talentmatch.domain.scoring.ScoringEngine;
import com.talentmatch.repository.MatchJdbcRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Scores the owner against one feed job with the existing match scoring (§1.2 step 4): the job's
 * {@code job_skill} rows, the owner's candidate skills, {@link ScoringEngine}, and the owner's
 * {@code job_match} row upserted exactly as {@code MatchService} does, under the same per-job advisory
 * lock. Other candidates are not scored here; {@code GET /api/jobs/{id}/matches} still scores them
 * lazily. Joins the caller's transaction (the processor's), so the job_skill changes it just made are
 * the ones scored, and the match row and the skills commit together.
 *
 * <p>{@link FeedProcessor} already takes the job's match lock before it changes the skills; the lock
 * is a transaction-level advisory lock, so taking it again here in the same transaction returns at
 * once (it is released, once, when the transaction ends). It is kept so this class stays correct on
 * its own.
 */
@Component
public class FeedScorer {

    private final MatchJdbcRepository matches;
    private final ScoringEngine engine;

    public FeedScorer(MatchJdbcRepository matches, ScoringEngine engine) {
        this.matches = matches;
        this.engine = engine;
    }

    /**
     * @return the owner's evaluation, or empty when the job has no skills (not matchable; nothing is
     *         written)
     * @throws org.springframework.dao.PessimisticLockingFailureException when the job's match lock
     *         stays held too long (the caller retries later)
     */
    @Transactional
    public Optional<MatchEvaluation> score(UUID jobId, UUID ownerCandidateId) {
        matches.lockJob(jobId);
        List<JobRequirement> reqs = matches.findRequirements(jobId);
        if (!ScoringEngine.isMatchable(reqs)) {
            return Optional.empty();
        }
        List<UUID> skillIds = reqs.stream().map(JobRequirement::skillId).toList();
        Map<UUID, List<CandidateSkillFact>> facts = matches.findFacts(skillIds, List.of(ownerCandidateId));
        MatchEvaluation evaluation = engine.evaluate(reqs, facts.getOrDefault(ownerCandidateId, List.of()));
        matches.upsertScores(jobId, List.of(ownerCandidateId), new double[] {evaluation.score()});
        return Optional.of(evaluation);
    }
}
