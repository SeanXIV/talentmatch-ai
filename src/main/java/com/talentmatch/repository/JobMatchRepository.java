package com.talentmatch.repository;

import com.talentmatch.domain.entity.JobMatch;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Read-only access to job_match; all writes go through {@link MatchJdbcRepository#upsertScores}. */
public interface JobMatchRepository extends JpaRepository<JobMatch, UUID> {

    long countByJobId(UUID jobId);

    Optional<JobMatch> findByCandidateIdAndJobId(UUID candidateId, UUID jobId);
}
