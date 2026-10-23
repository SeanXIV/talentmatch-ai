package com.talentmatch.profile;

import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Reads one version of the owner's profile history ({@code owner_profile_version}, V4; append-only). Phase 7
 * edits resolve refs and read-only facts against the version a document was built from, not the current one.
 */
@Repository
public class ProfileVersionReader {

    private final NamedParameterJdbcTemplate jdbc;

    public ProfileVersionReader(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The profile document saved as {@code version}, normalized as {@link OwnerProfileRepository#find()} reads it. */
    public Optional<ProfileDocument> find(int version) {
        return jdbc.query("SELECT profile::text AS profile FROM owner_profile_version WHERE version = :version",
                new MapSqlParameterSource("version", version), (rs, n) -> ProfileJson.document(rs.getString("profile")))
                .stream().findFirst();
    }
}
