package com.talentmatch.preferences;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JDBC access to the single-row {@code job_preferences} table (V5). No row = no preferences
 * (no filters). Uses its own JSON mapper so the stored format doesn't depend on the web layer.
 */
@Repository
public class PreferencesRepository {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final NamedParameterJdbcTemplate jdbc;

    public PreferencesRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Stored(JobPreferences preferences, int version, Instant updatedAt) {
    }

    public Optional<Stored> find() {
        return jdbc.query("SELECT preferences::text AS preferences, version, updated_at FROM job_preferences WHERE id",
                new MapSqlParameterSource(), (rs, n) -> new Stored(read(rs.getString("preferences")),
                        rs.getInt("version"), instant(rs, "updated_at"))).stream().findFirst();
    }

    /** The current version, if preferences were saved. */
    public Optional<Integer> findVersion() {
        return jdbc.queryForList("SELECT version FROM job_preferences WHERE id", new MapSqlParameterSource(),
                Integer.class).stream().findFirst();
    }

    /** Replaces the preferences (version 1 on the first save, then +1 each save; atomic). */
    public Stored save(JobPreferences preferences) {
        return jdbc.queryForObject("INSERT INTO job_preferences (id, preferences, version) "
                        + "VALUES (true, CAST(:preferences AS jsonb), 1) "
                        + "ON CONFLICT (id) DO UPDATE SET preferences = EXCLUDED.preferences, "
                        + "version = job_preferences.version + 1 "
                        + "RETURNING preferences::text AS preferences, version, updated_at",
                new MapSqlParameterSource("preferences", write(preferences)),
                (rs, n) -> new Stored(read(rs.getString("preferences")), rs.getInt("version"),
                        instant(rs, "updated_at")));
    }

    static String write(JobPreferences preferences) {
        try {
            return MAPPER.writeValueAsString(preferences);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize job preferences", e);
        }
    }

    static JobPreferences read(String json) {
        try {
            return MAPPER.readValue(json, JobPreferences.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored job preferences JSON could not be read", e);
        }
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }
}
