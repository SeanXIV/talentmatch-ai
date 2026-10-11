package com.talentmatch.web.dto;

import java.util.UUID;

/**
 * Job list item; matchable is false when the job has no skills. origin is MANUAL or FEED
 * (FEED jobs come from the job feed and are read-only in this API).
 */
public record JobSummaryResponse(UUID id, String title, String company, String description,
                                 int skillCount, boolean matchable, String origin) {
}
