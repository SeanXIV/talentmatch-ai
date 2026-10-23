package com.talentmatch.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.talentmatch.application.GenerationRepository.GenerationRow;
import com.talentmatch.application.content.DocumentKind;
import com.talentmatch.application.tailoring.GenerationFailure;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/** Phase 7 step 5: {@link GenerationRepository#finishKind} guards and {@link GenerationRow#view()}. */
class GenerationRepositoryTest {

    private static final UUID GEN = UUID.randomUUID();
    private static final UUID APP = UUID.randomUUID();
    private static final UUID DOC = UUID.randomUUID();

    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final GenerationRepository repository = new GenerationRepository(jdbc);

    // ------------------------------------------------------------------ finishKind

    @Test
    void finishKindNeedsExactlyOneOfDocumentAndFailure() {
        assertThatThrownBy(() -> repository.finishKind(GEN, DocumentKind.CV, null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("exactly one");
        assertThatThrownBy(() -> repository.finishKind(GEN, DocumentKind.CV, DOC, GenerationFailure.TIMEOUT))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("exactly one");
        verifyNoInteractions(jdbc);
    }

    @Test
    void finishKindRejectsGenerationLevelFailures() {
        for (GenerationFailure f : GenerationFailure.values()) {
            if (!f.perKind()) {
                assertThatThrownBy(() -> repository.finishKind(GEN, DocumentKind.COVER_LETTER, null, f))
                        .as("%s", f).isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("not a per-kind failure");
            }
        }
        verifyNoInteractions(jdbc);
    }

    @Test
    void finishKindWritesTheKindsColumnsOnlyWhileRunning() {
        when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(1);
        assertThat(repository.finishKind(GEN, DocumentKind.CV, DOC, null)).isTrue();
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<SqlParameterSource> params = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).update(sql.capture(), params.capture());
        assertThat(sql.getValue()).contains("cv_document_id = :document", "cv_failure = :failure",
                "status = 'RUNNING'").doesNotContain("cover_letter");
        MapSqlParameterSource p = (MapSqlParameterSource) params.getValue();
        assertThat(p.getValue("id")).isEqualTo(GEN);
        assertThat(p.getValue("document")).isEqualTo(DOC);
        assertThat(p.getValue("failure")).isNull();
    }

    @Test
    void finishKindRecordsAPerKindFailureForTheLetter() {
        when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(0);
        assertThat(repository.finishKind(GEN, DocumentKind.COVER_LETTER, null, GenerationFailure.CONTEXT_OVERFLOW))
                .as("0 rows (no longer RUNNING) → false").isFalse();
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<SqlParameterSource> params = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).update(sql.capture(), params.capture());
        assertThat(sql.getValue()).contains("cover_letter_document_id = :document",
                "cover_letter_failure = :failure", "status = 'RUNNING'");
        MapSqlParameterSource p = (MapSqlParameterSource) params.getValue();
        assertThat(p.getValue("document")).isNull();
        assertThat(p.getValue("failure")).isEqualTo("CONTEXT_OVERFLOW");
    }

    @Test
    void everyPerKindFailureIsAccepted() {
        when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(1);
        for (GenerationFailure f : List.of(GenerationFailure.TIMEOUT, GenerationFailure.PROVIDER_ERROR,
                GenerationFailure.REFUSED, GenerationFailure.INVALID_OUTPUT, GenerationFailure.CONTEXT_OVERFLOW)) {
            assertThat(f.perKind()).isTrue();
            assertThat(repository.finishKind(GEN, DocumentKind.CV, null, f)).isTrue();
        }
    }

    // ------------------------------------------------------------------ view

    private static GenerationRow row(String status, boolean wantCv, boolean wantLetter, GenerationFailure failure,
                                     UUID cvDoc, GenerationFailure cvFailure, UUID letterDoc,
                                     GenerationFailure letterFailure) {
        Instant t = Instant.parse("2026-10-19T09:00:00Z");
        return new GenerationRow(GEN, APP, wantCv, wantLetter, false, status, failure, 1, "fake/tailor", 4, cvDoc,
                cvFailure, letterDoc, letterFailure, t, t.plusSeconds(1), failure == null && cvDoc == null
                && letterDoc == null ? null : t.plusSeconds(60));
    }

    @Test
    void aPendingViewHasNoResultsAndNoFailure() {
        GenerationView v = new GenerationRow(GEN, APP, true, true, true, "PENDING", null, 0, null, null, null, null,
                null, null, Instant.EPOCH, null, null).view();
        assertThat(v.id()).isEqualTo(GEN);
        assertThat(v.applicationId()).isEqualTo(APP);
        assertThat(v.status()).isEqualTo("PENDING");
        assertThat(v.wantCv()).isTrue();
        assertThat(v.wantCoverLetter()).isTrue();
        assertThat(v.failureReason()).isNull();
        assertThat(v.hint()).isNull();
        assertThat(v.model()).isNull();
        assertThat(v.profileVersion()).isNull();
        assertThat(v.attempts()).isZero();
        assertThat(v.queuedAt()).isEqualTo(Instant.EPOCH);
        assertThat(v.startedAt()).isNull();
        assertThat(v.finishedAt()).isNull();
        assertThat(v.results()).isEmpty();
    }

    @Test
    void resultsListFinishedWantedKindsInCvThenLetterOrder() {
        UUID letterDoc = UUID.randomUUID();
        GenerationView v = row("SUCCEEDED", true, true, null, null, GenerationFailure.INVALID_OUTPUT, letterDoc,
                null).view();
        assertThat(v.results()).extracting(GenerationView.Result::kind, GenerationView.Result::documentId,
                GenerationView.Result::failureReason, GenerationView.Result::hint).containsExactly(
                tuple(DocumentKind.CV, null, "INVALID_OUTPUT", GenerationFailure.INVALID_OUTPUT.hint()),
                tuple(DocumentKind.COVER_LETTER, letterDoc, null, null));
        assertThat(v.failureReason()).isNull();
        assertThat(v.model()).isEqualTo("fake/tailor");
        assertThat(v.profileVersion()).isEqualTo(4);
        assertThat(v.attempts()).isEqualTo(1);
    }

    @Test
    void aKindStillRunningIsNotAResult() {
        GenerationView v = row("RUNNING", true, true, null, DOC, null, null, null).view();
        assertThat(v.results()).singleElement().satisfies(r -> {
            assertThat(r.kind()).isEqualTo(DocumentKind.CV);
            assertThat(r.documentId()).isEqualTo(DOC);
        });
    }

    @Test
    void unwantedKindsAreNeverResults() {
        GenerationView v = row("SUCCEEDED", false, true, null, DOC, null, DOC, null).view();
        assertThat(v.results()).extracting(GenerationView.Result::kind).containsExactly(DocumentKind.COVER_LETTER);
    }

    @Test
    void aFailedViewCarriesTheReasonAndItsHint() {
        GenerationView v = row("FAILED", true, false, GenerationFailure.REMOTE_GENERATION_DISABLED, null, null, null,
                null).view();
        assertThat(v.failureReason()).isEqualTo("REMOTE_GENERATION_DISABLED");
        assertThat(v.hint()).isEqualTo(GenerationFailure.REMOTE_GENERATION_DISABLED.hint());
        assertThat(v.results()).isEmpty();
        assertThat(v.finishedAt()).isNotNull();
    }

    @Test
    void rowAccessorsByKind() {
        UUID letterDoc = UUID.randomUUID();
        GenerationRow r = row("RUNNING", true, false, null, DOC, null, letterDoc, GenerationFailure.TIMEOUT);
        assertThat(r.wants(DocumentKind.CV)).isTrue();
        assertThat(r.wants(DocumentKind.COVER_LETTER)).isFalse();
        assertThat(r.documentId(DocumentKind.CV)).isEqualTo(DOC);
        assertThat(r.documentId(DocumentKind.COVER_LETTER)).isEqualTo(letterDoc);
        assertThat(r.failure(DocumentKind.CV)).isNull();
        assertThat(r.failure(DocumentKind.COVER_LETTER)).isEqualTo(GenerationFailure.TIMEOUT);
    }
}
