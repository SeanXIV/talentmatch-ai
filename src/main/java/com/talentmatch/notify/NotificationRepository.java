package com.talentmatch.notify;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JDBC access to {@code feed_notification} (V5): at most one row per job, ever (decision g). The feed
 * processor queues rows (PENDING); {@link NotificationDispatcher} claims, sends and finishes them.
 *
 * <p><b>Locking.</b> Every statement is short. The batch statements ({@link #claimNext},
 * {@link #expirePending}) use {@code FOR UPDATE SKIP LOCKED} and never wait for a row lock. The finish
 * statements ({@link #markSent}, {@link #retry}, {@link #fail}, {@link #release}) and
 * {@link #resetSending} touch rows only this dispatcher holds in SENDING; callers run them in a
 * transaction bounded by {@link #limitLockWait}. Every finish keeps the {@code status = 'SENDING'} guard,
 * so applying one twice is harmless. No statement changes {@code job_id}, so none takes a key-share
 * lock on {@code feed_job}.
 */
@Repository
public class NotificationRepository {

    /** {@code last_error} is a varchar(300). */
    public static final int MAX_ERROR = 300;
    public static final String EXPIRED = "expired";

    private final NamedParameterJdbcTemplate jdbc;

    public NotificationRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** A row claimed for sending (now SENDING). */
    public record Claimed(UUID id, UUID jobId, Channel channel, double score, int attempts, Instant createdAt) {
    }

    /** One history entry, with the job's title and company. */
    public record Row(UUID id, UUID jobId, String title, String company, Channel channel, String status, double score,
                      int attempts, String lastError, Instant createdAt, Instant sentAt) {
    }

    // ------------------------------------------------------------------ queueing (feed processor)

    /**
     * Queues a notification for the job unless one exists already (whatever its status).
     *
     * @return true when a row was inserted now
     */
    public boolean insertIfAbsent(UUID jobId, Channel channel, double score, Instant now) {
        Timestamp at = Timestamp.from(now);
        return jdbc.update("INSERT INTO feed_notification (job_id, channel, score, next_attempt_at, created_at) "
                        + "VALUES (:jobId, :channel, :score, :now, :now) ON CONFLICT (job_id) DO NOTHING",
                new MapSqlParameterSource("jobId", jobId)
                        .addValue("channel", channel.name())
                        .addValue("score", Math.max(0.0, Math.min(1.0, score)))
                        .addValue("now", at, Types.TIMESTAMP)) == 1;
    }

    public boolean existsForJob(UUID jobId) {
        Boolean exists = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM feed_notification WHERE job_id = :jobId)",
                new MapSqlParameterSource("jobId", jobId), Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    // ------------------------------------------------------------------ dispatcher

    /**
     * Bounds every later lock wait of the current transaction ({@code SET LOCAL lock_timeout}; reset at
     * commit or rollback). A wait that runs out fails with SQLState 55P03. Must run inside a transaction.
     */
    public void limitLockWait(Duration timeout) {
        jdbc.queryForObject("SELECT set_config('lock_timeout', :value, true)",
                new MapSqlParameterSource("value", Math.max(1, timeout.toMillis()) + "ms"), String.class);
    }

    /**
     * Claims the oldest due PENDING row (→ SENDING). Autocommit; skips rows locked elsewhere.
     */
    public Optional<Claimed> claimNext(Instant now) {
        return jdbc.query("UPDATE feed_notification n SET status = 'SENDING' "
                        + "WHERE n.id = (SELECT id FROM feed_notification WHERE status = 'PENDING' "
                        + "AND next_attempt_at <= :now ORDER BY created_at, id LIMIT 1 FOR UPDATE SKIP LOCKED) "
                        + "AND n.status = 'PENDING' "
                        + "RETURNING n.id, n.job_id, n.channel, n.score, n.attempts, n.created_at",
                new MapSqlParameterSource("now", ts(now)),
                (rs, i) -> new Claimed(rs.getObject("id", UUID.class), rs.getObject("job_id", UUID.class),
                        Channel.valueOf(rs.getString("channel").strip()), rs.getDouble("score"), rs.getInt("attempts"),
                        instant(rs, "created_at")))
                .stream().findFirst();
    }

    /** SENDING → SENT. */
    public boolean markSent(UUID id, int attempts, Instant sentAt) {
        return jdbc.update("UPDATE feed_notification SET status = 'SENT', sent_at = :sentAt, attempts = :attempts, "
                        + "last_error = NULL WHERE id = :id AND status = 'SENDING'",
                new MapSqlParameterSource("id", id).addValue("attempts", attempts).addValue("sentAt", ts(sentAt))) == 1;
    }

    /** SENDING → PENDING after a transient failure, counted, due again at {@code nextAttemptAt}. */
    public boolean retry(UUID id, int attempts, Instant nextAttemptAt, String error) {
        return jdbc.update("UPDATE feed_notification SET status = 'PENDING', attempts = :attempts, "
                        + "next_attempt_at = :next, last_error = :error WHERE id = :id AND status = 'SENDING'",
                new MapSqlParameterSource("id", id).addValue("attempts", attempts)
                        .addValue("next", ts(nextAttemptAt)).addValue("error", truncate(error))) == 1;
    }

    /** SENDING → FAILED (final). */
    public boolean fail(UUID id, int attempts, String error) {
        return jdbc.update("UPDATE feed_notification SET status = 'FAILED', attempts = :attempts, last_error = :error "
                        + "WHERE id = :id AND status = 'SENDING'",
                new MapSqlParameterSource("id", id).addValue("attempts", attempts)
                        .addValue("error", truncate(error))) == 1;
    }

    /** SENDING → PENDING without counting an attempt (configuration error), due again at {@code nextAttemptAt}. */
    public boolean release(UUID id, Instant nextAttemptAt, String error) {
        return jdbc.update("UPDATE feed_notification SET status = 'PENDING', next_attempt_at = :next, "
                        + "last_error = :error WHERE id = :id AND status = 'SENDING'",
                new MapSqlParameterSource("id", id).addValue("next", ts(nextAttemptAt))
                        .addValue("error", truncate(error))) == 1;
    }

    /**
     * PENDING rows queued before {@code now − maxAge} → FAILED "expired" (no SMTP). Autocommit; skips
     * rows locked elsewhere.
     *
     * @return the number of rows expired
     */
    public int expirePending(Instant now, Duration maxAge) {
        return jdbc.update("UPDATE feed_notification SET status = 'FAILED', last_error = '" + EXPIRED + "' "
                        + "WHERE status = 'PENDING' AND id IN (SELECT id FROM feed_notification "
                        + "WHERE status = 'PENDING' AND created_at < :cutoff FOR UPDATE SKIP LOCKED)",
                new MapSqlParameterSource("cutoff", ts(now.minus(maxAge))));
    }

    /**
     * SENDING → PENDING after a restart (at least once: the send may have gone out). Rows in
     * {@code exclude} are skipped: their result is known and still being written.
     */
    public int resetSending(Collection<UUID> exclude) {
        if (exclude == null || exclude.isEmpty()) {
            return jdbc.update("UPDATE feed_notification SET status = 'PENDING' WHERE status = 'SENDING'",
                    new MapSqlParameterSource());
        }
        return jdbc.update("UPDATE feed_notification SET status = 'PENDING' WHERE status = 'SENDING' "
                + "AND id NOT IN (:exclude)", new MapSqlParameterSource("exclude", List.copyOf(exclude)));
    }

    // ------------------------------------------------------------------ counts (cap, status, health)

    /** SENT rows with {@code sent_at >= since} (the hourly cap; app clock). */
    public long countSentSince(Instant since) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM feed_notification WHERE status = 'SENT' AND sent_at >= :since",
                new MapSqlParameterSource("since", ts(since)), Long.class);
        return n == null ? 0 : n;
    }

    /** The oldest {@code sent_at >= since}, if any. */
    public Optional<Instant> oldestSentSince(Instant since) {
        Timestamp t = jdbc.queryForObject("SELECT min(sent_at) FROM feed_notification "
                        + "WHERE status = 'SENT' AND sent_at >= :since",
                new MapSqlParameterSource("since", ts(since)), Timestamp.class);
        return Optional.ofNullable(t).map(Timestamp::toInstant);
    }

    /** PENDING + SENDING. */
    public long countPending() {
        Long n = jdbc.queryForObject("SELECT count(*) FROM feed_notification WHERE status IN ('PENDING','SENDING')",
                new MapSqlParameterSource(), Long.class);
        return n == null ? 0 : n;
    }

    /**
     * FAILED rows whose last change ({@code updated_at}, set by the trigger on the <b>database</b> clock)
     * is within {@code window} of the database's {@code now()} (D7).
     *
     * @param includeExpired false: rows that expired unsent are not counted (health, D8)
     */
    public long countFailedSince(Duration window, boolean includeExpired) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM feed_notification WHERE status = 'FAILED' "
                        + "AND updated_at >= now() - (CAST(:seconds AS double precision) * interval '1 second')"
                        + (includeExpired ? "" : " AND last_error IS DISTINCT FROM '" + EXPIRED + "'"),
                new MapSqlParameterSource("seconds", window.toSeconds()), Long.class);
        return n == null ? 0 : n;
    }

    // ------------------------------------------------------------------ history

    public long count() {
        Long n = jdbc.queryForObject("SELECT count(*) FROM feed_notification", new MapSqlParameterSource(), Long.class);
        return n == null ? 0 : n;
    }

    /** Newest first. */
    public List<Row> findPage(long offset, int limit) {
        return jdbc.query("SELECT n.id, n.job_id, j.title, j.company, n.channel, n.status, n.score, n.attempts, "
                        + "n.last_error, n.created_at, n.sent_at FROM feed_notification n JOIN job j ON j.id = n.job_id "
                        + "ORDER BY n.created_at DESC, n.id LIMIT :limit OFFSET :offset",
                new MapSqlParameterSource("limit", limit).addValue("offset", offset),
                (rs, i) -> new Row(rs.getObject("id", UUID.class), rs.getObject("job_id", UUID.class),
                        rs.getString("title"), rs.getString("company"),
                        Channel.valueOf(rs.getString("channel").strip()), rs.getString("status").strip(),
                        rs.getDouble("score"), rs.getInt("attempts"), rs.getString("last_error"),
                        instant(rs, "created_at"), instant(rs, "sent_at")));
    }

    static String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= MAX_ERROR ? error : error.substring(0, MAX_ERROR);
    }

    /** PgJDBC does not bind Instant; timestamptz takes an OffsetDateTime. */
    private static OffsetDateTime ts(Instant value) {
        return value == null ? null : OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }
}
