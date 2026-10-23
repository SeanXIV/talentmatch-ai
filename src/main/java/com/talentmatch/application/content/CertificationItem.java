package com.talentmatch.application.content;

/**
 * One certification of a tailored CV, copied from the profile version (no credential id or URL).
 *
 * @param ref {@code C1..Cn} as numbered in the profile
 */
public record CertificationItem(String ref, String name, String issuer, String issued, String expires) {
}
