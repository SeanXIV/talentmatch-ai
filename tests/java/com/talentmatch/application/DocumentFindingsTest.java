package com.talentmatch.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.talentmatch.application.DocumentRepository.DocumentRow;
import com.talentmatch.application.content.DocumentKind;
import com.talentmatch.application.content.DocumentSource;
import com.talentmatch.application.content.Finding;
import com.talentmatch.application.content.FindingCode;
import com.talentmatch.application.content.Severity;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Phase 7 step 6: the computed {@code PROFILE_CHANGED} finding ({@link DocumentService#findings}) and
 * {@link DocumentRepository#profileCurrent} (§4.7 get).
 */
class DocumentFindingsTest {

    private static final Finding WARNING = Finding.warning(FindingCode.NUMBER_NOT_IN_PROFILE, "paragraphs[1]", "13",
            "The number 13 is not in your profile. Check it before approving.");
    private static final Finding INFO = Finding.info(FindingCode.REVERTED, "experience[0].highlights[0]", "40%",
            "Reverted.");

    private static DocumentRow row(int profileVersion, List<Finding> stored) {
        return new DocumentRow(UUID.randomUUID(), UUID.randomUUID(), DocumentKind.CV, 1, DocumentSource.AI, null,
                profileVersion, "{}", DocumentJson.write(stored), "fake/m", Instant.parse("2026-10-19T08:00:00Z"),
                null, null, false);
    }

    // ------------------------------------------------------------------ profileCurrent

    @Test
    void profileCurrentWhenTheVersionIsTheCurrentOneOrNewer() {
        assertThat(DocumentRepository.profileCurrent(3, 3)).isTrue();
        assertThat(DocumentRepository.profileCurrent(4, 3)).isTrue();
        assertThat(DocumentRepository.profileCurrent(2, 3)).isFalse();
        assertThat(DocumentRepository.profileCurrent(1, 100)).isFalse();
    }

    @Test
    void profileCurrentWithoutASavedProfile() {
        assertThat(DocumentRepository.profileCurrent(1, null)).isTrue();
    }

    // ------------------------------------------------------------------ findings

    @Test
    void aCurrentVersionHasOnlyItsStoredFindings() {
        assertThat(DocumentService.findings(row(3, List.of(WARNING, INFO)), 3)).containsExactly(WARNING, INFO);
        assertThat(DocumentService.findings(row(3, List.of()), 3)).isEmpty();
        assertThat(DocumentService.findings(row(3, List.of(WARNING)), null)).containsExactly(WARNING);
    }

    @Test
    void anOlderVersionGetsProfileChangedFirstAsBlocking() {
        List<Finding> all = DocumentService.findings(row(2, List.of(WARNING, INFO)), 5);
        assertThat(all).hasSize(3);
        Finding first = all.get(0);
        assertThat(first.code()).isEqualTo(FindingCode.PROFILE_CHANGED);
        assertThat(first.severity()).isEqualTo(Severity.BLOCKING);
        assertThat(first.path()).isNull();
        assertThat(first.value()).isNull();
        assertThat(first.message()).contains("profile version 2", "the current one is 5", "acknowledgeFindings=true");
        assertThat(all.subList(1, 3)).containsExactly(WARNING, INFO);
    }

    @Test
    void profileChangedIsAddedToAnEmptyFindingList() {
        List<Finding> all = DocumentService.findings(row(1, List.of()), 2);
        assertThat(all).extracting(Finding::code).containsExactly(FindingCode.PROFILE_CHANGED);
    }

    @Test
    void profileChangedIsNotDuplicatedWithAStoredBlocking() {
        Finding storedBlocking = Finding.blocking(FindingCode.NUMBER_NOT_IN_PROFILE, "paragraphs[1]", "12", "x");
        List<Finding> all = DocumentService.findings(row(1, List.of(storedBlocking)), 2);
        assertThat(all).extracting(Finding::code)
                .containsExactly(FindingCode.PROFILE_CHANGED, FindingCode.NUMBER_NOT_IN_PROFILE);
        assertThat(all).filteredOn(f -> f.code() == FindingCode.PROFILE_CHANGED).hasSize(1);
    }

    @Test
    void theResultIsImmutable() {
        List<Finding> all = DocumentService.findings(row(1, List.of(WARNING)), 2);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> all.add(INFO))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
