package com.talentmatch.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.talentmatch.feed.CompanyLookupService.Found;
import com.talentmatch.feed.CompanyLookupService.LookupResult;
import com.talentmatch.feed.CompanyLookupService.Unchecked;
import com.talentmatch.feed.source.BoardInfo;
import com.talentmatch.feed.source.SourceFailure;
import com.talentmatch.feed.source.SourceKind;
import com.talentmatch.service.exception.FeedLookupRateLimitedException;
import com.talentmatch.service.exception.RequestValidationException;
import com.talentmatch.support.MutableClock;
import com.talentmatch.web.error.ErrorCode;
import com.talentmatch.web.error.FieldErrorDto;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;

/** Step 12: the company lookup, prober and repository mocked, fake clock and nano time. */
class CompanyLookupServiceTest {

    private static final String NAME = "Acme Payments (Pty) Ltd";
    private static final List<String> SLUGS = List.of("acmepayments", "acme-payments", "acme");

    private SourceProber prober;
    private FeedSourceRepository repo;
    private MutableClock clock;
    private final AtomicLong nanos = new AtomicLong(1_000_000_000L);

    @BeforeEach
    void setUp() {
        prober = mock(SourceProber.class);
        repo = mock(FeedSourceRepository.class);
        clock = MutableClock.fixedAt(Instant.parse("2026-10-13T10:00:00Z"));
        when(repo.findByKey(anyString())).thenReturn(Optional.empty());
        when(prober.probe(any(), anyString(), anyMap(), any(Duration.class))).thenReturn(new SourceProber.NotFound());
    }

    private static FeedProperties props(Duration probeTimeout, FeedProperties.Lookup lookup) {
        return new FeedProperties(true, null, null, probeTimeout, null, null, null, null, lookup);
    }

    private CompanyLookupService service(Duration probeTimeout, FeedProperties.Lookup lookup) {
        return new CompanyLookupService(prober, repo, props(probeTimeout, lookup), clock, nanos::get);
    }

    private CompanyLookupService service() {
        return service(Duration.ofSeconds(10), FeedProperties.Lookup.defaults());
    }

    private static FeedSource watched(UUID id, String key, SourceKind kind, String token, String company,
                                      int openPostings) {
        Instant now = Instant.now();
        return new FeedSource(id, key, kind, SourceManagedBy.OWNER, FeedSourceState.ACTIVE, company, token, Map.of(),
                null, now, null, null, null, null, null, 0, null, null, null, null, null, openPostings, now, now);
    }

    private void probeReturns(SourceKind kind, String slug, SourceProber.Result result) {
        when(prober.probe(eq(kind), eq(slug), anyMap(), any(Duration.class))).thenReturn(result);
    }

    // ------------------------------------------------------------------ order and mapping

    @Test
    void probesSlugMajorGreenhouseLeverAshbyAndNeverAdzuna() {
        LookupResult r = service().lookup(NAME);
        assertThat(r.candidates()).isEqualTo(SLUGS);
        assertThat(r.companyName()).isEqualTo(NAME);
        assertThat(r.found()).isEmpty();
        assertThat(r.unchecked()).isEmpty();
        assertThat(r.complete()).isTrue();

        InOrder order = inOrder(repo, prober);
        for (String slug : SLUGS) {
            order.verify(repo).findByKey("greenhouse:" + slug);
            order.verify(prober).probe(eq(SourceKind.GREENHOUSE), eq(slug), eq(Map.of()), any(Duration.class));
            order.verify(repo).findByKey("lever:" + slug);
            order.verify(prober).probe(eq(SourceKind.LEVER), eq(slug), eq(Map.of()), any(Duration.class));
            order.verify(repo).findByKey("ashby:" + slug);
            order.verify(prober).probe(eq(SourceKind.ASHBY), eq(slug), eq(Map.of()), any(Duration.class));
        }
        verify(prober, never()).probe(eq(SourceKind.ADZUNA), anyString(), anyMap(), any(Duration.class));
        verify(prober, never()).probe(any(), anyString(), anyMap());
        verify(prober, times(9)).probe(any(), anyString(), anyMap(), any(Duration.class));
        verify(repo, times(9)).findByKey(anyString());
        verifyNoMoreInteractions(repo, prober);
    }

    @Test
    void foundNotFoundAndUncheckedMapping() {
        probeReturns(SourceKind.GREENHOUSE, "acmepayments",
                new SourceProber.Found(new BoardInfo("  Acme\tPayments  ", null, null)));
        probeReturns(SourceKind.LEVER, "acmepayments",
                new SourceProber.Found(new BoardInfo(null, 0, BoardInfo.NO_OPEN_POSTINGS)));
        probeReturns(SourceKind.ASHBY, "acmepayments", new SourceProber.Unchecked(SourceFailure.Kind.SERVER_ERROR));
        probeReturns(SourceKind.GREENHOUSE, "acme-payments", new SourceProber.Unchecked(null));
        probeReturns(SourceKind.LEVER, "acme-payments", new SourceProber.Unchecked(SourceFailure.Kind.RATE_LIMITED));
        probeReturns(SourceKind.ASHBY, "acme", new SourceProber.Found(new BoardInfo(null, 4, null)));

        LookupResult r = service().lookup(NAME);
        assertThat(r.found()).containsExactly(
                new Found(SourceKind.GREENHOUSE, "acmepayments", "Acme Payments", null, null, null),
                new Found(SourceKind.LEVER, "acmepayments", null, 0, BoardInfo.NO_OPEN_POSTINGS, null),
                new Found(SourceKind.ASHBY, "acme", null, 4, null, null));
        assertThat(r.unchecked()).containsExactly(
                new Unchecked(SourceKind.ASHBY, "acmepayments", "SERVER_ERROR"),
                new Unchecked(SourceKind.GREENHOUSE, "acme-payments", CompanyLookupService.REASON_BUSY),
                new Unchecked(SourceKind.LEVER, "acme-payments", "RATE_LIMITED"));
        assertThat(r.complete()).as("provider trouble doesn't make it incomplete").isTrue();
    }

    @Test
    void everyFailureKindIsItsName() {
        for (SourceFailure.Kind kind : SourceFailure.Kind.values()) {
            if (kind == SourceFailure.Kind.NOT_FOUND) {
                continue;
            }
            probeReturns(SourceKind.GREENHOUSE, "acme", new SourceProber.Unchecked(kind));
            clock.advance(Duration.ofMinutes(1));
            LookupResult r = service().lookup("Acme");
            assertThat(r.unchecked()).containsExactly(new Unchecked(SourceKind.GREENHOUSE, "acme", kind.name()));
        }
    }

    @Test
    void longProviderNameIsTruncatedTo200() {
        probeReturns(SourceKind.GREENHOUSE, "acme",
                new SourceProber.Found(new BoardInfo("x".repeat(250), null, null)));
        LookupResult r = service().lookup("Acme");
        assertThat(r.found().get(0).companyName()).hasSize(200);
    }

    @Test
    void companyNameIsNormalized() {
        LookupResult r = service().lookup("  Acme \n  Payments ");
        assertThat(r.companyName()).isEqualTo("Acme Payments");
    }

    // ------------------------------------------------------------------ watched boards

    @Test
    void watchedBoardIsFoundWithSourceIdAndNotProbed() {
        UUID id = UUID.randomUUID();
        when(repo.findByKey("lever:acme"))
                .thenReturn(Optional.of(watched(id, "lever:acme", SourceKind.LEVER, "acme", "Acme Ltd", 12)));
        LookupResult r = service().lookup("Acme");
        assertThat(r.found()).containsExactly(
                new Found(SourceKind.LEVER, "acme", "Acme Ltd", 12, CompanyLookupService.ALREADY_WATCHED, id));
        verify(prober, never()).probe(eq(SourceKind.LEVER), anyString(), anyMap(), any(Duration.class));
        verify(prober).probe(eq(SourceKind.GREENHOUSE), eq("acme"), anyMap(), any(Duration.class));
        verify(prober).probe(eq(SourceKind.ASHBY), eq("acme"), anyMap(), any(Duration.class));
    }

    @Test
    void watchedBoardStillReportedAfterBudgetRunsOut() {
        UUID id = UUID.randomUUID();
        when(repo.findByKey("ashby:acme"))
                .thenReturn(Optional.of(watched(id, "ashby:acme", SourceKind.ASHBY, "acme", "Acme", 3)));
        // first probe eats the whole budget
        when(prober.probe(eq(SourceKind.GREENHOUSE), eq("acmepayments"), anyMap(), any(Duration.class)))
                .thenAnswer(inv -> {
                    nanos.addAndGet(Duration.ofSeconds(11).toNanos());
                    return new SourceProber.Unchecked(SourceFailure.Kind.TIMEOUT);
                });
        LookupResult r = service().lookup(NAME);
        assertThat(r.complete()).isFalse();
        assertThat(r.found()).containsExactly(
                new Found(SourceKind.ASHBY, "acme", "Acme", 3, CompanyLookupService.ALREADY_WATCHED, id));
        assertThat(r.unchecked()).hasSize(8);
        assertThat(r.unchecked().get(0)).isEqualTo(new Unchecked(SourceKind.GREENHOUSE, "acmepayments", "TIMEOUT"));
        assertThat(r.unchecked().subList(1, 8)).allSatisfy(u ->
                assertThat(u.reason()).isEqualTo(CompanyLookupService.REASON_BUDGET_EXCEEDED));
        assertThat(r.unchecked().subList(1, 8)).noneMatch(u -> u.kind() == SourceKind.ASHBY && u.boardToken().equals("acme"));
        verify(prober, times(1)).probe(any(), anyString(), anyMap(), any(Duration.class));
    }

    // ------------------------------------------------------------------ budget

    @Test
    void perProbeTimeoutIsMinOfProbeTimeoutAndRemainingBudget() {
        // budget 10s, probe-timeout 5s, every probe takes 4s
        when(prober.probe(any(), anyString(), anyMap(), any(Duration.class))).thenAnswer(inv -> {
            nanos.addAndGet(Duration.ofSeconds(4).toNanos());
            return new SourceProber.NotFound();
        });
        CompanyLookupService s = service(Duration.ofSeconds(5),
                new FeedProperties.Lookup(Duration.ofSeconds(10), Duration.ofSeconds(10), 3));
        LookupResult r = s.lookup(NAME);

        ArgumentCaptor<Duration> waits = ArgumentCaptor.forClass(Duration.class);
        verify(prober, times(3)).probe(any(), anyString(), anyMap(), waits.capture());
        assertThat(waits.getAllValues()).containsExactly(Duration.ofSeconds(5), Duration.ofSeconds(5),
                Duration.ofSeconds(2));
        assertThat(r.complete()).isFalse();
        assertThat(r.unchecked()).hasSize(6).allSatisfy(u ->
                assertThat(u.reason()).isEqualTo(CompanyLookupService.REASON_BUDGET_EXCEEDED));
        assertThat(r.unchecked().get(0)).isEqualTo(new Unchecked(SourceKind.GREENHOUSE, "acme-payments",
                CompanyLookupService.REASON_BUDGET_EXCEEDED));
        assertThat(r.unchecked().get(5)).isEqualTo(new Unchecked(SourceKind.ASHBY, "acme",
                CompanyLookupService.REASON_BUDGET_EXCEEDED));
    }

    @Test
    void lessThanOneSecondLeftIsBudgetExceeded() {
        when(prober.probe(any(), anyString(), anyMap(), any(Duration.class))).thenAnswer(inv -> {
            nanos.addAndGet(Duration.ofMillis(9_001).toNanos());
            return new SourceProber.NotFound();
        });
        LookupResult r = service().lookup("Acme");
        verify(prober, times(1)).probe(any(), anyString(), anyMap(), any(Duration.class));
        assertThat(r.unchecked()).containsExactly(
                new Unchecked(SourceKind.LEVER, "acme", CompanyLookupService.REASON_BUDGET_EXCEEDED),
                new Unchecked(SourceKind.ASHBY, "acme", CompanyLookupService.REASON_BUDGET_EXCEEDED));
        assertThat(r.complete()).isFalse();
    }

    @Test
    void exactlyOneSecondLeftStillProbes() {
        when(prober.probe(any(), anyString(), anyMap(), any(Duration.class))).thenAnswer(inv -> {
            nanos.addAndGet(Duration.ofMillis(4_500).toNanos());
            return new SourceProber.NotFound();
        });
        LookupResult r = service().lookup("Acme");
        ArgumentCaptor<Duration> waits = ArgumentCaptor.forClass(Duration.class);
        verify(prober, times(3)).probe(any(), anyString(), anyMap(), waits.capture());
        assertThat(waits.getAllValues()).containsExactly(Duration.ofSeconds(10), Duration.ofMillis(5_500),
                Duration.ofSeconds(1));
        assertThat(r.complete()).isTrue();
    }

    @Test
    void maxCandidatesSetting() {
        CompanyLookupService s = service(Duration.ofSeconds(10),
                new FeedProperties.Lookup(Duration.ofSeconds(10), Duration.ofSeconds(10), 1));
        assertThat(s.lookup(NAME).candidates()).containsExactly("acmepayments");
        verify(prober, times(3)).probe(any(), anyString(), anyMap(), any(Duration.class));
    }

    // ------------------------------------------------------------------ rate gate

    @Test
    void repeatWithinMinIntervalIs429WithRetryAfter() {
        CompanyLookupService s = service();
        s.lookup("Acme");
        clock.advance(Duration.ofMillis(3_200));
        FeedLookupRateLimitedException e = catchThrowableOfType(() -> s.lookup("Acme"),
                FeedLookupRateLimitedException.class);
        assertThat(e).isNotNull();
        assertThat(e.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(e.getCode()).isEqualTo(ErrorCode.FEED_LOOKUP_RATE_LIMITED);
        assertThat(e.isBusy()).isFalse();
        assertThat(e.getRetryAfterSeconds()).isEqualTo(7);
        assertThat(e.getMessage()).contains("less than 10 seconds ago").contains("7 seconds");

        // a rejected call doesn't move the window
        clock.advance(Duration.ofMillis(6_800));
        assertThat(s.lookup("Acme").candidates()).containsExactly("acme");
    }

    @Test
    void retryAfterIsAtLeastOne() {
        CompanyLookupService s = service();
        s.lookup("Acme");
        clock.advance(Duration.ofMillis(9_999));
        FeedLookupRateLimitedException e = catchThrowableOfType(() -> s.lookup("Acme"),
                FeedLookupRateLimitedException.class);
        assertThat(e.getRetryAfterSeconds()).isEqualTo(1);
        assertThat(e.getMessage()).contains("1 second.");
    }

    @Test
    void zeroMinIntervalAllowsBackToBack() {
        CompanyLookupService s = service(Duration.ofSeconds(10),
                new FeedProperties.Lookup(Duration.ofSeconds(10), Duration.ZERO, 3));
        s.lookup("Acme");
        s.lookup("Acme");
        verify(prober, times(6)).probe(any(), anyString(), anyMap(), any(Duration.class));
    }

    @Test
    void concurrentLookupIs429BusyAndSlotIsFreedAfterwards() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(prober.probe(eq(SourceKind.GREENHOUSE), eq("acme"), anyMap(), any(Duration.class))).thenAnswer(inv -> {
            entered.countDown();
            release.await(10, TimeUnit.SECONDS);
            return new SourceProber.NotFound();
        });
        CompanyLookupService s = service(Duration.ofSeconds(10),
                new FeedProperties.Lookup(Duration.ofMillis(7_500), Duration.ZERO, 3));
        CompletableFuture<LookupResult> first = CompletableFuture.supplyAsync(() -> s.lookup("Acme"));
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        try {
            FeedLookupRateLimitedException e = catchThrowableOfType(() -> s.lookup("Other"),
                    FeedLookupRateLimitedException.class);
            assertThat(e).isNotNull();
            assertThat(e.isBusy()).isTrue();
            assertThat(e.getRetryAfterSeconds()).as("ceil(budget)").isEqualTo(8);
            assertThat(e.getMessage()).contains("Another company lookup is running");
            // validation still answers 400 while busy
            catchThrowableOfType(() -> s.lookup(" "), RequestValidationException.class);
        } finally {
            release.countDown();
        }
        assertThat(first.get(5, TimeUnit.SECONDS).complete()).isTrue();
        // the slot is free again (min-interval 0)
        assertThat(s.lookup("Other").candidates()).containsExactly("other");
    }

    @Test
    void slotIsReleasedWhenTheLookupThrows() {
        when(prober.probe(any(), anyString(), anyMap(), any(Duration.class))).thenThrow(new IllegalStateException("x"));
        CompanyLookupService s = service(Duration.ofSeconds(10),
                new FeedProperties.Lookup(Duration.ofSeconds(10), Duration.ZERO, 3));
        catchThrowableOfType(() -> s.lookup("Acme"), IllegalStateException.class);
        when(prober.probe(any(), anyString(), anyMap(), any(Duration.class))).thenReturn(new SourceProber.NotFound());
        assertThat(s.lookup("Acme").complete()).isTrue();
    }

    // ------------------------------------------------------------------ validation

    private static List<FieldErrorDto> invalid(CompanyLookupService s, String name) {
        RequestValidationException e = catchThrowableOfType(() -> s.lookup(name), RequestValidationException.class);
        assertThat(e).as("400 for %s", name).isNotNull();
        assertThat(e.getCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        return e.getFieldErrors();
    }

    @Test
    void validationErrorsDontUseASlot() {
        CompanyLookupService s = service();
        List<String> bad = new ArrayList<>(List.of("", "   ", "\t\n", "!!!", "Яндекс", "x".repeat(201)));
        bad.add(null);
        for (String name : bad) {
            List<FieldErrorDto> errors = invalid(s, name);
            assertThat(errors).extracting(FieldErrorDto::field).containsExactly("companyName");
        }
        assertThat(invalid(s, "x".repeat(201)).get(0).message()).contains("200");
        assertThat(invalid(s, null).get(0).message()).contains("required");
        assertThat(invalid(s, "Яндекс").get(0).message()).contains("boardToken");
        verifyNoInteractions(prober, repo);

        // no slot used: a valid lookup runs straight away, without moving the clock
        assertThat(s.lookup("Acme").candidates()).containsExactly("acme");
        verify(repo, atLeastOnce()).findByKey(anyString());
    }

    @Test
    void exactly200CharactersIsAccepted() {
        String name = "Acme " + "x".repeat(195);
        LookupResult r = service().lookup(name);
        assertThat(r.companyName()).hasSize(200);
        assertThat(r.candidates()).as("joined/hyphenated forms are over 100 chars").containsExactly("acme");
    }

    @Test
    void singleWordOver100CharsIsRejectedAsHavingNoUsableBoardName() {
        // 101..200 letters in one word: letters exist, but no guess is a valid board token (max 100 chars).
        for (int len : new int[] {101, 150, 200}) {
            List<FieldErrorDto> errors = invalid(service(), "a".repeat(len));
            assertThat(errors).extracting(FieldErrorDto::field).containsExactly("companyName");
            assertThat(errors.get(0).message()).isEqualTo(NO_USABLE_BOARD_NAME);
        }
    }

    @Test
    void noLettersOrDigitsKeepsItsOwnMessage() {
        for (String name : List.of("!!!", "...", "Яндекс")) {
            assertThat(invalid(service(), name).get(0).message()).isEqualTo(NO_LETTERS_OR_DIGITS);
        }
    }

    @Test
    void exactly100CharWordIsAccepted() {
        assertThat(service().lookup("a".repeat(100)).candidates()).containsExactly("a".repeat(100));
    }

    static final String NO_LETTERS_OR_DIGITS = "companyName has no letters or digits (a\u2013z, 0\u20139) to build a "
            + "board name from. Add the source with the boardToken from its board URL instead.";
    static final String NO_USABLE_BOARD_NAME = "companyName gives no usable board name (board names are at most 100 "
            + "characters of a\u2013z, 0\u20139 and -). Add the source with the boardToken from its board URL instead.";
}
