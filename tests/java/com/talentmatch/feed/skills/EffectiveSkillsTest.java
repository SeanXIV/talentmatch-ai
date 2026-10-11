package com.talentmatch.feed.skills;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import com.talentmatch.feed.skills.EffectiveSkills.EffectiveSkill;
import com.talentmatch.feed.skills.EffectiveSkills.Source;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Spec §9.1 item 9, §4.7: union, AI required wins, deterministic order. */
class EffectiveSkillsTest {

    private static final UUID JAVA = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID SQL = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID DOCKER = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID GO_A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID GO_B = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    private static SkillRequirement r(UUID id, String name, boolean required) {
        return new SkillRequirement(id, name, required);
    }

    @Test
    void notEnrichedGivesTheDictionarySkills() {
        List<EffectiveSkill> out = EffectiveSkills.merge(List.of(r(SQL, "SQL", true), r(JAVA, "Java", false)), null);
        assertThat(out).extracting(EffectiveSkill::name, EffectiveSkill::required, EffectiveSkill::source)
                .containsExactly(tuple("Java", false, Source.DICTIONARY), tuple("SQL", true, Source.DICTIONARY));
    }

    @Test
    void unionWithAiRequiredWinning() {
        List<EffectiveSkill> out = EffectiveSkills.merge(
                List.of(r(JAVA, "Java", true), r(SQL, "SQL", false)),
                List.of(r(SQL, "SQL", true), r(JAVA, "Java", false), r(DOCKER, "Docker", false)));
        assertThat(out).extracting(EffectiveSkill::name, EffectiveSkill::required, EffectiveSkill::source)
                .containsExactly(tuple("Docker", false, Source.AI),
                        tuple("Java", false, Source.BOTH),
                        tuple("SQL", true, Source.BOTH));
    }

    @Test
    void orderIsByNameIgnoringCaseThenId() {
        List<EffectiveSkill> out = EffectiveSkills.merge(
                List.of(r(GO_B, "go", true), r(SQL, "sql", true), r(GO_A, "Go", true), r(DOCKER, "docker", true)),
                List.of());
        assertThat(out).extracting(EffectiveSkill::skillId).containsExactly(DOCKER, GO_A, GO_B, SQL);
    }

    @Test
    void mergeIsDeterministic() {
        List<SkillRequirement> d1 = List.of(r(JAVA, "Java", true), r(SQL, "SQL", false));
        List<SkillRequirement> d2 = List.of(r(SQL, "SQL", false), r(JAVA, "Java", true));
        List<SkillRequirement> ai = List.of(r(DOCKER, "Docker", true));
        assertThat(EffectiveSkills.merge(d1, ai)).isEqualTo(EffectiveSkills.merge(d2, ai));
        assertThat(EffectiveSkills.requiredBySkill(EffectiveSkills.merge(d1, ai)))
                .containsExactly(java.util.Map.entry(DOCKER, true), java.util.Map.entry(JAVA, true),
                        java.util.Map.entry(SQL, false));
    }

    @Test
    void duplicatesCollapseToRequiredIfAnySaysSo() {
        List<EffectiveSkill> out = EffectiveSkills.merge(
                List.of(r(JAVA, "Java", false), r(JAVA, "Java", true)),
                List.of(r(SQL, "SQL", false), r(SQL, "SQL", true)));
        assertThat(out).extracting(EffectiveSkill::name, EffectiveSkill::required)
                .containsExactly(tuple("Java", true), tuple("SQL", true));
    }

    @Test
    void nullsAndEmptyListsAreSafe() {
        assertThat(EffectiveSkills.merge(null, null)).isEmpty();
        assertThat(EffectiveSkills.merge(List.of(), List.of())).isEmpty();
        assertThat(EffectiveSkills.merge(Arrays.asList(null, r(null, "X", true), r(JAVA, "Java", true)), null))
                .extracting(EffectiveSkill::skillId).containsExactly(JAVA);
        assertThat(EffectiveSkills.requiredBySkill(List.of())).isEmpty();
    }

    @Test
    void resultIsUnmodifiable() {
        List<EffectiveSkill> out = EffectiveSkills.merge(List.of(r(JAVA, "Java", true)), null);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> out.add(null))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
