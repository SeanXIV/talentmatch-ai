package com.talentmatch.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** Composite key of {@link JobSkill}. */
@Embeddable
public class JobSkillId implements Serializable {

    private static final long serialVersionUID = 1L;

    @Column(name = "job_id")
    private UUID jobId;

    @Column(name = "skill_id")
    private UUID skillId;

    protected JobSkillId() {
    }

    public JobSkillId(UUID jobId, UUID skillId) {
        this.jobId = jobId;
        this.skillId = skillId;
    }

    public UUID getJobId() {
        return jobId;
    }

    public UUID getSkillId() {
        return skillId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof JobSkillId other)) {
            return false;
        }
        return Objects.equals(jobId, other.jobId) && Objects.equals(skillId, other.skillId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(jobId, skillId);
    }
}
