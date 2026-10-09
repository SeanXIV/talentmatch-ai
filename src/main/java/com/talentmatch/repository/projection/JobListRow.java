package com.talentmatch.repository.projection;

import java.util.UUID;

/** Row of the job list with its origin and skill count (single grouped JPQL query, no N+1). */
public record JobListRow(UUID id, String title, String company, String description, String origin,
                         Long skillCount) {
}
