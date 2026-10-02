package com.talentmatch.web.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Job with skills (sorted by name). */
public record JobDetailResponse(
        UUID id,
        String title,
        String company,
        String description,
        boolean matchable,
        List<JobSkillResponse> skills,
        Instant createdAt,
        Instant updatedAt) {
}
