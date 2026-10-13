package com.talentmatch.feed.skills;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** §9.1 item 10: grounding, accepted vs suggestions, echo and format rejection. */
class JobSkillValidatorTest {

    private static final UUID DOCKER = UUID.randomUUID();
    private static final UUID JAVA = UUID.randomUUID();
    private static final UUID POSTGRES = UUID.randomUUID();
    private static final UUID GO = UUID.randomUUID();

    private static final String POSTING = """
            Senior Backend Engineer

            You will run services in Docker containers and write Java.
            Our data lives in Postgres.
            Nice to have: Terraform for infrastructure.
            Experience with Go is a bonus.""";

    private final SkillDictionary.Snapshot vocabulary = snapshot(
            Map.of(DOCKER, "Docker", JAVA, "Java", POSTGRES, "PostgreSQL", GO, "Go"),
            Map.of("postgres", POSTGRES));

    static SkillDictionary.Snapshot snapshot(Map<UUID, String> names, Map<String, UUID> aliases) {
        Map<String, UUID> byKey = new HashMap<>();
        aliases.forEach(byKey::put);
        names.forEach((id, n) -> byKey.put(n.toLowerCase(java.util.Locale.ROOT), id));
        return new SkillDictionary.Snapshot("fp", Map.copyOf(names), Map.copyOf(byKey),
                new DictionarySkillMatcher(List.of(), List.of()));
    }

    private static String item(String name, String requirement, String evidence) {
        return "{\"name\":\"" + name + "\",\"requirement\":\"" + requirement + "\",\"evidence\":\"" + evidence + "\"}";
    }

    private static String answer(String... items) {
        return "{\"skills\":[" + String.join(",", items) + "]}";
    }

    private JobSkillValidator.Result validate(String answer) throws Exception {
        return JobSkillValidator.validate(answer, POSTING, vocabulary);
    }

    @Test
    void knownGroundedIsAcceptedUnknownGroundedIsSuggestedUngroundedIsDropped() throws Exception {
        JobSkillValidator.Result r = validate(answer(
                item("Docker", "REQUIRED", "run services in Docker containers"),
                item("Terraform", "NICE_TO_HAVE", "Terraform for infrastructure"),
                item("Rust", "REQUIRED", "write Rust")));
        assertThat(r.accepted()).containsExactly(new SkillRequirement(DOCKER, "Docker", true));
        assertThat(r.suggestions()).containsExactly(
                new SkillSuggestion("Terraform", "NICE_TO_HAVE", "Terraform for infrastructure"));
        assertThat(r.ungrounded()).isEqualTo(1);
    }

    @Test
    void aliasResolvesToTheSkillNameAndEvidenceMayUseTheAlias() throws Exception {
        JobSkillValidator.Result r = validate(answer(item("postgres", "REQUIRED", "Our data lives in Postgres")));
        assertThat(r.accepted()).containsExactly(new SkillRequirement(POSTGRES, "PostgreSQL", true));
        // this snapshot's matcher is empty (no alias patterns), so only the stored name or the written name
        // can ground the item, and "PostgreSQL" is not in the span. With a real matcher the alias would
        // ground it (see JobSkillValidatorGroundingTest).
        JobSkillValidator.Result canonical = validate(answer(item("PostgreSQL", "REQUIRED", "lives in Postgres")));
        assertThat(canonical.accepted()).isEmpty();
        assertThat(canonical.ungrounded())
                .as("PostgreSQL isn't in the span and the empty matcher holds no alias pattern").isEqualTo(1);
    }

    @Test
    void evidenceMustBeInThePostingButWhitespaceAndCaseDontMatter() throws Exception {
        assertThat(validate(answer(item("Java", "REQUIRED", "WRITE   java"))).accepted()).hasSize(1);
        assertThat(validate(answer(item("Java", "REQUIRED", "five years of Java"))).ungrounded()).isEqualTo(1);
        assertThat(validate(answer(item("Java", "REQUIRED", ""))).ungrounded()).isEqualTo(1);
    }

    @Test
    void nameMustBeInTheEvidenceByTokenBoundariesAndShortNamesCaseSensitively() throws Exception {
        // "Java" inside "JavaScript"-like token: not a mention
        assertThat(validate(answer(item("Dock", "REQUIRED", "Docker containers"))).ungrounded()).isEqualTo(1);
        // "Go" is case-sensitive (3 characters or fewer)
        assertThat(validate(answer(item("go", "NICE_TO_HAVE", "Experience with Go"))).accepted())
                .as("go resolves to Go, whose name is in the evidence").hasSize(1);
        assertThat(validate(answer(item("GO", "REQUIRED", "Experience with Go"))).accepted()).hasSize(1);
    }

    @Test
    void niceToHaveAndAnythingElseIsRequired() throws Exception {
        JobSkillValidator.Result r = validate(answer(item("Go", "NICE_TO_HAVE", "Go is a bonus"),
                item("Java", "MANDATORY", "write Java")));
        assertThat(r.accepted()).containsExactly(new SkillRequirement(GO, "Go", false),
                new SkillRequirement(JAVA, "Java", true));
    }

    @Test
    void duplicatesBlankAndLongNamesAreDropped() throws Exception {
        JobSkillValidator.Result r = validate(answer(item("Docker", "REQUIRED", "Docker containers"),
                item("docker", "NICE_TO_HAVE", "in Docker"),
                item("Terraform", "REQUIRED", "Terraform"), item("terraform", "REQUIRED", "Terraform"),
                item("  ", "REQUIRED", "Docker"), item("x".repeat(101), "REQUIRED", "Docker")));
        assertThat(r.accepted()).hasSize(1);
        assertThat(r.accepted().get(0).required()).isTrue();
        assertThat(r.suggestions()).hasSize(1);
        assertThat(r.dropped()).isEqualTo(4);
    }

    @Test
    void onlyTheFirst40ItemsAndAtMost20Suggestions() throws Exception {
        StringBuilder posting = new StringBuilder();
        String[] items = IntStream.range(0, 45).mapToObj(i -> {
            posting.append("Tool").append(i).append(' ');
            return item("Tool" + i, "REQUIRED", "Tool" + i);
        }).toArray(String[]::new);
        JobSkillValidator.Result r = JobSkillValidator.validate(answer(items), posting.toString(), vocabulary);
        assertThat(r.suggestions()).hasSize(20);
        assertThat(r.dropped()).isEqualTo(25);                 // 5 past item 40, 20 past the suggestion cap
    }

    @Test
    void unusableAnswersAreRejected() {
        for (String bad : List.of("", "not json", "{\"skills\":{}}", "{\"other\":[]}", "[]",
                answer(item("Docker", "REQUIRED", "<job_posting> Docker")),
                answer(item("MATCH FACTS", "REQUIRED", "Docker")))) {
            assertThrows(JobSkillValidator.InvalidOutputException.class, () -> validate(bad), bad);
        }
    }

    @Test
    void rejectionMessageHoldsNoAnswerText() {
        JobSkillValidator.InvalidOutputException e = assertThrows(JobSkillValidator.InvalidOutputException.class,
                () -> validate("{\"skills\": [SECRET-ANSWER"));
        assertThat(e.getMessage()).doesNotContain("SECRET-ANSWER");
    }

    @Test
    void emptyListIsAValidAnswer() throws Exception {
        JobSkillValidator.Result r = validate("{\"skills\":[]}");
        assertThat(r.accepted()).isEmpty();
        assertThat(r.suggestions()).isEmpty();
    }
}
