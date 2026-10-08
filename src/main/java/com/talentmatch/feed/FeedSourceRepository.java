package com.talentmatch.feed;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.talentmatch.feed.source.SourceKind;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Clock;
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
 * JDBC access to {@code feed_source} (V5; not mapped in JPA): the CRUD side (step 5) and the
 * poller's claim / lease / record-outcome statements (step 6). Every statement that finishes a poll
 * is guarded with {@code WHERE id = :id AND lease_until = :lease}, so a poller whose lease was taken
 * over writes nothing.
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
    private final Clock clock;

    public FeedSourceRepository(NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
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
                .addValue("interval", pollIntervalSeconds, java.sql.Types.INTEGER)
                .addValue("now", ts(clock.instant()), Types.TIMESTAMP);   // app Clock, as claimDue compares
        return jdbc.query("UPDATE feed_source SET company_name = :companyName, state = :state, "
                        + "poll_interval_seconds = :interval" + (dueNow ? ", next_poll_at = :now" : "")
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

    // ------------------------------------------------------------------ poller: claim and lease (§4.1)

    /**
     * Claims up to {@code limit} due ACTIVE sources (oldest {@code next_poll_at} first) by leasing them
     * until {@code leaseUntil}. Rows locked by another transaction are skipped, and so are rows with a
     * lease that hasn't expired; one autocommit statement.
     *
     * @return the claimed rows, each with {@code leaseUntil} set (the poller's lease token)
     */
    public List<FeedSource> claimDue(int limit, Instant now, Instant leaseUntil) {
        if (limit <= 0) {
            return List.of();
        }
        MapSqlParameterSource params = new MapSqlParameterSource("limit", limit)
                .addValue("now", ts(now), Types.TIMESTAMP)
                .addValue("leaseUntil", ts(leaseUntil), Types.TIMESTAMP);
        return jdbc.query("UPDATE feed_source SET lease_until = :leaseUntil WHERE id IN (SELECT id FROM feed_source "
                + "WHERE state = 'ACTIVE' AND next_poll_at <= :now AND (lease_until IS NULL OR lease_until < :now) "
                + "ORDER BY next_poll_at LIMIT :limit FOR UPDATE SKIP LOCKED) RETURNING " + COLUMNS, params, ROW);
    }

    /**
     * Claims one source for a poll on request, whatever its state or due time, unless another poll
     * holds an unexpired lease.
     *
     * @return the claimed row, or empty when it is leased (or no longer exists)
     */
    public Optional<FeedSource> claim(UUID id, Instant now, Instant leaseUntil) {
        MapSqlParameterSource params = new MapSqlParameterSource("id", id)
                .addValue("now", ts(now), Types.TIMESTAMP)
                .addValue("leaseUntil", ts(leaseUntil), Types.TIMESTAMP);
        return jdbc.query("UPDATE feed_source SET lease_until = :leaseUntil WHERE id = :id "
                + "AND (lease_until IS NULL OR lease_until < :now) RETURNING " + COLUMNS, params, ROW)
                .stream().findFirst();
    }

    /**
     * Gives a lease back without polling (no free poll thread). {@code dueNow} makes the source due
     * at once so the next tick picks it up.
     *
     * @return false when the lease was no longer ours
     */
    public boolean release(UUID id, Instant lease, boolean dueNow, Instant now) {
        MapSqlParameterSource params = leaseParams(id, lease).addValue("now", ts(now), Types.TIMESTAMP);
        return jdbc.update("UPDATE feed_source SET lease_until = NULL"
                + (dueNow ? ", next_poll_at = LEAST(next_poll_at, :now)" : "")
                + " WHERE id = :id AND lease_until = :lease", params) == 1;
    }

    /** Drops every lease (startup: with one instance a lease that survived a restart is stale). @return rows */
    public int releaseAllLeases() {
        return jdbc.update("UPDATE feed_source SET lease_until = NULL WHERE lease_until IS NOT NULL",
                new MapSqlParameterSource());
    }

    /**
     * Locks the source row for the poll's write transaction if the lease is still ours. Edits and
     * deletes lock the same row, so they wait for (or are waited on by) the poll's writes.
     *
     * @return false when the lease was taken over or the source was deleted
     */
    public boolean lockLeased(UUID id, Instant lease) {
        return !jdbc.queryForList("SELECT id FROM feed_source WHERE id = :id AND lease_until = :lease FOR UPDATE",
                leaseParams(id, lease), UUID.class).isEmpty();
    }

    /**
     * The state after a successful poll (status OK or SUSPICIOUS_EMPTY), guarded by the lease.
     *
     * @param etag        null when it must not be sent next time (Greenhouse details still pending)
     * @param contentHash null when the next fetch must not be treated as unchanged (same reason)
     * @return false when the lease was no longer ours (nothing written)
     */
    public boolean recordSuccess(UUID id, Instant lease, Instant now, Instant nextPollAt, String status, String etag,
                                 String lastModified, String contentHash, Instant suspiciousSince, int openPostings) {
        MapSqlParameterSource params = leaseParams(id, lease)
                .addValue("now", ts(now), Types.TIMESTAMP)
                .addValue("next", ts(nextPollAt), Types.TIMESTAMP)
                .addValue("status", status)
                .addValue("etag", fits(etag, 300), Types.VARCHAR)
                .addValue("lastModified", fits(lastModified, 100), Types.VARCHAR)
                .addValue("hash", contentHash, Types.VARCHAR)
                .addValue("suspiciousSince", ts(suspiciousSince), Types.TIMESTAMP)
                .addValue("open", openPostings);
        return jdbc.update("UPDATE feed_source SET lease_until = NULL, next_poll_at = :next, last_polled_at = :now, "
                + "last_success_at = :now, last_status = :status, last_error = NULL, consecutive_failures = 0, "
                + "etag = :etag, last_modified = :lastModified, content_hash = :hash, "
                + "baseline_at = COALESCE(baseline_at, :now), suspicious_since = :suspiciousSince, "
                + "open_postings = :open WHERE id = :id AND lease_until = :lease", params) == 1;
    }

    /**
     * The state after a 304 or an identical body (counts as a success), guarded by the lease.
     *
     * @param etag the provider's current ETag; null keeps the stored one
     */
    public boolean recordNotModified(UUID id, Instant lease, Instant now, Instant nextPollAt, String etag) {
        MapSqlParameterSource params = leaseParams(id, lease)
                .addValue("now", ts(now), Types.TIMESTAMP)
                .addValue("next", ts(nextPollAt), Types.TIMESTAMP)
                .addValue("etag", fits(etag, 300), Types.VARCHAR);
        return jdbc.update("UPDATE feed_source SET lease_until = NULL, next_poll_at = :next, last_polled_at = :now, "
                + "last_success_at = :now, last_status = 'NOT_MODIFIED', last_error = NULL, consecutive_failures = 0, "
                + "etag = COALESCE(:etag, etag) WHERE id = :id AND lease_until = :lease", params) == 1;
    }

    /**
     * The state after a failed poll, guarded by the lease. One autocommit statement (it runs after a
     * rolled-back write transaction, too).
     *
     * @param error          sanitized: no body, no URL query, no key (cut to 300 characters)
     * @param countAsFailure false for BUDGET_EXHAUSTED (not a failure, §6.1)
     */
    public boolean recordFailure(UUID id, Instant lease, Instant now, Instant nextPollAt, String status, String error,
                                 boolean countAsFailure) {
        MapSqlParameterSource params = leaseParams(id, lease)
                .addValue("now", ts(now), Types.TIMESTAMP)
                .addValue("next", ts(nextPollAt), Types.TIMESTAMP)
                .addValue("status", status)
                .addValue("error", cut(error, 300), Types.VARCHAR)
                .addValue("inc", countAsFailure ? 1 : 0);
        return jdbc.update("UPDATE feed_source SET lease_until = NULL, next_poll_at = :next, last_polled_at = :now, "
                + "last_status = :status, last_error = :error, consecutive_failures = consecutive_failures + :inc "
                + "WHERE id = :id AND lease_until = :lease", params) == 1;
    }

    // ------------------------------------------------------------------ status and health

    /** Counts for {@code GET /api/feed/status}. @param failing ACTIVE sources whose last poll failed */
    public record SourceCounts(long total, long active, long failing, Instant lastSuccessAt) {
    }

    public SourceCounts counts() {
        return jdbc.queryForObject("SELECT count(*) AS total, count(*) FILTER (WHERE state = 'ACTIVE') AS active, "
                        + "count(*) FILTER (WHERE state = 'ACTIVE' AND consecutive_failures > 0) AS failing, "
                        + "max(last_success_at) AS last_success FROM feed_source", new MapSqlParameterSource(),
                (rs, n) -> new SourceCounts(rs.getLong("total"), rs.getLong("active"), rs.getLong("failing"),
                        instant(rs, "last_success")));
    }

    /** Every ACTIVE source (health check; a personal watchlist is small). */
    public List<FeedSource> findActive() {
        return jdbc.query("SELECT " + COLUMNS + " FROM feed_source WHERE state = 'ACTIVE' ORDER BY created_at, id",
                new MapSqlParameterSource(), ROW);
    }

    private static MapSqlParameterSource leaseParams(UUID id, Instant lease) {
        return new MapSqlParameterSource("id", id).addValue("lease", ts(lease), Types.TIMESTAMP);
    }

    private static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    /** Null when the value doesn't fit (an ETag that can't be stored is simply not used). */
    private static String fits(String value, int max) {
        return value == null || value.length() > max ? null : value;
    }

    private static String cut(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
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
