package com.talentmatch.feed;

import static org.assertj.core.api.Assertions.assertThat;

import com.talentmatch.feed.skills.DictionarySkillMatcher;
import com.talentmatch.feed.skills.DictionarySkillMatcher.Term;
import com.talentmatch.feed.skills.EffectiveSkills;
import com.talentmatch.feed.skills.EffectiveSkills.EffectiveSkill;
import com.talentmatch.feed.skills.SkillRequirement;
import com.talentmatch.preferences.FeedFacts;
import com.talentmatch.preferences.JobPreferences;
import com.talentmatch.preferences.PreferenceFilter;
import com.talentmatch.preferences.Workplace;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** §1.2 steps 1–3, §4.6: dictionary + AI merge, current names, matchability, determinism. */
class FeedEvaluationTest {

    private static final UUID JAVA = UUID.nameUUIDFromBytes("Java".getBytes());
    private static final UUID SQL = UUID.nameUUIDFromBytes("SQL".getBytes());
    private static final UUID K8S = UUID.nameUUIDFromBytes("Kubernetes".getBytes());
    private static final UUID DOCKER = UUID.nameUUIDFromBytes("Docker".getBytes());
    private static final UUID DELETED = UUID.nameUUIDFromBytes("Deleted".getBytes());

    private static final String TITLE = "Backend Engineer";
    private static final String DESC = "You will write Java and SQL every day. Kubernetes is advantageous.";

    private static Map<UUID, String> names() {
        Map<UUID, String> m = new LinkedHashMap<>();
        m.put(JAVA, "Java");
        m.put(SQL, "SQL");
        m.put(K8S, "Kubernetes");
        m.put(DOCKER, "Docker");
        return m;
    }

    private static DictionarySkillMatcher matcher(Map<UUID, String> names) {
        List<Term> terms = new ArrayList<>();
        names.forEach((id, n) -> terms.add(new Term(id, n, n)));
        return new DictionarySkillMatcher(terms, DictionarySkillMatcher.DEFAULT_AMBIGUOUS_NAMES);
    }

    private static FeedFacts facts(String title) {
        return new FeedFacts(title, DESC, List.of("ZA"), Workplace.ONSITE, "Cape Town", null, null, null, null, false);
    }

    private static FeedEvaluation.Result eval(String description, List<SkillRequirement> ai, Map<UUID, String> names) {
        return FeedEvaluation.evaluate(TITLE, description, ai, matcher(names), names, facts(TITLE), null);
    }

    private static Map<String, String> summary(List<EffectiveSkill> skills) {
        Map<String, String> m = new LinkedHashMap<>();
        skills.forEach(s -> m.put(s.name(), (s.required() ? "req" : "nice") + "/" + s.source()));
        return m;
    }

    @Test
    void dictionaryOnly() {
        FeedEvaluation.Result r = eval(DESC, null, names());
        assertThat(summary(r.skills())).containsExactly(
                Map.entry("Java", "req/DICTIONARY"),
                Map.entry("Kubernetes", "nice/DICTIONARY"),
                Map.entry("SQL", "req/DICTIONARY"));
        assertThat(r.dictionarySkills()).extracting(SkillRequirement::name)
                .containsExactlyInAnyOrder("Java", "SQL", "Kubernetes");
        assertThat(r.matchable()).isTrue();
        assertThat(r.jobSkills()).containsEntry(JAVA, true).containsEntry(SQL, true).containsEntry(K8S, false)
                .hasSize(3);
        assertThat(r.verdict().pass()).as("no preferences = no filters").isTrue();
    }

    @Test
    void dictionaryAndAiMerge() {
        List<SkillRequirement> ai = List.of(
                new SkillRequirement(K8S, "Kubernetes", true),        // AI overrides the heuristic
                new SkillRequirement(DOCKER, "Docker", false));       // AI only
        FeedEvaluation.Result r = eval(DESC, ai, names());
        assertThat(summary(r.skills())).containsExactly(
                Map.entry("Docker", "nice/AI"),
                Map.entry("Java", "req/DICTIONARY"),
                Map.entry("Kubernetes", "req/BOTH"),
                Map.entry("SQL", "req/DICTIONARY"));
        assertThat(r.dictionarySkills()).as("dictionary_skills holds only the dictionary stage")
                .extracting(SkillRequirement::skillId).doesNotContain(DOCKER);
    }

    @Test
    void aiSkillsOfDeletedSkillsAreDropped() {
        List<SkillRequirement> ai = new ArrayList<>();
        ai.add(new SkillRequirement(DELETED, "Deleted", true));
        ai.add(null);
        ai.add(new SkillRequirement(null, "NoId", true));
        ai.add(new SkillRequirement(DOCKER, "Docker", true));
        FeedEvaluation.Result r = eval(DESC, ai, names());
        assertThat(r.skills()).extracting(EffectiveSkill::skillId).doesNotContain(DELETED).contains(DOCKER);
        assertThat(r.jobSkills()).doesNotContainKey(DELETED).hasSize(4);
    }

    @Test
    void renamedSkillsShowTheCurrentName() {
        Map<UUID, String> renamed = names();
        renamed.put(DOCKER, "Docker Engine");
        FeedEvaluation.Result r = eval("Plain text with no skills.", List.of(new SkillRequirement(DOCKER, "Docker", true)),
                renamed);
        assertThat(r.skills()).singleElement().satisfies(s -> {
            assertThat(s.name()).isEqualTo("Docker Engine");
            assertThat(s.source()).isEqualTo(EffectiveSkills.Source.AI);
        });
    }

    @Test
    void noSkillsIsNotMatchable() {
        FeedEvaluation.Result r = FeedEvaluation.evaluate("Office Manager", "Run the office.", null,
                matcher(names()), names(), facts("Office Manager"), null);
        assertThat(r.skills()).isEmpty();
        assertThat(r.dictionarySkills()).isEmpty();
        assertThat(r.jobSkills()).isEmpty();
        assertThat(r.matchable()).isFalse();

        FeedEvaluation.Result emptyAi = FeedEvaluation.evaluate("Office Manager", null, List.of(), matcher(names()),
                names(), facts("Office Manager"), null);
        assertThat(emptyAi.matchable()).isFalse();
    }

    @Test
    void evaluatingTwiceGivesTheSameOutput() {
        List<SkillRequirement> ai = List.of(new SkillRequirement(DOCKER, "Docker", false),
                new SkillRequirement(K8S, "Kubernetes", true));
        JobPreferences prefs = new JobPreferences(List.of("Data Engineer"), List.of(), null, null, null, null, null);
        FeedEvaluation.Result a = FeedEvaluation.evaluate(TITLE, DESC, ai, matcher(names()), names(), facts(TITLE),
                prefs);
        FeedEvaluation.Result b = FeedEvaluation.evaluate(TITLE, DESC, ai, matcher(names()), names(), facts(TITLE),
                prefs);
        assertThat(b).isEqualTo(a);
        assertThat(List.copyOf(b.jobSkills().entrySet())).isEqualTo(List.copyOf(a.jobSkills().entrySet()));
        assertThat(FeedJobJson.write(b.dictionarySkills())).isEqualTo(FeedJobJson.write(a.dictionarySkills()));
        assertThat(a.verdict().pass()).isFalse();
        assertThat(a.verdict().reasons()).containsExactly(PreferenceFilter.Reason.TITLE);
    }
}
