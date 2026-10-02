package com.talentmatch.web.dto;

/** Body of POST /api/skills. */
public record SkillRequest(String name, String category) {
}
