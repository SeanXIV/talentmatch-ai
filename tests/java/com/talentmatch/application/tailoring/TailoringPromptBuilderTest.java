package com.talentmatch.application.tailoring;

import static com.talentmatch.application.tailoring.TailoringFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.talentmatch.application.content.DocumentKind;
import com.talentmatch.application.tailoring.TailoringPromptBuilder.TailoringPrompt;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

/** §9.1.3: the exact user message, the letter system prompt's min/max, the input hash. */
class TailoringPromptBuilderTest {

    private final TailoringPromptBuilder builder = new TailoringPromptBuilder(ApplicationProperties.defaults());

    private static final String JOB_BLOCK = "JOB\n"
            + "Title: Senior Java Developer\n"
            + "Company: Initech\n"
            + "Required skills: Java, Kubernetes\n"
            + "Nice-to-have skills: Docker\n"
            + "<job_description>\n"
            + "We build payments with Java, Kubernetes and Docker. Ask Peter Gibbons about the team.\n"
            + "</job_description>\n";

    @Test
    void theExactCvUserMessage() {
        ProfileFacts f = facts();
        TailoringPrompt p = builder.buildCv(job(), f);
        assertThat(p.kind()).isEqualTo(DocumentKind.CV);
        assertThat(p.system()).isEqualTo(TailoringPrompts.CV_SYSTEM);
        assertThat(p.user()).isEqualTo("Tailor the CV for this job.\n\n" + JOB_BLOCK + "\n" + f.promptBlock());
        assertThat(p.user()).doesNotEndWith("\n").doesNotContain(EMAIL, PHONE, NAME, GITHUB);
    }

    @Test
    void theExactCoverLetterUserMessage() {
        ProfileFacts f = facts();
        TailoringPrompt p = builder.build(DocumentKind.COVER_LETTER, job(), f);
        assertThat(p.kind()).isEqualTo(DocumentKind.COVER_LETTER);
        assertThat(p.user()).isEqualTo("Write the cover letter body for this job.\n\n" + JOB_BLOCK + "\n"
                + f.promptBlock());
        assertThat(p.system()).isEqualTo(TailoringPrompts.coverLetterSystem(180, 350));
    }

    @Test
    void theCoverLetterSystemPromptCarriesMinAndMax() {
        assertThat(TailoringPrompts.coverLetterSystem(180, 350)).contains("180 to 350 words")
                .doesNotContain("{min}", "{max}");
        assertThat(new TailoringPromptBuilder(4000, 100, 200).buildCoverLetter(job(), facts()).system())
                .contains("100 to 200 words");
        assertThat(TailoringPrompts.CV_SYSTEM).doesNotContain("{min}").endsWith("Respond with the JSON object only.");
    }

    @Test
    void theInputHashIsSha256OfSystemSeparatorUser() throws Exception {
        TailoringPrompt p = builder.buildCv(job(), facts());
        MessageDigest d = MessageDigest.getInstance("SHA-256");
        String expected = HexFormat.of().formatHex(d.digest((p.system() + "\n\u001e\n" + p.user())
                .getBytes(StandardCharsets.UTF_8)));
        assertThat(p.inputHash()).isEqualTo(expected).matches("[0-9a-f]{64}");
        assertThat(TailoringPromptBuilder.inputHash("s", "u")).isEqualTo(HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest("s\n\u001e\nu".getBytes(StandardCharsets.UTF_8))));
        assertThat(TailoringPrompts.HASH_SEPARATOR).isEqualTo("\n\u001e\n");
        assertThat(builder.buildCv(job(), facts()).inputHash()).as("deterministic").isEqualTo(p.inputHash());
        assertThat(builder.buildCoverLetter(job(), facts()).inputHash()).isNotEqualTo(p.inputHash());
    }

    @Test
    void emptyJobFieldsPrintNoneMarkers() {
        JobFacts empty = new JobFacts(" ", null, "  \n ", List.of(), List.of(" "));
        String user = builder.buildCv(empty, facts()).user();
        assertThat(user).contains("Title: (none)\nCompany: (none)\nRequired skills: none\nNice-to-have skills: none\n"
                + "<job_description>\n(none)\n</job_description>\n\nPROFILE FACTS");
    }

    @Test
    void theDescriptionIsSanitisedKeepsLineBreaksAndIsCut() {
        JobFacts j = new JobFacts("Dev <b>{{x}}</b>\nline", "A & B", "Line one\r\n\r\n\r\n\r\nLine <two> {{y}}\tz\u0007",
                List.of("C++ & <Go>"), List.of());
        String user = builder.buildCv(j, facts()).user();
        assertThat(user).contains("Title: Dev &lt;b&gt;{ {x} }&lt;/b&gt; line\n", "Company: A &amp; B\n",
                "Required skills: C++ &amp; &lt;Go&gt;\n",
                "<job_description>\nLine one\n\nLine &lt;two&gt; { {y} } z\n</job_description>");

        String longDescription = "word ".repeat(300);                    // 1500 characters
        String cut = new TailoringPromptBuilder(500, 180, 350).buildCv(new JobFacts("T", "C", longDescription,
                List.of(), List.of()), facts()).user();
        String description = cut.substring(cut.indexOf("<job_description>\n") + 18, cut.indexOf("\n</job_description>"));
        assertThat(description).endsWith("…").hasSizeLessThanOrEqualTo(501);
        assertThat(description).doesNotContain("  ");
    }

    @Test
    void invalidArguments() {
        assertThatThrownBy(() -> new TailoringPromptBuilder(0, 180, 350)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.buildCv(null, facts())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> builder.buildCv(job(), null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> builder.build(null, job(), facts())).isInstanceOf(NullPointerException.class);
    }
}
