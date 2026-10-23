package com.talentmatch.feed;

import static org.assertj.core.api.Assertions.assertThat;

import com.talentmatch.preferences.JobPreferences;
import com.talentmatch.preferences.RemoteScope;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** §4.11 derived Adzuna searches (pure). */
class DerivedQueriesTest {

    private static JobPreferences prefs(List<String> titles, boolean includeRemote) {
        return new JobPreferences(titles, List.of(),
                new JobPreferences.Regions(List.of("ZA"), includeRemote, RemoteScope.ELIGIBLE_FROM_COUNTRIES, List.of()),
                Set.of(), null, List.of(), null);
    }

    private static List<String> whats(List<Map<String, String>> queries) {
        return queries.stream().map(q -> q.get("what")).toList();
    }

    @Test
    void onePerTargetTitleUpToTheMaxPlusRemote() {
        List<Map<String, String>> q = DerivedQueries.of(
                prefs(List.of("Java Developer", "java  developer", "Backend Engineer", "SRE", "Data Engineer"), true),
                "Ignored", 3, "za");
        assertThat(whats(q)).containsExactly("Java Developer", "Backend Engineer", "SRE", "remote");
        assertThat(q).allSatisfy(m -> {
            assertThat(m.get("country")).isEqualTo("za");
            assertThat(m).doesNotContainKey("where");
        });
    }

    @Test
    void noTitlesFallsBackToTheLatestProfileTitle() {
        assertThat(whats(DerivedQueries.of(prefs(List.of(), false), " Senior Engineer ", 3, "za")))
                .containsExactly("Senior Engineer");
        assertThat(whats(DerivedQueries.of(prefs(List.of(), false), null, 3, "za"))).isEmpty();
    }

    @Test
    void noPreferencesSavedMeansRemoteWanted() {
        assertThat(whats(DerivedQueries.of(null, "Engineer", 3, "za"))).containsExactly("Engineer", "remote");
    }

    @Test
    void zeroDerivedQueriesLeavesOnlyRemote() {
        assertThat(whats(DerivedQueries.of(prefs(List.of("Java"), true), "x", 0, "za"))).containsExactly("remote");
    }

    @Test
    void remoteTitleIsNotRepeated() {
        assertThat(whats(DerivedQueries.of(prefs(List.of("Remote"), true), null, 3, "za"))).containsExactly("Remote");
    }

    @Test
    void longTitlesAreCut() {
        String title = "x".repeat(150);
        assertThat(whats(DerivedQueries.of(prefs(List.of(title), false), null, 3, "za")).get(0)).hasSize(100);
    }
}
