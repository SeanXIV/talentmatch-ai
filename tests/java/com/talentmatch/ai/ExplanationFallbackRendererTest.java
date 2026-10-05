package com.talentmatch.ai;

import static com.talentmatch.ai.AiFixtures.ada;
import static com.talentmatch.ai.AiFixtures.eval;
import static com.talentmatch.ai.AiFixtures.has;
import static com.talentmatch.ai.AiFixtures.miss;
import static org.assertj.core.api.Assertions.assertThat;

import com.talentmatch.domain.scoring.MatchEvaluation;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Spec §6 template rules, exact examples and notes / §10 unit 4. */
class ExplanationFallbackRendererTest {

    private static ExplanationView render(MatchEvaluation e, int percent) {
        return ExplanationFallbackRenderer.render("Ada Lovelace", percent, e,
                ExplanationReason.PROVIDER_UNAVAILABLE, false, 5);
    }

    @Test
    void example1StrongMatch() {
        ExplanationView v = render(ada(), 83);
        assertThat(v.source()).isEqualTo(ExplanationSource.TEMPLATE);
        assertThat(v.headline()).isEqualTo("Strong match: 2 of 2 required skills");
        assertThat(v.text()).isEqualTo("Ada Lovelace has all 2 required skills: Java (5 years), SQL. "
                + "Nice-to-have skills: has Docker; missing Kubernetes.");
        assertThat(v.strengths()).containsExactly("Java (5 years)", "SQL", "Docker");
        assertThat(v.gaps()).containsExactly("Kubernetes (nice-to-have)");
        assertThat(v.model()).isNull();
        assertThat(v.generatedAt()).isNull();
        assertThat(v.reason()).isEqualTo(ExplanationReason.PROVIDER_UNAVAILABLE);
        assertThat(v.note()).isEqualTo("The AI explanation service is unavailable right now, so this summary "
                + "was built from the skill breakdown.");
    }

    @Test
    void example2NoOverlap() {
        ExplanationView v = render(eval(List.of(), List.of(), List.of(miss("Java")), List.of()), 0);
        assertThat(v.headline()).isEqualTo("No skill overlap: 0 of 1 required skill");
        assertThat(v.text()).isEqualTo("Ada Lovelace has none of the required skills (missing: Java).");
        assertThat(v.strengths()).isEmpty();
        assertThat(v.gaps()).containsExactly("Java (required)");
    }

    @Test
    void example3NiceToHaveOnly() {
        ExplanationView v = render(eval(List.of(), List.of(has("Docker"), has("Git")), List.of(),
                List.of(miss("Kubernetes"))), 67);
        assertThat(v.headline()).isEqualTo("Partial match: 2 of 3 nice-to-have skills");
        assertThat(v.text()).isEqualTo("Ada Lovelace was scored on nice-to-have skills only. "
                + "Nice-to-have skills: has Docker, Git; missing Kubernetes.");
        assertThat(v.strengths()).containsExactly("Docker", "Git");
        assertThat(v.gaps()).containsExactly("Kubernetes (nice-to-have)");
    }

    @Test
    void singularFormsAndYears() {
        ExplanationView one = render(eval(List.of(has("Java", 1)), List.of(), List.of(), List.of()), 100);
        assertThat(one.headline()).isEqualTo("Strong match: 1 of 1 required skill");
        assertThat(one.text()).isEqualTo("Ada Lovelace has the required skill: Java (1 year).");
        assertThat(one.strengths()).containsExactly("Java (1 year)");

        ExplanationView zero = render(eval(List.of(has("Java", 0)), List.of(has("Git", 2)), List.of(), List.of()), 100);
        assertThat(zero.text()).isEqualTo("Ada Lovelace has the required skill: Java (0 years). "
                + "Nice-to-have skills: has Git (2 years).");

        ExplanationView niceOne = render(eval(List.of(), List.of(has("Docker")), List.of(), List.of()), 100);
        assertThat(niceOne.headline()).isEqualTo("Strong match: 1 of 1 nice-to-have skill");
        assertThat(niceOne.text()).isEqualTo("Ada Lovelace was scored on nice-to-have skills only. "
                + "Nice-to-have skills: has Docker.");
    }

    @Test
    void partialRequiredAndAllNiceMissing() {
        ExplanationView v = render(eval(List.of(has("Java", 5)), List.of(), List.of(miss("SQL")),
                List.of(miss("Docker"), miss("Kubernetes"))), 40);
        assertThat(v.headline()).isEqualTo("Weak match: 1 of 2 required skills");
        assertThat(v.text()).isEqualTo("Ada Lovelace has 1 of 2 required skills: Java (5 years); missing: SQL. "
                + "Nice-to-have skills: missing Docker, Kubernetes.");
        assertThat(v.gaps()).containsExactly("SQL (required)", "Docker (nice-to-have)", "Kubernetes (nice-to-have)");
    }

    @Test
    void bands() {
        Map<Integer, String> expected = Map.of(0, "No skill overlap", 1, "Weak match", 49, "Weak match",
                50, "Partial match", 79, "Partial match", 80, "Strong match", 100, "Strong match");
        MatchEvaluation e = ada();
        expected.forEach((pct, band) ->
                assertThat(render(e, pct).headline()).as("%d%%", pct).startsWith(band + ": "));
    }

    @Test
    void strengthsAndGapsAreCappedAtFive() {
        MatchEvaluation big = eval(List.of(has("A"), has("B"), has("C"), has("D")), List.of(has("E"), has("F"), has("G")),
                List.of(miss("H"), miss("I"), miss("J"), miss("K")), List.of(miss("L"), miss("M")));
        ExplanationView v = render(big, 50);
        assertThat(v.strengths()).containsExactly("A", "B", "C", "D", "E");
        assertThat(v.gaps()).containsExactly("H (required)", "I (required)", "J (required)", "K (required)",
                "L (nice-to-have)");
    }

    @Test
    void notesForEveryReasonAndStalePrefix() {
        assertThat(ExplanationReason.AI_DISABLED.note(5))
                .isEqualTo("AI explanations are turned off, so this summary was built from the skill breakdown.");
        assertThat(ExplanationReason.NOT_IN_TOP_N.note(5)).isEqualTo("AI explanations are generated for the top 5 "
                + "matches only, so this summary was built from the skill breakdown.");
        assertThat(ExplanationReason.NOT_IN_TOP_N.note(3)).contains("top 3 matches");
        assertThat(ExplanationReason.GENERATING.note(5)).isEqualTo("An AI explanation is being written. Reload in a "
                + "few seconds; until then this summary was built from the skill breakdown.");
        assertThat(ExplanationReason.AI_BUSY.note(5)).isEqualTo("The AI service is busy right now, so this summary "
                + "was built from the skill breakdown. Reload later for an AI explanation.");
        assertThat(ExplanationReason.PROVIDER_UNAVAILABLE.note(5)).isEqualTo("The AI explanation service is "
                + "unavailable right now, so this summary was built from the skill breakdown.");
        assertThat(ExplanationReason.GENERATION_FAILED.note(5)).isEqualTo("An AI explanation couldn't be produced "
                + "for this match, so this summary was built from the skill breakdown.");

        ExplanationView stale = ExplanationFallbackRenderer.render("Ada", 83, ada(), ExplanationReason.NOT_IN_TOP_N,
                true, 5);
        assertThat(stale.note()).isEqualTo("The previous AI explanation is out of date because the candidate or job "
                + "changed. AI explanations are generated for the top 5 matches only, so this summary was built "
                + "from the skill breakdown.");
    }

    @Test
    void blankNameFallsBackAndRenderIsDeterministic() {
        ExplanationView v = ExplanationFallbackRenderer.render(" ", 83, ada(), ExplanationReason.AI_DISABLED, false, 5);
        assertThat(v.text()).startsWith("The candidate has all 2 required skills");
        assertThat(render(ada(), 83)).isEqualTo(render(ada(), 83));
    }
}
