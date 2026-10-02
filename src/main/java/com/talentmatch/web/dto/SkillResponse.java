package com.talentmatch.web.dto;

import java.util.UUID;

/** A skill. */
public record SkillResponse(UUID id, String name, String category) {
}
