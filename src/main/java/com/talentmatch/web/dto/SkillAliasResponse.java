package com.talentmatch.web.dto;

import java.time.Instant;
import java.util.UUID;

/** One alias (other name) of a skill. */
public record SkillAliasResponse(UUID id, String alias, Instant createdAt) {
}
