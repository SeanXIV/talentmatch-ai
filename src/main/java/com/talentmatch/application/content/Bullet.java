package com.talentmatch.application.content;

/**
 * One point under a role or project.
 *
 * @param ref  the profile highlight it comes from, e.g. {@code E1.H2} or {@code P1.H1}
 * @param text the (possibly reworded) text
 */
public record Bullet(String ref, String text) {
}
