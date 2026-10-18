package com.talentmatch.ai;

import static com.talentmatch.ai.AiFixtures.ada;
import static com.talentmatch.ai.AiFixtures.eval;
import static com.talentmatch.ai.AiFixtures.has;
import static com.talentmatch.ai.AiFixtures.job;
import static com.talentmatch.ai.AiFixtures.match;
import static com.talentmatch.ai.AiFixtures.miss;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.talentmatch.domain.scoring.MatchEvaluation;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Spec §10 unit 2: staleness hash. */
class ExplanationInputHasherTest {

    private final ExplanationPromptBuilder builder = new ExplanationPromptBuilder(200);

    private String hashOf(JobContext j, MatchContext m) {
        return ExplanationInputHasher.hash(builder.build(j, m));
    }

    @Test
    void deterministicLowercaseSha256OfSystemSeparatorAndUserMessage() throws Exception {
        String h = ExplanationInputHasher.hash("hello");
        assertThat(h).matches("^[0-9a-f]{64}$").isEqualTo(ExplanationInputHasher.hash("hello"));
        byte[] expected = MessageDigest.getInstance("SHA-256")
                .digest((ExplanationPrompts.SYSTEM + "\n\u001e\n" + "hello").getBytes(StandardCharsets.UTF_8));
        assertThat(h).isEqualTo(HexFormat.of().formatHex(expected));
    }

    @Test
    void changesWhenAnythingTheModelSeesChanges() {
        String base = hashOf(job("Build APIs."), match(1, "Ada Lovelace", "Engineer.", ada(), null));

        assertThat(hashOf(job("Build APIs."), match(1, "Ada Lovelace", "Engineer!", ada(), null)))
                .as("summary").isNotEqualTo(base);
        assertThat(hashOf(job("Build APIs!"), match(1, "Ada Lovelace", "Engineer.", ada(), null)))
                .as("description").isNotEqualTo(base);
        assertThat(hashOf(job("Build APIs."), match(1, "Ada Byron", "Engineer.", ada(), null)))
                .as("name").isNotEqualTo(base);
        MatchEvaluation fewer = eval(List.of(has("Java", 5)), List.of(has("Docker")), List.of(miss("SQL")),
                List.of(miss("Kubernetes")));
        assertThat(hashOf(job("Build APIs."), match(1, "Ada Lovelace", "Engineer.", fewer, null)))
                .as("skills and score").isNotEqualTo(base);
        MatchEvaluation years = eval(List.of(has("Java", 6), has("SQL")), List.of(has("Docker")), List.of(),
                List.of(miss("Kubernetes")));
        assertThat(hashOf(job("Build APIs."), match(1, "Ada Lovelace", "Engineer.", years, null)))
                .as("years").isNotEqualTo(base);
        MatchContext m = match(1, "Ada Lovelace", "Engineer.", ada(), null);
        MatchContext otherPercent = new MatchContext(1, m.candidateId(), m.candidateName(), m.candidateSummary(),
                m.candidateUpdatedAt(), m.storedScore(), 84, m.evaluation(), null);
        assertThat(hashOf(job("Build APIs."), otherPercent)).as("score percent").isNotEqualTo(base);

        String prompt = builder.build(job("Build APIs."), m);
        assertThat(ExplanationInputHasher.hash(ExplanationPrompts.SYSTEM + " ", prompt))
                .as("system prompt").isNotEqualTo(ExplanationInputHasher.hash(prompt));
    }

    @Test
    void unchangedForThingsTheModelDoesNotSee() {
        String prefix = "word ".repeat(60); // 300 chars, limit is 200
        MatchContext a = match(1, "Ada", prefix + "tail one", ada(), null);
        MatchContext b = match(1, "Ada", prefix + "a completely different tail", ada(), null);
        assertThat(hashOf(job("d"), a)).as("beyond the truncation point").isEqualTo(hashOf(job("d"), b));

        // rank, stored explanation, timestamps and raw score are not part of the prompt
        MatchContext moved = new MatchContext(4, a.candidateId(), a.candidateName(), a.candidateSummary(),
                AiFixtures.T0, 0.123, a.scorePercent(), a.evaluation(), null);
        assertThat(hashOf(job("d"), moved)).isEqualTo(hashOf(job("d"), a));
    }
    // ------------------------------------------------------------------ Phase 6 step 2: audience

    /** Computed with the Phase 3 hasher (commit 4490db7); a change here invalidates every stored V3 explanation. */
    private static final String GOLDEN_HELLO = "ee1406434f24039e41856cacca6bc9c724c20a768e76e487065bceab1aa5190e";
    private static final String GOLDEN_FIXTURE = "49b33db3a6a4121c59e1b18a335fc997e62f5164d3b47ad814ecfbe9112bb210";

    private static String fixturePrompt() {
        return new ExplanationPromptBuilder(2000).build(job("Build APIs."), match(1, "Ada Lovelace"));
    }

    @Test
    void recruiterHashMatchesThePhase3GoldenValue() {
        assertThat(ExplanationInputHasher.hash("hello")).isEqualTo(GOLDEN_HELLO);
        assertThat(ExplanationInputHasher.hash(Audience.RECRUITER, "hello")).isEqualTo(GOLDEN_HELLO);
        String prompt = fixturePrompt();
        assertThat(prompt).hasSize(612);
        assertThat(ExplanationInputHasher.hash(prompt)).isEqualTo(GOLDEN_FIXTURE);
        assertThat(ExplanationInputHasher.hash(Audience.RECRUITER, prompt)).isEqualTo(GOLDEN_FIXTURE);
    }

    @Test
    void recruiterAudienceEqualsTheSingleArgumentHashForAnyMessage() {
        for (String msg : List.of("", "x", "\u001e", "line\nbreak", "\u00e9\u4e2d\ud83d\ude00", fixturePrompt())) {
            assertThat(ExplanationInputHasher.hash(Audience.RECRUITER, msg)).isEqualTo(ExplanationInputHasher.hash(msg));
        }
    }

    @Test
    void seekerFailsFastUntilStep3AndNullsAreRejected() {
        assertThatIllegalStateException().isThrownBy(() -> ExplanationInputHasher.hash(Audience.SEEKER, "hello"))
                .withMessage("SEEKER is wired in step 3");
        assertThatNullPointerException().isThrownBy(() -> ExplanationInputHasher.hash((Audience) null, "hello"));
        assertThatNullPointerException().isThrownBy(() -> ExplanationInputHasher.hash(Audience.RECRUITER, null));
    }
}
