package com.talentmatch.feed;

import com.talentmatch.feed.source.SourceKind;
import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Per-provider daily request counter ({@code feed_api_usage}, V5; §4.3). One atomic statement per
 * request, so concurrent polls and probes never overspend the budget. Days are UTC days of the
 * application clock.
 */
@Repository
public class FeedApiUsage {

    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    public FeedApiUsage(NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * Counts one request against today's budget, if any is left. Autocommit (or the caller's
     * transaction): the row is locked only for the statement.
     *
     * @return true when the request may be made; false when the budget for today is spent
     */
    public boolean tryConsume(SourceKind provider, int dailyBudget) {
        MapSqlParameterSource params = new MapSqlParameterSource("provider", provider.name())
                .addValue("day", Date.valueOf(today()))
                .addValue("budget", dailyBudget);
        return !jdbc.queryForList("INSERT INTO feed_api_usage (provider, day, requests) "
                + "SELECT :provider, :day, 1 WHERE :budget >= 1 "
                + "ON CONFLICT (provider, day) DO UPDATE SET requests = feed_api_usage.requests + 1 "
                + "WHERE feed_api_usage.requests < :budget RETURNING requests", params, Integer.class).isEmpty();
    }

    /** Requests counted today (0 when none). */
    public int requestsToday(SourceKind provider) {
        Integer n = jdbc.query("SELECT requests FROM feed_api_usage WHERE provider = :provider AND day = :day",
                new MapSqlParameterSource("provider", provider.name()).addValue("day", Date.valueOf(today())),
                rs -> rs.next() ? rs.getInt(1) : 0);
        return n == null ? 0 : n;
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
