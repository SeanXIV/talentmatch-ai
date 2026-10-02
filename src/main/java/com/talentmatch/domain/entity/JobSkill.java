package com.talentmatch.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;

/** A skill listed on a job: required (must-have) or nice-to-have. */
@Entity
@Table(name = "job_skill")
public class JobSkill {

    @EmbeddedId
    private JobSkillId id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("jobId")
    @JoinColumn(name = "job_id")
    private Job job;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("skillId")
    @JoinColumn(name = "skill_id")
    private Skill skill;

    @Column(name = "required", nullable = false)
    private boolean required;

    protected JobSkill() {
    }

    /** Both parents must already have ids (persist the job first). */
    public JobSkill(Job job, Skill skill, boolean required) {
        this.id = new JobSkillId(job.getId(), skill.getId());
        this.job = job;
        this.skill = skill;
        this.required = required;
    }

    public JobSkillId getId() {
        return id;
    }

    public Job getJob() {
        return job;
    }

    public Skill getSkill() {
        return skill;
    }

    public boolean isRequired() {
        return required;
    }

    public void setRequired(boolean required) {
        this.required = required;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof JobSkill other)) {
            return false;
        }
        JobSkillId otherId = other.getId();
        return id != null && id.getJobId() != null && id.getSkillId() != null && id.equals(otherId);
    }

    @Override
    public int hashCode() {
        return JobSkill.class.hashCode();
    }
}
