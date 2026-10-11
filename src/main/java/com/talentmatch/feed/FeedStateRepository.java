package com.talentmatch.feed;

import java.sql.Types;
import java.time.Duration;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JDBC access to the single-row {@code feed_state} table (V5): the profile and preferences versions
 * and the skill-vocabulary fingerprint the open feed jobs were last marked for (§4.11). No row means
 * nothing was applied yet. Each setter upserts one column and leaves the others alone.
 */
@Repository
public class FeedStateRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public FeedStateRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** What was applied; every field is null until first set. */
    public record State(Integer appliedProfileVersion, Integer appliedPreferencesVersion, String vocabFingerprint) {

        public static final State EMPTY = new State(null, null, null);
    }

    public State find() {
        return jdbc.query("SELECT applied_profile_version, applied_preferences_version, skill_vocab_fingerprint "
                        + "FROM feed_state WHERE id", new MapSqlParameterSource(),
                (rs, n) -> new State((Integer) rs.getObject("applied_profile_version"),
                        (Integer) rs.getObject("applied_preferences_version"),
                        rs.getString("skill_vocab_fingerprint")))
                .stream().findFirst().orElse(State.EMPTY);
    }

    /**
     * Bounds every later lock wait of the current transaction to {@code timeout}
     * ({@code SET LOCAL lock_timeout}; reset at commit or rollback). A wait that runs out fails with
     * SQLState 55P03 and rolls the transaction back. Must run inside a transaction: outside one it has
     * no effect.
     */
    public void limitLockWait(Duration timeout) {
        jdbc.queryForObject("SELECT set_config('lock_timeout', :value, true)",
                new MapSqlParameterSource("value", Math.max(1, timeout.toMillis()) + "ms"), String.class);
    }

    /**
     * Locks the row for a version comparison (creating it first if missing), so two reconciliations
     * don't both mark the open jobs for one change. Must run inside a transaction.
     */
    public State lock() {
        jdbc.update("INSERT INTO feed_state (id) VALUES (true) ON CONFLICT (id) DO NOTHING", new MapSqlParameterSource());
        return jdbc.query("SELECT applied_profile_version, applied_preferences_version, skill_vocab_fingerprint "
                        + "FROM feed_state WHERE id FOR UPDATE", new MapSqlParameterSource(),
                (rs, n) -> new State((Integer) rs.getObject("applied_profile_version"),
                        (Integer) rs.getObject("applied_preferences_version"),
                        rs.getString("skill_vocab_fingerprint")))
                .stream().findFirst().orElse(State.EMPTY);
    }

    public void setProfileVersion(Integer version) {
        upsert("applied_profile_version", version, Types.INTEGER);
    }

    public void setPreferencesVersion(Integer version) {
        upsert("applied_preferences_version", version, Types.INTEGER);
    }

    public void setVocabFingerprint(String fingerprint) {
        upsert("skill_vocab_fingerprint", fingerprint, Types.VARCHAR);
    }

    /** {@code column} is one of the three constants above (never user input). */
    private void upsert(String column, Object value, int sqlType) {
        jdbc.update("INSERT INTO feed_state (id, " + column + ") VALUES (true, :value) "
                        + "ON CONFLICT (id) DO UPDATE SET " + column + " = EXCLUDED." + column,
                new MapSqlParameterSource().addValue("value", value, sqlType));
    }
}
