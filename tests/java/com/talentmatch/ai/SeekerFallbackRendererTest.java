package com.talentmatch.ai;

import static com.talentmatch.ai.AiFixtures.ada;
import static com.talentmatch.ai.AiFixtures.eval;
import static com.talentmatch.ai.AiFixtures.has;
import static com.talentmatch.ai.AiFixtures.miss;
import static org.assertj.core.api.Assertions.assertThat;

import com.talentmatch.domain.scoring.MatchEvaluation;
import com.talentmatch.domain.scoring.SkillHit;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Phase 6 spec §4.6 / §9.1 item 4: the seeker template (second person, no name). */
class SeekerFallbackRendererTest {

    private static ExplanationView render(int percent, MatchEvaluation e) {
        return SeekerFallbackRenderer.render(percent, e, ExplanationReason.NOT_IN_TOP_N, false, 5);
    }

    private static void assertSecondPerson(ExplanationView v) {
        assertThat(v.headline()).contains("you have");
        assertThat(v.text()).containsPattern("\\b[Yy]ou\\b|This job");
        // never a third-person or named form (the recruiter template's wording)
        for (String s : List.of(v.headline(), v.text())) {
            assertThat(s).doesNotContainIgnoringCase("candidate").doesNotContain(" has ")
                    .doesNotContain("The candidate").doesNotContain("Ada").doesNotContain("scored on");
        }
    }

    @Test
    void allRequiredMatchedWithNiceToHaveMixedForTheSpecExample() {
        ExplanationView v = render(83, ada());
        assertThat(v.source()).isEqualTo(ExplanationSource.TEMPLATE);
        assertThat(v.headline()).isEqualTo("Strong match: you have 2 of 2 required skills");
        assertThat(v.text()).isEqualTo("You have all 2 required skills: Java (5 years), SQL. "
                + "Nice-to-have skills: you have Docker; you're missing Kubernetes.");
        assertThat(v.strengths()).containsExactly("Java (5 years)", "SQL", "Docker");
        assertThat(v.gaps()).containsExactly("Kubernetes (nice-to-have)");
        assertThat(v.model()).isNull();
        assertThat(v.generatedAt()).isNull();
        assertThat(v.reason()).isEqualTo(ExplanationReason.NOT_IN_TOP_N);
        assertThat(v.note()).isEqualTo(ExplanationReason.NOT_IN_TOP_N.note(5));
        assertSecondPerson(v);
    }

    @Test
    void noneOfTheRequiredMatched() {
        ExplanationView v = render(0, eval(List.of(), List.of(), List.of(miss("Java"), miss("SQL")), List.of()));
        assertThat(v.headline()).isEqualTo("No skill overlap: you have 0 of 2 required skills");
        assertThat(v.text()).isEqualTo("You have none of the required skills (missing: Java, SQL).");
        assertThat(v.strengths()).isEmpty();
        assertThat(v.gaps()).containsExactly("Java (required)", "SQL (required)");
        assertSecondPerson(v);
    }

    @Test
    void someRequiredMatchedWithYearsAndNiceToHaveBothWays() {
        MatchEvaluation e = eval(List.of(has("Java", 2)), List.of(has("Docker", 1)), List.of(miss("Go"), miss("SQL")),
                List.of(miss("Kubernetes")));
        ExplanationView v = render(38, e);
        assertThat(v.headline()).isEqualTo("Weak match: you have 1 of 3 required skills");
        assertThat(v.text()).isEqualTo("You have 1 of 3 required skills: Java (2 years); you're missing Go, SQL. "
                + "Nice-to-have skills: you have Docker (1 year); you're missing Kubernetes.");
        assertThat(v.strengths()).containsExactly("Java (2 years)", "Docker (1 year)");
        assertThat(v.gaps()).containsExactly("Go (required)", "SQL (required)", "Kubernetes (nice-to-have)");
        assertSecondPerson(v);
    }

    @Test
    void niceToHaveOnlyJobs() {
        ExplanationView mixed = render(50, eval(List.of(), List.of(has("Docker")), List.of(), List.of(miss("Kubernetes"))));
        assertThat(mixed.headline()).isEqualTo("Partial match: you have 1 of 2 nice-to-have skills");
        assertThat(mixed.text()).isEqualTo("This job lists only nice-to-have skills. "
                + "Nice-to-have skills: you have Docker; you're missing Kubernetes.");

        ExplanationView all = render(100, eval(List.of(), List.of(has("Docker", 3), has("Git")), List.of(), List.of()));
        assertThat(all.headline()).isEqualTo("Strong match: you have 2 of 2 nice-to-have skills");
        assertThat(all.text()).isEqualTo("This job lists only nice-to-have skills. "
                + "Nice-to-have skills: you have Docker (3 years), Git.");

        ExplanationView none = render(0, eval(List.of(), List.of(), List.of(), List.of(miss("Kubernetes"))));
        assertThat(none.headline()).isEqualTo("No skill overlap: you have 0 of 1 nice-to-have skill");
        assertThat(none.text()).isEqualTo("This job lists only nice-to-have skills. "
                + "Nice-to-have skills: you're missing Kubernetes.");
        assertThat(none.gaps()).containsExactly("Kubernetes (nice-to-have)");
        for (ExplanationView v : List.of(mixed, all, none)) {
            assertSecondPerson(v);
        }
    }

    @Test
    void exactlyOneRequiredSkillUsesTheSingularForms() {
        ExplanationView has = render(100, eval(List.of(has("Java", 0)), List.of(), List.of(), List.of()));
        assertThat(has.headline()).isEqualTo("Strong match: you have 1 of 1 required skill");
        assertThat(has.text()).isEqualTo("You have the required skill: Java (0 years).");
        assertThat(has.strengths()).containsExactly("Java (0 years)");
        assertThat(has.gaps()).isEmpty();

        ExplanationView missing = render(0, eval(List.of(), List.of(), List.of(miss("Java")), List.of()));
        assertThat(missing.headline()).isEqualTo("No skill overlap: you have 0 of 1 required skill");
        assertThat(missing.text()).isEqualTo("You have none of the required skills (missing: Java).");
    }

    @Test
    void yearsFormats() {
        ExplanationView v = render(100, eval(List.of(has("Java", 1), has("SQL", 0), has("Go", 12), has("Git")),
                List.of(), List.of(), List.of()));
        assertThat(v.text()).isEqualTo("You have all 4 required skills: Java (1 year), SQL (0 years), Go (12 years), Git.");
        assertThat(v.strengths()).containsExactly("Java (1 year)", "SQL (0 years)", "Go (12 years)", "Git");
    }

    @Test
    void strengthsAndGapsAreCappedAtFiveButTheTextListsEverything() {
        List<SkillHit> matched = IntStream.rangeClosed(1, 4).mapToObj(i -> has("M" + i, i)).toList();
        List<SkillHit> matchedNice = IntStream.rangeClosed(1, 3).mapToObj(i -> has("N" + i)).toList();
        List<SkillHit> missing = IntStream.rangeClosed(1, 3).mapToObj(i -> miss("X" + i)).toList();
        List<SkillHit> missingNice = IntStream.rangeClosed(1, 4).mapToObj(i -> miss("Y" + i)).toList();
        ExplanationView v = render(55, eval(matched, matchedNice, missing, missingNice));
        assertThat(v.strengths()).containsExactly("M1 (1 year)", "M2 (2 years)", "M3 (3 years)", "M4 (4 years)", "N1");
        assertThat(v.gaps()).containsExactly("X1 (required)", "X2 (required)", "X3 (required)",
                "Y1 (nice-to-have)", "Y2 (nice-to-have)");
        assertThat(v.text()).contains("M4 (4 years)").contains("N3").contains("X3").contains("Y4");
        assertThat(v.headline()).isEqualTo("Partial match: you have 4 of 7 required skills");
    }

    @ParameterizedTest
    @CsvSource({"0,No skill overlap", "1,Weak match", "49,Weak match", "50,Partial match", "79,Partial match",
            "80,Strong match", "100,Strong match"})
    void bandBoundaries(int percent, String band) {
        ExplanationView v = render(percent, ada());
        assertThat(v.headline()).isEqualTo(band + ": you have 2 of 2 required skills");
        assertThat(v.headline()).startsWith(ExplanationFallbackRenderer.band(percent) + ": ");
    }

    @Test
    void sameStrengthsAndGapsAsTheRecruiterTemplateButNoName() {
        List<MatchEvaluation> cases = List.of(ada(),
                eval(List.of(), List.of(), List.of(miss("Java")), List.of(miss("Go"))),
                eval(List.of(has("Java", 3)), List.of(), List.of(miss("SQL")), List.of()),
                eval(List.of(), List.of(has("Docker")), List.of(), List.of()));
        for (MatchEvaluation e : cases) {
            ExplanationView seeker = render(60, e);
            ExplanationView recruiter = ExplanationFallbackRenderer.render("Ada Lovelace", 60, e,
                    ExplanationReason.NOT_IN_TOP_N, false, 5);
            assertThat(seeker.strengths()).isEqualTo(recruiter.strengths());
            assertThat(seeker.gaps()).isEqualTo(recruiter.gaps());
            assertThat(seeker.reason()).isEqualTo(recruiter.reason());
            assertThat(seeker.note()).isEqualTo(recruiter.note());
            assertThat(recruiter.text()).contains("Ada Lovelace");
            assertSecondPerson(seeker);
        }
    }

    @Test
    void stalePrefixIsTheSeekerOneAndAddressesTheSeeker() {
        for (ExplanationReason reason : ExplanationReason.values()) {
            ExplanationView stale = SeekerFallbackRenderer.render(83, ada(), reason, true, 7);
            assertThat(stale.reason()).isEqualTo(reason);
            assertThat(stale.note()).isEqualTo(ExplanationReason.SEEKER_STALE_PREFIX + reason.note(7))
                    .startsWith("The previous AI explanation is out of date because your profile or the job changed. ")
                    .doesNotContain("candidate");
            ExplanationView fresh = SeekerFallbackRenderer.render(83, ada(), reason, false, 7);
            assertThat(fresh.note()).isEqualTo(reason.note(7));
        }
        assertThat(SeekerFallbackRenderer.render(83, ada(), ExplanationReason.NOT_IN_TOP_N, true, 7).note())
                .contains("top 7 matches");
    }

    @Test
    void nullReasonGivesNoReasonAndNoNoteEvenWhenStale() {
        for (boolean stale : List.of(true, false)) {
            ExplanationView v = SeekerFallbackRenderer.render(83, ada(), null, stale, 5);
            assertThat(v.reason()).isNull();
            assertThat(v.note()).isNull();
            assertThat(v.text()).isNotBlank();
        }
    }

    @Test
    void audienceAwareNoteKeepsTheRecruiterWordingByteIdentical() {
        for (ExplanationReason r : ExplanationReason.values()) {
            for (boolean stale : List.of(true, false)) {
                assertThat(r.note(5, stale, Audience.RECRUITER)).isEqualTo(r.note(5, stale));
            }
            assertThat(r.note(5, false, Audience.SEEKER)).isEqualTo(r.note(5));
            assertThat(r.note(5, true, Audience.SEEKER)).isEqualTo(ExplanationReason.SEEKER_STALE_PREFIX + r.note(5));
        }
        assertThat(ExplanationReason.STALE_PREFIX)
                .isEqualTo("The previous AI explanation is out of date because the candidate or job changed. ");
    }

    @Test
    void outputHasNoMarkdownOrBulletsAndIsDeterministic() {
        MatchEvaluation e = eval(List.of(has("Java", 2)), List.of(has("Docker", 1)), List.of(miss("SQL")),
                List.of(miss("Kubernetes")));
        ExplanationView a = render(60, e);
        assertThat(render(60, e)).isEqualTo(a);
        List<String> all = new ArrayList<>(List.of(a.headline(), a.text()));
        all.addAll(a.strengths());
        all.addAll(a.gaps());
        Pattern bad = Pattern.compile("[*#`•\\n]|^- ");
        assertThat(all).noneMatch(s -> bad.matcher(s).find());
    }
}
