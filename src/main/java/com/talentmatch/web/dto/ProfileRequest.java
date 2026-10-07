package com.talentmatch.web.dto;

import com.talentmatch.profile.ProfileDocument;
import java.util.UUID;

/**
 * Body of {@code PUT /api/profile}: the reviewed profile to save as the owner's master profile.
 *
 * @param resumeId            the uploaded CV this profile came from (optional)
 * @param createMissingSkills create skills that don't exist yet (otherwise unknown skills are errors)
 * @param profile             the profile document
 */
public record ProfileRequest(UUID resumeId, Boolean createMissingSkills, ProfileDocument profile) {
}
