package com.talentmatch.repository.projection;

import java.util.UUID;

/** Row of the candidate list (JPQL constructor projection; no skills loaded). */
public record CandidateListRow(UUID id, String fullName, String email, String summary) {
}
