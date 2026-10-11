package com.talentmatch.feed.skills;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

/** Spec §9.1 item 8, §4.6: sentence and heading rules for nice-to-have. */
class RequirementHeuristicTest {

    private static boolean nice(String text, String word) {
        int at = text.indexOf(word);
        assertThat(at).as("'%s' in text", word).isNotNegative();
        return RequirementHeuristic.niceToHave(text, at);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Kubernetes is advantageous.",
            "Kubernetes would be advantageous.",
            "Kubernetes will be an advantage.",
            "Kubernetes is a big advantage.",
            "Kubernetes is a plus.",
            "Kubernetes is nice to have.",
            "Kubernetes is nice-to-have.",
            "Bonus: Kubernetes.",
            "Kubernetes preferred.",
            "Kubernetes is desirable.",
            "Kubernetes would be great."})
    void niceWordsInTheSentence(String text) {
        assertThat(nice(text, "Kubernetes")).as(text).isTrue();
    }

    @Test
    void otherSentencesDoNotLeak() {
        String text = "You must know Java. Kubernetes is advantageous! SQL is essential? Docker too.";
        assertThat(nice(text, "Java")).isFalse();
        assertThat(nice(text, "Kubernetes")).isTrue();
        assertThat(nice(text, "SQL")).isFalse();
        assertThat(nice(text, "Docker")).isFalse();
        assertThat(nice("Java\nKubernetes is a plus", "Java")).as("a line break ends the sentence").isFalse();
    }

    @Test
    void noMarkersMeansRequired() {
        assertThat(nice("Strong Java skills.", "Java")).isFalse();
        assertThat(nice("Java", "Java")).isFalse();
    }

    @Test
    void nearestHeadingDecides() {
        String text = """
                Requirements:
                - Java
                - SQL

                Nice to have:
                - Kubernetes
                - Docker

                What you'll need
                - Git
                """;
        assertThat(nice(text, "Java")).isFalse();
        assertThat(nice(text, "SQL")).isFalse();
        assertThat(nice(text, "Kubernetes")).isTrue();
        assertThat(nice(text, "Docker")).isTrue();
        assertThat(nice(text, "Git")).as("a required heading ends the nice section").isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Nice to have:", "Bonus points", "Preferred qualifications:", "Advantageous",
            "Desirable skills:", "Would be great if you have:", "NICE TO HAVE"})
    void niceHeadings(String heading) {
        assertThat(nice(heading + "\n- Kubernetes\n- Helm", "Helm")).as(heading).isTrue();
    }

    @Test
    void bulletLinesAreNeverHeadings() {
        assertThat(RequirementHeuristic.isHeading("- Nice to have")).isFalse();
        assertThat(RequirementHeuristic.isHeading("* Bonus")).isFalse();
        assertThat(RequirementHeuristic.isHeading("• Preferred")).isFalse();
        // a nice-to-have bullet does not turn the following bullets into nice-to-haves
        String text = "Requirements:\n- Docker is a plus\n- Java\n";
        assertThat(nice(text, "Docker")).isTrue();
        assertThat(nice(text, "Java")).isFalse();
    }

    @Test
    void proseLinesAreNotHeadings() {
        assertThat(RequirementHeuristic.isHeading("We pay an annual bonus.")).isFalse();
        assertThat(RequirementHeuristic.isHeading("x".repeat(81))).isFalse();
        assertThat(RequirementHeuristic.isHeading("")).isFalse();
        assertThat(RequirementHeuristic.isHeading("Nice to have:")).isTrue();
        assertThat(RequirementHeuristic.isHeading("Requirements")).isTrue();
        String text = "We pay an annual bonus.\nJava and SQL.";
        assertThat(nice(text, "Java")).isFalse();
    }

    /**
     * Spec §4.6: a mention under a nice-to-have heading is nice-to-have. Plain (unbulleted) list lines
     * under that heading must not be mistaken for a new "required" heading just because they contain
     * a word like "experience" or "skills".
     */
    @Test
    void unbulletedListLinesUnderANiceHeadingStayNice() {
        String text = "Nice to have:\nExperience with Docker\nKubernetes knowledge\n";
        assertThat(nice(text, "Docker")).isTrue();
        assertThat(nice(text, "Kubernetes")).isTrue();
    }

    @Test
    void outOfRangeOffsetsAreRequired() {
        assertThat(RequirementHeuristic.niceToHave(null, 0)).isFalse();
        assertThat(RequirementHeuristic.niceToHave("Java", -1)).isFalse();
        assertThat(RequirementHeuristic.niceToHave("Java", 5)).isFalse();
        assertThat(RequirementHeuristic.niceToHave("Java is a plus", 14)).isTrue();
    }

    @Test
    void sentenceBounds() {
        String text = "One. Two three! Four?\nFive";
        int two = text.indexOf("three");
        assertThat(text.substring(RequirementHeuristic.sentenceStart(text, two),
                RequirementHeuristic.sentenceEnd(text, two))).isEqualTo("Two three!");
        int five = text.indexOf("Five");
        assertThat(text.substring(RequirementHeuristic.sentenceStart(text, five),
                RequirementHeuristic.sentenceEnd(text, five))).isEqualTo("Five");
        // "Node.js" does not end a sentence (no whitespace after the dot)
        String js = "Use Node.js and Docker is a plus.";
        assertThat(RequirementHeuristic.sentenceStart(js, js.indexOf("Docker"))).isZero();
    }
}
