package com.talentmatch.profile;

/**
 * Something in an extracted draft the owner should check, e.g. a skill that does not appear in
 * the CV text. Warnings never block saving; the owner decides.
 *
 * @param path    location in the profile document, e.g. {@code skills[3].name}
 * @param value   the value in question (may be null)
 * @param message plain-English explanation and what to do
 */
public record ProfileWarning(String path, String value, String message) {
}
