package com.talentmatch.web.dto;

import java.util.List;

/**
 * Body of POST/PUT /api/candidates (PUT replaces everything, including skills).
 * Validated in {@code CandidateService} after normalization.
 */
public record CandidateRequest(String fullName, String email, String summary, List<CandidateSkillRequest> skills) {
}
