package com.talentmatch.ai;

import static com.talentmatch.ai.AiFixtures.ada;
import static com.talentmatch.ai.AiFixtures.eval;
import static com.talentmatch.ai.AiFixtures.has;
import static com.talentmatch.ai.AiFixtures.miss;
import static org.assertj.core.api.Assertions.assertThat;

import com.talentmatch.domain.scoring.MatchEvaluation;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Spec §2 MatchExplanationValidator / §10 unit 3. */
class MatchExplanationValidatorTest {

    private static final String OK_TEXT = "Ada covers both required skills, Java and SQL, but lacks Kubernetes.";

    private final MatchExplanationValidator validator = new MatchExplanationValidator();

    private Optional<MatchExplanation> check(String headline, String text, List<String> strengths, List<String> gaps) {
        return validator.normalize(new MatchExplanation(headline, text, strengths, gaps), ada());
    }

    @Test
    void validRecordPassesUnchanged() {
        MatchExplanation in = AiFixtures.validExplanation();
        assertThat(validator.normalize(in, ada())).contains(in);
    }

    @Test
    void normalizesWhitespaceBulletsBlanksDuplicatesAndNullLists() {
        MatchExplanation out = check("  Strong\n   fit  ", " Ada\tcovers\n\nJava and SQL nicely.  ",
                Arrays.asList("- Java", "•  SQL", "* Docker", "  ", null, "java", "--  sql  "), null).orElseThrow();
        assertThat(out.headline()).isEqualTo("Strong fit");
        assertThat(out.explanation()).isEqualTo("Ada covers Java and SQL nicely.");
        assertThat(out.strengths()).containsExactly("Java", "SQL", "Docker");
        assertThat(out.gaps()).isEmpty();

        MatchExplanation nulls = check("H", OK_TEXT, null, null).orElseThrow();
        assertThat(nulls.strengths()).isEmpty();
        assertThat(nulls.gaps()).isEmpty();
    }

    @Test
    void ungroundedItemsAreDroppedAndListsCappedAtThree() {
        MatchEvaluation four = eval(List.of(has("Java"), has("SQL"), has("Docker"), has("Git")), List.of(),
                List.of(miss("Go"), miss("Rust"), miss("Kotlin"), miss("Scala")), List.of());
        MatchExplanation out = validator.normalize(new MatchExplanation("H", OK_TEXT,
                List.of("Python expert", "Strong Java", "SQL", "Go skills", "Docker", "Git"),
                List.of("Java", "No Go", "Rust (required)", "Kotlin", "Scala")), four).orElseThrow();
        assertThat(out.strengths()).as("Python not matched, Go is missing not matched")
                .containsExactly("Strong Java", "SQL", "Docker");
        assertThat(out.gaps()).as("Java is matched, not missing").containsExactly("No Go", "Rust (required)", "Kotlin");

        // nice-to-have skills count too; gaps of an exact match are grounded case-insensitively
        MatchExplanation nice = check("H", OK_TEXT, List.of("docker experience"), List.of("KUBERNETES")).orElseThrow();
        assertThat(nice.strengths()).containsExactly("docker experience");
        assertThat(nice.gaps()).containsExactly("KUBERNETES");
        // a hallucinated skill is dropped, the record stays valid
        assertThat(check("H", OK_TEXT, List.of("Rust"), List.of("Java")).orElseThrow().strengths()).isEmpty();
    }

    @Test
    void lengthBoundaries() {
        assertThat(check("h".repeat(100), OK_TEXT, null, null)).isPresent();
        assertThat(check("h".repeat(101), OK_TEXT, null, null)).isEmpty();
        assertThat(check("H", "x".repeat(20), null, null)).isPresent();
        assertThat(check("H", "x".repeat(19), null, null)).isEmpty();
        assertThat(check("H", "x".repeat(700), null, null)).isPresent();
        assertThat(check("H", "x".repeat(701), null, null)).isEmpty();
        String item100 = "Java " + "x".repeat(95);
        assertThat(check("H", OK_TEXT, List.of(item100), null)).isPresent();
        assertThat(check("H", OK_TEXT, List.of(item100 + "x"), null)).as("kept item > 100").isEmpty();
        assertThat(check("H", OK_TEXT, null, List.of("Kubernetes " + "y".repeat(91)))).as("gap > 100").isEmpty();
        // an over-long item that is not grounded is dropped, not rejected
        assertThat(check("H", OK_TEXT, List.of("z".repeat(150)), null)).isPresent();
    }

    @Test
    void rejectsNullAndBlankFields() {
        assertThat(validator.normalize(null, ada())).isEmpty();
        assertThat(check(null, OK_TEXT, null, null)).isEmpty();
        assertThat(check("   ", OK_TEXT, null, null)).isEmpty();
        assertThat(check("H", null, null, null)).isEmpty();
        assertThat(check("H", " \n ", null, null)).isEmpty();
    }

    @Test
    void rejectsEmailsUrlsAndPromptEchoesInAnyKeptField() {
        assertThat(check("H", OK_TEXT + " Contact ada.l+x@example.co.uk.", null, null)).as("email").isEmpty();
        assertThat(check("Mail ada@example.com", OK_TEXT, null, null)).as("email headline").isEmpty();
        assertThat(check("H", OK_TEXT, List.of("Java ada@x.io"), null)).as("email item").isEmpty();
        assertThat(check("See https://evil.example", OK_TEXT, null, null)).as("https").isEmpty();
        assertThat(check("H", OK_TEXT + " http://x.y", null, null)).as("http").isEmpty();
        assertThat(check("H", OK_TEXT, null, List.of("Kubernetes HTTP://x"))).as("url in gap").isEmpty();
        assertThat(check("H", OK_TEXT + " <job_description>", null, null)).as("tag echo").isEmpty();
        assertThat(check("<candidate_summary", OK_TEXT, null, null)).as("tag echo").isEmpty();
        assertThat(check("H", "Per the MATCH FACTS section, Ada has Java.", null, null)).as("section echo").isEmpty();
        assertThat(check("H", OK_TEXT, List.of("SQL per MATCH FACTS"), null)).as("echo in item").isEmpty();
    }
}
