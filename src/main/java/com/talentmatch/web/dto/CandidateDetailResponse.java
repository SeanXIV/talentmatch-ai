package com.talentmatch.web.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Candidate with skills (sorted by name). */
public record CandidateDetailResponse(
        UUID id,
        String fullName,
        String email,
        String summary,
        List<CandidateSkillResponse> skills,
        Instant createdAt,
        Instant updatedAt) {
}
