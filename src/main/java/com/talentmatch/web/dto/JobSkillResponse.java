package com.talentmatch.web.dto;

import java.util.UUID;

/** A job's skill. */
public record JobSkillResponse(UUID skillId, String name, String category, boolean required) {
}
