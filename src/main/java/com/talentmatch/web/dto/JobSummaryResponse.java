package com.talentmatch.web.dto;

import java.util.UUID;

/** Job list item; matchable is false when the job has no skills. */
public record JobSummaryResponse(UUID id, String title, String company, String description,
                                 int skillCount, boolean matchable) {
}
