package com.talentmatch.web.dto;

import java.util.List;

/**
 * Body of POST/PUT /api/jobs (PUT replaces everything, including skills).
 * Validated in {@code JobService} after normalization.
 */
public record JobRequest(String title, String company, String description, List<JobSkillRequest> skills) {
}
