package com.talentmatch.feed;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.talentmatch.feed.source.SourceKind;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JDBC access to {@code feed_source} (V5; not mapped in JPA). Step 5 covers the CRUD side; the
 * poller's claim / release / record-outcome statements arrive with step 6.
 */
@Repository
public class FeedSourceRepository {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final TypeReference<Map<String, String>> OPTIONS_TYPE = new TypeReference<>() {
    };

    private static final String COLUMNS = "id, source_key, kind, managed_by, state, company_name, board_token, "
            + "options::text AS options, poll_interval_seconds, next_poll_at, lease_until, last_polled_at, "
            + "last_success_at, last_status, last_error, consecutive_failures, etag, last_modified, content_hash, "
            + "baseline_at, suspicious_since, open_postings, created_at, updated_at";

    private static final RowMapper<FeedSource> ROW = (rs, n) -> new FeedSource(
            rs.getObject("id", UUID.class),
            rs.getString("source_key"),
            SourceKind.valueOf(rs.getString("kind")),
            SourceManagedBy.valueOf(rs.getString("managed_by")),
            FeedSourceState.valueOf(rs.getString("state")),
            rs.getString("company_name"),
            rs.getString("board_token"),
            readOptions(rs.getString("options")),
            (Integer) rs.getObject("poll_interval_seconds"),
            instant(rs, "next_poll_at"),
            instant(rs, "lease_until"),
            instant(rs, "last_polled_at"),
            instant(rs, "last_success_at"),
            rs.getString("last_status"),
            rs.getString("last_error"),
            rs.getInt("consecutive_failures"),
            rs.getString("etag"),
            rs.getString("last_modified"),
            rs.getString("content_hash"),
            instant(rs, "baseline_at"),
            instant(rs, "suspicious_since"),
            rs.getInt("open_postings"),
            instant(rs, "created_at"),
            instant(rs, "updated_at"));

    private final NamedParameterJdbcTemplate jdbc;

    public FeedSourceRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The values of a new source; everything else takes the V5 defaults (ACTIVE, due now). */
    public record NewSource(String sourceKey, SourceKind kind, SourceManagedBy managedBy, String companyName,
                            String boardToken, Map<String, String> options, Integer pollIntervalSeconds) {
    }

    public Optional<FeedSource> findById(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM feed_source WHERE id = :id",
                new MapSqlParameterSource("id", id), ROW).stream().findFirst();
    }

    /** Locks the row until the transaction ends (edits and deletes; the poller skips locked rows). */
    public Optional<FeedSource> findByIdForUpdate(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM feed_source WHERE id = :id FOR UPDATE",
                new MapSqlParameterSource("id", id), ROW).stream().findFirst();
    }

    public Optional<FeedSource> findByKey(String sourceKey) {
        return jdbc.query("SELECT " + COLUMNS + " FROM feed_source WHERE source_key = :key",
                new MapSqlParameterSource("key", sourceKey), ROW).stream().findFirst();
    }

    /** One page, newest first; {@code kind} and {@code state} are optional filters. */
    public List<FeedSource> findPage(SourceKind kind, FeedSourceState state, long offset, int limit) {
        MapSqlParameterSource params = filterParams(kind, state).addValue("offset", offset).addValue("limit", limit);
        return jdbc.query("SELECT " + COLUMNS + " FROM feed_source" + where(kind, state)
                + " ORDER BY created_at DESC, id LIMIT :limit OFFSET :offset", params, ROW);
    }

    public long count(SourceKind kind, FeedSourceState state) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM feed_source" + where(kind, state),
                filterParams(kind, state), Long.class);
        return count == null ? 0 : count;
    }

    /**
     * Inserts the source unless its key exists already (the UNIQUE source_key decides, so concurrent
     * adds of one board are safe).
     *
     * @return the new row, or empty when a source with this key exists
     */
    public Optional<FeedSource> insertIfAbsent(NewSource source) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("key", source.sourceKey())
                .addValue("kind", source.kind().name())
                .addValue("managedBy", source.managedBy().name())
                .addValue("companyName", source.companyName())
                .addValue("boardToken", source.boardToken())
                .addValue("options", writeOptions(source.options()))
                .addValue("interval", source.pollIntervalSeconds(), java.sql.Types.INTEGER);
        return jdbc.query("INSERT INTO feed_source (source_key, kind, managed_by, company_name, board_token, options, "
                        + "poll_interval_seconds) VALUES (:key, :kind, :managedBy, :companyName, :boardToken, "
                        + "CAST(:options AS jsonb), :interval) ON CONFLICT (source_key) DO NOTHING RETURNING " + COLUMNS,
                params, ROW).stream().findFirst();
    }

    /**
     * Sets the owner-editable fields. {@code dueNow} makes the source due at once (a paused source
     * being resumed).
     *
     * @return the updated row, or empty when it no longer exists
     */
    public Optional<FeedSource> updateSettings(UUID id, String companyName, FeedSourceState state,
                                               Integer pollIntervalSeconds, boolean dueNow) {
        MapSqlParameterSource params = new MapSqlParameterSource("id", id)
                .addValue("companyName", companyName)
                .addValue("state", state.name())
                .addValue("interval", pollIntervalSeconds, java.sql.Types.INTEGER);
        return jdbc.query("UPDATE feed_source SET company_name = :companyName, state = :state, "
                        + "poll_interval_seconds = :interval" + (dueNow ? ", next_poll_at = now()" : "")
                        + " WHERE id = :id RETURNING " + COLUMNS,
                params, ROW).stream().findFirst();
    }

    /** Deletes the source; its postings cascade (V5). @return rows deleted */
    public int delete(UUID id) {
        return jdbc.update("DELETE FROM feed_source WHERE id = :id", new MapSqlParameterSource("id", id));
    }

    /**
     * Deletes FEED jobs that have no posting left (after a source delete, §3.3). Cascades to
     * {@code feed_job}, {@code job_skill}, {@code job_match} and {@code feed_notification}. A job that
     * still has a posting from another source is kept.
     *
     * @return jobs deleted
     */
    public int deleteOrphanedFeedJobs() {
        return jdbc.update("DELETE FROM job j WHERE j.origin = 'FEED' "
                + "AND NOT EXISTS (SELECT 1 FROM job_posting p WHERE p.job_id = j.id)", new MapSqlParameterSource());
    }

    private static String where(SourceKind kind, FeedSourceState state) {
        List<String> clauses = new ArrayList<>(2);
        if (kind != null) {
            clauses.add("kind = :kind");
        }
        if (state != null) {
            clauses.add("state = :state");
        }
        return clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses);
    }

    private static MapSqlParameterSource filterParams(SourceKind kind, FeedSourceState state) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (kind != null) {
            params.addValue("kind", kind.name());
        }
        if (state != null) {
            params.addValue("state", state.name());
        }
        return params;
    }

    static String writeOptions(Map<String, String> options) {
        try {
            // Sorted, so equal options are stored as equal JSON.
            return MAPPER.writeValueAsString(options == null ? Map.of() : new TreeMap<>(options));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize feed source options", e);
        }
    }

    static Map<String, String> readOptions(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, String> options = MAPPER.readValue(json, OPTIONS_TYPE);
            if (options == null) {
                return Map.of();
            }
            options.values().removeIf(Objects::isNull);   // FeedSource copies with Map.copyOf (no nulls)
            return options;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored feed source options could not be read", e);
        }
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }
}
