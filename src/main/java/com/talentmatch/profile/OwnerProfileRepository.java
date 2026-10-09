package com.talentmatch.profile;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** JDBC access to the single-row {@code owner_profile} table (V4). */
@Repository
public class OwnerProfileRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public OwnerProfileRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The stored owner profile row. */
    public record StoredProfile(UUID candidateId, UUID resumeId, int version, ProfileDocument profile,
                                Instant confirmedAt, Instant updatedAt) {
    }

    public Optional<StoredProfile> find() {
        return jdbc.query("SELECT candidate_id, resume_id, version, profile::text AS profile, confirmed_at, updated_at "
                + "FROM owner_profile WHERE id", new MapSqlParameterSource(), (rs, n) -> new StoredProfile(
                        rs.getObject("candidate_id", UUID.class),
                        rs.getObject("resume_id", UUID.class),
                        rs.getInt("version"),
                        ProfileJson.document(rs.getString("profile")),
                        ResumeRepository.instant(rs, "confirmed_at"),
                        ResumeRepository.instant(rs, "updated_at"))).stream().findFirst();
    }

    /**
     * Serializes profile saves until the transaction ends. An advisory lock, not SELECT … FOR
     * UPDATE: before the first save there is no row to lock, and two first saves at once (a
     * double click) would each create an owner candidate. Must be the first statement of the save.
     */
    public void lockForSave() {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtext('talentmatch.owner_profile'))",
                new MapSqlParameterSource(), rs -> null);
    }

    /** The owner's candidate and current profile version, without the profile document. */
    public record OwnerRef(UUID candidateId, int version) {
    }

    /** The owner's candidate id and profile version, if a profile was saved (cheap: no JSON). */
    public Optional<OwnerRef> findRef() {
        return jdbc.query("SELECT candidate_id, version FROM owner_profile WHERE id", new MapSqlParameterSource(),
                (rs, n) -> new OwnerRef(rs.getObject("candidate_id", UUID.class), rs.getInt("version")))
                .stream().findFirst();
    }

    /** The owner's candidate id, if a profile was saved. */
    public Optional<UUID> findCandidateId() {
        return jdbc.queryForList("SELECT candidate_id FROM owner_profile WHERE id",
                new MapSqlParameterSource(), UUID.class).stream().findFirst();
    }

    /**
     * Appends the next history version and makes it current. Call under {@link #lockForSave()}
     * (the next version number is MAX + 1).
     *
     * @return the new version number
     */
    public int save(UUID candidateId, UUID resumeId, String profileJson) {
        MapSqlParameterSource p = new MapSqlParameterSource("candidateId", candidateId).addValue("resumeId", resumeId)
                .addValue("profile", profileJson);
        Integer version = jdbc.queryForObject("INSERT INTO owner_profile_version (version, profile, resume_id) "
                + "SELECT COALESCE(MAX(version), 0) + 1, CAST(:profile AS jsonb), :resumeId FROM owner_profile_version "
                + "RETURNING version", p, Integer.class);
        jdbc.update("INSERT INTO owner_profile (id, candidate_id, resume_id, profile, version, confirmed_at) "
                + "SELECT true, :candidateId, :resumeId, CAST(:profile AS jsonb), v.version, v.confirmed_at "
                + "FROM owner_profile_version v WHERE v.version = :version "
                + "ON CONFLICT (id) DO UPDATE SET candidate_id = EXCLUDED.candidate_id, resume_id = EXCLUDED.resume_id, "
                + "profile = EXCLUDED.profile, version = EXCLUDED.version, confirmed_at = EXCLUDED.confirmed_at",
                p.addValue("version", version));
        return version;
    }
}
