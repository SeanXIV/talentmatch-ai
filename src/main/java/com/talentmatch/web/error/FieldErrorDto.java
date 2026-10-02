package com.talentmatch.web.error;

import java.util.Comparator;

/**
 * One invalid field or parameter.
 *
 * @param field   JSON path (e.g. {@code skills[0].name}) or query parameter name
 * @param message what is wrong and how to fix it
 */
public record FieldErrorDto(String field, String message) {

    /** Order used in responses: by field, then message. */
    public static final Comparator<FieldErrorDto> ORDER = Comparator
            .comparing(FieldErrorDto::field, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(FieldErrorDto::message, Comparator.nullsFirst(Comparator.naturalOrder()));
}
