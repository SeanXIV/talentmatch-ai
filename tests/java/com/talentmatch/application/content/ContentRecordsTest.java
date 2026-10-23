package com.talentmatch.application.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Finding codes, severities and the content records' list handling. */
class ContentRecordsTest {

    @Test
    void theFifteenFindingCodesAreStoredByName() {
        assertThat(FindingCode.values()).extracting(Enum::name).containsExactly("UNKNOWN_REF", "DUPLICATE_REF",
                "SKILL_NOT_IN_PROFILE", "ROLE_DEFAULTED", "SKILLS_DEFAULTED", "REVERTED", "PROFILE_TRUNCATED",
                "NOTICE_PERIOD_UNKNOWN", "HEAVILY_REWORDED", "UNKNOWN_NAME", "LENGTH", "NUMBER_NOT_IN_PROFILE",
                "YEAR_NOT_IN_PROFILE", "CONTACT_DETAILS", "PROFILE_CHANGED");
        assertThat(Severity.values()).extracting(Enum::name).containsExactly("BLOCKING", "WARNING", "INFO");
        assertThat(DocumentKind.values()).extracting(Enum::name).containsExactly("CV", "COVER_LETTER");
        assertThat(DocumentKind.COVER_LETTER.label()).isEqualTo("cover letter");
        assertThat(DocumentSource.values()).extracting(Enum::name).containsExactly("AI", "EDIT");
    }

    @Test
    void findingFactoriesAndSeverityChange() {
        Finding info = Finding.info(FindingCode.REVERTED, "summary", "40%", "m");
        assertThat(info.severity()).isEqualTo(Severity.INFO);
        assertThat(Finding.warning(FindingCode.LENGTH, null, null, "m").severity()).isEqualTo(Severity.WARNING);
        Finding blocking = Finding.blocking(FindingCode.NUMBER_NOT_IN_PROFILE, "paragraphs[0]", "30", "m");
        assertThat(blocking.severity()).isEqualTo(Severity.BLOCKING);
        Finding warning = blocking.withSeverity(Severity.WARNING);
        assertThat(warning).isEqualTo(new Finding(Severity.WARNING, FindingCode.NUMBER_NOT_IN_PROFILE, "paragraphs[0]",
                "30", "m"));
        assertThat(blocking.withSeverity(Severity.BLOCKING)).isSameAs(blocking);
    }

    @Test
    void listsAreCopiedAndNullBecomesEmpty() {
        List<String> skills = new ArrayList<>(List.of("Java"));
        TailoredCvContent cv = new TailoredCvContent(null, null, null, skills, null, null, null, null, null);
        skills.add("Go");
        assertThat(cv.skills()).containsExactly("Java");
        assertThat(cv.experience()).isEmpty();
        assertThat(cv.languages()).isEmpty();
        assertThatThrownBy(() -> cv.skills().add("x")).isInstanceOf(UnsupportedOperationException.class);
        CoverLetterContent letter = new CoverLetterContent(null, null, null, null, null, Arrays.asList("a", null),
                null, null);
        assertThat(letter.paragraphs()).as("null elements are kept for the validators").containsExactly("a", null);
        assertThat(new Header(null, null, null, null, null).links()).isEmpty();
        assertThat(new ExperienceItem("E1", null, null, null, null, null, null, null, null).highlights()).isEmpty();
        assertThat(new ProjectItem("P1", null, null, null, null, null).technologies()).isEmpty();
    }
}
