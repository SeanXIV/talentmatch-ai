package com.talentmatch.web.dto;

import java.util.UUID;

/** Candidate list item. */
public record CandidateSummaryResponse(UUID id, String fullName, String email, String summary) {
}
