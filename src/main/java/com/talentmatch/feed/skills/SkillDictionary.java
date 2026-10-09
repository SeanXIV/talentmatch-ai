package com.talentmatch.feed.skills;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * A cached snapshot of the skill vocabulary (skill names plus {@code skill_alias}) with its
 * compiled {@link DictionarySkillMatcher}. The snapshot is rebuilt when the vocabulary
 * fingerprint changes; {@link #refresh()} checks it (the feed processor calls it once per sweep),
 * {@link #snapshot()} only loads the first one.
 *
 * <p>The ambiguous-name list comes from {@code talentmatch.feed.skills.ambiguous-names}
 * (default {@link DictionarySkillMatcher#DEFAULT_AMBIGUOUS_NAMES}).
 */
@Component
public class SkillDictionary {

    static final String AMBIGUOUS_NAMES_PROPERTY = "talentmatch.feed.skills.ambiguous-names";

    /** count(skill) | max(skill.updated_at) | count(skill_alias) | max(skill_alias.created_at), hashed. */
    private static final String FINGERPRINT_SQL = "SELECT md5("
            + "(SELECT count(*) FROM skill)::text || '|' || "
            + "coalesce((SELECT max(updated_at) FROM skill)::text, '') || '|' || "
            + "(SELECT count(*) FROM skill_alias)::text || '|' || "
            + "coalesce((SELECT max(created_at) FROM skill_alias)::text, ''))";

    private final NamedParameterJdbcTemplate jdbc;
    private final List<String> ambiguousNames;
    private volatile Snapshot current;

    public SkillDictionary(NamedParameterJdbcTemplate jdbc, Environment environment) {
        this.jdbc = jdbc;
        this.ambiguousNames = List.copyOf(Binder.get(environment)
                .bind(AMBIGUOUS_NAMES_PROPERTY, Bindable.listOf(String.class))
                .orElse(DictionarySkillMatcher.DEFAULT_AMBIGUOUS_NAMES));
    }

    /**
     * One version of the vocabulary.
     *
     * @param fingerprint vocabulary fingerprint this snapshot was built from (stored in feed_state)
     * @param names       skill id → skill name
     * @param byKey       lower-cased skill name or alias → skill id (a skill name wins over an alias)
     * @param matcher     compiled matcher over every name and alias
     */
    public record Snapshot(String fingerprint, Map<UUID, String> names, Map<String, UUID> byKey,
                           DictionarySkillMatcher matcher) {

        /** The skill a name or alias refers to (case-insensitive). */
        public Optional<UUID> resolve(String nameOrAlias) {
            if (nameOrAlias == null) {
                return Optional.empty();
            }
            return Optional.ofNullable(byKey.get(nameOrAlias.strip().toLowerCase(Locale.ROOT)));
        }

        public Optional<String> name(UUID skillId) {
            return Optional.ofNullable(names.get(skillId));
        }
    }

    /** The current fingerprint of the vocabulary in the database. */
    public String fingerprint() {
        return jdbc.queryForObject(FINGERPRINT_SQL, new MapSqlParameterSource(), String.class);
    }

    /** The cached snapshot (loaded on first use; not re-checked). */
    public Snapshot snapshot() {
        Snapshot s = current;
        return s != null ? s : refresh();
    }

    /** Checks the fingerprint and rebuilds the snapshot when the vocabulary changed. */
    public synchronized Snapshot refresh() {
        String fingerprint = fingerprint();             // read before the data: a later change is seen next time
        Snapshot s = current;
        if (s != null && s.fingerprint().equals(fingerprint)) {
            return s;
        }
        s = load(fingerprint);
        current = s;
        return s;
    }

    private Snapshot load(String fingerprint) {
        Map<UUID, String> names = new HashMap<>();
        jdbc.query("SELECT id, name FROM skill", new MapSqlParameterSource(),
                rs -> {
                    names.put(rs.getObject("id", UUID.class), rs.getString("name"));
                });
        List<DictionarySkillMatcher.Term> terms = new ArrayList<>();
        Map<String, UUID> byKey = new HashMap<>();
        jdbc.query("SELECT skill_id, alias FROM skill_alias", new MapSqlParameterSource(),
                rs -> {
                    UUID skillId = rs.getObject("skill_id", UUID.class);
                    String alias = rs.getString("alias");
                    String skillName = names.get(skillId);
                    if (skillName != null) {                // skill created after the name query: next refresh
                        terms.add(new DictionarySkillMatcher.Term(skillId, skillName, alias));
                        byKey.put(alias.toLowerCase(Locale.ROOT), skillId);
                    }
                });
        names.forEach((id, name) -> {
            terms.add(new DictionarySkillMatcher.Term(id, name, name));
            byKey.put(name.toLowerCase(Locale.ROOT), id);   // a skill name wins over an alias
        });
        return new Snapshot(fingerprint, Map.copyOf(names), Map.copyOf(byKey),
                new DictionarySkillMatcher(terms, ambiguousNames));
    }
}
