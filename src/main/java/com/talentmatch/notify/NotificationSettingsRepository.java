package com.talentmatch.notify;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JDBC access to the single-row {@code notification_settings} table (V5). No row means the defaults:
 * notifications disabled, channel EMAIL, min score 0.6, at most 20 an hour (§3.4). Step 7 only
 * reads; the settings endpoint (step 8) writes.
 */
@Repository
public class NotificationSettingsRepository {

    public static final double DEFAULT_MIN_SCORE = 0.6;
    public static final int DEFAULT_MAX_PER_HOUR = 20;

    private final NamedParameterJdbcTemplate jdbc;

    public NotificationSettingsRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param enabled    the owner turned notifications on
     * @param channel    the channel to notify on
     * @param minScore   a job must score at least this much (0..1)
     * @param maxPerHour hourly cap of sent notifications
     */
    public record Settings(boolean enabled, Channel channel, double minScore, int maxPerHour) {

        public static Settings defaults() {
            return new Settings(false, Channel.EMAIL, DEFAULT_MIN_SCORE, DEFAULT_MAX_PER_HOUR);
        }
    }

    /** The stored settings, or the defaults when none were saved. */
    public Settings find() {
        return jdbc.query("SELECT enabled, channel, min_score, max_per_hour FROM notification_settings WHERE id",
                new MapSqlParameterSource(), (rs, n) -> new Settings(rs.getBoolean("enabled"),
                        Channel.valueOf(rs.getString("channel").strip()), rs.getDouble("min_score"),
                        rs.getInt("max_per_hour"))).stream().findFirst().orElseGet(Settings::defaults);
    }
}
