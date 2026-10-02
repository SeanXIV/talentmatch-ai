package com.talentmatch.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;

/** A skill a candidate has, with optional years of experience. */
@Entity
@Table(name = "candidate_skill")
public class CandidateSkill {

    @EmbeddedId
    private CandidateSkillId id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("candidateId")
    @JoinColumn(name = "candidate_id")
    private Candidate candidate;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("skillId")
    @JoinColumn(name = "skill_id")
    private Skill skill;

    @Column(name = "years_experience")
    private Integer yearsExperience;

    protected CandidateSkill() {
    }

    /** Both parents must already have ids (persist the candidate first). */
    public CandidateSkill(Candidate candidate, Skill skill, Integer yearsExperience) {
        this.id = new CandidateSkillId(candidate.getId(), skill.getId());
        this.candidate = candidate;
        this.skill = skill;
        this.yearsExperience = yearsExperience;
    }

    public CandidateSkillId getId() {
        return id;
    }

    public Candidate getCandidate() {
        return candidate;
    }

    public Skill getSkill() {
        return skill;
    }

    public Integer getYearsExperience() {
        return yearsExperience;
    }

    public void setYearsExperience(Integer yearsExperience) {
        this.yearsExperience = yearsExperience;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CandidateSkill other)) {
            return false;
        }
        CandidateSkillId otherId = other.getId();
        return id != null && id.getCandidateId() != null && id.getSkillId() != null && id.equals(otherId);
    }

    @Override
    public int hashCode() {
        return CandidateSkill.class.hashCode();
    }
}
