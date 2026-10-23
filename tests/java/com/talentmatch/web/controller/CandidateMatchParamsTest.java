package com.talentmatch.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.talentmatch.seeker.CandidateMatchQuery;
import com.talentmatch.seeker.OriginFilter;
import com.talentmatch.seeker.RemoteFilter;
import com.talentmatch.seeker.Sort;
import com.talentmatch.service.exception.InvalidParameterException;
import com.talentmatch.web.error.ErrorCode;
import com.talentmatch.web.error.FieldErrorDto;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Phase 6 spec §5.1, §5.2, §9.1 item 1: pure parsing of the candidate-matches query parameters. */
class CandidateMatchParamsTest {

    private static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static CandidateMatchParams.Raw raw() {
        return new CandidateMatchParams.Raw(null, null, null, null, null, null, null, null, null, false, false);
    }

    private static CandidateMatchParams.Parsed parse(CandidateMatchParams.Raw raw) {
        return CandidateMatchParams.parse(raw, 10, 100, CLOCK);
    }

    /** Asserts a 400 INVALID_PARAMETER naming {@code field} with exactly {@code message}. */
    private static void invalid(ThrowingCallable call, String field, String message) {
        assertThatThrownBy(call).isInstanceOfSatisfying(InvalidParameterException.class, e -> {
            assertThat(e.getCode()).isEqualTo(ErrorCode.INVALID_PARAMETER);
            assertThat(e.getStatus().value()).isEqualTo(400);
            assertThat(e.getMessage()).isEqualTo(message);
            assertThat(e.getFieldErrors()).containsExactly(new FieldErrorDto(field, message));
        });
    }

    // ------------------------------------------------------------------ defaults

    @Test
    void everythingAbsentGivesTheDefaults() {
        CandidateMatchParams.Parsed p = parse(raw());
        assertThat(p.page()).isZero();
        assertThat(p.limit()).isEqualTo(10);
        assertThat(p.query()).isEqualTo(CandidateMatchQuery.defaults());
        CandidateMatchQuery q = p.query();
        assertThat(q.origin()).isEqualTo(OriginFilter.ALL);
        assertThat(q.sort()).isEqualTo(Sort.SCORE);
        assertThat(q.remote()).isEqualTo(RemoteFilter.INCLUDE);
        assertThat(q.postedSince()).isNull();
        assertThat(q.countries()).isEmpty();
        assertThat(q.location()).isNull();
        assertThat(q.minScore()).isZero();
        assertThat(q.includeClosed()).isFalse();
        assertThat(q.includeFiltered()).isFalse();
    }

    @Test
    void blankTextParametersMeanAbsent() {
        CandidateMatchParams.Parsed p = parse(new CandidateMatchParams.Raw(null, null, null, " ", "", List.of(" ", ""),
                "\t", "   ", "", false, false));
        assertThat(p.query()).isEqualTo(CandidateMatchQuery.defaults());
    }

    @Test
    void allParametersAreCarriedThrough() {
        CandidateMatchParams.Parsed p = parse(new CandidateMatchParams.Raw(25, 3, 0.6, "newest", "P2D",
                List.of("za", "gb"), "exclude", "Cape Town", "feed", true, true));
        assertThat(p.page()).isEqualTo(3);
        assertThat(p.limit()).isEqualTo(25);
        CandidateMatchQuery q = p.query();
        assertThat(q.sort()).isEqualTo(Sort.NEWEST);
        assertThat(q.postedSince()).isEqualTo(NOW.minus(Duration.ofDays(2)));
        assertThat(q.countries()).containsExactly("ZA", "GB");
        assertThat(q.remote()).isEqualTo(RemoteFilter.EXCLUDE);
        assertThat(q.location()).isEqualTo("Cape Town");
        assertThat(q.origin()).isEqualTo(OriginFilter.FEED);
        assertThat(q.minScore()).isEqualTo(0.6);
        assertThat(q.includeClosed()).isTrue();
        assertThat(q.includeFiltered()).isTrue();
    }

    @Test
    void theFirstInvalidParameterInRawOrderWins() {
        invalid(() -> parse(new CandidateMatchParams.Raw(0, -1, 2.0, "x", "x", List.of("XX"), "x", "x", "x", false,
                false)), "limit", "limit must be between 1 and 100.");
        invalid(() -> parse(new CandidateMatchParams.Raw(null, -1, 2.0, "x", "x", List.of("XX"), "x", "x", "x", false,
                false)), "page", ApiParams.PAGE_MESSAGE);
        invalid(() -> parse(new CandidateMatchParams.Raw(null, null, 2.0, "x", "x", List.of("XX"), "x", "x", "x",
                false, false)), "minScore", ApiParams.MIN_SCORE_MESSAGE);
        invalid(() -> parse(new CandidateMatchParams.Raw(null, null, null, "x", "x", List.of("XX"), "x", "x", "x",
                false, false)), "sort", CandidateMatchParams.SORT_MESSAGE);
        invalid(() -> parse(new CandidateMatchParams.Raw(null, null, null, null, "x", List.of("XX"), "x", "x", "x",
                false, false)), "postedSince", CandidateMatchParams.POSTED_SINCE_MESSAGE);
        invalid(() -> parse(new CandidateMatchParams.Raw(null, null, null, null, null, List.of("XX"), "x", "x", "x",
                false, false)), "country", "'XX' is not an ISO country code.");
        invalid(() -> parse(new CandidateMatchParams.Raw(null, null, null, null, null, null, "x", "x", "x",
                false, false)), "remote", CandidateMatchParams.REMOTE_MESSAGE);
        invalid(() -> parse(new CandidateMatchParams.Raw(null, null, null, null, null, null, null, "x", "x",
                false, false)), "location", CandidateMatchParams.LOCATION_MESSAGE);
        invalid(() -> parse(new CandidateMatchParams.Raw(null, null, null, null, null, null, null, null, "x",
                false, false)), "origin", CandidateMatchParams.ORIGIN_MESSAGE);
    }

    @Test
    void messagesMatchTheSpec() {
        assertThat(CandidateMatchParams.SORT_MESSAGE).isEqualTo("sort must be score or newest.");
        assertThat(CandidateMatchParams.POSTED_SINCE_MESSAGE).isEqualTo("postedSince must be an ISO-8601 instant like "
                + "2026-10-16T00:00:00Z or a duration like P2D (at most P366D).");
        assertThat(CandidateMatchParams.TOO_MANY_COUNTRIES_MESSAGE).isEqualTo("Use at most 20 country codes.");
        assertThat(CandidateMatchParams.REMOTE_MESSAGE).isEqualTo("remote must be include, only or exclude.");
        assertThat(CandidateMatchParams.ORIGIN_MESSAGE).isEqualTo("origin must be all, feed or manual.");
        assertThat(CandidateMatchParams.LOCATION_MESSAGE).isEqualTo("location must be 2 to 100 characters.");
    }

    // ------------------------------------------------------------------ enums

    @Test
    void sortIsCaseInsensitive() {
        assertThat(CandidateMatchParams.sort(null)).isEqualTo(Sort.SCORE);
        assertThat(CandidateMatchParams.sort("score")).isEqualTo(Sort.SCORE);
        assertThat(CandidateMatchParams.sort("SCORE")).isEqualTo(Sort.SCORE);
        assertThat(CandidateMatchParams.sort("Newest")).isEqualTo(Sort.NEWEST);
        assertThat(CandidateMatchParams.sort(" newest ")).isEqualTo(Sort.NEWEST);
        for (String bad : new String[] {"date", "new", "scores", "-score", "score,newest"}) {
            invalid(() -> CandidateMatchParams.sort(bad), "sort", CandidateMatchParams.SORT_MESSAGE);
        }
    }

    @Test
    void remoteIsCaseInsensitive() {
        assertThat(CandidateMatchParams.remote(null)).isEqualTo(RemoteFilter.INCLUDE);
        assertThat(CandidateMatchParams.remote("INCLUDE")).isEqualTo(RemoteFilter.INCLUDE);
        assertThat(CandidateMatchParams.remote("Only")).isEqualTo(RemoteFilter.ONLY);
        assertThat(CandidateMatchParams.remote("exclude")).isEqualTo(RemoteFilter.EXCLUDE);
        for (String bad : new String[] {"yes", "true", "remote", "includes"}) {
            invalid(() -> CandidateMatchParams.remote(bad), "remote", CandidateMatchParams.REMOTE_MESSAGE);
        }
    }

    @Test
    void originIsCaseInsensitive() {
        assertThat(CandidateMatchParams.origin(null)).isEqualTo(OriginFilter.ALL);
        assertThat(CandidateMatchParams.origin("ALL")).isEqualTo(OriginFilter.ALL);
        assertThat(CandidateMatchParams.origin("Feed")).isEqualTo(OriginFilter.FEED);
        assertThat(CandidateMatchParams.origin("manual")).isEqualTo(OriginFilter.MANUAL);
        for (String bad : new String[] {"etl", "both", "feeds"}) {
            invalid(() -> CandidateMatchParams.origin(bad), "origin", CandidateMatchParams.ORIGIN_MESSAGE);
        }
    }

    // ------------------------------------------------------------------ postedSince

    @Test
    void postedSinceAcceptsInstantsAndPositiveDurations() {
        assertThat(CandidateMatchParams.postedSince(null, CLOCK)).isNull();
        assertThat(CandidateMatchParams.postedSince("  ", CLOCK)).isNull();
        assertThat(CandidateMatchParams.postedSince("2026-10-16T00:00:00Z", CLOCK))
                .isEqualTo(Instant.parse("2026-10-16T00:00:00Z"));
        assertThat(CandidateMatchParams.postedSince("2026-10-16T00:00:00+02:00", CLOCK))
                .as("an offset is accepted").isEqualTo(Instant.parse("2026-10-15T22:00:00Z"));
        assertThat(CandidateMatchParams.postedSince("2026-10-16T00:00:00.123Z", CLOCK))
                .isEqualTo(Instant.parse("2026-10-16T00:00:00.123Z"));
        assertThat(CandidateMatchParams.postedSince("P2D", CLOCK)).isEqualTo(NOW.minus(Duration.ofDays(2)));
        assertThat(CandidateMatchParams.postedSince("PT6H", CLOCK)).isEqualTo(NOW.minus(Duration.ofHours(6)));
        assertThat(CandidateMatchParams.postedSince("p2d", CLOCK)).as("lower case").isEqualTo(NOW.minusSeconds(172800));
        assertThat(CandidateMatchParams.postedSince("+P2D", CLOCK)).isEqualTo(NOW.minus(Duration.ofDays(2)));
        assertThat(CandidateMatchParams.postedSince(" P1DT12H ", CLOCK)).isEqualTo(NOW.minus(Duration.ofHours(36)));
        assertThat(CandidateMatchParams.postedSince("P366D", CLOCK)).as("the maximum")
                .isEqualTo(NOW.minus(Duration.ofDays(366)));
        assertThat(CandidateMatchParams.postedSince("PT1S", CLOCK)).isEqualTo(NOW.minusSeconds(1));
    }

    @Test
    void durationsAreRelativeToTheGivenClock() {
        Clock later = Clock.offset(CLOCK, Duration.ofHours(5));
        assertThat(CandidateMatchParams.postedSince("P2D", later))
                .isEqualTo(NOW.plus(Duration.ofHours(5)).minus(Duration.ofDays(2)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"P0D", "PT0S", "-P2D", "-PT6H", "P-2D", "P400D", "P367D", "PT8785H", "P1M", "P1Y",
            "P1W", "P", "PT", "2D", "garbage", "yesterday", "2026-10-16", "2026-10-16T00:00:00", "1697414400",
            "P2D,P3D", "+", "-"})
    void invalidPostedSinceIs400(String value) {
        invalid(() -> CandidateMatchParams.postedSince(value, CLOCK), "postedSince",
                CandidateMatchParams.POSTED_SINCE_MESSAGE);
    }

    // ------------------------------------------------------------------ country

    @Test
    void countriesAreUpperCasedDistinctAndInOrder() {
        assertThat(CandidateMatchParams.countries(null)).isEmpty();
        assertThat(CandidateMatchParams.countries(List.of())).isEmpty();
        assertThat(CandidateMatchParams.countries(List.of("za,GB", "gb"))).containsExactly("ZA", "GB");
        assertThat(CandidateMatchParams.countries(List.of("za,GB", "us"))).containsExactly("ZA", "GB", "US");
        assertThat(CandidateMatchParams.countries(List.of(" za , ,gb,", ","))).containsExactly("ZA", "GB");
        assertThat(CandidateMatchParams.countries(Arrays.asList(null, "De"))).containsExactly("DE");
        assertThat(CandidateMatchParams.countries(List.of(",", " "))).as("no codes = no filter").isEmpty();
    }

    @Test
    void unknownCodesAre400WithTheCodeEchoed() {
        invalid(() -> CandidateMatchParams.countries(List.of("XX")), "country", "'XX' is not an ISO country code.");
        invalid(() -> CandidateMatchParams.countries(List.of("za,xx")), "country", "'xx' is not an ISO country code.");
        invalid(() -> CandidateMatchParams.countries(List.of("ZAF")), "country", "'ZAF' is not an ISO country code.");
        invalid(() -> CandidateMatchParams.countries(List.of("UK")), "country", "'UK' is not an ISO country code.");
        invalid(() -> CandidateMatchParams.countries(List.of("Z")), "country", "'Z' is not an ISO country code.");
        invalid(() -> CandidateMatchParams.countries(List.of("South Africa")), "country",
                "'South Africa' is not an ISO country code.");
        // a long value is echoed truncated
        invalid(() -> CandidateMatchParams.countries(List.of("abcdefghijklmnopqrstuvwxyz")), "country",
                "'abcdefghijklmnop...' is not an ISO country code.");
    }

    @Test
    void atMostTwentyDistinctCountries() {
        List<String> codes = List.of("AD", "AE", "AF", "AG", "AI", "AL", "AM", "AO", "AQ", "AR", "AS", "AT", "AU", "AW",
                "AX", "AZ", "BA", "BB", "BD", "BE", "BF");
        assertThat(CandidateMatchParams.countries(codes.subList(0, 20))).hasSize(20);
        invalid(() -> CandidateMatchParams.countries(codes), "country", CandidateMatchParams.TOO_MANY_COUNTRIES_MESSAGE);
        invalid(() -> CandidateMatchParams.countries(List.of(String.join(",", codes))), "country",
                CandidateMatchParams.TOO_MANY_COUNTRIES_MESSAGE);
        // 21 values but only 20 distinct codes: fine
        List<String> withDuplicate = new ArrayList<>(codes.subList(0, 20));
        withDuplicate.add("ad");
        assertThat(CandidateMatchParams.countries(withDuplicate)).hasSize(20);
    }

    // ------------------------------------------------------------------ location

    @Test
    void locationIsNormalized() {
        assertThat(CandidateMatchParams.location(null)).isNull();
        assertThat(CandidateMatchParams.location("   ")).isNull();
        assertThat(CandidateMatchParams.location("  Cape \t\n  Town  ")).isEqualTo("Cape Town");
        assertThat(CandidateMatchParams.location("Ｃａｐｅ Town")).as("NFKC full width").isEqualTo("Cape Town");
        assertThat(CandidateMatchParams.location("Cape  Town")).as("NBSP/em space collapse")
                .isEqualTo("Cape Town");
        assertThat(CandidateMatchParams.location("ﬁnland")).as("ligature").isEqualTo("finland");
        assertThat(CandidateMatchParams.location("50%_x")).as("wildcards kept; escaped in SQL").isEqualTo("50%_x");
        assertThat(CandidateMatchParams.location("NY")).isEqualTo("NY");
    }

    @Test
    void locationLengthIs2To100CodePoints() {
        invalid(() -> CandidateMatchParams.location("a"), "location", CandidateMatchParams.LOCATION_MESSAGE);
        invalid(() -> CandidateMatchParams.location("  a  "), "location", CandidateMatchParams.LOCATION_MESSAGE);
        assertThat(CandidateMatchParams.location("x".repeat(100))).hasSize(100);
        invalid(() -> CandidateMatchParams.location("x".repeat(101)), "location", CandidateMatchParams.LOCATION_MESSAGE);
        assertThat(CandidateMatchParams.location("  " + "x".repeat(100) + "  ")).as("trimmed first").hasSize(100);
        assertThat(CandidateMatchParams.location("a" + " ".repeat(200) + "b")).as("collapsed first").isEqualTo("a b");
        String emoji = "🌍";                    // one code point, two chars
        assertThat(CandidateMatchParams.location(emoji.repeat(100))).as("counted in code points").isNotNull();
        invalid(() -> CandidateMatchParams.location(emoji.repeat(101)), "location",
                CandidateMatchParams.LOCATION_MESSAGE);
        invalid(() -> CandidateMatchParams.location(emoji), "location", CandidateMatchParams.LOCATION_MESSAGE);
    }

    // ------------------------------------------------------------------ paging and minScore

    @Test
    void limitBounds() {
        assertThat(CandidateMatchParams.limit(null, 10, 100)).isEqualTo(10);
        assertThat(CandidateMatchParams.limit(1, 10, 100)).isEqualTo(1);
        assertThat(CandidateMatchParams.limit(100, 10, 100)).isEqualTo(100);
        invalid(() -> CandidateMatchParams.limit(0, 10, 100), "limit", "limit must be between 1 and 100.");
        invalid(() -> CandidateMatchParams.limit(101, 10, 100), "limit", "limit must be between 1 and 100.");
        invalid(() -> CandidateMatchParams.limit(-5, 10, 100), "limit", "limit must be between 1 and 100.");
        invalid(() -> CandidateMatchParams.limit(51, 10, 50), "limit", "limit must be between 1 and 50.");
    }

    @Test
    void pageBoundsAndOverflow() {
        assertThat(CandidateMatchParams.page(null, 10)).isZero();
        assertThat(CandidateMatchParams.page(7, 10)).isEqualTo(7);
        invalid(() -> CandidateMatchParams.page(-1, 10), "page", ApiParams.PAGE_MESSAGE);
        invalid(() -> CandidateMatchParams.page(Integer.MIN_VALUE, 10), "page", ApiParams.PAGE_MESSAGE);
        int maxPage = Integer.MAX_VALUE / 100;
        assertThat(CandidateMatchParams.page(maxPage, 100)).isEqualTo(maxPage);
        invalid(() -> CandidateMatchParams.page(maxPage + 1, 100), "page", CandidateMatchParams.PAGE_TOO_LARGE_MESSAGE);
        invalid(() -> CandidateMatchParams.page(Integer.MAX_VALUE, 2), "page",
                CandidateMatchParams.PAGE_TOO_LARGE_MESSAGE);
        assertThat(CandidateMatchParams.page(Integer.MAX_VALUE, 1)).isEqualTo(Integer.MAX_VALUE);
        // through parse: the overflow check uses the effective (default) limit
        invalid(() -> parse(new CandidateMatchParams.Raw(null, Integer.MAX_VALUE / 10 + 1, null, null, null, null, null,
                null, null, false, false)), "page", CandidateMatchParams.PAGE_TOO_LARGE_MESSAGE);
    }

    @Test
    void minScoreBounds() {
        assertThat(CandidateMatchParams.minScore(null)).isZero();
        assertThat(CandidateMatchParams.minScore(0d)).isZero();
        assertThat(CandidateMatchParams.minScore(1d)).isEqualTo(1d);
        assertThat(CandidateMatchParams.minScore(0.6)).isEqualTo(0.6);
        for (double bad : new double[] {-0.01, 1.01, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            invalid(() -> CandidateMatchParams.minScore(bad), "minScore", ApiParams.MIN_SCORE_MESSAGE);
        }
    }
}
