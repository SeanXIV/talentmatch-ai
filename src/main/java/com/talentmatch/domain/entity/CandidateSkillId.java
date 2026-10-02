package com.talentmatch.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** Composite key of {@link CandidateSkill}. */
@Embeddable
public class CandidateSkillId implements Serializable {

    private static final long serialVersionUID = 1L;

    @Column(name = "candidate_id")
    private UUID candidateId;

    @Column(name = "skill_id")
    private UUID skillId;

    protected CandidateSkillId() {
    }

    public CandidateSkillId(UUID candidateId, UUID skillId) {
        this.candidateId = candidateId;
        this.skillId = skillId;
    }

    public UUID getCandidateId() {
        return candidateId;
    }

    public UUID getSkillId() {
        return skillId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CandidateSkillId other)) {
            return false;
        }
        return Objects.equals(candidateId, other.candidateId) && Objects.equals(skillId, other.skillId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(candidateId, skillId);
    }
}
