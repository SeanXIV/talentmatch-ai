package com.talentmatch.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.talentmatch.application.GenerationService.Kinds;
import com.talentmatch.service.FieldErrors;
import com.talentmatch.service.exception.RequestValidationException;
import com.talentmatch.web.error.FieldErrorDto;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Phase 7 §5.3 / §5.4: the {@code kinds} parser shared by create and the generations endpoint. */
class GenerationServiceKindsTest {

    private static final String MESSAGE = "kinds must contain CV and/or COVER_LETTER, each at most once.";

    private static Kinds valid(List<String> raw) {
        FieldErrors errors = new FieldErrors();
        Kinds kinds = GenerationService.kinds(raw, errors);
        assertThat(errors.isEmpty()).as("no error for %s", raw).isTrue();
        return kinds;
    }

    private static List<FieldErrorDto> invalid(List<String> raw) {
        FieldErrors errors = new FieldErrors();
        Kinds kinds = GenerationService.kinds(raw, errors);
        assertThat(kinds).as("falls back to both").isEqualTo(Kinds.BOTH);
        RequestValidationException e = catchThrowableOfType(RequestValidationException.class, errors::throwIfAny);
        assertThat(e).as("an error for %s", raw).isNotNull();
        return e.getFieldErrors();
    }

    @Test
    void nullMeansBoth() {
        assertThat(valid(null)).isEqualTo(new Kinds(true, true)).isEqualTo(Kinds.BOTH);
    }

    @Test
    void validCombinationsInAnyOrderAndCase() {
        assertThat(valid(List.of("CV"))).isEqualTo(new Kinds(true, false));
        assertThat(valid(List.of("COVER_LETTER"))).isEqualTo(new Kinds(false, true));
        assertThat(valid(List.of("CV", "COVER_LETTER"))).isEqualTo(Kinds.BOTH);
        assertThat(valid(List.of("COVER_LETTER", "CV"))).isEqualTo(Kinds.BOTH);
        assertThat(valid(List.of("cv", " cover_letter "))).isEqualTo(Kinds.BOTH);
        assertThat(valid(List.of("Cv"))).isEqualTo(new Kinds(true, false));
    }

    @Test
    void invalidValuesGiveOneFieldError() {
        for (List<String> raw : List.of(List.<String>of(), List.of("PDF"), List.of("CV", "CV"),
                List.of("cv", "CV"), List.of(""), List.of("  "), List.of("CV", "COVER_LETTER", "CV"),
                List.of("COVER LETTER"), Arrays.asList("CV", null))) {
            assertThat(invalid(raw)).as("%s", raw).containsExactly(new FieldErrorDto("kinds", MESSAGE));
        }
    }

    @Test
    void errorsAreAddedToTheSharedCollector() {
        FieldErrors errors = new FieldErrors().add("notes", "too long");
        GenerationService.kinds(List.of("X"), errors);
        RequestValidationException e = catchThrowableOfType(RequestValidationException.class, errors::throwIfAny);
        assertThat(e.getFieldErrors()).extracting(FieldErrorDto::field).containsExactly("notes", "kinds");
    }
}
