package com.talentmatch.web.error;

import static org.assertj.core.api.Assertions.assertThat;

import com.talentmatch.service.exception.MatchesBusyException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Phase 6 step 5 (§5.3, §12.5): candidate-side MATCHES_BUSY carries the candidate message on
 * {@code /api/candidates/{id}/matches} and {@code /api/profile/matches}; every other matches path keeps
 * the job message byte-for-byte.
 */
class CandidateMatchesBusyMessageTest {

    private static final String JOB = "Matches for this job are being recalculated by another request. Please try "
            + "again in a few seconds.";
    private static final String CANDIDATE = "Matches for this candidate are being recalculated by another request. "
            + "Please try again in a few seconds.";

    private static final String[] CANDIDATE_PATHS = {
        "/api/candidates/3f2c0e9a-1b2c-4d5e-8f90-a1b2c3d4e5f6/matches", "/api/candidates/x/matches/",
        "/api/candidates/not-a-uuid/matches", "/api/profile/matches", "/api/profile/matches/"};

    private static final String[] JOB_PATHS = {
        "/api/jobs/3f2c0e9a-1b2c-4d5e-8f90-a1b2c3d4e5f6/matches", "/api/jobs/x/matches/", "/api/matches",
        "/api/matches/recompute", "/api/matches/recompute/abc"};

    private static MockHttpServletRequest req(String method, String uri) {
        MockHttpServletRequest r = new MockHttpServletRequest(method, uri);
        r.setAttribute(RequestIdFilter.ATTRIBUTE, "req-9");
        return r;
    }

    private static boolean candidateEndpoint(String uri) {
        return GlobalExceptionHandler.isCandidateMatchesEndpoint(new MockHttpServletRequest("GET", uri));
    }

    @Test
    void isCandidateMatchesEndpoint() {
        for (String uri : CANDIDATE_PATHS) {
            assertThat(candidateEndpoint(uri)).as(uri).isTrue();
        }
        for (String uri : JOB_PATHS) {
            assertThat(candidateEndpoint(uri)).as(uri).isFalse();
        }
        for (String uri : new String[] {"/api/candidates", "/api/candidates/x", "/api/candidates//matches",
                "/api/candidates/x/y/matches", "/api/candidates/x/matchesx", "/api/profile", "/api/profilex/matches",
                "/api/profile/matchesx", "/api/profile/x/matches", "/x/api/candidates/y/matches",
                "/x/api/profile/matches", "/api/jobs/x", ""}) {
            assertThat(candidateEndpoint(uri)).as(uri).isFalse();
        }
        MockHttpServletRequest noUri = new MockHttpServletRequest();
        noUri.setRequestURI(null);
        assertThat(GlobalExceptionHandler.isCandidateMatchesEndpoint(noUri)).isFalse();
    }

    @Test
    void everyCandidateMatchesPathIsAlsoAMatchesPath() {
        for (String uri : CANDIDATE_PATHS) {
            assertThat(GlobalExceptionHandler.isMatchesEndpoint(new MockHttpServletRequest("GET", uri))).as(uri)
                    .isTrue();
        }
    }

    private static void assertBusy(ResponseEntity<ApiError> res, String uri, String message) {
        assertThat(res.getStatusCode().value()).as(uri).isEqualTo(503);
        assertThat(res.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).as(uri).isEqualTo("2");
        ApiError body = res.getBody();
        assertThat(body).isNotNull();
        assertThat(body.code()).as(uri).isEqualTo("MATCHES_BUSY");
        assertThat(body.message()).as(uri).isEqualTo(message);
        assertThat(body.path()).isEqualTo(uri);
        assertThat(body.requestId()).isEqualTo("req-9");
    }

    @Test
    void lockFailureUsesTheCandidateMessageOnCandidatePathsOnly() {
        GlobalExceptionHandler h = new GlobalExceptionHandler();
        for (String uri : CANDIDATE_PATHS) {
            assertBusy(h.lockFailure(new CannotAcquireLockException("x"), req("GET", uri)), uri, CANDIDATE);
        }
        for (String uri : JOB_PATHS) {
            assertBusy(h.lockFailure(new CannotAcquireLockException("x"), req("GET", uri)), uri, JOB);
        }
    }

    @Test
    void matchesBusyExceptionKeepsItsOwnMessage() {
        GlobalExceptionHandler h = new GlobalExceptionHandler();
        assertBusy(h.matchesBusy(MatchesBusyException.forCandidate(null), req("GET", CANDIDATE_PATHS[0])),
                CANDIDATE_PATHS[0], CANDIDATE);
        assertBusy(h.matchesBusy(MatchesBusyException.forCandidate(null), req("GET", "/api/profile/matches")),
                "/api/profile/matches", CANDIDATE);
        assertBusy(h.matchesBusy(new MatchesBusyException(null), req("GET", JOB_PATHS[0])), JOB_PATHS[0], JOB);
        assertThat(MatchesBusyException.MESSAGE).isEqualTo(JOB);
        assertThat(MatchesBusyException.CANDIDATE_MESSAGE).isEqualTo(CANDIDATE);
    }
}
