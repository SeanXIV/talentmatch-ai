package com.talentmatch.seeker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.talentmatch.ai.AiProperties;
import com.talentmatch.ai.AiProvider;
import com.talentmatch.ai.ExplanationRateLimiter;
import com.talentmatch.ai.ExplanationService;
import com.talentmatch.config.MatchProperties;
import com.talentmatch.domain.scoring.ScoringEngine;
import com.talentmatch.domain.scoring.ScoringWeights;
import com.talentmatch.preferences.Workplace;
import com.talentmatch.profile.OwnerProfileRepository;
import com.talentmatch.repository.MatchJdbcRepository;
import com.talentmatch.seeker.CandidateMatchRepository.CandidateHeader;
import com.talentmatch.seeker.CandidateMatchRepository.RankedJobRow;
import com.talentmatch.seeker.CandidateMatchRepository.Scope;
import com.talentmatch.service.MatchReason;
import com.talentmatch.service.exception.ApiException;
import com.talentmatch.service.exception.MatchesBusyException;
import com.talentmatch.service.exception.NotFoundException;
import com.talentmatch.service.exception.RegenerateRateLimitedException;
import com.talentmatch.web.error.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Phase 6 step 5 (decisions a, e, f; §4.3, §5.1, §5.3, §12.5): the owner scope switch, the
 * owner-only/computed flags, and the candidate regenerate slot in {@link CandidateMatchService}
 * (mocked repositories).
 */
class CandidateMatchOwnerFeaturesTest {

    private static CandidateMatchQuery query(List<String> countries, boolean includeFiltered) {
        return new CandidateMatchQuery(null, null, countries, null, null, 0d, null, false, includeFiltered);
    }

    // ------------------------------------------------------------------ scopeFor (decision f)

    @Test
    void scopeHidesFilteredOnlyForTheOwnerWithoutIncludeFiltered() {
        CandidateMatchQuery plain = query(List.of(), false);
        CandidateMatchQuery include = query(List.of(), true);

        Scope ownerDefault = CandidateMatchService.scopeFor(plain, true);
        assertThat(ownerDefault.hideFiltered()).isTrue();
        assertThat(ownerDefault.where()).contains("f.preference_verdict IS DISTINCT FROM 'FILTERED'");
        assertThat(ownerDefault.query()).isSameAs(plain);

        Scope ownerInclude = CandidateMatchService.scopeFor(include, true);
        assertThat(ownerInclude.hideFiltered()).isFalse();
        assertThat(ownerInclude.where()).doesNotContain("preference_verdict");

        for (CandidateMatchQuery q : List.of(plain, include)) {
            Scope other = CandidateMatchService.scopeFor(q, false);
            assertThat(other.hideFiltered()).as("non-owner, includeFiltered=%s", q.includeFiltered()).isFalse();
            assertThat(other.where()).doesNotContain("preference_verdict");
        }
    }

    // ------------------------------------------------------------------ flags (decisions e, f)

    private static RankedJobRow row(boolean feed, Workplace workplace, List<String> countries, String verdict,
                                    List<String> reasons, List<String> flags) {
        return new RankedJobRow(UUID.randomUUID(), "T", "C", "D", feed ? "FEED" : "MANUAL", Instant.EPOCH, feed,
                null, null, null, null, feed ? Boolean.FALSE : null, workplace, null, countries, null, null, null,
                null, null, null, null, verdict, reasons, flags, 0.5, Instant.EPOCH, Instant.EPOCH,
                ListedAtSource.CREATED, null);
    }

    private static RankedJobRow feedRow(Workplace workplace, List<String> countries, List<String> flags) {
        return row(true, workplace, countries, "PASS", List.of(), flags);
    }

    private static final CandidateMatchQuery NO_COUNTRY = query(List.of(), false);
    private static final CandidateMatchQuery ZA = query(List.of("ZA"), false);

    @Test
    void nonOwnerWithoutCountryFilterHasNoFlags() {
        RankedJobRow r = feedRow(Workplace.ONSITE, List.of(), List.of("SALARY_OTHER_CURRENCY", "LOCATION_UNKNOWN"));
        assertThat(CandidateMatchService.flags(r, false, NO_COUNTRY)).isEmpty();
    }

    @Test
    void nonOwnerWithCountryFilterGetsOnlyTheComputedFlagNeverStoredOnes() {
        RankedJobRow onsite = feedRow(Workplace.ONSITE, List.of(), List.of("SALARY_OTHER_CURRENCY", "IMMEDIATE_START"));
        assertThat(CandidateMatchService.flags(onsite, false, ZA)).containsExactly("LOCATION_UNKNOWN");
        for (Workplace w : new Workplace[] {Workplace.HYBRID, Workplace.UNKNOWN}) {
            assertThat(CandidateMatchService.flags(feedRow(w, List.of(), List.of()), false, ZA)).as("%s", w)
                    .containsExactly("LOCATION_UNKNOWN");
        }
        assertThat(CandidateMatchService.flags(row(true, null, List.of(), null, List.of(), List.of()), false, ZA))
                .as("null workplace is not REMOTE").containsExactly("LOCATION_UNKNOWN");
    }

    @Test
    void ownerStoredFlagsAreUnchangedWithoutACountryFilter() {
        List<String> stored = List.of("SALARY_OTHER_CURRENCY", "REMOTE_ELIGIBILITY_UNKNOWN");
        assertThat(CandidateMatchService.flags(feedRow(Workplace.ONSITE, List.of(), stored), true, NO_COUNTRY))
                .containsExactlyElementsOf(stored);
        assertThat(CandidateMatchService.flags(feedRow(Workplace.ONSITE, List.of(), List.of()), true, NO_COUNTRY))
                .isEmpty();
    }

    @Test
    void ownerGetsStoredThenComputedWithoutDuplicates() {
        RankedJobRow r = feedRow(Workplace.HYBRID, List.of(), List.of("IMMEDIATE_START", "SALARY_OTHER_CURRENCY"));
        List<String> flags = CandidateMatchService.flags(r, true, ZA);
        assertThat(flags).containsExactly("IMMEDIATE_START", "SALARY_OTHER_CURRENCY", "LOCATION_UNKNOWN");
        assertThatThrownBy(() -> flags.add("X")).isInstanceOf(UnsupportedOperationException.class);

        RankedJobRow already = feedRow(Workplace.HYBRID, List.of(),
                List.of("SALARY_OTHER_CURRENCY", "LOCATION_UNKNOWN", "IMMEDIATE_START"));
        assertThat(CandidateMatchService.flags(already, true, ZA))
                .as("stored order kept, not added twice")
                .containsExactly("SALARY_OTHER_CURRENCY", "LOCATION_UNKNOWN", "IMMEDIATE_START");
    }

    @Test
    void remoteManualAndJobsWithCountriesGetNoComputedFlag() {
        for (boolean owner : new boolean[] {true, false}) {
            assertThat(CandidateMatchService.flags(feedRow(Workplace.REMOTE, List.of(), List.of()), owner, ZA))
                    .as("REMOTE, owner=%s", owner).isEmpty();
            assertThat(CandidateMatchService.flags(row(false, null, List.of(), null, List.of(), List.of()), owner, ZA))
                    .as("MANUAL, owner=%s", owner).isEmpty();
            assertThat(CandidateMatchService.flags(feedRow(Workplace.ONSITE, List.of("GB"), List.of()), owner, ZA))
                    .as("has countries, owner=%s", owner).isEmpty();
            assertThat(CandidateMatchService.flags(feedRow(Workplace.ONSITE, List.of("ZA"), List.of()), owner, ZA))
                    .as("matching country, owner=%s", owner).isEmpty();
        }
        // REMOTE with stored flags: owner sees them, no computed flag
        assertThat(CandidateMatchService.flags(feedRow(Workplace.REMOTE, List.of(), List.of("REMOTE_ELIGIBILITY_UNKNOWN")),
                true, ZA)).containsExactly("REMOTE_ELIGIBILITY_UNKNOWN");
    }

    @Test
    void locationUnknownConstantMatchesThePreferenceFlag() {
        assertThat(CandidateMatchService.LOCATION_UNKNOWN)
                .isEqualTo(com.talentmatch.preferences.PreferenceFilter.Flag.LOCATION_UNKNOWN.name());
    }

    // ------------------------------------------------------------------ MatchesBusyException (§5.3, §12.5)

    @Test
    void matchesBusyMessagesAreGolden() {
        assertThat(MatchesBusyException.MESSAGE).isEqualTo(
                "Matches for this job are being recalculated by another request. Please try again in a few seconds.");
        assertThat(MatchesBusyException.CANDIDATE_MESSAGE).isEqualTo(
                "Matches for this candidate are being recalculated by another request. Please try again in a few "
                        + "seconds.");
        assertThat(MatchesBusyException.RETRY_AFTER_SECONDS).isEqualTo(2);

        RuntimeException cause = new CannotAcquireLockException("lock");
        MatchesBusyException job = new MatchesBusyException(cause);
        assertThat(job.getMessage()).isEqualTo(MatchesBusyException.MESSAGE);
        assertThat(job.getCause()).isSameAs(cause);
        assertThat(job.getCode()).isEqualTo(ErrorCode.MATCHES_BUSY);
        assertThat(job.getStatus().value()).isEqualTo(503);

        MatchesBusyException cand = MatchesBusyException.forCandidate(cause);
        assertThat(cand.getMessage()).isEqualTo(MatchesBusyException.CANDIDATE_MESSAGE);
        assertThat(cand.getCause()).isSameAs(cause);
        assertThat(cand.getCode()).isEqualTo(ErrorCode.MATCHES_BUSY);
        assertThat(cand.getStatus().value()).isEqualTo(503);

        assertThat(MatchesBusyException.forCandidate(null).getCause()).isNull();
        assertThat(new MatchesBusyException(null).getCause()).isNull();
    }

    // ------------------------------------------------------------------ the service's regenerate slot

    private static final Instant NOW = Instant.parse("2026-10-18T10:00:00Z");

    private CandidateMatchRepository repository;
    private MatchJdbcRepository matchRepository;
    private OwnerProfileRepository ownerProfiles;
    private ExplanationService explanationService;
    private ExplanationRateLimiter limiter;
    private final UUID cid = UUID.fromString("3f2c0e9a-1b2c-4d5e-8f90-a1b2c3d4e5f6");

    private static AiProperties ai(boolean enabled) {
        return new AiProperties(enabled, AiProvider.OLLAMA, 3, Duration.ofSeconds(60), Duration.ofSeconds(2), 2, 20,
                2000, Duration.ofSeconds(60), Duration.ofSeconds(60), new AiProperties.Circuit(3, Duration.ofSeconds(30)),
                null, null, null);
    }

    @BeforeEach
    void mocks() {
        repository = mock(CandidateMatchRepository.class);
        matchRepository = mock(MatchJdbcRepository.class);
        ownerProfiles = mock(OwnerProfileRepository.class);
        explanationService = mock(ExplanationService.class);
        limiter = spy(new ExplanationRateLimiter(ai(true), Clock.fixed(NOW, ZoneOffset.UTC)));
    }

    private CandidateMatchService service(boolean aiEnabled) {
        return new CandidateMatchService(repository, matchRepository, ownerProfiles, new ScoringEngine(new ScoringWeights(10, 5)),
                explanationService, limiter, ai(aiEnabled), new MatchProperties(10, 100, 200),
                mock(PlatformTransactionManager.class));
    }

    /** The candidate exists, is not the owner, has no stale jobs and nothing in scope. */
    private void emptyCandidate(UUID id) {
        when(repository.findCandidate(id)).thenReturn(Optional.of(new CandidateHeader(id, null, NOW)));
        when(ownerProfiles.findRef()).thenReturn(Optional.empty());
        when(repository.findTargetJobIds(eq(id), any(), anyBoolean(), any())).thenReturn(List.of());
        when(repository.countRanked(eq(id), any(), anyDouble())).thenReturn(0L);
        when(repository.countUnmatchable(any())).thenReturn(0L);
        when(repository.candidateFacts(id)).thenReturn(List.of());
        when(repository.anyMatchable(any())).thenReturn(false);
    }

    private static void assertRateLimited(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(RegenerateRateLimitedException.class, e -> {
            assertThat(e.getCode()).isEqualTo(ErrorCode.REGENERATE_RATE_LIMITED);
            assertThat(e.getRetryAfterSeconds()).isEqualTo(60);
            assertThat(e.getMessage()).isEqualTo("Matches for this candidate were regenerated recently. You can "
                    + "regenerate again in 60 seconds; reload without regenerate=true to see the current results.");
        });
    }

    @Test
    void regenerateTakesTheCandidateSlotBeforeAnyRepositoryCallAndKeepsItOnSuccess() {
        emptyCandidate(cid);
        CandidateMatchService service = service(true);
        CandidateMatchPageView page = service.getMatches(cid, CandidateMatchQuery.defaults(), 0, 10, true);
        assertThat(page.reason()).isEqualTo(MatchReason.NO_MATCHABLE_JOBS);

        InOrder order = inOrder(limiter, repository, ownerProfiles);
        order.verify(limiter).acquireForCandidate(cid);
        order.verify(repository).findCandidate(cid);
        order.verify(ownerProfiles).findRef();
        verify(limiter, never()).release(any());
        verify(repository).findTargetJobIds(eq(cid), any(), eq(true), isNull());

        // the slot is kept: a repeat is 429 before any DB work
        org.mockito.Mockito.clearInvocations(repository, ownerProfiles, matchRepository);
        assertRateLimited(() -> service.getMatches(cid, CandidateMatchQuery.defaults(), 0, 10, true));
        verifyNoInteractions(repository, ownerProfiles, matchRepository, explanationService);
        // a plain GET is never limited
        assertThat(service.getMatches(cid, CandidateMatchQuery.defaults(), 0, 10, false)).isNotNull();
        // the job scope with the same UUID is a different slot
        assertThat(limiter.acquire(cid)).isNotNull();
    }

    @Test
    void notFoundReleasesTheSlot() {
        when(repository.findCandidate(cid)).thenReturn(Optional.empty());
        CandidateMatchService service = service(true);
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> service.getMatches(cid, CandidateMatchQuery.defaults(), 0, 10, true))
                    .as("attempt %d is 404, not 429", i)
                    .isInstanceOfSatisfying(NotFoundException.class,
                            e -> assertThat(e.getCode()).isEqualTo(ErrorCode.CANDIDATE_NOT_FOUND));
        }
        verify(limiter, org.mockito.Mockito.times(2)).acquireForCandidate(cid);
        verify(limiter, org.mockito.Mockito.times(2)).release(any(ExplanationRateLimiter.Lease.class));
        // now the candidate exists: regenerate is granted (the failed attempts left no slot behind)
        emptyCandidate(cid);
        assertThat(service.getMatches(cid, CandidateMatchQuery.defaults(), 0, 10, true)).isNotNull();
    }

    @Test
    void matchesBusyReleasesTheSlotAndCarriesTheCandidateMessage() {
        emptyCandidate(cid);
        UUID job = UUID.randomUUID();
        when(repository.findTargetJobIds(eq(cid), any(), anyBoolean(), isNull())).thenReturn(List.of(job));
        CannotAcquireLockException lock = new CannotAcquireLockException("lock timeout");
        doThrow(lock).when(matchRepository).lockCandidate(cid);
        CandidateMatchService service = service(true);

        assertThatThrownBy(() -> service.getMatches(cid, CandidateMatchQuery.defaults(), 0, 10, true))
                .isInstanceOfSatisfying(MatchesBusyException.class, e -> {
                    assertThat(e.getMessage()).isEqualTo(MatchesBusyException.CANDIDATE_MESSAGE);
                    assertThat(e.getCause()).isSameAs(lock);
                });
        verify(limiter).release(any(ExplanationRateLimiter.Lease.class));
        // not 429 on retry: the slot was freed
        assertThatThrownBy(() -> service.getMatches(cid, CandidateMatchQuery.defaults(), 0, 10, true))
                .isInstanceOf(MatchesBusyException.class);
        // without regenerate the busy exception still has the candidate message
        assertThatThrownBy(() -> service.getMatches(cid, CandidateMatchQuery.defaults(), 0, 10, false))
                .isInstanceOfSatisfying(MatchesBusyException.class,
                        e -> assertThat(e.getMessage()).isEqualTo(MatchesBusyException.CANDIDATE_MESSAGE));
    }

    @Test
    void anErrorAlsoReleasesTheSlot() {
        when(repository.findCandidate(cid)).thenThrow(new StackOverflowError("boom"));
        CandidateMatchService service = service(true);
        assertThatThrownBy(() -> service.getMatches(cid, CandidateMatchQuery.defaults(), 0, 10, true))
                .isInstanceOf(StackOverflowError.class);
        verify(limiter).release(any(ExplanationRateLimiter.Lease.class));
        org.mockito.Mockito.reset(repository);
        emptyCandidate(cid);
        assertThat(service.getMatches(cid, CandidateMatchQuery.defaults(), 0, 10, true)).isNotNull();
    }

    /** No slot acquired; a release (if any) is the no-op release(null). */
    private void assertNoSlotTaken() {
        verify(limiter, never()).acquireForCandidate(any());
        verify(limiter, never()).acquire(any());
        verify(limiter, never()).release(org.mockito.ArgumentMatchers.notNull());
    }

    @Test
    void aiDisabledNeverTouchesTheLimiter() {
        emptyCandidate(cid);
        CandidateMatchService service = service(false);
        for (int i = 0; i < 3; i++) {
            assertThat(service.getMatches(cid, CandidateMatchQuery.defaults(), 0, 10, true)).isNotNull();
        }
        when(repository.findCandidate(cid)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getMatches(cid, CandidateMatchQuery.defaults(), 0, 10, true))
                .isInstanceOf(NotFoundException.class);
        assertNoSlotTaken();
        // regenerate still rescores everything in scope (forced targets)
        verify(repository, org.mockito.Mockito.atLeastOnce()).findTargetJobIds(eq(cid), any(), eq(true), isNull());
    }

    @Test
    void noRegenerateNeverTouchesTheLimiter() {
        emptyCandidate(cid);
        service(true).getMatches(cid, CandidateMatchQuery.defaults(), 0, 10, false);
        when(repository.findCandidate(cid)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service(true).getMatches(cid, CandidateMatchQuery.defaults(), 0, 10, false))
                .isInstanceOf(NotFoundException.class);
        assertNoSlotTaken();
    }

    @Test
    void ownerMatchesWithoutAProfileIsProfileNotFoundBeforeTheLimiter() {
        when(ownerProfiles.findCandidateId()).thenReturn(Optional.empty());
        CandidateMatchService service = service(true);
        for (boolean regenerate : new boolean[] {true, true, false}) {
            assertThatThrownBy(() -> service.getOwnerMatches(CandidateMatchQuery.defaults(), 0, 10, regenerate))
                    .isInstanceOfSatisfying(NotFoundException.class, e -> {
                        assertThat(e.getCode()).isEqualTo(ErrorCode.PROFILE_NOT_FOUND);
                        assertThat(((ApiException) e).getStatus().value()).isEqualTo(404);
                    });
        }
        verifyNoInteractions(limiter, repository, matchRepository, explanationService);
    }

    @Test
    void ownerMatchesUseTheOwnersCandidateSlotSharedWithTheCandidatePath() {
        UUID owner = UUID.randomUUID();
        emptyCandidate(owner);
        when(ownerProfiles.findCandidateId()).thenReturn(Optional.of(owner));
        when(ownerProfiles.findRef()).thenReturn(Optional.of(new OwnerProfileRepository.OwnerRef(owner, 4)));
        CandidateMatchService service = service(true);

        CandidateMatchPageView page = service.getOwnerMatches(CandidateMatchQuery.defaults(), 0, 10, true);
        assertThat(page.candidateId()).isEqualTo(owner);
        assertThat(page.owner()).isTrue();
        assertThat(page.profileVersion()).isEqualTo(4);
        InOrder order = inOrder(ownerProfiles, limiter, repository);
        order.verify(ownerProfiles).findCandidateId();
        order.verify(limiter).acquireForCandidate(owner);
        order.verify(repository).findCandidate(owner);

        assertRateLimited(() -> service.getMatches(owner, CandidateMatchQuery.defaults(), 0, 10, true));
        assertRateLimited(() -> service.getOwnerMatches(CandidateMatchQuery.defaults(), 0, 10, true));
        // the owner scope hides FILTERED jobs by default
        verify(repository).findTargetJobIds(eq(owner), org.mockito.ArgumentMatchers.argThat(Scope::hideFiltered),
                eq(true), isNull());
    }
}
