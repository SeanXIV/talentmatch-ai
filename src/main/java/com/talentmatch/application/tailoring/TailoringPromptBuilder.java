package com.talentmatch.application.tailoring;

import com.talentmatch.ai.PromptText;
import com.talentmatch.application.content.DocumentKind;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Builds the tailoring prompts (§4.5). Pure and thread-safe.
 *
 * <p>User message, the same for both kinds except the first line ({@link TailoringPrompts#CV_TASK} /
 * {@link TailoringPrompts#COVER_LETTER_TASK}):
 * <pre>
 * {task}
 *
 * JOB
 * Title: {title}
 * Company: {company}
 * Required skills: {names, comma-separated | none}
 * Nice-to-have skills: {… | none}
 * &lt;job_description&gt;
 * {description, sanitized, cut to max-job-chars | (none)}
 * &lt;/job_description&gt;
 *
 * {ProfileFacts#promptBlock()}
 * </pre>
 * Every job field goes through {@link PromptText}; the description keeps its line breaks. The profile block
 * never holds contact data (decision q).
 */
public final class TailoringPromptBuilder {

    static final String NONE = "(none)";
    static final String NO_SKILLS = "none";

    private final int maxJobChars;
    private final int minWords;
    private final int maxWords;

    public TailoringPromptBuilder(ApplicationProperties properties) {
        this(properties.generation().maxJobChars(), properties.coverLetter().minWords(),
                properties.coverLetter().maxWords());
    }

    TailoringPromptBuilder(int maxJobChars, int minWords, int maxWords) {
        if (maxJobChars < 1) {
            throw new IllegalArgumentException("maxJobChars must be positive");
        }
        this.maxJobChars = maxJobChars;
        this.minWords = minWords;
        this.maxWords = maxWords;
    }

    /**
     * One prompt, ready to send.
     *
     * @param inputHash lower-case sha256 hex of system + {@link TailoringPrompts#HASH_SEPARATOR} + user
     *                  (stored on AI versions)
     */
    public record TailoringPrompt(DocumentKind kind, String system, String user, String inputHash) {
    }

    public TailoringPrompt buildCv(JobFacts job, ProfileFacts facts) {
        return prompt(DocumentKind.CV, TailoringPrompts.CV_SYSTEM, TailoringPrompts.CV_TASK, job, facts);
    }

    public TailoringPrompt buildCoverLetter(JobFacts job, ProfileFacts facts) {
        return prompt(DocumentKind.COVER_LETTER, TailoringPrompts.coverLetterSystem(minWords, maxWords),
                TailoringPrompts.COVER_LETTER_TASK, job, facts);
    }

    /** The prompt for {@code kind}. */
    public TailoringPrompt build(DocumentKind kind, JobFacts job, ProfileFacts facts) {
        return switch (Objects.requireNonNull(kind, "kind")) {
            case CV -> buildCv(job, facts);
            case COVER_LETTER -> buildCoverLetter(job, facts);
        };
    }

    private TailoringPrompt prompt(DocumentKind kind, String system, String task, JobFacts job, ProfileFacts facts) {
        Objects.requireNonNull(job, "job");
        Objects.requireNonNull(facts, "facts");
        String user = userMessage(task, job, facts);
        return new TailoringPrompt(kind, system, user, inputHash(system, user));
    }

    String userMessage(String task, JobFacts job, ProfileFacts facts) {
        StringBuilder sb = new StringBuilder(facts.promptBlock().length() + maxJobChars + 512);
        sb.append(task).append("\n\n");
        sb.append("JOB\n");
        sb.append("Title: ").append(orNone(PromptText.line(job.title()))).append('\n');
        sb.append("Company: ").append(orNone(PromptText.line(job.company()))).append('\n');
        sb.append("Required skills: ").append(skills(job.requiredSkills())).append('\n');
        sb.append("Nice-to-have skills: ").append(skills(job.niceSkills())).append('\n');
        sb.append("<job_description>\n").append(description(job.description())).append("\n</job_description>\n");
        sb.append('\n');
        sb.append(facts.promptBlock());
        return sb.toString();
    }

    /** Sanitized (line breaks kept, runs of blank lines collapsed), then cut to max-job-chars. */
    private String description(String description) {
        String s = PromptText.sanitize(description).strip().replaceAll("\n{3,}", "\n\n");
        if (s.isEmpty()) {
            return NONE;
        }
        return PromptText.truncate(s, maxJobChars);
    }

    private static String skills(List<String> names) {
        List<String> out = new ArrayList<>();
        for (String n : names) {
            String v = PromptText.line(n);
            if (!v.isEmpty()) {
                out.add(v);
            }
        }
        return out.isEmpty() ? NO_SKILLS : String.join(", ", out);
    }

    private static String orNone(String value) {
        return value.isEmpty() ? NONE : value;
    }

    /** Lower-case sha256 hex of system + {@link TailoringPrompts#HASH_SEPARATOR} (newline, U+001E, newline) + user, UTF-8. */
    public static String inputHash(String system, String user) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(system.getBytes(StandardCharsets.UTF_8));
            digest.update(TailoringPrompts.HASH_SEPARATOR.getBytes(StandardCharsets.UTF_8));
            digest.update(user.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
