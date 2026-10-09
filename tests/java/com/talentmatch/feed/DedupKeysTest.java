package com.talentmatch.feed;

import static org.assertj.core.api.Assertions.assertThat;

import com.talentmatch.preferences.Workplace;
import org.junit.jupiter.api.Test;

/** §4.4 dedup key. */
class DedupKeysTest {

    @Test
    void seniorityAbbreviationsAndRemoteSuffix() {
        assertThat(DedupKeys.titleKey("Sr. Backend Engineer - Remote"))
                .isEqualTo(DedupKeys.titleKey("Senior Backend Engineer"))
                .isEqualTo("senior backend engineer");
        assertThat(DedupKeys.titleKey("Jr Developer")).isEqualTo("junior developer");
        assertThat(DedupKeys.titleKey("Jr. Developer")).isEqualTo("junior developer");
    }

    @Test
    void bracketsDroppedNonPlaceSegmentKeptPlaceSegmentDropped() {
        assertThat(DedupKeys.titleKey("Backend Engineer (Remote) [Contract]")).isEqualTo("backend engineer");
        assertThat(DedupKeys.titleKey("Backend Engineer - Payments")).isEqualTo("backend engineer payments");
        assertThat(DedupKeys.titleKey("Backend Engineer | South Africa")).isEqualTo("backend engineer");
        assertThat(DedupKeys.titleKey("Backend Engineer - Hybrid - Payments")).isEqualTo("backend engineer");
    }

    @Test
    void plusAndHashKept() {
        assertThat(DedupKeys.titleKey("C++ Developer")).isEqualTo("c++ developer");
        assertThat(DedupKeys.titleKey("C# Developer")).isEqualTo("c# developer");
        assertThat(DedupKeys.titleKey("C++ Developer")).isNotEqualTo(DedupKeys.titleKey("C# Developer"));
    }

    @Test
    void remoteAndOnsiteBucketsDiffer() {
        String remote = DedupKeys.of("Acme", "Backend Engineer", Workplace.REMOTE);
        String onsite = DedupKeys.of("Acme", "Backend Engineer", Workplace.ONSITE);
        assertThat(remote).isEqualTo("acme|backend engineer|remote");
        assertThat(onsite).isEqualTo("acme|backend engineer|onsite");
        assertThat(DedupKeys.of("Acme", "Backend Engineer", Workplace.HYBRID)).isEqualTo(onsite);
        assertThat(DedupKeys.of("Acme", "Backend Engineer", Workplace.UNKNOWN)).isEqualTo(onsite);
        assertThat(DedupKeys.of("Acme", "Backend Engineer", null)).isEqualTo(onsite);
    }

    @Test
    void sameRoleAcrossSourcesGivesSameKey() {
        assertThat(DedupKeys.of("Acme Inc.", "Sr. Backend Engineer - Remote", Workplace.REMOTE))
                .isEqualTo(DedupKeys.of("ACME (Pty) Ltd", "Senior Backend Engineer", Workplace.REMOTE));
    }

    @Test
    void bracketOnlyTitleFallsBackToWholeTitle() {
        assertThat(DedupKeys.titleKey("(Remote)")).isEqualTo("remote");
        assertThat(DedupKeys.titleKey("[Contract]")).isEqualTo("contract");
    }

    @Test
    void blankTitleIsEmptyAndKeysAreCapped() {
        assertThat(DedupKeys.titleKey(null)).isEmpty();
        assertThat(DedupKeys.titleKey("  ")).isEmpty();
        String key = DedupKeys.of("x".repeat(500), "y".repeat(500), Workplace.REMOTE);
        String[] parts = key.split("\\|");
        assertThat(parts[0]).hasSize(DedupKeys.MAX_COMPANY_KEY);
        assertThat(parts[1]).hasSize(DedupKeys.MAX_TITLE_KEY);
        assertThat(key.length()).isLessThanOrEqualTo(600);
    }
}
