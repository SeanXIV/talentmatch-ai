package com.talentmatch.feed;

import static org.assertj.core.api.Assertions.assertThat;

import com.talentmatch.feed.CanonicalJob.Candidate;
import com.talentmatch.feed.CanonicalJob.Canonical;
import com.talentmatch.feed.source.SourceKind;
import com.talentmatch.preferences.SalaryPeriod;
import com.talentmatch.preferences.Seniority;
import com.talentmatch.preferences.Workplace;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** §4.4 canonical fields of a deduplicated job. */
class CanonicalJobTest {

    private static final Instant T0 = Instant.parse("2026-10-01T00:00:00Z");

    private static Candidate c(SourceKind kind, String title, String description, Instant postedAt, String country,
                               Workplace workplace, BigDecimal min, BigDecimal max, boolean estimated) {
        return new Candidate(UUID.randomUUID(), kind, title, "Acme", description, "https://x/" + title, postedAt, T0,
                "loc-" + title, country, workplace, null, min, max, min == null && max == null ? null : "USD",
                min == null && max == null ? null : SalaryPeriod.YEAR, estimated);
    }

    private static Candidate simple(SourceKind kind, String title, String description) {
        return c(kind, title, description, null, null, Workplace.UNKNOWN, null, null, false);
    }

    private static Canonical of(Candidate... candidates) {
        return CanonicalJob.of(List.of(candidates)).orElseThrow();
    }

    @Test
    void atsBeatsAggregatorEvenWithShorterDescription() {
        Canonical canon = of(simple(SourceKind.ADZUNA, "Agg", "x".repeat(5000)),
                simple(SourceKind.LEVER, "Ats", "short"));
        assertThat(canon.title()).isEqualTo("Ats");
        assertThat(canon.primaryUrl()).isEqualTo("https://x/Ats");
        assertThat(canon.description()).isEqualTo("short");
        assertThat(canon.descriptionHash()).isEqualTo(PostingNormalizer.sha256Hex("short"));
    }

    @Test
    void completeDescriptionBeatsPending() {
        Canonical canon = of(simple(SourceKind.GREENHOUSE, "Pending", null),
                simple(SourceKind.GREENHOUSE, "Complete", "desc"));
        assertThat(canon.title()).isEqualTo("Complete");
    }

    @Test
    void longerDescriptionWins() {
        Canonical canon = of(simple(SourceKind.LEVER, "Short", "abc"), simple(SourceKind.ASHBY, "Long", "abcdef"));
        assertThat(canon.title()).isEqualTo("Long");
    }

    @Test
    void ties_areStableByFirstSeenThenId() {
        UUID low = new UUID(0, 1);
        UUID high = new UUID(0, 2);
        Candidate a = new Candidate(high, SourceKind.LEVER, "A", "Acme", "same", "https://a", null, T0, null, null,
                null, null, null, null, null, null, false);
        Candidate b = new Candidate(low, SourceKind.LEVER, "B", "Acme", "same", "https://b", null, T0, null, null,
                null, null, null, null, null, null, false);
        assertThat(of(a, b).title()).isEqualTo("B");
        assertThat(of(b, a).title()).isEqualTo("B");
        Candidate earlier = new Candidate(high, SourceKind.LEVER, "E", "Acme", "same", "https://e", null,
                T0.minusSeconds(1), null, null, null, null, null, null, null, null, false);
        assertThat(of(b, earlier).title()).isEqualTo("E");
    }

    @Test
    void unionFieldsEarliestPostedAtCountriesAndRemote() {
        Canonical canon = of(
                c(SourceKind.LEVER, "A", "aaaa", T0.plusSeconds(60), "ZA", Workplace.ONSITE, null, null, false),
                c(SourceKind.GREENHOUSE, "B", "bb", T0, "US", Workplace.REMOTE, null, null, false),
                c(SourceKind.ASHBY, "C", "c", null, "ZA", Workplace.HYBRID, null, null, false));
        assertThat(canon.title()).isEqualTo("A");
        assertThat(canon.postedAt()).isEqualTo(T0);
        assertThat(canon.countryCodes()).containsExactly("US", "ZA");
        assertThat(canon.workplace()).isEqualTo(Workplace.REMOTE);
        assertThat(canon.seniority()).isEqualTo(Seniority.fromTitle("A"));
        assertThat(CanonicalJob.union(Workplace.HYBRID, Workplace.ONSITE)).isEqualTo(Workplace.HYBRID);
        assertThat(CanonicalJob.union(Workplace.UNKNOWN, Workplace.ONSITE)).isEqualTo(Workplace.ONSITE);
    }

    @Test
    void salaryFallsBackToANonEstimatedOne() {
        Candidate best = c(SourceKind.LEVER, "Best", "long description", null, null, Workplace.UNKNOWN, null, null,
                false);
        Candidate estimated = c(SourceKind.ADZUNA, "Est", "x", null, null, Workplace.UNKNOWN, new BigDecimal("1"),
                new BigDecimal("2"), true);
        Candidate real = c(SourceKind.ADZUNA, "Real", "y", null, null, Workplace.UNKNOWN, new BigDecimal("10"),
                new BigDecimal("20"), false);
        Canonical canon = of(best, estimated, real);
        assertThat(canon.title()).isEqualTo("Best");
        assertThat(canon.salaryMin()).isEqualByComparingTo("10");
        assertThat(canon.salaryMax()).isEqualByComparingTo("20");
        assertThat(canon.salaryEstimated()).isFalse();
        assertThat(canon.salaryCurrency()).isEqualTo("USD");

        Canonical onlyEstimated = of(best, estimated);
        assertThat(onlyEstimated.salaryMin()).isNull();
        assertThat(onlyEstimated.salaryCurrency()).isNull();
        assertThat(onlyEstimated.salaryPeriod()).isNull();
    }

    @Test
    void canonicalPostingsOwnSalaryWins() {
        Candidate best = c(SourceKind.LEVER, "Best", "long description", null, null, Workplace.UNKNOWN,
                new BigDecimal("5"), null, false);
        Candidate other = c(SourceKind.ADZUNA, "Other", "x", null, null, Workplace.UNKNOWN, new BigDecimal("10"),
                new BigDecimal("20"), false);
        Canonical canon = of(best, other);
        assertThat(canon.salaryMin()).isEqualByComparingTo("5");
        assertThat(canon.salaryMax()).isNull();
    }

    @Test
    void noDescriptionGivesNullHash() {
        Canonical canon = of(simple(SourceKind.GREENHOUSE, "Pending", null));
        assertThat(canon.description()).isNull();
        assertThat(canon.descriptionHash()).isNull();
        assertThat(of(simple(SourceKind.GREENHOUSE, "Blank", "   ")).descriptionHash()).isNull();
    }

    @Test
    void noPostingsGivesEmpty() {
        assertThat(CanonicalJob.of(List.of())).isEmpty();
        assertThat(CanonicalJob.of(null)).isEmpty();
        List<Candidate> nulls = new ArrayList<>();
        nulls.add(null);
        assertThat(CanonicalJob.of(nulls)).isEmpty();
    }
}
