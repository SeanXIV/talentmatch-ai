package com.talentmatch.web.dto;

import java.util.UUID;

/** A candidate's skill. */
public record CandidateSkillResponse(UUID skillId, String name, String category, Integer yearsExperience) {
}
