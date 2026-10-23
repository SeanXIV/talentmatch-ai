package com.talentmatch.application.render;

import java.util.Objects;

/**
 * A rendered export. The file name holds the owner's name: never log it (§6).
 *
 * @param bytes       the file content (not copied; treat as read-only)
 * @param contentType the MIME type for {@code Content-Type}
 * @param fileName    the download name for {@code Content-Disposition} (unsafe characters removed)
 */
public record RenderedFile(byte[] bytes, String contentType, String fileName) {

    public RenderedFile {
        Objects.requireNonNull(bytes, "bytes");
        Objects.requireNonNull(contentType, "contentType");
        Objects.requireNonNull(fileName, "fileName");
    }
}
