package com.talentmatch.seeker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.talentmatch.ai.ExplanationReason;
import com.talentmatch.ai.ExplanationView;
import com.talentmatch.ai.MatchExplanation;
import com.talentmatch.ai.SeekerFallbackRenderer;
import com.talentmatch.domain.scoring.MatchEvaluation;
import com.talentmatch.domain.scoring.SkillHit;
import com.talentmatch.feed.skills.DictionarySkillMatcher;
import com.talentmatch.feed.skills.DictionarySkillMatcher.Term;
import com.talentmatch.feed.skills.SkillDictionary;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;

/** Phase 6 spec §4.5 / §9.1 item 5: free-text grounding of seeker explanations (real matcher). */
@ExtendWith(OutputCaptureExtension.class)
class SeekerGroundingCheckTest {

    /** "Name" or "Name=alias1,alias2" (aliases stored lower-cased, as in skill_alias). */
    private static final List<String> VOCABULARY = List.of("Java", "SQL", "PostgreSQL=postgres,psql",
            "Kubernetes=k8s", "Docker", "Go=golang", "Spring", "Spring Boot", "Python", "JavaScript", "Terraform",
            "C++", "R", "Swift", "HTML5=html 5");

    private static UUID id(String skill) {
        return UUID.nameUUIDFromBytes(("skill:" + skill).getBytes());
    }

    static SkillDictionary.Snapshot snapshot() {
        List<Term> terms = new ArrayList<>();
        Map<UUID, String> names = new HashMap<>();
        Map<String, UUID> byKey = new HashMap<>();
        for (String s : VOCABULARY) {
            String[] parts = s.split("=", 2);
            String name = parts[0];
            names.put(id(name), name);
            terms.add(new Term(id(name), name, name));
            if (parts.length == 2) {
                for (String alias : parts[1].split(",")) {
                    terms.add(new Term(id(name), name, alias));
                    byKey.put(alias, id(name));
                }
            }
        }
        names.forEach((k, v) -> byKey.put(v.toLowerCase(Locale.ROOT), k));
        return new SkillDictionary.Snapshot("fp", Map.copyOf(names), Map.copyOf(byKey),
                new DictionarySkillMatcher(terms, DictionarySkillMatcher.DEFAULT_AMBIGUOUS_NAMES));
    }

    private static SkillHit has(String name, Integer years) {
        return new SkillHit(id(name), name, years);
    }

    private static SkillHit has(String name) {
        return has(name, null);
    }

    private static SkillHit miss(String name) {
        return new SkillHit(id(name), name, null);
    }

    /** Weights 10 required / 5 nice; summary like the scoring engine's. */
    private static MatchEvaluation eval(List<SkillHit> mr, List<SkillHit> mn, List<SkillHit> xr, List<SkillHit> xn) {
        int earned = mr.size() * 10 + mn.size() * 5;
        int max = earned + xr.size() * 10 + xn.size() * 5;
        String summary = "Matches " + mr.size() + " of " + (mr.size() + xr.size()) + " required skills; "
                + mn.size() + " of " + (mn.size() + xn.size()) + " nice-to-have.";
        return new MatchEvaluation(max == 0 ? 0 : (double) earned / max, earned, max, mr, mn, xr, xn, summary);
    }

    /** Java (5 years) and SQL required and matched; Docker nice matched; Kubernetes nice missing. 83%, 25 of 30. */
    private static MatchEvaluation javaSql() {
        return eval(List.of(has("Java", 5), has("SQL")), List.of(has("Docker")), List.of(), List.of(miss("Kubernetes")));
    }

    private final SkillDictionary dictionary = mock(SkillDictionary.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<SkillDictionary> provider = mock(ObjectProvider.class);
    private final SeekerGroundingCheck check = new SeekerGroundingCheck(provider);

    {
        when(provider.getIfAvailable()).thenReturn(dictionary);
        when(dictionary.refresh()).thenReturn(snapshot());
    }

    private static MatchExplanation out(String headline, String text) {
        return new MatchExplanation(headline, text, List.of(), List.of());
    }

    private boolean accept(MatchExplanation v, MatchEvaluation e) {
        return check.accept(v, e, "Backend Engineer", "Acme");
    }

    private boolean accept(String headline, String text) {
        return accept(out(headline, text), javaSql());
    }

    // ------------------------------------------------------------------ skills

    @Test
    void matchedAndMissingSkillsOfTheJobAreAccepted() {
        assertThat(accept("A strong fit for your Java and SQL skills",
                "You have both required skills, Java with 5 years and SQL. Docker is a plus. "
                        + "You're missing Kubernetes, a nice-to-have skill.")).isTrue();
        assertThat(accept(new MatchExplanation("Strong fit", "You have Java and SQL, the required skills here.",
                List.of("Java (5 years)", "SQL"), List.of("Kubernetes (nice-to-have)")), javaSql())).isTrue();
    }

    @Test
    void aVocabularySkillNotOnTheJobIsRejectedInAnyField() {
        assertThat(accept("Strong fit", "You have Java and SQL, and your Kubernetes work helps too.")).as("missing is "
                + "fine").isTrue();
        MatchEvaluation onlyJavaSql = eval(List.of(has("Java", 5), has("SQL")), List.of(), List.of(), List.of());
        assertThat(accept(out("Strong fit", "You have Java and SQL. Kubernetes would also help."), onlyJavaSql))
                .as("Kubernetes on a Java/SQL job").isFalse();
        assertThat(accept(out("Java, SQL and Kubernetes", "You have Java and SQL, the required skills."), onlyJavaSql))
                .as("headline").isFalse();
        assertThat(accept(new MatchExplanation("Strong fit", "You have Java and SQL, the required skills.",
                List.of("Python scripting"), List.of()), onlyJavaSql)).as("strengths").isFalse();
        assertThat(accept(new MatchExplanation("Strong fit", "You have Java and SQL, the required skills.",
                List.of(), List.of("Terraform")), onlyJavaSql)).as("gaps").isFalse();
        assertThat(accept(out("Strong fit", "You have Java and SQL; experience with C++ is a bonus."), onlyJavaSql))
                .as("symbol name").isFalse();
    }

    @Test
    void aliasesResolveToTheirSkill() {
        MatchEvaluation pg = eval(List.of(has("PostgreSQL", 3)), List.of(), List.of(), List.of(miss("Kubernetes")));
        assertThat(accept(out("A good fit for your Postgres skills",
                "You have 3 years of Postgres, the required skill. You're missing Kubernetes, a nice-to-have."), pg))
                .as("aliases of matched and missing skills").isTrue();
        assertThat(accept(out("Strong fit", "You have PostgreSQL. You don't have Kubernetes yet."), pg)).isTrue();
        MatchEvaluation onlyJavaSql = eval(List.of(has("Java", 5), has("SQL")), List.of(), List.of(), List.of());
        assertThat(accept(out("Strong fit", "You have Java and SQL; k8s would help."), onlyJavaSql))
                .as("alias of a foreign skill").isFalse();
        assertThat(accept(out("Strong fit", "You have Java and SQL; some psql use too."), onlyJavaSql))
                .as("alias of a foreign skill").isFalse();
    }

    /** Regression: the "8" in "k8s" (an alias of the job's own skill) is not a quantity. */
    @Test
    void anAliasWithADigitOfAJobSkillIsAccepted() {
        MatchEvaluation pg = eval(List.of(has("PostgreSQL", 3)), List.of(), List.of(), List.of(miss("Kubernetes")));
        assertThat(accept(out("A good fit for your Postgres skills",
                "You have 3 years of Postgres, the required skill. You're missing k8s, a nice-to-have."), pg))
                .isTrue();
    }

    @Test
    void aJobSkillWhoseOwnNameHasADigitIsAcceptedEvenWithoutASnapshot() {
        MatchEvaluation web = eval(List.of(has("HTML5", 2), has("Java", 5)), List.of(), List.of(), List.of());
        MatchExplanation v = out("A strong HTML5 fit", "You have 2 years of HTML5 and 5 of Java, both required.");
        assertThat(SeekerGroundingCheck.numbersGrounded(v, web, "Frontend Engineer", "Acme")).isTrue();
        assertThat(SeekerGroundingCheck.numbersGrounded(v, web, "Frontend Engineer", "Acme", null)).isTrue();
        assertThat(SeekerGroundingCheck.numbersGrounded(v, web, "Frontend Engineer", "Acme", snapshot())).isTrue();
        assertThat(check.accept(v, web, "Frontend Engineer", "Acme")).isTrue();
        // ...but a bare 5 is checked against the facts on its own (step 4, §12.4: skill-name digits are
        // no longer allowed numbers)
        MatchEvaluation noFive = eval(List.of(has("HTML5", 2)), List.of(), List.of(), List.of());
        assertThat(SeekerGroundingCheck.numbersGrounded(out("Strong fit", "You have HTML5 and 5 more skills."),
                noFive, "Frontend Engineer", "Acme", snapshot())).as("stand-alone 5 on an HTML5 job").isFalse();
        assertThat(SeekerGroundingCheck.numbersGrounded(out("Strong fit", "You have HTML5 and 5 more skills."),
                noFive, "Frontend Engineer", "Acme", null)).as("same without a snapshot").isFalse();
        assertThat(check.accept(out("Strong fit", "You have HTML5 and 5 more skills."), noFive, "Frontend Engineer",
                "Acme")).isFalse();
        assertThat(SeekerGroundingCheck.allowedNumbers(noFive, "Frontend Engineer", "Acme")).doesNotContain("5");
        assertThat(SeekerGroundingCheck.numbersGrounded(out("Strong fit", "You have HTML5 and 7 more skills."),
                noFive, "Frontend Engineer", "Acme", null)).isFalse();
    }

    @Test
    void aliasDigitsAreExemptOnlyWithTheSnapshot() {
        MatchEvaluation pg = eval(List.of(has("PostgreSQL", 3)), List.of(), List.of(), List.of(miss("Kubernetes")));
        MatchExplanation v = out("Good fit", "You have 3 years of Postgres. You're missing k8s, a nice-to-have.");
        assertThat(SeekerGroundingCheck.numbersGrounded(v, pg, "Engineer", "Acme", snapshot())).isTrue();
        assertThat(SeekerGroundingCheck.numbersGrounded(v, pg, "Engineer", "Acme"))
                .as("without a snapshot only breakdown names are exempt").isFalse();
    }

    @Test
    void k8sIsStillRejectedWhenKubernetesIsNotOnTheJob() {
        MatchEvaluation onlyJavaSql = eval(List.of(has("Java", 5), has("SQL")), List.of(), List.of(), List.of());
        MatchExplanation v = out("Strong fit", "You have Java and SQL; k8s would help too.");
        assertThat(accept(v, onlyJavaSql)).isFalse();
        assertThat(SeekerGroundingCheck.numbersGrounded(v, onlyJavaSql, "Engineer", "Acme", snapshot()))
                .as("the 8 of a foreign skill's alias is not exempt").isFalse();
    }

    @Test
    void numbersNextToAnExemptAliasAreStillChecked() {
        MatchEvaluation pg = eval(List.of(has("PostgreSQL", 3)), List.of(), List.of(), List.of(miss("Kubernetes")));
        assertThat(accept(out("Good fit", "You have 3 years of Postgres. You're missing 12 years of k8s."), pg))
                .as("12 is not in the facts").isFalse();
        assertThat(accept(out("Good fit", "You have 3 years of Postgres. You're missing k8s and 8 other things."), pg))
                .as("a bare 8 is not inside the alias").isFalse();
        assertThat(accept(out("Good fit", "You have 3 years of Postgres. You're missing k8s for now."), pg)).isTrue();
    }

    @Test
    void ambiguousAndCaseSensitiveNamesFollowTheMatcherRules() {
        MatchEvaluation onlyJavaSql = eval(List.of(has("Java", 5), has("SQL")), List.of(), List.of(), List.of());
        assertThat(accept(out("Ready to go", "You are ready to go: you have Java and SQL, both required."),
                onlyJavaSql)).as("\"go\" in \"ready to go\" is not Go").isTrue();
        assertThat(accept(out("Strong fit", "You have Java and SQL, so you can hit the ground running and go far."),
                onlyJavaSql)).isTrue();
        assertThat(accept(out("Strong fit", "You have Java and SQL. Your swift delivery stands out."), onlyJavaSql))
                .as("unconfirmed ambiguous name").isTrue();
        assertThat(accept(out("Strong fit", "You have Java and SQL. You also have R and some sql."), onlyJavaSql))
                .as("short names are case-sensitive: R counts, sql does not").isFalse();
        assertThat(accept(out("Strong fit", "You have Java, SQL and experience with Go."), onlyJavaSql))
                .as("confirmed Go (non-ambiguous skill in the sentence)").isFalse();
        assertThat(accept(out("Strong fit", "You have Java and SQL. Golang is a language you could learn."),
                onlyJavaSql)).as("ambiguous alias confirmed by a tech context word").isFalse();
        assertThat(accept(out("Strong fit", "You have Java and SQL, not JavaScript."), onlyJavaSql))
                .as("Java inside JavaScript is not Java; JavaScript is foreign").isFalse();
    }

    @Test
    void springBootOnASpringBootJobIsNotAlsoSpring() {
        MatchEvaluation boot = eval(List.of(has("Spring Boot", 4), has("Java", 5)), List.of(), List.of(), List.of());
        assertThat(accept(out("Strong Spring Boot fit", "You have 4 years of Spring Boot and 5 of Java."), boot))
                .isTrue();
        assertThat(accept(new MatchExplanation("Strong fit", "You have Spring Boot and Java, the required skills.",
                List.of("Spring Boot (4 years)"), List.of()), boot)).isTrue();
        assertThat(accept(out("Strong fit", "You have Spring Boot and Java, and Spring itself."), boot))
                .as("a standalone Spring is still foreign").isFalse();
    }

    @Test
    void springBootOnASpringJobIsRejected() {
        MatchEvaluation spring = eval(List.of(has("Spring", 4), has("Java", 5)), List.of(), List.of(), List.of());
        assertThat(accept(out("Strong fit", "You have 4 years of Spring and 5 of Java."), spring)).isTrue();
        assertThat(accept(out("Strong fit", "You have 4 years of Spring Boot and 5 of Java."), spring))
                .as("Spring Boot is a different skill").isFalse();
    }

    @Test
    void nullListsAndFieldsAreTolerated() {
        assertThat(accept(new MatchExplanation("Strong fit", "You have Java and SQL, both required.", null, null),
                javaSql())).isTrue();
        assertThat(accept(new MatchExplanation(null, "You have Java and SQL, both required.",
                java.util.Arrays.asList("Java", null), null), javaSql())).isTrue();
    }

    // ------------------------------------------------------------------ numbers

    @Test
    void numbersFromTheFactsAreAccepted() {
        assertThat(accept("An 83% fit for you", "You scored 25 of 30 points: you have 2 of 2 required skills and "
                + "1 of 2 nice-to-have skills, with 5 years of Java.")).isTrue();
        assertThat(accept("83 % match", "You have Java (5.0 years) and SQL, both required skills.")).as("5.0 == 5")
                .isTrue();
        assertThat(accept("Strong fit", "You have Java and SQL; you're missing 1 nice-to-have, Kubernetes."))
                .isTrue();
    }

    @Test
    void numbersNotInTheFactsAreRejected() {
        assertThat(accept("Strong fit", "You have 10 years of Java and SQL, both required.")).as("10 years")
                .isFalse();
        assertThat(accept("A 90% fit", "You have Java and SQL, both required skills.")).as("headline").isFalse();
        assertThat(accept("Strong fit", "You have 83.3% of the points with Java and SQL.")).as("83.3").isFalse();
        assertThat(accept("Strong fit", "You have Java and SQL; 3 more skills would help.")).as("3").isFalse();
        assertThat(accept("Strong fit", "You have Java and SQL, both required, and earn 1,000 points."))
                .isFalse();
    }

    @Test
    void digitsFromTheJobTitleOrCompanyAreAccepted() {
        MatchExplanation web3 = out("A Web3 role that fits you", "You have Java and SQL, both required for this Web3 "
                + "Engineer role.");
        assertThat(check.accept(web3, javaSql(), "Web3 Engineer", "Acme")).isTrue();
        assertThat(check.accept(web3, javaSql(), "Backend Engineer", "Acme")).as("3 is not in the facts").isFalse();
        MatchExplanation team = out("Strong fit", "You have Java and SQL, both required at Team 42.");
        assertThat(check.accept(team, javaSql(), "Engineer II", "Team 42")).as("company").isTrue();
        assertThat(check.accept(team, javaSql(), "Engineer II", null)).as("null company").isFalse();
        assertThat(check.accept(out("Strong fit", "You have Java and SQL; a 1,000-person company."), javaSql(),
                "Engineer", "Acme 1000")).as("1,000 == 1000").isTrue();
    }

    @Test
    void numbersOnlyInStrengthsOrGapsAreNotChecked() {
        assertThat(accept(new MatchExplanation("Strong fit", "You have Java and SQL, both required skills.",
                List.of("Java (10 years)"), List.of()), javaSql())).isTrue();
    }

    @Test
    void numberHelpers() {
        assertThat(SeekerGroundingCheck.canonical("5.0")).isEqualTo("5");
        assertThat(SeekerGroundingCheck.canonical("05")).isEqualTo("5");
        assertThat(SeekerGroundingCheck.canonical("1,000")).isEqualTo("1000");
        assertThat(SeekerGroundingCheck.canonical("2,5")).isEqualTo("2.5");
        assertThat(SeekerGroundingCheck.canonical("83.30")).isEqualTo("83.3");
        assertThat(SeekerGroundingCheck.allowedNumbers(javaSql(), "Backend Engineer", "Acme"))
                .containsExactlyInAnyOrder("83", "25", "30", "2", "1", "5");
        // the score percent comes from eval.score(); the engine summary contributes digits, skill names
        // no longer do (step 4, §12.4)
        MatchEvaluation e = eval(List.of(has("Python 3", 7)), List.of(), List.of(miss("Java")), List.of());
        assertThat(SeekerGroundingCheck.allowedNumbers(e, "Engineer", null)).contains("50", "10", "20", "1", "2", "0",
                "7").doesNotContain("3");
    }

    // ------------------------------------------------------------------ polarity

    @Test
    void claimingAMissingSkillIsRejected() {
        assertThat(accept("Strong fit", "You have Kubernetes experience. You also have Java and SQL.")).isFalse();
        assertThat(accept("You have Kubernetes", "You have Java and SQL, the required skills here.")).as("headline")
                .isFalse();
        assertThat(accept("Strong fit", "You know Kubernetes well. Java and SQL are covered too.")).isFalse();
        assertThat(accept("Strong fit", "You bring k8s skills. Java and SQL are covered too.")).as("alias")
                .isFalse();
        MatchEvaluation missingRequired = eval(List.of(has("Java", 5)), List.of(), List.of(miss("SQL")), List.of());
        assertThat(accept(out("Partial fit", "You have SQL and more. Java is covered."), missingRequired)).isFalse();
    }

    @Test
    void negationOrAMatchedSkillInTheSentenceKeepsItAccepted() {
        assertThat(accept("Strong fit", "You have Java and SQL, but you don't have Kubernetes yet.")).isTrue();
        assertThat(accept("Strong fit", "You have Java and SQL. You have no Kubernetes experience.")).isTrue();
        assertThat(accept("Strong fit", "You have Java and SQL. You have not used Kubernetes.")).isTrue();
        assertThat(accept("Strong fit", "You have Java and SQL. You have yet to learn Kubernetes.")).isTrue();
        assertThat(accept("Strong fit", "You have Java and SQL; you're missing Kubernetes.")).isTrue();
        assertThat(accept("Strong fit", "Kubernetes is missing. You have Java and SQL, both required.")).isTrue();
        // best effort (spec): a sentence that also names a matched skill is not judged
        assertThat(accept("Strong fit", "You have Java, SQL and Kubernetes.")).isTrue();
    }

    @Test
    void polarityIsSkippedWhenNothingIsMissing() {
        MatchEvaluation all = eval(List.of(has("Java", 5), has("SQL")), List.of(), List.of(), List.of());
        assertThat(accept(out("Strong fit", "You have Java and SQL. Nothing is missing."), all)).isTrue();
    }

    // ------------------------------------------------------------------ templates are always grounded

    @Test
    void theSeekerTemplateForAnyBreakdownPassesTheCheck() {
        List<MatchEvaluation> cases = List.of(javaSql(),
                eval(List.of(), List.of(), List.of(miss("Java"), miss("SQL")), List.of(miss("Docker"))),
                eval(List.of(has("Java", 2)), List.of(has("Docker", 1)), List.of(miss("Go"), miss("SQL")),
                        List.of(miss("Kubernetes"))),
                eval(List.of(), List.of(has("Docker")), List.of(), List.of(miss("Kubernetes"))),
                eval(List.of(has("Spring Boot", 1)), List.of(), List.of(), List.of(miss("PostgreSQL"))));
        for (MatchEvaluation e : cases) {
            int percent = (int) Math.round(e.score() * 100);
            ExplanationView v = SeekerFallbackRenderer.render(percent, e, ExplanationReason.NOT_IN_TOP_N, false, 5);
            MatchExplanation asAi = new MatchExplanation(v.headline(), v.text(), v.strengths(), v.gaps());
            assertThat(accept(asAi, e)).as(v.text()).isTrue();
        }
    }

    // ------------------------------------------------------------------ fail closed

    @Test
    void usesRefreshSoNewSkillsAreSeen() {
        accept("Strong fit", "You have Java and SQL, both required.");
        accept("Strong fit", "You have Java and SQL, both required.");
        verify(dictionary, times(2)).refresh();
    }

    @Test
    void noDictionaryBeanRejects(CapturedOutput output) {
        when(provider.getIfAvailable()).thenReturn(null);
        assertThat(accept("Strong fit", "You have Java and SQL, both required.")).isFalse();
        assertThat(output.getAll()).contains("no skill dictionary").doesNotContain("You have Java");
    }

    @Test
    void vocabularyLoadFailureRejectsWithoutLoggingTheText(CapturedOutput output) {
        when(dictionary.refresh()).thenThrow(new DataAccessResourceFailureException("db down"));
        assertThat(accept("Strong fit", "You have Java and SQL, both required.")).isFalse();
        assertThat(output.getAll()).contains("DataAccessResourceFailureException").doesNotContain("You have Java")
                .doesNotContain("db down");
    }

    @Test
    void nullSnapshotOrMatcherRejects() {
        when(dictionary.refresh()).thenReturn(null);
        assertThat(accept("Strong fit", "You have Java and SQL, both required.")).isFalse();
        when(dictionary.refresh()).thenReturn(new SkillDictionary.Snapshot("fp", Map.of(), Map.of(), null));
        assertThat(accept("Strong fit", "You have Java and SQL, both required.")).isFalse();
    }

    @Test
    void nullValueOrEvaluationRejects() {
        assertThat(check.accept(null, javaSql(), "t", "c")).isFalse();
        assertThat(check.accept(out("Strong fit", "You have Java and SQL."), null, "t", "c")).isFalse();
    }

    @Test
    void emptyVocabularyStillChecksNumbersAndAcceptsOtherwise() {
        when(dictionary.refresh()).thenReturn(new SkillDictionary.Snapshot("fp", Map.of(), Map.of(),
                new DictionarySkillMatcher(List.of(), List.of())));
        assertThat(accept("Strong fit", "You have Java and SQL, both required.")).isTrue();
        assertThat(accept("Strong fit", "You have 10 years of Java.")).isFalse();
    }

    @Test
    void isThreadSafeUnderConcurrentUse() throws Exception {
        var pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        try {
            List<java.util.concurrent.Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < 200; i++) {
                boolean good = i % 2 == 0;
                futures.add(pool.submit(() -> accept("Strong fit", good
                        ? "You have Java and SQL, both required." : "You have Java, SQL and Terraform.")));
            }
            for (int i = 0; i < futures.size(); i++) {
                assertThat(futures.get(i).get()).isEqualTo(i % 2 == 0);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ step 4 (§12.4): skill-name digits

    /** The real engine (its summary names missing required skills: "missing: HTML5"). */
    private static MatchEvaluation engine(List<com.talentmatch.domain.scoring.JobRequirement> reqs,
                                          List<com.talentmatch.domain.scoring.CandidateSkillFact> facts) {
        return new com.talentmatch.domain.scoring.ScoringEngine(new com.talentmatch.domain.scoring.ScoringWeights(10, 5))
                .evaluate(reqs, facts);
    }

    private static com.talentmatch.domain.scoring.JobRequirement req(String name, boolean required) {
        return new com.talentmatch.domain.scoring.JobRequirement(id(name), name, required);
    }

    /** Java (3 years) matched; HTML5 required and missing; Docker nice and missing: 10 of 25 = 40%. */
    private static MatchEvaluation html5Missing() {
        return engine(List.of(req("Java", true), req("HTML5", true), req("Docker", false)),
                List.of(new com.talentmatch.domain.scoring.CandidateSkillFact(id("Java"), 3)));
    }

    @Test
    void theEngineSummaryNamesTheMissingSkillButItsDigitsAreMaskedOut() {
        MatchEvaluation e = html5Missing();
        assertThat(e.summary()).contains("HTML5");
        assertThat(SeekerGroundingCheck.allowedNumbers(e, "Frontend Engineer", "Acme"))
                .contains("40", "10", "25", "3", "1", "2", "0").doesNotContain("5");
    }

    @Test
    void aStandAloneFiveIsRejectedWhenHtml5IsAMissingRequiredSkill() {
        MatchEvaluation e = html5Missing();
        assertThat(check.accept(out("A partial fit", "You have 3 years of Java. You're missing HTML5, which is "
                + "required, and Docker, a nice-to-have."), e, "Frontend Engineer", "Acme"))
                .as("HTML5 named as a gap").isTrue();
        assertThat(check.accept(out("A partial fit", "You have 3 years of Java but are missing HTML5 and 5 other "
                + "skills."), e, "Frontend Engineer", "Acme")).as("invented 5").isFalse();
        assertThat(check.accept(out("A 5-star fit", "You have Java but are missing HTML5."), e, "Frontend Engineer",
                "Acme")).as("5 in the headline").isFalse();
        assertThat(SeekerGroundingCheck.numbersGrounded(out("Fit", "You lack HTML5 and 5 more."), e,
                "Frontend Engineer", "Acme", null)).as("null snapshot").isFalse();
        assertThat(SeekerGroundingCheck.numbersGrounded(out("Fit", "You lack HTML5 and 2 more."), e,
                "Frontend Engineer", "Acme", null)).as("2 is a count (of 2 required)").isTrue();
    }

    @Test
    void html5IsAcceptedWithAndWithoutTheSnapshotAndItsAlias() {
        MatchEvaluation e = html5Missing();
        MatchExplanation v = out("You lack HTML5", "You have 3 years of Java. HTML5 is the one required skill "
                + "you're missing.");
        assertThat(SeekerGroundingCheck.numbersGrounded(v, e, "Frontend Engineer", "Acme", null)).isTrue();
        assertThat(SeekerGroundingCheck.numbersGrounded(v, e, "Frontend Engineer", "Acme", snapshot())).isTrue();
        assertThat(check.accept(v, e, "Frontend Engineer", "Acme")).isTrue();
        MatchExplanation alias = out("You lack html 5", "You have 3 years of Java; html 5 is missing.");
        assertThat(SeekerGroundingCheck.numbersGrounded(alias, e, "Frontend Engineer", "Acme", snapshot()))
                .as("alias with a space").isTrue();
        assertThat(SeekerGroundingCheck.numbersGrounded(alias, e, "Frontend Engineer", "Acme", null))
                .as("without the snapshot the alias is not known").isFalse();
    }

    @Test
    void web3TitleDigitsStillCountButSkillNameDigitsDoNot() {
        MatchEvaluation e = html5Missing();
        MatchExplanation v = out("A Web3 role", "You have 3 years of Java for this Web3 team; HTML5 is missing.");
        assertThat(check.accept(v, e, "Web3 Frontend Engineer", "Acme")).isTrue();
        assertThat(SeekerGroundingCheck.allowedNumbers(e, "Web3 Frontend Engineer", "Acme")).contains("3")
                .doesNotContain("5");
        assertThat(SeekerGroundingCheck.allowedNumbers(e, "HTML5 Engineer", "Acme"))
                .as("a digit in the title is still allowed").contains("5");
    }

    @Test
    void maskingDoesNotDropCountsNextToSkillNames() {
        // matched names do not appear in the summary; missing ones do, next to the counts
        MatchEvaluation e = engine(List.of(req("Java", true), req("SQL", true), req("Kubernetes", true),
                        req("Go", true)), List.of(new com.talentmatch.domain.scoring.CandidateSkillFact(id("Java"), null)));
        assertThat(e.summary()).contains("missing:", "Go", "Kubernetes", "SQL", "1 of 4");
        assertThat(SeekerGroundingCheck.allowedNumbers(e, "Engineer", "Acme")).contains("1", "4", "25", "10", "40");
    }
}
