package com.talentmatch.web;

import org.springframework.beans.propertyeditors.CustomBooleanEditor;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.InitBinder;

/**
 * Accepts only "true"/"false" (case-insensitive, trimmed) for boolean request parameters.
 * Spring's default also accepts yes/no/on/off/1/0, which hides typos like {@code regenerate=yes}.
 *
 * <p>Uses custom editors rather than a {@code Converter}: when a conversion-service converter
 * throws, Spring's TypeConverterDelegate falls back to the default lenient editor, whereas a
 * registered custom editor takes priority and its failure surfaces as a
 * {@code MethodArgumentTypeMismatchException} (400 INVALID_PARAMETER).
 *
 * <p>A missing or empty parameter still gets its {@code @RequestParam(defaultValue = ...)}:
 * the default is substituted before the editor runs.
 */
@ControllerAdvice
public class StrictBooleanBindingAdvice {

    @InitBinder
    void strictBooleans(WebDataBinder binder) {
        binder.registerCustomEditor(boolean.class, new CustomBooleanEditor("true", "false", false));
        binder.registerCustomEditor(Boolean.class, new CustomBooleanEditor("true", "false", true));
    }
}
