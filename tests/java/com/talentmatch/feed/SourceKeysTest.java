package com.talentmatch.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.talentmatch.feed.source.SourceKind;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** §10 step 5: source natural keys (§3.3 source_key, probe findings on token case). */
class SourceKeysTest {

    private static String key(SourceKind kind, String token) {
        return SourceKeys.sourceKey(kind, token, null);
    }

    private static Map<String, String> lever(String instance) {
        Map<String, String> m = new HashMap<>();
        m.put("leverInstance", instance);
        return m;
    }

    @Test
    void greenhouseAndAshbyKeysAreLowerCased() {
        assertThat(key(SourceKind.GREENHOUSE, "Acme")).isEqualTo("greenhouse:acme")
                .isEqualTo(key(SourceKind.GREENHOUSE, "acme"));
        assertThat(key(SourceKind.ASHBY, "ACME.io")).isEqualTo("ashby:acme.io")
                .isEqualTo(key(SourceKind.ASHBY, "acme.io"));
    }

    @Test
    void leverKeepsCase() {
        assertThat(key(SourceKind.LEVER, "Acme")).isEqualTo("lever:Acme");
        assertThat(key(SourceKind.LEVER, "acme")).isEqualTo("lever:acme");
        assertThat(key(SourceKind.LEVER, "Acme")).isNotEqualTo(key(SourceKind.LEVER, "acme"));
    }

    @Test
    void leverEuInstance() {
        assertThat(SourceKeys.sourceKey(SourceKind.LEVER, "x", lever("eu"))).isEqualTo("lever:eu:x");
        assertThat(SourceKeys.sourceKey(SourceKind.LEVER, "x", lever("EU"))).isEqualTo("lever:eu:x");
        assertThat(SourceKeys.sourceKey(SourceKind.LEVER, "x", lever("Eu"))).isEqualTo("lever:eu:x");
        assertThat(SourceKeys.sourceKey(SourceKind.LEVER, "Xy", lever(" eu "))).isEqualTo("lever:eu:Xy");
    }

    @Test
    void leverGlobalOrBlankInstanceGivesPlainKey() {
        assertThat(SourceKeys.sourceKey(SourceKind.LEVER, "x", lever("global"))).isEqualTo("lever:x");
        assertThat(SourceKeys.sourceKey(SourceKind.LEVER, "x", lever("GLOBAL"))).isEqualTo("lever:x");
        assertThat(SourceKeys.sourceKey(SourceKind.LEVER, "x", lever(""))).isEqualTo("lever:x");
        assertThat(SourceKeys.sourceKey(SourceKind.LEVER, "x", lever("   "))).isEqualTo("lever:x");
        assertThat(SourceKeys.sourceKey(SourceKind.LEVER, "x", lever(null))).isEqualTo("lever:x");
        assertThat(SourceKeys.sourceKey(SourceKind.LEVER, "x", Map.of())).isEqualTo("lever:x");
    }

    @Test
    void otherLeverInstanceThrows() {
        assertThatThrownBy(() -> SourceKeys.sourceKey(SourceKind.LEVER, "x", lever("us")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SourceKeys.isLeverEu("europe")).isInstanceOf(IllegalArgumentException.class);
        assertThat(SourceKeys.isLeverEu("eu")).isTrue();
        assertThat(SourceKeys.isLeverEu("global")).isFalse();
        assertThat(SourceKeys.isLeverEu(null)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"a b", "a/b", "a:b", "acme?x", "", "   ", ".", "..", "...", "é"})
    void invalidTokensThrow(String token) {
        for (SourceKind kind : new SourceKind[] {SourceKind.GREENHOUSE, SourceKind.LEVER, SourceKind.ASHBY}) {
            assertThatThrownBy(() -> key(kind, token)).as("%s %s", kind, token)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(SourceKeys.isValidToken(token)).isFalse();
    }

    @Test
    void nullAndOverlongTokensThrow() {
        assertThatThrownBy(() -> key(SourceKind.GREENHOUSE, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> key(SourceKind.LEVER, "a".repeat(101))).isInstanceOf(IllegalArgumentException.class);
        assertThat(key(SourceKind.LEVER, "a".repeat(100))).isEqualTo("lever:" + "a".repeat(100));
        assertThat(SourceKeys.isValidToken(null)).isFalse();
        assertThat(SourceKeys.isValidToken("a.b-c_d")).isTrue();
        assertThat(SourceKeys.isValidToken(".a")).isTrue();
    }

    @Test
    void nullKindThrows() {
        assertThatThrownBy(() -> key(null, "acme")).isInstanceOf(NullPointerException.class);
    }

    @Test
    void keysFitTheColumn() {
        assertThat(SourceKeys.sourceKey(SourceKind.LEVER, "a".repeat(100), lever("eu")).length())
                .isLessThanOrEqualTo(SourceKeys.MAX_LENGTH);
    }

    // ------------------------------------------------------------------ Adzuna

    private static String adzuna(String country, String what, String where) {
        Map<String, String> m = new HashMap<>();
        m.put("country", country);
        m.put("what", what);
        m.put("where", where);
        return SourceKeys.sourceKey(SourceKind.ADZUNA, null, m);
    }

    @Test
    void adzunaKeyIsStableUnderWhitespaceAndCase() {
        String k = adzuna("za", "java developer", "cape town");
        assertThat(k).matches("^adzuna:za:[0-9a-f]{40}$");
        assertThat(adzuna(" ZA ", "  Java   Developer ", "Cape\tTown")).isEqualTo(k);
        assertThat(adzuna("za", "JAVA\nDEVELOPER", " cape  town ")).isEqualTo(k);
    }

    @Test
    void adzunaKeyDiffersWhenQueryDiffers() {
        String k = adzuna("za", "java", "cape town");
        assertThat(adzuna("za", "kotlin", "cape town")).isNotEqualTo(k);
        assertThat(adzuna("za", "java", "durban")).isNotEqualTo(k);
        assertThat(adzuna("gb", "java", "cape town")).isNotEqualTo(k);
        // what and where are kept apart
        assertThat(adzuna("za", "java cape", "town")).isNotEqualTo(adzuna("za", "java", "cape town"));
        assertThat(adzuna("za", null, null)).matches("^adzuna:za:[0-9a-f]{40}$");
    }

    @Test
    void adzunaWithoutCountryThrows() {
        assertThatThrownBy(() -> adzuna(null, "java", "x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adzuna("", "java", "x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adzuna("zaf", "java", "x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adzuna("z1", "java", "x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SourceKeys.sourceKey(SourceKind.ADZUNA, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
