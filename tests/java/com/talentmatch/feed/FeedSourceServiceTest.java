package com.talentmatch.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.talentmatch.feed.FeedSourceService.NewSource;
import com.talentmatch.feed.FeedSourceService.SourceChanges;
import com.talentmatch.feed.FeedSourceService.SourceOptions;
import com.talentmatch.feed.source.BoardInfo;
import com.talentmatch.feed.source.SourceFailure;
import com.talentmatch.feed.source.SourceKind;
import com.talentmatch.service.exception.ApiException;
import com.talentmatch.service.exception.ConflictException;
import com.talentmatch.service.exception.NotFoundException;
import com.talentmatch.service.exception.RequestValidationException;
import com.talentmatch.web.error.ErrorCode;
import com.talentmatch.web.error.FieldErrorDto;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

/** §5.1 source CRUD service, repository and prober mocked. */
class FeedSourceServiceTest {

    private FeedSourceRepository repo;
    private SourceProber prober;
    private FeedSourceService service;

    @BeforeEach
    void setUp() {
        repo = mock(FeedSourceRepository.class);
        prober = mock(SourceProber.class);
        service = new FeedSourceService(repo, prober, FeedProperties.defaults());
        when(repo.findByKey(anyString())).thenReturn(Optional.empty());
        when(repo.insertIfAbsent(any())).thenAnswer(inv -> {
            FeedSourceRepository.NewSource n = inv.getArgument(0);
            return Optional.of(source(UUID.randomUUID(), n.sourceKey(), n.kind(), SourceManagedBy.OWNER,
                    FeedSourceState.ACTIVE, n.companyName(), n.boardToken(), n.options(), n.pollIntervalSeconds()));
        });
    }

    static FeedSource source(UUID id, String key, SourceKind kind, SourceManagedBy managedBy, FeedSourceState state,
                             String companyName, String token, Map<String, String> options, Integer interval) {
        Instant now = Instant.now();
        return new FeedSource(id, key, kind, managedBy, state, companyName, token, options, interval, now, null, null,
                null, null, null, 0, null, null, null, null, null, 0, now, now);
    }

    private static NewSource req(SourceKind kind, String token) {
        return new NewSource(kind, token, null, null, null, null);
    }

    private static Map<String, String> fieldErrors(Throwable t) {
        assertThat(t).isInstanceOf(RequestValidationException.class);
        return ((ApiException) t).getFieldErrors().stream()
                .collect(Collectors.toMap(FieldErrorDto::field, FieldErrorDto::message, (a, b) -> a + " | " + b,
                        LinkedHashMap::new));
    }

    private Map<String, String> createErrors(NewSource request) {
        RequestValidationException e = catchThrowableOfType(() -> service.create(request),
                RequestValidationException.class);
        assertThat(e).as("expected a 400 for %s", request).isNotNull();
        return fieldErrors(e);
    }

    private void probeReturns(SourceProber.Result result) {
        when(prober.probe(any(), anyString(), anyMap())).thenReturn(result);
    }

    // ------------------------------------------------------------------ field validation

    @Test
    void kindMissing() {
        Map<String, String> errors = createErrors(req(null, "acme"));
        assertThat(errors).containsOnlyKeys("kind");
        assertThat(errors.get("kind")).contains("kind is required").contains("GREENHOUSE, LEVER or ASHBY");
        verifyNoInteractions(prober);
        verify(repo, never()).insertIfAbsent(any());
    }

    @Test
    void nullRequestIsKindMissing() {
        assertThat(createErrors(null)).containsKey("kind");
    }

    @Test
    void adzunaRejected() {
        Map<String, String> errors = createErrors(req(SourceKind.ADZUNA, null));
        assertThat(errors).containsOnlyKeys("kind");
        assertThat(errors.get("kind")).contains("Adzuna sources can't be added yet");
        verifyNoInteractions(prober);
    }

    @Test
    void tokenMissing() {
        for (String token : new String[] {null, "", "   "}) {
            Map<String, String> errors = createErrors(req(SourceKind.GREENHOUSE, token));
            assertThat(errors).containsOnlyKeys("boardToken");
            assertThat(errors.get("boardToken")).contains("boardToken is required")
                    .contains("boards.greenhouse.io/<token>");
        }
        assertThat(createErrors(req(SourceKind.LEVER, null)).get("boardToken")).contains("jobs.lever.co/<site>");
        assertThat(createErrors(req(SourceKind.ASHBY, null)).get("boardToken")).contains("jobs.ashbyhq.com/<name>");
    }

    @Test
    void tokenBad() {
        for (String token : new String[] {"a b", "a/b", "..", "x".repeat(101), "acme:eu"}) {
            Map<String, String> errors = createErrors(req(SourceKind.ASHBY, token));
            assertThat(errors).as(token).containsOnlyKeys("boardToken");
            assertThat(errors.get("boardToken")).contains("may only contain letters, digits");
        }
        verifyNoInteractions(prober);
    }

    @Test
    void tokenIsTrimmed() {
        probeReturns(new SourceProber.Found(new BoardInfo(null, null, null)));
        service.create(req(SourceKind.ASHBY, "  acme  "));
        verify(prober).probe(SourceKind.ASHBY, "acme", Map.of());
        verify(repo).findByKey("ashby:acme");
    }

    @Test
    void optionsOnTheWrongKind() {
        Map<String, String> errors = createErrors(new NewSource(SourceKind.GREENHOUSE, "acme", null,
                new SourceOptions("eu", "java", "x"), null, null));
        assertThat(errors).containsOnlyKeys("options.leverInstance", "options.what", "options.where");
        assertThat(errors.get("options.leverInstance")).contains("only for LEVER");
        assertThat(errors.get("options.what")).contains("only for ADZUNA");
        assertThat(errors.get("options.where")).contains("only for ADZUNA");

        assertThat(createErrors(new NewSource(SourceKind.LEVER, "acme", null, new SourceOptions(null, "java", null),
                null, null))).containsOnlyKeys("options.what");
    }

    @Test
    void badLeverInstance() {
        Map<String, String> errors = createErrors(new NewSource(SourceKind.LEVER, "acme", null,
                new SourceOptions("us", null, null), null, null));
        assertThat(errors).containsOnlyKeys("options.leverInstance");
        assertThat(errors.get("options.leverInstance")).contains("\"eu\"").contains("\"global\"");
    }

    @Test
    void intervalOutOfRange() {
        for (int seconds : new int[] {119, 60, 0, -5, 86_401}) {
            Map<String, String> errors = createErrors(new NewSource(SourceKind.GREENHOUSE, "acme", null, null,
                    seconds, null));
            assertThat(errors).as("%d", seconds).containsOnlyKeys("pollIntervalSeconds");
            assertThat(errors.get("pollIntervalSeconds")).contains("between 120 and 86400").contains("300");
        }
        verifyNoInteractions(prober);
    }

    @Test
    void intervalBoundsAccepted() {
        probeReturns(new SourceProber.Found(new BoardInfo(null, null, null)));
        assertThat(service.create(new NewSource(SourceKind.LEVER, "a", null, null, 120, null))
                .effectivePollIntervalSeconds()).isEqualTo(120);
        assertThat(service.create(new NewSource(SourceKind.LEVER, "b", null, null, 86_400, null))
                .effectivePollIntervalSeconds()).isEqualTo(86_400);
        assertThat(service.create(req(SourceKind.LEVER, "c")).effectivePollIntervalSeconds()).isEqualTo(300);
    }

    @Test
    void companyNameTooLong() {
        Map<String, String> errors = createErrors(new NewSource(SourceKind.GREENHOUSE, "acme", "n".repeat(201), null,
                null, null));
        assertThat(errors).containsOnlyKeys("companyName");
        assertThat(errors.get("companyName")).contains("at most 200");
    }

    @Test
    void companyNameNormalizedAndExactly200Allowed() {
        probeReturns(new SourceProber.Found(new BoardInfo("Board Name", null, null)));
        FeedSourceView v = service.create(new NewSource(SourceKind.GREENHOUSE, "acme", "  Acme \n\t Inc\u0007 ",
                null, null, null));
        assertThat(v.source().companyName()).isEqualTo("Acme Inc");
        FeedSourceView v2 = service.create(new NewSource(SourceKind.GREENHOUSE, "acme2", "n".repeat(200), null,
                null, null));
        assertThat(v2.source().companyName()).hasSize(200);
    }

    @Test
    void severalErrorsAtOnce() {
        Map<String, String> errors = createErrors(new NewSource(SourceKind.LEVER, "a b", "n".repeat(201),
                new SourceOptions("mars", null, null), 1, null));
        assertThat(errors).containsOnlyKeys("boardToken", "companyName", "options.leverInstance",
                "pollIntervalSeconds");
    }

    // ------------------------------------------------------------------ probe outcomes

    @Test
    void probeNotFoundIs400OnBoardToken() {
        probeReturns(new SourceProber.NotFound());
        Map<String, String> errors = createErrors(req(SourceKind.GREENHOUSE, "acme"));
        assertThat(errors).containsOnlyKeys("boardToken");
        assertThat(errors.get("boardToken")).isEqualTo("Greenhouse has no job board 'acme'. Check the token in the "
                + "board URL (boards.greenhouse.io/<token>).");
        verify(repo, never()).insertIfAbsent(any());
    }

    @Test
    void probeNotFoundLeverEuMentionsInstance() {
        probeReturns(new SourceProber.NotFound());
        Map<String, String> errors = createErrors(new NewSource(SourceKind.LEVER, "Acme", null,
                new SourceOptions("EU", null, null), null, null));
        assertThat(errors.get("boardToken")).contains("on its EU instance").contains("case-sensitive");
        verify(prober).probe(SourceKind.LEVER, "Acme", Map.of("leverInstance", "eu"));
    }

    @Test
    void foundWithNoOpenPostingsWarns() {
        probeReturns(new SourceProber.Found(new BoardInfo(null, 0, BoardInfo.NO_OPEN_POSTINGS)));
        FeedSourceView v = service.create(req(SourceKind.LEVER, "acme"));
        assertThat(v.warnings()).containsExactly(BoardInfo.NO_OPEN_POSTINGS);
        verify(repo).insertIfAbsent(any());
    }

    @Test
    void foundNameFillsEmptyCompanyName() {
        probeReturns(new SourceProber.Found(new BoardInfo("  Acme\nCorp  ", null, null)));
        FeedSourceView v = service.create(new NewSource(SourceKind.GREENHOUSE, "acme", "   ", null, null, null));
        assertThat(v.source().companyName()).isEqualTo("Acme Corp");
        assertThat(v.warnings()).isEmpty();
    }

    @Test
    void foundNameDoesNotOverrideOwnerName() {
        probeReturns(new SourceProber.Found(new BoardInfo("Board Name", null, null)));
        assertThat(service.create(new NewSource(SourceKind.GREENHOUSE, "acme", "Mine", null, null, null))
                .source().companyName()).isEqualTo("Mine");
    }

    @Test
    void foundNameOver200IsTruncated() {
        probeReturns(new SourceProber.Found(new BoardInfo("x".repeat(250), null, null)));
        assertThat(service.create(req(SourceKind.GREENHOUSE, "acme")).source().companyName()).hasSize(200);
    }

    @Test
    void uncheckedWarns() {
        probeReturns(new SourceProber.Unchecked(SourceFailure.Kind.NETWORK));
        FeedSourceView v = service.create(req(SourceKind.LEVER, "acme"));
        assertThat(v.warnings()).containsExactly(
                "Couldn't reach Lever to check the board; it will be checked on the first poll.");
        verify(repo).insertIfAbsent(any());
    }

    @Test
    void uncheckedWarningPerFailureKind() {
        for (SourceFailure.Kind kind : SourceFailure.Kind.values()) {
            assertThat(FeedSourceService.uncheckedWarning(SourceKind.ASHBY, kind)).as(kind.name())
                    .contains("Ashby").endsWith("it will be checked on the first poll.");
        }
        assertThat(FeedSourceService.uncheckedWarning(SourceKind.GREENHOUSE, null))
                .isEqualTo("Couldn't check the Greenhouse board right now; it will be checked on the first poll.");
        assertThat(FeedSourceService.uncheckedWarning(SourceKind.LEVER, SourceFailure.Kind.TIMEOUT))
                .startsWith("Couldn't reach Lever");
        assertThat(FeedSourceService.uncheckedWarning(SourceKind.LEVER, SourceFailure.Kind.RATE_LIMITED))
                .contains("limiting requests");
    }

    @Test
    void verifyFalseSkipsProbeAndWarns() {
        FeedSourceView v = service.create(new NewSource(SourceKind.GREENHOUSE, "acme", null, null, null, false));
        verifyNoInteractions(prober);
        assertThat(v.warnings()).singleElement().asString().contains("verify=false");
        assertThat(v.source().companyName()).isNull();
    }

    @Test
    void insertCarriesKeyAndValues() {
        probeReturns(new SourceProber.Found(new BoardInfo(null, null, null)));
        service.create(new NewSource(SourceKind.LEVER, "Acme", "Acme", new SourceOptions("eu", null, null), 600, true));
        ArgumentCaptor<FeedSourceRepository.NewSource> cap = ArgumentCaptor.forClass(FeedSourceRepository.NewSource.class);
        verify(repo).insertIfAbsent(cap.capture());
        FeedSourceRepository.NewSource n = cap.getValue();
        assertThat(n.sourceKey()).isEqualTo("lever:eu:Acme");
        assertThat(n.kind()).isEqualTo(SourceKind.LEVER);
        assertThat(n.managedBy()).isEqualTo(SourceManagedBy.OWNER);
        assertThat(n.boardToken()).isEqualTo("Acme");
        assertThat(n.options()).isEqualTo(Map.of("leverInstance", "eu"));
        assertThat(n.pollIntervalSeconds()).isEqualTo(600);
    }

    @Test
    void leverGlobalInstanceStoresNoOptions() {
        probeReturns(new SourceProber.Found(new BoardInfo(null, null, null)));
        service.create(new NewSource(SourceKind.LEVER, "acme", null, new SourceOptions("global", null, null), null,
                null));
        ArgumentCaptor<FeedSourceRepository.NewSource> cap = ArgumentCaptor.forClass(FeedSourceRepository.NewSource.class);
        verify(repo).insertIfAbsent(cap.capture());
        assertThat(cap.getValue().options()).isEmpty();
        assertThat(cap.getValue().sourceKey()).isEqualTo("lever:acme");
    }

    // ------------------------------------------------------------------ conflicts

    @Test
    void existingKeyIs409WithIdAndNoProbe() {
        UUID id = UUID.randomUUID();
        when(repo.findByKey("greenhouse:acme")).thenReturn(Optional.of(source(id, "greenhouse:acme",
                SourceKind.GREENHOUSE, SourceManagedBy.OWNER, FeedSourceState.ACTIVE, null, "acme", Map.of(), null)));
        ConflictException e = catchThrowableOfType(() -> service.create(req(SourceKind.GREENHOUSE, "ACME")),
                ConflictException.class);
        assertThat(e).isNotNull();
        assertThat(e.getCode()).isEqualTo(ErrorCode.FEED_SOURCE_ALREADY_EXISTS);
        assertThat(e.getMessage()).contains(id.toString());
        verifyNoInteractions(prober);
        verify(repo, never()).insertIfAbsent(any());
    }

    @Test
    void concurrentInsertWithKeyFoundIs409() {
        UUID id = UUID.randomUUID();
        probeReturns(new SourceProber.Found(new BoardInfo(null, null, null)));
        org.mockito.Mockito.doReturn(Optional.empty()).when(repo).insertIfAbsent(any());
        when(repo.findByKey("ashby:acme")).thenReturn(Optional.empty(), Optional.of(source(id, "ashby:acme",
                SourceKind.ASHBY, SourceManagedBy.OWNER, FeedSourceState.ACTIVE, null, "acme", Map.of(), null)));
        ConflictException e = catchThrowableOfType(() -> service.create(req(SourceKind.ASHBY, "acme")),
                ConflictException.class);
        assertThat(e.getCode()).isEqualTo(ErrorCode.FEED_SOURCE_ALREADY_EXISTS);
        assertThat(e.getMessage()).contains(id.toString());
    }

    @Test
    void concurrentInsertWithKeyGoneIsDataConflict() {
        probeReturns(new SourceProber.Found(new BoardInfo(null, null, null)));
        org.mockito.Mockito.doReturn(Optional.empty()).when(repo).insertIfAbsent(any());
        ConflictException e = catchThrowableOfType(() -> service.create(req(SourceKind.ASHBY, "acme")),
                ConflictException.class);
        assertThat(e.getCode()).isEqualTo(ErrorCode.DATA_CONFLICT);
    }

    // ------------------------------------------------------------------ update

    private FeedSource stored(UUID id, SourceManagedBy managedBy, FeedSourceState state) {
        FeedSource s = managedBy == SourceManagedBy.PREFERENCES
                ? source(id, "adzuna:za:abc", SourceKind.ADZUNA, managedBy, state, null, null, Map.of("country", "za"),
                        null)
                : source(id, "greenhouse:acme", SourceKind.GREENHOUSE, managedBy, state, "Acme", "acme", Map.of(), null);
        when(repo.findByIdForUpdate(id)).thenReturn(Optional.of(s));
        when(repo.findById(id)).thenReturn(Optional.of(s));
        return s;
    }

    private void updateReturnsInput(UUID id) {
        when(repo.updateSettings(eq(id), any(), any(), any(), anyBoolean())).thenAnswer(inv -> Optional.of(
                source(id, "greenhouse:acme", SourceKind.GREENHOUSE, SourceManagedBy.OWNER, inv.getArgument(2),
                        inv.getArgument(1), "acme", Map.of(), inv.getArgument(3))));
    }

    @Test
    void updatePreferencesSourceIs409() {
        UUID id = UUID.randomUUID();
        stored(id, SourceManagedBy.PREFERENCES, FeedSourceState.ACTIVE);
        ConflictException e = catchThrowableOfType(() -> service.update(id,
                new SourceChanges(null, FeedSourceState.PAUSED, null)), ConflictException.class);
        assertThat(e.getCode()).isEqualTo(ErrorCode.DATA_CONFLICT);
        assertThat(e.getMessage()).contains("managed by your job preferences");
        verify(repo, never()).updateSettings(any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void updateMissingStateIs400() {
        UUID id = UUID.randomUUID();
        stored(id, SourceManagedBy.OWNER, FeedSourceState.ACTIVE);
        RequestValidationException e = catchThrowableOfType(() -> service.update(id,
                new SourceChanges("Acme", null, null)), RequestValidationException.class);
        assertThat(fieldErrors(e)).containsOnlyKeys("state");
        assertThat(catchThrowableOfType(() -> service.update(id, null), RequestValidationException.class)).isNotNull();
    }

    @Test
    void updateIntervalAndNameValidated() {
        UUID id = UUID.randomUUID();
        stored(id, SourceManagedBy.OWNER, FeedSourceState.ACTIVE);
        RequestValidationException e = catchThrowableOfType(() -> service.update(id,
                new SourceChanges("n".repeat(201), FeedSourceState.ACTIVE, 90_000)), RequestValidationException.class);
        assertThat(fieldErrors(e)).containsOnlyKeys("companyName", "pollIntervalSeconds");
    }

    @Test
    void updateUnknownIdIs404() {
        UUID id = UUID.randomUUID();
        when(repo.findByIdForUpdate(id)).thenReturn(Optional.empty());
        NotFoundException e = catchThrowableOfType(() -> service.update(id,
                new SourceChanges(null, FeedSourceState.ACTIVE, null)), NotFoundException.class);
        assertThat(e.getCode()).isEqualTo(ErrorCode.FEED_SOURCE_NOT_FOUND);
    }

    @Test
    void resumeMakesDueNow() {
        UUID id = UUID.randomUUID();
        stored(id, SourceManagedBy.OWNER, FeedSourceState.PAUSED);
        updateReturnsInput(id);
        FeedSourceView v = service.update(id, new SourceChanges(" Acme ", FeedSourceState.ACTIVE, 600));
        verify(repo).updateSettings(id, "Acme", FeedSourceState.ACTIVE, 600, true);
        assertThat(v.source().state()).isEqualTo(FeedSourceState.ACTIVE);
        assertThat(v.effectivePollIntervalSeconds()).isEqualTo(600);
        assertThat(v.warnings()).isEmpty();
    }

    @Test
    void otherTransitionsAreNotDueNow() {
        UUID id = UUID.randomUUID();
        stored(id, SourceManagedBy.OWNER, FeedSourceState.ACTIVE);
        updateReturnsInput(id);
        service.update(id, new SourceChanges(null, FeedSourceState.ACTIVE, null));
        service.update(id, new SourceChanges(null, FeedSourceState.PAUSED, null));
        verify(repo).updateSettings(id, null, FeedSourceState.ACTIVE, null, false);
        verify(repo).updateSettings(id, null, FeedSourceState.PAUSED, null, false);
    }

    // ------------------------------------------------------------------ delete

    @Test
    void deletePreferencesSourceIs409() {
        UUID id = UUID.randomUUID();
        stored(id, SourceManagedBy.PREFERENCES, FeedSourceState.ACTIVE);
        ConflictException e = catchThrowableOfType(() -> service.delete(id), ConflictException.class);
        assertThat(e.getCode()).isEqualTo(ErrorCode.DATA_CONFLICT);
        verify(repo, never()).delete(any());
        verify(repo, never()).deleteOrphanedFeedJobs();
    }

    @Test
    void deleteRemovesSourceThenOrphans() {
        UUID id = UUID.randomUUID();
        stored(id, SourceManagedBy.OWNER, FeedSourceState.ACTIVE);
        when(repo.delete(id)).thenReturn(1);
        service.delete(id);
        InOrder order = inOrder(repo);
        order.verify(repo).findByIdForUpdate(id);
        order.verify(repo).delete(id);
        order.verify(repo).deleteOrphanedFeedJobs();
    }

    @Test
    void deleteUnknownIdIs404() {
        UUID id = UUID.randomUUID();
        when(repo.findByIdForUpdate(id)).thenReturn(Optional.empty());
        assertThat(catchThrowableOfType(() -> service.delete(id), NotFoundException.class).getCode())
                .isEqualTo(ErrorCode.FEED_SOURCE_NOT_FOUND);
        verify(repo, never()).delete(any());
    }

    // ------------------------------------------------------------------ read

    @Test
    void getUnknownIs404AndKnownHasNoWarnings() {
        UUID id = UUID.randomUUID();
        when(repo.findById(id)).thenReturn(Optional.empty());
        assertThat(catchThrowableOfType(() -> service.get(id), NotFoundException.class).getCode())
                .isEqualTo(ErrorCode.FEED_SOURCE_NOT_FOUND);
        UUID id2 = UUID.randomUUID();
        stored(id2, SourceManagedBy.PREFERENCES, FeedSourceState.ACTIVE);
        FeedSourceView v = service.get(id2);
        assertThat(v.warnings()).isEmpty();
        assertThat(v.effectivePollIntervalSeconds()).isEqualTo(900);
    }

    @Test
    void listSkipsPageQueryWhenEmpty() {
        when(repo.count(null, null)).thenReturn(0L);
        assertThat(service.list(null, null, 0, 20).getTotalElements()).isZero();
        verify(repo, never()).findPage(any(), any(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyInt());
        when(repo.count(SourceKind.LEVER, FeedSourceState.PAUSED)).thenReturn(25L);
        when(repo.findPage(SourceKind.LEVER, FeedSourceState.PAUSED, 20L, 10)).thenReturn(List.of());
        assertThat(service.list(SourceKind.LEVER, FeedSourceState.PAUSED, 2, 10).getTotalElements()).isEqualTo(25);
        verify(repo).findPage(SourceKind.LEVER, FeedSourceState.PAUSED, 20L, 10);
    }
}
