package com.talentmatch.web.dto;

/** Body of {@code POST /api/skills/{id}/aliases}: another name for the skill, e.g. "Postgres". */
public record SkillAliasRequest(String alias) {
}
