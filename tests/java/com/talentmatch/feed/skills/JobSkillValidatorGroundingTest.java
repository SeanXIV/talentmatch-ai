package com.talentmatch.feed.skills;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Step 10 deviation "Grounding: the name the model wrote, or the vocabulary skill it resolves to, must
 * appear in the evidence", plus the evidence-in-posting rule on the edges.
 */
class JobSkillValidatorGroundingTest {

    private static final UUID POSTGRES = UUID.randomUUID();
    private static final UUID K8S = UUID.randomUUID();
    private static final UUID JAVA = UUID.randomUUID();

    private static final String POSTING = "Backend Engineer\n\nOur data lives in PostgreSQL.\n"
            + "We deploy to Kubernetes.\nYou will write JavaScript and some Java.";

    private final SkillDictionary.Snapshot vocabulary = JobSkillValidatorTest.snapshot(
            Map.of(POSTGRES, "PostgreSQL", K8S, "Kubernetes", JAVA, "Java"),
            Map.of("postgres", POSTGRES, "k8s", K8S));

    private static String answer(String name, String evidence) {
        return "{\"skills\":[{\"name\":\"" + name + "\",\"requirement\":\"REQUIRED\",\"evidence\":\"" + evidence
                + "\"}]}";
    }

    private JobSkillValidator.Result validate(String name, String evidence) throws Exception {
        return JobSkillValidator.validate(answer(name, evidence), POSTING, vocabulary);
    }

    @Test
    void aliasWrittenByTheModelIsGroundedByTheCanonicalNameInTheEvidence() throws Exception {
        JobSkillValidator.Result r = validate("postgres", "data lives in PostgreSQL");
        assertThat(r.accepted()).containsExactly(new SkillRequirement(POSTGRES, "PostgreSQL", true));
        assertThat(r.ungrounded()).isZero();
        assertThat(validate("k8s", "deploy to Kubernetes").accepted())
                .containsExactly(new SkillRequirement(K8S, "Kubernetes", true));
    }

    @Test
    void neitherTheWrittenNameNorItsSkillInTheEvidenceIsUngrounded() throws Exception {
        JobSkillValidator.Result r = validate("k8s", "Our data lives in PostgreSQL");
        assertThat(r.accepted()).isEmpty();
        assertThat(r.suggestions()).isEmpty();
        assertThat(r.ungrounded()).isEqualTo(1);
    }

    @Test
    void anUnknownNameMustItselfBeInTheEvidence() throws Exception {
        JobSkillValidator.Result r = validate("Terraform", "We deploy to Kubernetes");
        assertThat(r.suggestions()).isEmpty();
        assertThat(r.ungrounded()).isEqualTo(1);
    }

    @Test
    void javaIsNotGroundedByJavaScript() throws Exception {
        assertThat(validate("Java", "write JavaScript").ungrounded()).isEqualTo(1);
        assertThat(validate("Java", "JavaScript and some Java").accepted()).hasSize(1);
    }

    @Test
    void evidenceSpanningALineBreakInThePostingIsGrounded() throws Exception {
        JobSkillValidator.Result r = validate("Kubernetes", "PostgreSQL. We deploy to Kubernetes");
        assertThat(r.accepted()).containsExactly(new SkillRequirement(K8S, "Kubernetes", true));
    }

    @Test
    void evidenceThatParaphrasesThePostingIsUngrounded() throws Exception {
        assertThat(validate("Kubernetes", "deploys on Kubernetes").ungrounded()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ H1: grounding against the posting span

    private static final UUID RUST = UUID.randomUUID();
    private static final UUID GO = UUID.randomUUID();
    private static final UUID PG = UUID.randomUUID();
    private static final UUID NODE = UUID.randomUUID();

    /** A snapshot whose matcher holds the real name and alias patterns (as SkillDictionary builds it). */
    private static SkillDictionary.Snapshot realVocabulary() {
        List<DictionarySkillMatcher.Term> terms = List.of(
                new DictionarySkillMatcher.Term(RUST, "Rust", "Rust"),
                new DictionarySkillMatcher.Term(GO, "Go", "Go"),
                new DictionarySkillMatcher.Term(GO, "Go", "Golang"),
                new DictionarySkillMatcher.Term(PG, "PostgreSQL", "PostgreSQL"),
                new DictionarySkillMatcher.Term(PG, "PostgreSQL", "Postgres"),
                new DictionarySkillMatcher.Term(NODE, "Node.js", "Node.js"));
        Map<UUID, String> names = Map.of(RUST, "Rust", GO, "Go", PG, "PostgreSQL", NODE, "Node.js");
        Map<String, UUID> byKey = new HashMap<>();
        terms.forEach(t -> byKey.put(t.text().toLowerCase(Locale.ROOT), t.skillId()));
        return new SkillDictionary.Snapshot("fp", names, Map.copyOf(byKey),
                new DictionarySkillMatcher(terms, DictionarySkillMatcher.DEFAULT_AMBIGUOUS_NAMES));
    }

    private static JobSkillValidator.Result real(String posting, String name, String evidence) throws Exception {
        return JobSkillValidator.validate(answer(name, evidence), posting, realVocabulary());
    }

    @Test
    void rustInsideTrustIsUngrounded() throws Exception {
        JobSkillValidator.Result r = real("We value trust", "Rust", "rust");
        assertThat(r.accepted()).isEmpty();
        assertThat(r.suggestions()).isEmpty();
        assertThat(r.ungrounded()).isEqualTo(1);
    }

    @Test
    void rustAtTheStartOfRustenburgIsUngrounded() throws Exception {
        JobSkillValidator.Result r = real("Based in Rustenburg", "Rust", "Rust");
        assertThat(r.accepted()).isEmpty();
        assertThat(r.ungrounded()).isEqualTo(1);
    }

    @Test
    void lowerCaseGoInReadyToGoIsUngrounded() throws Exception {
        JobSkillValidator.Result r = real("You are ready to go", "go", "go");
        assertThat(r.accepted()).as("short names match case-sensitively in the posting").isEmpty();
        assertThat(r.suggestions()).isEmpty();
        assertThat(r.ungrounded()).isEqualTo(1);
        // the model's casing of a short name is never trusted either
        assertThat(real("You are ready to go", "Go", "go").ungrounded()).isEqualTo(1);
        assertThat(real("You are ready to go", "GO", "ready to go").ungrounded()).isEqualTo(1);
    }

    @Test
    void goNamedInThePostingIsAcceptedAsGo() throws Exception {
        JobSkillValidator.Result r = real("Experience with Go", "go", "go");
        assertThat(r.accepted()).containsExactly(new SkillRequirement(GO, "Go", true));
        assertThat(r.ungrounded()).isZero();
        // the span comes from the posting: the model's miscased copy is located case-insensitively
        assertThat(real("Experience with Go", "Go", "experience with GO").accepted())
                .containsExactly(new SkillRequirement(GO, "Go", true));
    }

    @Test
    void anAliasInThePostingGroundsTheSkillWithARealMatcher() throws Exception {
        JobSkillValidator.Result r = real("Our data lives in Postgres.", "PostgreSQL", "lives in Postgres");
        assertThat(r.accepted()).containsExactly(new SkillRequirement(PG, "PostgreSQL", true));
        assertThat(real("We build services in Golang", "Go", "services in Golang").accepted())
                .containsExactly(new SkillRequirement(GO, "Go", true));
    }

    @Test
    void mentionsSkillUsesTheStoredNameAndAliasPatterns() {
        DictionarySkillMatcher m = realVocabulary().matcher();
        assertThat(m.mentionsSkill("we use Postgres daily", PG)).isTrue();
        assertThat(m.mentionsSkill("we use POSTGRESQL daily", PG)).as("long terms ignore case").isTrue();
        assertThat(m.mentionsSkill("Experience with Go", GO)).isTrue();
        assertThat(m.mentionsSkill("ready to go", GO)).as("short terms are case-sensitive").isFalse();
        assertThat(m.mentionsSkill("We value trust", RUST)).isFalse();
        assertThat(m.mentionsSkill("Based in Rustenburg", RUST)).isFalse();
        assertThat(m.mentionsSkill("Node.js services", NODE)).isTrue();
        assertThat(m.mentionsSkill("we use Postgres", UUID.randomUUID())).as("unknown skill").isFalse();
        assertThat(m.mentionsSkill(null, PG)).isFalse();
        assertThat(m.mentionsSkill("Postgres", null)).isFalse();
    }

    @Test
    void locateReturnsTheSpanAsWrittenInThePostingAcrossLineBreaks() {
        String posting = "Our data\nlives in   PostgreSQL.\nWe deploy to Kubernetes.";
        assertThat(DictionarySkillMatcher.locate(posting, "data lives in postgresql"))
                .contains("data\nlives in   PostgreSQL");
        assertThat(DictionarySkillMatcher.locate(posting, "PostgreSQL. We deploy"))
                .contains("PostgreSQL.\nWe deploy");
        assertThat(DictionarySkillMatcher.locate(posting, "  Kubernetes.  ")).contains("Kubernetes.");
    }

    @Test
    void locateKeepsPunctuatedTokensAndTokenBoundaries() {
        assertThat(DictionarySkillMatcher.locate("Write C++ and Node.js, daily.", "c++ and node.js"))
                .contains("C++ and Node.js");
        assertThat(DictionarySkillMatcher.locate("You will write JavaScript.", "Java")).isEmpty();
        assertThat(DictionarySkillMatcher.locate("You will write JavaScript.", "Script")).isEmpty();
        assertThat(DictionarySkillMatcher.locate("We value trust", "rust")).isEmpty();
        assertThat(DictionarySkillMatcher.locate("Based in Rustenburg", "Rust")).isEmpty();
        assertThat(DictionarySkillMatcher.locate("Regex chars (.*) are literal", "(.*)")).contains("(.*)");
        assertThat(DictionarySkillMatcher.locate("anything", "a.*")).isEmpty();
        assertThat(DictionarySkillMatcher.locate("text", "  ")).isEqualTo(Optional.empty());
        assertThat(DictionarySkillMatcher.locate(null, "x")).isEmpty();
        assertThat(DictionarySkillMatcher.locate("x", null)).isEmpty();
    }
}
