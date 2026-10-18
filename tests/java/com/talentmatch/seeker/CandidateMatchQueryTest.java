package com.talentmatch.seeker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.talentmatch.service.MatchReason;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Phase 6 §4.1, §5.1: the query record, LIKE escaping, the scope conditions and the reasons. */
class CandidateMatchQueryTest {

    // ------------------------------------------------------------------ CandidateMatchQuery

    @Test
    void nullsBecomeDefaults() {
        CandidateMatchQuery q = new CandidateMatchQuery(null, null, null, null, null, 0d, null, false, false);
        assertThat(q).isEqualTo(CandidateMatchQuery.defaults());
        assertThat(q.origin()).isEqualTo(OriginFilter.ALL);
        assertThat(q.remote()).isEqualTo(RemoteFilter.INCLUDE);
        assertThat(q.sort()).isEqualTo(Sort.SCORE);
        assertThat(q.countries()).isNotNull().isEmpty();
    }

    @Test
    void countriesAreCopiedAndImmutable() {
        List<String> codes = new ArrayList<>(List.of("ZA"));
        CandidateMatchQuery q = new CandidateMatchQuery(OriginFilter.FEED, Instant.EPOCH, codes, RemoteFilter.ONLY,
                "Cape", 0.5, Sort.NEWEST, true, true);
        codes.add("GB");
        assertThat(q.countries()).containsExactly("ZA");
        assertThatThrownBy(() -> q.countries().add("US")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void minScoreMustBeIn0To1() {
        assertThat(new CandidateMatchQuery(null, null, null, null, null, 1d, null, false, false).minScore()).isEqualTo(1d);
        for (double bad : new double[] {-0.0001, 1.0001, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThatThrownBy(() -> new CandidateMatchQuery(null, null, null, null, null, bad, null, false, false))
                    .as("minScore %s", bad).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void defaultsAreAllDefaults() {
        CandidateMatchQuery d = CandidateMatchQuery.defaults();
        assertThat(d.postedSince()).isNull();
        assertThat(d.location()).isNull();
        assertThat(d.minScore()).isZero();
        assertThat(d.includeClosed()).isFalse();
        assertThat(d.includeFiltered()).isFalse();
    }

    // ------------------------------------------------------------------ escapeLike

    @Test
    void escapeLikeEscapesTheThreeLikeMetacharacters() {
        assertThat(CandidateMatchRepository.escapeLike("Cape Town")).isEqualTo("Cape Town");
        assertThat(CandidateMatchRepository.escapeLike("50%")).isEqualTo("50\\%");
        assertThat(CandidateMatchRepository.escapeLike("a_b")).isEqualTo("a\\_b");
        assertThat(CandidateMatchRepository.escapeLike("C:\\x")).isEqualTo("C:\\\\x");
        assertThat(CandidateMatchRepository.escapeLike("%_\\")).isEqualTo("\\%\\_\\\\");
        assertThat(CandidateMatchRepository.escapeLike("")).isEmpty();
        assertThat(CandidateMatchRepository.escapeLike("São Paulo [x] *?'")).isEqualTo("São Paulo [x] *?'");
    }

    // ------------------------------------------------------------------ Scope (§4.1)

    private static String where(CandidateMatchQuery q) {
        return CandidateMatchRepository.Scope.of(q, false).where();
    }

    @Test
    void defaultScopeHidesOnlyClosedJobs() {
        assertThat(where(CandidateMatchQuery.defaults())).isEqualTo("f.closed_at IS NULL");
        assertThat(where(new CandidateMatchQuery(null, null, null, null, null, 0d, null, true, false)))
                .isEqualTo("TRUE");
    }

    @Test
    void scopeConditionsPerParameter() {
        assertThat(where(new CandidateMatchQuery(OriginFilter.FEED, null, null, null, null, 0d, null, true, false)))
                .isEqualTo("j.origin = 'FEED'");
        assertThat(where(new CandidateMatchQuery(OriginFilter.MANUAL, null, null, null, null, 0d, null, true, false)))
                .isEqualTo("j.origin = 'MANUAL'");
        assertThat(where(new CandidateMatchQuery(null, null, null, RemoteFilter.ONLY, null, 0d, null, true, false)))
                .isEqualTo("f.workplace = 'REMOTE'");
        assertThat(where(new CandidateMatchQuery(null, null, null, RemoteFilter.EXCLUDE, null, 0d, null, true, false)))
                .isEqualTo("f.workplace IS DISTINCT FROM 'REMOTE'");
        assertThat(where(new CandidateMatchQuery(null, Instant.EPOCH, null, null, null, 0d, null, true, false)))
                .contains(">= :since").contains("LEAST(f.posted_at, f.first_seen_at)");
        assertThat(where(new CandidateMatchQuery(null, null, null, null, "cape", 0d, null, true, false)))
                .isEqualTo("f.location_text ILIKE '%' || :loc || '%' ESCAPE '\\'");
        assertThat(CandidateMatchRepository.Scope.of(CandidateMatchQuery.defaults(), true).where())
                .contains("f.preference_verdict IS DISTINCT FROM 'FILTERED'");
    }

    @Test
    void countryLetsRemoteJobsThroughUnlessRemoteIsExcluded() {
        String include = where(new CandidateMatchQuery(null, null, List.of("ZA"), RemoteFilter.INCLUDE, null, 0d, null,
                true, false));
        assertThat(include).contains("f.job_id IS NULL").contains("cardinality(f.country_codes) = 0")
                .contains("f.country_codes &&").contains("OR f.workplace = 'REMOTE'");
        String exclude = where(new CandidateMatchQuery(null, null, List.of("ZA"), RemoteFilter.EXCLUDE, null, 0d, null,
                true, false));
        assertThat(exclude).doesNotContain("OR f.workplace = 'REMOTE'");
    }

    @Test
    void userValuesAreNeverInlinedInTheSql() {
        CandidateMatchQuery q = new CandidateMatchQuery(OriginFilter.FEED, Instant.parse("2026-10-16T00:00:00Z"),
                List.of("ZA"), RemoteFilter.INCLUDE, "x' OR '1'='1", 0.5, Sort.NEWEST, false, false);
        String w = where(q);
        assertThat(w).doesNotContain("x' OR").doesNotContain("ZA").doesNotContain("2026");
    }

    // ------------------------------------------------------------------ reasons (§5.1)

    @Test
    void candidateSideReasonMessages() {
        assertThat(MatchReason.NO_MATCHABLE_JOBS.message()).isEqualTo("No job with listed skills matches these "
                + "filters. Jobs without skills can't be ranked; widen the filters or wait for the feed to find skills.");
        assertThat(MatchReason.CANDIDATE_HAS_NO_SKILLS.message()).isEqualTo("This candidate has no skills yet, so "
                + "every job scores 0%. Add skills (or confirm your profile with PUT /api/profile) to see real matches.");
        assertThat(MatchReason.JOB_HAS_NO_SKILLS.message())
                .isEqualTo("This job has no skills listed yet, so candidates can't be ranked. Add skills to see matches.");
        assertThat(MatchReason.NO_CANDIDATES.message()).isEqualTo("There are no candidates yet. Add candidates to see matches.");
    }

    @Test
    void enumNamesAreTheApiValues() {
        assertThat(ListedAtSource.values()).extracting(Enum::name)
                .containsExactly("POSTED", "FIRST_SEEN", "CREATED", "UNKNOWN");
        assertThat(Sort.values()).extracting(Enum::name).containsExactly("SCORE", "NEWEST");
        assertThat(RemoteFilter.values()).extracting(Enum::name).containsExactly("INCLUDE", "ONLY", "EXCLUDE");
        assertThat(OriginFilter.values()).extracting(Enum::name).containsExactly("ALL", "FEED", "MANUAL");
    }
}
