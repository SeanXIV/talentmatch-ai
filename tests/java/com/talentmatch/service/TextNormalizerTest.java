package com.talentmatch.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Same normalization rules as the ETL (scripts/etl/talentmatch_etl/cleaning.py). */
class TextNormalizerTest {

    @Test
    void textTrimsAndBlankBecomesNull() {
        assertThat(TextNormalizer.text(null)).isNull();
        assertThat(TextNormalizer.text("")).isNull();
        assertThat(TextNormalizer.text("   \t\n ")).isNull();
        assertThat(TextNormalizer.text("  Ada  Lovelace ")).isEqualTo("Ada  Lovelace");
        // Unicode whitespace (e.g. ideographic space) is stripped too
        assertThat(TextNormalizer.text("　Ada ")).isEqualTo("Ada");
    }

    @Test
    void emailIsTrimmedAndLowercased() {
        assertThat(TextNormalizer.email("  Ada@Example.COM ")).isEqualTo("ada@example.com");
        assertThat(TextNormalizer.email("   ")).isNull();
        assertThat(TextNormalizer.email(null)).isNull();
    }

    @Test
    void skillNameCollapsesInternalWhitespace() {
        assertThat(TextNormalizer.skillName("  Spring \t  Boot\n ")).isEqualTo("Spring Boot");
        assertThat(TextNormalizer.skillName("Machine  Learning")).isEqualTo("Machine Learning");
        assertThat(TextNormalizer.skillName(" ")).isNull();
        assertThat(TextNormalizer.skillName(null)).isNull();
        assertThat(TextNormalizer.skillKey("Spring Boot")).isEqualTo("spring boot");
    }

    @Test
    void emailShapeCheck() {
        assertThat(TextNormalizer.isValidEmail("ada@example.com")).isTrue();
        assertThat(TextNormalizer.isValidEmail("a.b+c@sub.example.co")).isTrue();
        assertThat(TextNormalizer.isValidEmail(null)).isFalse();
        assertThat(TextNormalizer.isValidEmail("ada")).isFalse();
        assertThat(TextNormalizer.isValidEmail("ada@example")).isFalse();
        assertThat(TextNormalizer.isValidEmail("ada@@example.com")).isFalse();
        assertThat(TextNormalizer.isValidEmail("a da@example.com")).isFalse();
        assertThat(TextNormalizer.isValidEmail("@example.com")).isFalse();
        String local = "a".repeat(320 - "@example.com".length());
        assertThat(TextNormalizer.isValidEmail(local + "@example.com")).isTrue();
        assertThat(TextNormalizer.isValidEmail("a" + local + "@example.com")).isFalse();
    }
}
