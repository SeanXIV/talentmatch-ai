package com.talentmatch.web.dto;

/**
 * A skill on a job request, referenced by name (case-insensitive, must exist).
 *
 * @param name     skill name
 * @param required true (default when null) for must-have, false for nice-to-have
 */
public record JobSkillRequest(String name, Boolean required) {
}
