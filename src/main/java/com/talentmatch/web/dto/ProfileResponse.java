package com.talentmatch.web.dto;

import com.talentmatch.profile.ProfileDocument;
import com.talentmatch.profile.ProfileWarning;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The owner's confirmed master profile.
 *
 * @param candidateId the owner's candidate row (use it with the candidate and match endpoints)
 * @param resumeId    the CV it came from, if any
 * @param version     confirmed-profile version (1, 2, …; one per save, kept in owner_profile_version)
 * @param profile     the full profile document
 * @param skills      the profile's skills as linked to the candidate (used for scoring)
 * @param warnings    things worth checking after a save, e.g. a new skill that looks like an
 *                    existing one ("Postgres" vs "PostgreSQL"); empty on GET
 */
public record ProfileResponse(UUID candidateId, UUID resumeId, int version, ProfileDocument profile,
                              List<CandidateSkillResponse> skills, Instant confirmedAt, Instant updatedAt,
                              List<ProfileWarning> warnings) {
}
