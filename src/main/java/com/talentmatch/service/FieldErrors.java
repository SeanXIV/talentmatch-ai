package com.talentmatch.service;

import com.talentmatch.service.exception.RequestValidationException;
import com.talentmatch.web.error.FieldErrorDto;
import java.util.ArrayList;
import java.util.List;

/** Collects request-body field errors so a single 400 can report all of them. */
public final class FieldErrors {

    private final List<FieldErrorDto> errors = new ArrayList<>();

    public FieldErrors add(String field, String message) {
        errors.add(new FieldErrorDto(field, message));
        return this;
    }

    public boolean isEmpty() {
        return errors.isEmpty();
    }

    /** Field is required: null value adds the given message. Returns true if the value is present. */
    public boolean required(String field, String value, String message) {
        if (value == null) {
            add(field, message);
            return false;
        }
        return true;
    }

    /** Adds an error if value is longer than max characters. */
    public void maxLength(String field, String value, int max, String label) {
        if (value != null && value.length() > max) {
            add(field, label + " must be at most " + max + " characters (got " + value.length() + ").");
        }
    }

    /** Throws {@link RequestValidationException} if any error was collected. */
    public void throwIfAny() {
        if (!errors.isEmpty()) {
            throw new RequestValidationException(List.copyOf(errors));
        }
    }
}
