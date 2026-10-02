package com.talentmatch.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.talentmatch.service.exception.InvalidParameterException;
import com.talentmatch.service.exception.RecomputeAlreadyRunningException;
import com.talentmatch.service.exception.RequestValidationException;
import com.talentmatch.web.error.ApiError;
import com.talentmatch.web.error.FieldErrorDto;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Unit tests for small service-layer helpers (no Spring). */
class SmallServiceUnitsTest {

    @Test
    void validationSummaryCountsDistinctFields() {
        assertThat(RequestValidationException.summarize(List.of(new FieldErrorDto("title", "x"))))
                .isEqualTo("1 field is invalid. Fix it and try again.");
        assertThat(RequestValidationException.summarize(List.of(
                new FieldErrorDto("title", "x"), new FieldErrorDto("skills", "y"))))
                .isEqualTo("2 fields are invalid. Fix them and try again.");
        // two messages on the same field still count as one field
        assertThat(RequestValidationException.summarize(List.of(
                new FieldErrorDto("email", "a"), new FieldErrorDto("email", "b"))))
                .isEqualTo("1 field is invalid. Fix it and try again.");
    }

    @Test
    void apiErrorSortsFieldErrorsAsPlainStrings() {
        ApiError e = new ApiError(400, "Bad Request", "VALIDATION_FAILED", "m", "/api/x", Instant.EPOCH, "r",
                List.of(new FieldErrorDto("title", "t"), new FieldErrorDto("skills[10].name", "b"),
                        new FieldErrorDto("skills[2].name", "a"), new FieldErrorDto("skills", "s")));
        assertThat(e.fieldErrors()).extracting(FieldErrorDto::field)
                .containsExactly("skills", "skills[10].name", "skills[2].name", "title");
        assertThat(new ApiError(404, "Not Found", "X", "m", "/", Instant.EPOCH, "r", null).fieldErrors()).isEmpty();
    }

    @Test
    void likePatternEscapesWildcardsAndEscapeChar() {
        assertThat(SkillService.likePattern("Java")).isEqualTo("%java%");
        assertThat(SkillService.likePattern("100%_x!")).isEqualTo("%100!%!_x!!%");
    }

    @Test
    void pagingRejectsOverflow() {
        assertThat(Paging.of(2, 20).getOffset()).isEqualTo(40);
        assertThatThrownBy(() -> Paging.of(Integer.MAX_VALUE, 100)).isInstanceOf(InvalidParameterException.class);
    }

    @Test
    void recomputeAlreadyRunningMessage() {
        UUID id = UUID.randomUUID();
        Instant started = Instant.parse("2026-10-01T10:00:00Z");
        RecomputeAlreadyRunningException e = new RecomputeAlreadyRunningException(id, started);
        assertThat(e.getMessage()).isEqualTo("A recompute is already running (started 2026-10-01T10:00:00Z). "
                + "Track it at /api/matches/recompute/" + id + ".");
        assertThat(e.getActiveRunId()).isEqualTo(id);
    }

    @Test
    void recomputeRunLifecycleAndCaps() {
        UUID runId = UUID.randomUUID();
        RecomputeRun run = new RecomputeRun(runId, true, Instant.EPOCH, 2);
        RecomputeRunView queued = run.view();
        assertThat(queued.state()).isEqualTo(RecomputeState.QUEUED);
        assertThat(queued.percentComplete()).isZero();
        assertThat(queued.onlyStale()).isTrue();

        run.begin(4);
        run.recordRecomputed(7);
        run.recordSkipped(UUID.randomUUID());
        RecomputeRunView running = run.view();
        assertThat(running.state()).isEqualTo(RecomputeState.RUNNING);
        assertThat(running.percentComplete()).isEqualTo(50);
        assertThat(running.message()).isEqualTo("Recomputing matches: 2 of 4 jobs done.");
        assertThat(running.finishedAt()).isNull();

        run.recordFailure(UUID.randomUUID(), "a");
        run.recordFailure(UUID.randomUUID(), "b");
        run.recordFailure(UUID.randomUUID(), "c"); // over the cap of 2
        run.complete(Instant.EPOCH.plusSeconds(1));
        RecomputeRunView done = run.view();
        assertThat(done.state()).isEqualTo(RecomputeState.COMPLETED_WITH_ERRORS);
        assertThat(done.failed()).isEqualTo(3);
        assertThat(done.failures()).hasSize(2);
        assertThat(done.processed() + done.skipped() + done.failed()).isEqualTo(5);
        assertThat(done.percentComplete()).isEqualTo(100);
        assertThat(done.matchesWritten()).isEqualTo(7);
        assertThat(done.finishedAt()).isEqualTo(Instant.EPOCH.plusSeconds(1));

        RecomputeRun ok = new RecomputeRun(UUID.randomUUID(), false, Instant.EPOCH, 50);
        ok.begin(0);
        ok.complete(Instant.EPOCH);
        assertThat(ok.view().state()).isEqualTo(RecomputeState.SUCCEEDED);
        assertThat(ok.view().percentComplete()).isEqualTo(100);

        RecomputeRun skippedCap = new RecomputeRun(UUID.randomUUID(), false, Instant.EPOCH, 50);
        skippedCap.begin(60);
        for (int i = 0; i < 60; i++) {
            skippedCap.recordSkipped(UUID.randomUUID());
        }
        assertThat(skippedCap.view().skipped()).isEqualTo(60);
        assertThat(skippedCap.view().skippedJobIds()).hasSize(RecomputeRun.MAX_SKIPPED_REPORTED);
    }
}
