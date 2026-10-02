package com.talentmatch.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/**
 * Persisted match score. Read-only in JPA (schema validation and reads only): every write
 * goes through the JDBC upsert in {@code MatchJdbcRepository}.
 */
@Entity
@Immutable
@Table(name = "job_match")
public class JobMatch {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "candidate_id", nullable = false)
    private UUID candidateId;

    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    @Column(name = "score", nullable = false)
    private double score;

    @Column(name = "ai_explanation", columnDefinition = "text")
    private String aiExplanation;

    @Column(name = "computed_at", nullable = false)
    private Instant computedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected JobMatch() {
    }

    public UUID getId() {
        return id;
    }

    public UUID getCandidateId() {
        return candidateId;
    }

    public UUID getJobId() {
        return jobId;
    }

    public double getScore() {
        return score;
    }

    public String getAiExplanation() {
        return aiExplanation;
    }

    public Instant getComputedAt() {
        return computedAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof JobMatch other)) {
            return false;
        }
        return id != null && id.equals(other.getId());
    }

    @Override
    public int hashCode() {
        return JobMatch.class.hashCode();
    }
}
