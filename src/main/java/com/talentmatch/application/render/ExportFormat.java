package com.talentmatch.application.render;

import java.util.Locale;
import java.util.Optional;

/** Download formats. Names must match V7's {@code application_document_file.format}. */
public enum ExportFormat {
    PDF("application/pdf", "pdf"),
    DOCX("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx");

    private final String contentType;
    private final String extension;

    ExportFormat(String contentType, String extension) {
        this.contentType = contentType;
        this.extension = extension;
    }

    public String contentType() {
        return contentType;
    }

    /** File extension without the dot. */
    public String extension() {
        return extension;
    }

    /** {@code pdf} / {@code docx}, case-insensitive and stripped; empty for anything else (including null). */
    public static Optional<ExportFormat> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "pdf" -> Optional.of(PDF);
            case "docx" -> Optional.of(DOCX);
            default -> Optional.empty();
        };
    }
}
