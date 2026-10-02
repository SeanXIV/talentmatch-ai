package com.talentmatch.domain.scoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Pure unit tests for the scoring engine (no Spring, no JPA). Spec §8 "Unit". */
class ScoringEngineTest {

    private static final ScoringEngine ENGINE = new ScoringEngine(new ScoringWeights(10, 5));

    private static final UUID JAVA = id(1);
    private static final UUID SQL = id(2);
    private static final UUID DOCKER = id(3);
    private static final UUID KUBERNETES = id(4);
    private static final UUID AWS = id(5);
    private static final UUID GO = id(6);

    private static UUID id(int n) {
        return UUID.fromString(String.format("00000000-0000-0000-0000-%012d", n));
    }

    private static JobRequirement req(UUID id, String name) {
        return new JobRequirement(id, name, true);
    }

    private static JobRequirement nice(UUID id, String name) {
        return new JobRequirement(id, name, false);
    }

    private static CandidateSkillFact has(UUID id) {
        return new CandidateSkillFact(id, null);
    }

    private static CandidateSkillFact has(UUID id, Integer years) {
        return new CandidateSkillFact(id, years);
    }

    private static List<String> names(List<SkillHit> hits) {
        return hits.stream().map(SkillHit::name).toList();
    }

    @Test
    void allRequiredMatchedScoresOne() {
        MatchEvaluation e = ENGINE.evaluate(List.of(req(JAVA, "Java"), req(SQL, "SQL")),
                List.of(has(JAVA), has(SQL)));
        assertThat(e.score()).isEqualTo(1.0);
        assertThat(e.earnedPoints()).isEqualTo(20);
        assertThat(e.maxPoints()).isEqualTo(20);
        assertThat(e.missingRequired()).isEmpty();
        assertThat(e.summary()).isEqualTo("Matches 2 of 2 required skills.");
    }

    @Test
    void twoRequiredOneNiceWithOneRequiredAndNiceMatchedIsPointSix() {
        MatchEvaluation e = ENGINE.evaluate(
                List.of(req(JAVA, "Java"), req(SQL, "SQL"), nice(DOCKER, "Docker")),
                List.of(has(JAVA), has(DOCKER)));
        assertThat(e.earnedPoints()).isEqualTo(15);
        assertThat(e.maxPoints()).isEqualTo(25);
        assertThat(e.score()).isEqualTo(0.6);
        assertThat(names(e.matchedRequired())).containsExactly("Java");
        assertThat(names(e.matchedNiceToHave())).containsExactly("Docker");
        assertThat(names(e.missingRequired())).containsExactly("SQL");
        assertThat(e.missingNiceToHave()).isEmpty();
        assertThat(e.summary()).isEqualTo("Matches 1 of 2 required skills; missing: SQL; 1 of 1 nice-to-have.");
    }

    @Test
    void onlyNiceToHaveOneOfTwoIsHalf() {
        MatchEvaluation e = ENGINE.evaluate(List.of(nice(DOCKER, "Docker"), nice(KUBERNETES, "Kubernetes")),
                List.of(has(DOCKER)));
        assertThat(e.score()).isEqualTo(0.5);
        assertThat(e.summary()).isEqualTo("Matches 1 of 2 nice-to-have skills.");
    }

    @Test
    void candidateWithoutSkillsScoresZeroWithEverythingMissing() {
        List<JobRequirement> reqs = List.of(req(JAVA, "Java"), nice(DOCKER, "Docker"));
        for (List<CandidateSkillFact> none : List.<List<CandidateSkillFact>>of(List.of())) {
            MatchEvaluation e = ENGINE.evaluate(reqs, none);
            assertThat(e.score()).isEqualTo(0.0);
            assertThat(e.earnedPoints()).isZero();
            assertThat(e.maxPoints()).isEqualTo(15);
            assertThat(e.matchedRequired()).isEmpty();
            assertThat(e.matchedNiceToHave()).isEmpty();
            assertThat(names(e.missingRequired())).containsExactly("Java");
            assertThat(names(e.missingNiceToHave())).containsExactly("Docker");
            assertThat(e.summary()).isEqualTo("Matches 0 of 1 required skill; missing: Java; 0 of 1 nice-to-have.");
        }
        // null candidate skills are treated as empty
        assertThat(ENGINE.evaluate(reqs, null).score()).isEqualTo(0.0);
    }

    @Test
    void emptyRequirementsAreRejectedAndNotMatchable() {
        assertThat(ScoringEngine.isMatchable(List.of())).isFalse();
        assertThat(ScoringEngine.isMatchable(null)).isFalse();
        assertThat(ScoringEngine.isMatchable(List.of(req(JAVA, "Java")))).isTrue();
        assertThatThrownBy(() -> ENGINE.evaluate(List.of(), List.of(has(JAVA))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void duplicateRequirementSkillIdIsRejected() {
        assertThatThrownBy(() -> ENGINE.evaluate(List.of(req(JAVA, "Java"), nice(JAVA, "Java")), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void extraCandidateSkillsAreIgnored() {
        List<JobRequirement> reqs = List.of(req(JAVA, "Java"), nice(DOCKER, "Docker"));
        MatchEvaluation plain = ENGINE.evaluate(reqs, List.of(has(JAVA)));
        MatchEvaluation extra = ENGINE.evaluate(reqs, List.of(has(JAVA), has(GO, 9), has(AWS, 2)));
        assertThat(extra).isEqualTo(plain);
        assertThat(extra.score()).isEqualTo(10 / 15.0);
    }

    @Test
    void yearsNeverChangeScoreButAppearOnMatchedHitsAndNullIsKept() {
        List<JobRequirement> reqs = List.of(req(JAVA, "Java"), req(SQL, "SQL"), nice(DOCKER, "Docker"));
        MatchEvaluation none = ENGINE.evaluate(reqs, List.of(has(JAVA), has(DOCKER)));
        MatchEvaluation lots = ENGINE.evaluate(reqs, List.of(has(JAVA, 60), has(DOCKER, 0)));
        assertThat(lots.score()).isEqualTo(none.score());
        assertThat(lots.earnedPoints()).isEqualTo(none.earnedPoints());

        assertThat(lots.matchedRequired()).containsExactly(new SkillHit(JAVA, "Java", 60));
        assertThat(lots.matchedNiceToHave()).containsExactly(new SkillHit(DOCKER, "Docker", 0));
        assertThat(none.matchedRequired()).containsExactly(new SkillHit(JAVA, "Java", null));
        // missing skills never carry years
        assertThat(lots.missingRequired()).containsExactly(new SkillHit(SQL, "SQL", null));
    }

    @Test
    void customWeightsChangeTheScore() {
        ScoringEngine custom = new ScoringEngine(new ScoringWeights(3, 1));
        List<JobRequirement> reqs = List.of(req(JAVA, "Java"), nice(DOCKER, "Docker"));
        MatchEvaluation onlyNice = custom.evaluate(reqs, List.of(has(DOCKER)));
        assertThat(onlyNice.earnedPoints()).isEqualTo(1);
        assertThat(onlyNice.maxPoints()).isEqualTo(4);
        assertThat(onlyNice.score()).isEqualTo(0.25);
        assertThat(ENGINE.evaluate(reqs, List.of(has(DOCKER))).score()).isEqualTo(5 / 15.0);
        assertThat(custom.weights()).isEqualTo(new ScoringWeights(3, 1));
    }

    @Test
    void nonPositiveWeightsAreRejected() {
        assertThatThrownBy(() -> new ScoringWeights(0, 5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScoringWeights(10, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScoringWeights(-1, 5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScoringEngine(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void singularAndPluralNouns() {
        assertThat(ENGINE.evaluate(List.of(req(JAVA, "Java")), List.of()).summary())
                .isEqualTo("Matches 0 of 1 required skill; missing: Java.");
        assertThat(ENGINE.evaluate(List.of(req(JAVA, "Java")), List.of(has(JAVA))).summary())
                .isEqualTo("Matches 1 of 1 required skill.");
        assertThat(ENGINE.evaluate(List.of(nice(DOCKER, "Docker")), List.of(has(DOCKER))).summary())
                .isEqualTo("Matches 1 of 1 nice-to-have skill.");
        assertThat(ENGINE.evaluate(List.of(nice(DOCKER, "Docker"), nice(AWS, "AWS"), nice(GO, "Go")),
                List.of(has(DOCKER), has(GO))).summary())
                .isEqualTo("Matches 2 of 3 nice-to-have skills.");
    }

    @Test
    void exactFourOfFiveSummaryText() {
        List<JobRequirement> reqs = List.of(
                req(JAVA, "Java"), req(SQL, "SQL"), req(DOCKER, "Docker"), req(KUBERNETES, "Kubernetes"),
                req(AWS, "AWS"), nice(GO, "Go"), nice(id(7), "Terraform"));
        MatchEvaluation e = ENGINE.evaluate(reqs, List.of(has(JAVA), has(SQL), has(DOCKER), has(AWS), has(GO)));
        assertThat(e.summary()).isEqualTo("Matches 4 of 5 required skills; missing: Kubernetes; 1 of 2 nice-to-have.");
        assertThat(e.earnedPoints()).isEqualTo(45);
        assertThat(e.maxPoints()).isEqualTo(60);
        assertThat(e.score()).isEqualTo(0.75);
    }

    @Test
    void listsAndMissingNamesAreSortedCaseInsensitivelyThenById() {
        UUID b1 = id(20);
        UUID b2 = id(10); // same name as b1 (different id) to exercise the id tie-break
        List<JobRequirement> reqs = List.of(
                req(id(11), "zookeeper"), req(id(12), "Apache Kafka"), req(id(13), "bash"),
                req(b1, "Docker"), req(b2, "docker"), nice(id(14), "yaml"), nice(id(15), "Ansible"));
        MatchEvaluation e = ENGINE.evaluate(reqs, List.of());
        assertThat(names(e.missingRequired())).containsExactly("Apache Kafka", "bash", "docker", "Docker", "zookeeper");
        assertThat(e.missingRequired().get(2).skillId()).isEqualTo(b2);
        assertThat(e.missingRequired().get(3).skillId()).isEqualTo(b1);
        assertThat(names(e.missingNiceToHave())).containsExactly("Ansible", "yaml");
        assertThat(e.summary()).isEqualTo(
                "Matches 0 of 5 required skills; missing: Apache Kafka, bash, docker, Docker, zookeeper; 0 of 2 nice-to-have.");
    }

    @Test
    void outputIsDeterministicRegardlessOfInputOrder() {
        List<JobRequirement> reqs = new ArrayList<>(List.of(
                req(JAVA, "Java"), req(SQL, "SQL"), nice(DOCKER, "Docker"), nice(KUBERNETES, "Kubernetes"),
                req(AWS, "AWS")));
        List<CandidateSkillFact> facts = new ArrayList<>(List.of(has(JAVA, 3), has(DOCKER, 1), has(GO, 2)));
        MatchEvaluation first = ENGINE.evaluate(reqs, facts);
        for (long seed = 1; seed <= 20; seed++) {
            Collections.shuffle(reqs, new java.util.Random(seed));
            Collections.shuffle(facts, new java.util.Random(seed * 31));
            assertThat(ENGINE.evaluate(reqs, facts)).isEqualTo(first);
        }
        assertThat(first.summary()).isEqualTo("Matches 1 of 3 required skills; missing: AWS, SQL; 1 of 2 nice-to-have.");
    }

    @Test
    void breakdownListsAreImmutable() {
        MatchEvaluation e = ENGINE.evaluate(List.of(req(JAVA, "Java")), List.of(has(JAVA)));
        assertThatThrownBy(() -> e.matchedRequired().add(new SkillHit(SQL, "SQL", null)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void duplicateCandidateFactsDoNotDoubleCount() {
        MatchEvaluation e = ENGINE.evaluate(List.of(req(JAVA, "Java"), req(SQL, "SQL")),
                List.of(has(JAVA, 2), has(JAVA, 5)));
        assertThat(e.earnedPoints()).isEqualTo(10);
        assertThat(e.score()).isEqualTo(0.5);
    }
}
