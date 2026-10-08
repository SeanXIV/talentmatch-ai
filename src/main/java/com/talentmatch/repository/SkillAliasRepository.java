package com.talentmatch.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JDBC access to {@code skill_alias} (V5): other names for a skill ("Postgres" → PostgreSQL).
 * Aliases are unique case-insensitively (uq_skill_alias_lower) and never equal a skill name; that
 * cross-table rule is checked under {@link #lockVocabulary()}.
 */
@Repository
public class SkillAliasRepository {

    private static final RowMapper<AliasRow> ROW = (rs, n) -> new AliasRow(
            rs.getObject("id", UUID.class),
            rs.getObject("skill_id", UUID.class),
            rs.getString("alias"),
            instant(rs, "created_at"));

    private final NamedParameterJdbcTemplate jdbc;

    public SkillAliasRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record AliasRow(UUID id, UUID skillId, String alias, Instant createdAt) {
    }

    /**
     * Serializes changes to the skill vocabulary (skill names and aliases) until the transaction
     * ends, so "an alias never equals a skill name" holds across the two tables. Call first.
     */
    public void lockVocabulary() {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtext('talentmatch.skill_vocab'))",
                new MapSqlParameterSource(), rs -> null);
    }

    public List<AliasRow> findBySkill(UUID skillId) {
        return jdbc.query("SELECT id, skill_id, alias, created_at FROM skill_alias WHERE skill_id = :skillId "
                + "ORDER BY lower(alias), id", new MapSqlParameterSource("skillId", skillId), ROW);
    }

    /** Case-insensitive lookup (same expression as uq_skill_alias_lower). */
    public Optional<AliasRow> findByAliasIgnoringCase(String alias) {
        return jdbc.query("SELECT id, skill_id, alias, created_at FROM skill_alias WHERE lower(alias) = lower(:alias)",
                new MapSqlParameterSource("alias", alias), ROW).stream().findFirst();
    }

    /** Skill id per alias key, for aliases whose lower(alias) is in the given (already lowercased) keys. */
    public Map<String, UUID> findSkillIdsByLowerAliases(Collection<String> keys) {
        Map<String, UUID> out = new HashMap<>();
        if (keys.isEmpty()) {
            return out;
        }
        jdbc.query("SELECT lower(alias) AS k, skill_id FROM skill_alias WHERE lower(alias) IN (:keys)",
                new MapSqlParameterSource("keys", keys),
                rs -> {
                    out.put(rs.getString("k"), rs.getObject("skill_id", UUID.class));
                });
        return out;
    }

    public List<AliasRow> findAll() {
        return jdbc.query("SELECT id, skill_id, alias, created_at FROM skill_alias ORDER BY lower(alias), id",
                new MapSqlParameterSource(), ROW);
    }

    public AliasRow insert(UUID skillId, String alias) {
        return jdbc.queryForObject("INSERT INTO skill_alias (skill_id, alias) VALUES (:skillId, :alias) "
                        + "RETURNING id, skill_id, alias, created_at",
                new MapSqlParameterSource("skillId", skillId).addValue("alias", alias), ROW);
    }

    /** @return rows deleted (0 when the alias doesn't exist or belongs to another skill) */
    public int delete(UUID skillId, UUID aliasId) {
        return jdbc.update("DELETE FROM skill_alias WHERE id = :id AND skill_id = :skillId",
                new MapSqlParameterSource("id", aliasId).addValue("skillId", skillId));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }
}
