package com.talentmatch.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.talentmatch.application.content.DocumentKind;
import com.talentmatch.application.render.ExportFormat;
import com.talentmatch.service.exception.InvalidParameterException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Phase 7 step 6: the {@code kind} and {@code format} parameters of the document endpoints (§5.5). */
class ApplicationDocumentParamsTest {

    @Test
    void kindIsCaseInsensitiveAndStripped() {
        assertThat(ApplicationController.kind("CV")).isEqualTo(DocumentKind.CV);
        assertThat(ApplicationController.kind("cv")).isEqualTo(DocumentKind.CV);
        assertThat(ApplicationController.kind(" Cover_Letter ")).isEqualTo(DocumentKind.COVER_LETTER);
        assertThat(ApplicationController.kind("cover_letter")).isEqualTo(DocumentKind.COVER_LETTER);
    }

    @Test
    void noKindMeansBoth() {
        assertThat(ApplicationController.kind(null)).isNull();
        assertThat(ApplicationController.kind("")).isNull();
        assertThat(ApplicationController.kind("   ")).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"letter", "COVER LETTER", "COVER-LETTER", "CVS", "pdf", "1"})
    void anyOtherKindIsInvalidParameter(String raw) {
        assertThatThrownBy(() -> ApplicationController.kind(raw))
                .isInstanceOf(InvalidParameterException.class)
                .hasMessage("kind must be CV or COVER_LETTER.");
    }

    @Test
    void formatDefaultsToPdfAndIsCaseInsensitive() {
        assertThat(ApplicationController.format(null)).isEqualTo(ExportFormat.PDF);
        assertThat(ApplicationController.format("")).isEqualTo(ExportFormat.PDF);
        assertThat(ApplicationController.format("  ")).isEqualTo(ExportFormat.PDF);
        assertThat(ApplicationController.format("pdf")).isEqualTo(ExportFormat.PDF);
        assertThat(ApplicationController.format("PDF")).isEqualTo(ExportFormat.PDF);
        assertThat(ApplicationController.format("docx")).isEqualTo(ExportFormat.DOCX);
        assertThat(ApplicationController.format("DocX")).isEqualTo(ExportFormat.DOCX);
        assertThat(ApplicationController.format(" docx ")).isEqualTo(ExportFormat.DOCX);
    }

    @ParameterizedTest
    @ValueSource(strings = {"xls", "doc", "txt", ".pdf", "pdfx", "application/pdf"})
    void anyOtherFormatIsInvalidParameter(String raw) {
        assertThatThrownBy(() -> ApplicationController.format(raw))
                .isInstanceOf(InvalidParameterException.class)
                .hasMessage("format must be pdf or docx.");
    }
}
