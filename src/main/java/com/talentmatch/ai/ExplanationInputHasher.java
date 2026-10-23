package com.talentmatch.ai;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Staleness hash of an explanation's inputs: lower-case sha256 hex of
 * {@code SYSTEM + "\n\u001e\n" + userMessage}, where SYSTEM is the audience's system prompt. Anything the model sees is covered; anything it
 * does not see (email, text beyond the truncation point, computed_at, the model) is not.
 */
public final class ExplanationInputHasher {

    private ExplanationInputHasher() {
    }

    /** The RECRUITER hash; same bytes as before Phase 6. */
    public static String hash(String userMessage) {
        return hash(ExplanationPrompts.SYSTEM, userMessage);
    }

    /**
     * The hash for one audience: its own {@link Audience#systemPrompt()} plus the user message.
     * RECRUITER gives the same bytes as {@link #hash(String)}; SEEKER uses
     * {@link SeekerExplanationPrompts#SYSTEM}, so the same user message hashes differently per audience.
     */
    public static String hash(Audience audience, String userMessage) {
        Objects.requireNonNull(audience, "audience");
        return hash(audience.systemPrompt(), userMessage);
    }

    /** Visible for tests: the same hash with an explicit system prompt. */
    static String hash(String systemPrompt, String userMessage) {
        Objects.requireNonNull(userMessage, "userMessage");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(systemPrompt.getBytes(StandardCharsets.UTF_8));
            digest.update(ExplanationPrompts.HASH_SEPARATOR.getBytes(StandardCharsets.UTF_8));
            digest.update(userMessage.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
