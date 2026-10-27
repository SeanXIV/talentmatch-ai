package com.talentmatch.application.tailoring;

import static com.talentmatch.application.tailoring.TailoringFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.talentmatch.application.content.CoverLetterContent;
import com.talentmatch.application.content.Finding;
import com.talentmatch.application.content.FindingCode;
import com.talentmatch.application.content.TailoredCvContent;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Found by the Phase 7 step 5 ITs (§9.2.4 "a letter with '12 years' → BLOCKING"): the profile's numbers include
 * every ","/"."-separated part of a written number ({@code ProfileText.numbers}, carried over from the Phase 4 CV
 * check), so "12,000" in E1.H1 also allowed 12 and 000. A thousands group is not a number the owner wrote.
 * Fixed by {@code ProfileText.wholeNumbers}; these tests guard the fix.
 */
class NumberPartsGroundingTest {

    @Test
    void aThousandsSeparatedNumberIsOneProfileNumber() {
        ProfileFacts f = facts();
        assertThat(f.numbers("E1.H1")).contains("12000", "40").doesNotContain("12", "000");
        assertThat(f.numbersGlobal()).doesNotContain("12", "000");
    }

    @Test
    void aLetterClaimingTwelveYearsIsBlocking() {
        AssemblyResult<CoverLetterContent> r = CoverLetterAssembler.assemble(new CoverLetterDraft(List.of(
                        CoverLetterAssemblerTest.G0, "I bring 12 years of backend experience.",
                        CoverLetterAssemblerTest.G1, CoverLetterAssemblerTest.G2)), facts(), job(), check(),
                new CoverLetterAssembler.Options(TODAY, false, null, 20, 400));
        assertThat(r).isInstanceOf(AssemblyResult.Assembled.class);
        assertThat(((AssemblyResult.Assembled<CoverLetterContent>) r).findings())
                .extracting(Finding::code, Finding::value)
                .contains(org.assertj.core.groups.Tuple.tuple(FindingCode.NUMBER_NOT_IN_PROFILE, "12"));
    }

    @Test
    void aHighlightShrinking12000To12IsReverted() throws Exception {
        TailoredCvDraft draft = TailoredCvJsonSchema.parse("""
                {"headline": null, "summary": null, "skills": ["Java", "PostgreSQL"],
                 "experience": [{"ref": "E1", "highlights": [
                                    {"ref": "E1.H1", "text": "Cut API latency by 40%% for 12 daily users"}]},
                                {"ref": "E2", "highlights": [{"ref": "E2.H1", "text": "%s"}]}],
                 "projects": [], "certifications": ["C1"]}""".formatted(E2H1));
        AssemblyResult<TailoredCvContent> r = TailoredCvAssembler.assemble(draft, facts(), job(), check());
        assertThat(r).isInstanceOf(AssemblyResult.Assembled.class);
        var a = (AssemblyResult.Assembled<TailoredCvContent>) r;
        assertThat(a.content().experience().get(0).highlights().get(0).text()).isEqualTo(E1H1);
        assertThat(a.findings()).extracting(Finding::code).contains(FindingCode.REVERTED);
    }
}
