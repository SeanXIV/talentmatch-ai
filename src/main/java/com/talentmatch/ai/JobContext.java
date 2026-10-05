package com.talentmatch.ai;

import java.time.Instant;
import java.util.UUID;

/**
 * The job side of an explanation prompt.
 *
 * @param updatedAt job.updated_at as read with the page (guards the explanation write)
 */
public record JobContext(UUID jobId, String title, String company, String description, Instant updatedAt) {
}
