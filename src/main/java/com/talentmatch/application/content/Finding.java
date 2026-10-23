package com.talentmatch.application.content;

/**
 * One grounding finding on a document version (§4.4). Stored and returned, never logged.
 *
 * @param path    where in the content, e.g. {@code experience[0].highlights[1]} or {@code paragraphs[2]};
 *                a list path ({@code skills}) for dropped items; null for the whole document
 * @param value   the offending token only (e.g. {@code "40%"}, {@code "Kubernetes"}, a ref), never a
 *                whole sentence; may be null
 * @param message plain-language explanation for the owner
 */
public record Finding(Severity severity, FindingCode code, String path, String value, String message) {

    public static Finding info(FindingCode code, String path, String value, String message) {
        return new Finding(Severity.INFO, code, path, value, message);
    }

    public static Finding warning(FindingCode code, String path, String value, String message) {
        return new Finding(Severity.WARNING, code, path, value, message);
    }

    public static Finding blocking(FindingCode code, String path, String value, String message) {
        return new Finding(Severity.BLOCKING, code, path, value, message);
    }

    /** The same finding with another severity. */
    public Finding withSeverity(Severity newSeverity) {
        return newSeverity == severity ? this : new Finding(newSeverity, code, path, value, message);
    }
}
