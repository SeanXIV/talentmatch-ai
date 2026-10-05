package com.talentmatch.profile;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Phase 4: ProfileService pure helpers (candidate summary, near-duplicate skills N11). */
class ProfileServiceTest {

    private static ProfileDocument doc(String headline, String summary) {
        return new ProfileDocument("Ada", "ada@example.com", null, null, headline, summary, List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of());
    }

    @Test
    void candidateSummaryCombinations() {
        assertThat(ProfileService.candidateSummary(doc(null, null))).isNull();
        assertThat(ProfileService.candidateSummary(doc("Backend Engineer", null))).isEqualTo("Backend Engineer");
        assertThat(ProfileService.candidateSummary(doc(null, "Ten years of APIs."))).isEqualTo("Ten years of APIs.");
        assertThat(ProfileService.candidateSummary(doc("Backend Engineer", "Ten years of APIs.")))
                .isEqualTo("Backend Engineer\n\nTen years of APIs.");
    }

    @Test
    void nearDuplicateSkills() {
        assertThat(ProfileService.nearDuplicate("Postgres", "PostgreSQL")).isTrue();
        assertThat(ProfileService.nearDuplicate("ReactJS", "React")).isTrue();
        assertThat(ProfileService.nearDuplicate("Node.js", "NodeJS")).isTrue();
        assertThat(ProfileService.nearDuplicate("Python3", "Python")).isTrue();
        assertThat(ProfileService.nearDuplicate("Java", "JavaScript")).as("6 extra letters").isFalse();
        assertThat(ProfileService.nearDuplicate("Go", "Golang")).as("too short to compare").isFalse();
        assertThat(ProfileService.nearDuplicate("C", "C#")).isFalse();
        assertThat(ProfileService.nearDuplicate("Docker", "Kubernetes")).isFalse();
        assertThat(ProfileService.nearDuplicate("---", "Java")).isFalse();
    }
}
