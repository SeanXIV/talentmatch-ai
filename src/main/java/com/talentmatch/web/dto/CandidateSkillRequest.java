package com.talentmatch.web.dto;

/**
 * A skill on a candidate request, referenced by name (case-insensitive, must exist).
 *
 * @param name            skill name
 * @param yearsExperience 0..60, or null if unknown
 */
public record CandidateSkillRequest(String name, Integer yearsExperience) {
}
