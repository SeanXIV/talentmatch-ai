package com.talentmatch.notify;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JDBC access to {@code feed_notification} (V5): at most one row per job, ever (decision g). Step 7
 * queues rows (PENDING); the dispatcher (step 8) claims, sends and finishes them.
 */
@Repository
public class NotificationRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public NotificationRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

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
}
