package com.talentmatch.application.render;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * The bundled Noto Sans TTFs (SIL OFL 1.1, licence in {@code fonts/OFL.txt}), read from the classpath
 * on first use and kept as bytes. Each PDF loads its own {@code PDType0Font} from them. A missing
 * resource throws {@link IllegalStateException} on every attempt (no cached failure).
 */
final class PdfFonts {

    static final String REGULAR = "fonts/NotoSans-Regular.ttf";
    static final String BOLD = "fonts/NotoSans-Bold.ttf";

    private static volatile byte[] regular;
    private static volatile byte[] bold;

    private PdfFonts() {
    }

    static byte[] regular() {
        byte[] bytes = regular;
        if (bytes == null) {
            bytes = read(REGULAR);
            regular = bytes; // a benign race reads the resource twice at most
        }
        return bytes;
    }

    static byte[] bold() {
        byte[] bytes = bold;
        if (bytes == null) {
            bytes = read(BOLD);
            bold = bytes;
        }
        return bytes;
    }

    static byte[] read(String resource) {
        ClassLoader loader = PdfFonts.class.getClassLoader();
        try (InputStream in = loader.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Font resource " + resource + " is missing from the classpath");
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read font resource " + resource, e);
        }
    }
}
