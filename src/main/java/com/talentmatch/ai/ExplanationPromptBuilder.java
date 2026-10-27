package com.talentmatch.ai;

import com.talentmatch.domain.scoring.MatchEvaluation;
import com.talentmatch.domain.scoring.SkillHit;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Builds the user message for one match (pure and deterministic).
 *
 * <p>Every interpolated string is user input, so it is sanitized: {@code & < >} are escaped,
 * {@code {{ / }}} are broken up (LangChain4j treats the message as a template), and control
 * characters other than newline are removed. Single-line fields also have whitespace collapsed.
 * The job description and candidate summary are truncated to {@code maxContextChars} at a word
 * boundary and placed only inside their tags. The candidate's email is never an input.
 */
public final class ExplanationPromptBuilder {

    private final int maxContextChars;

    public ExplanationPromptBuilder(int maxContextChars) {
        if (maxContextChars < 1) {
            throw new IllegalArgumentException("maxContextChars must be positive");
        }
        this.maxContextChars = maxContextChars;
    }

    public int maxContextChars() {
        return maxContextChars;
    }

    /** The exact recruiter user message sent to the model for this match. */
    public String build(JobContext job, MatchContext match) {
        MatchEvaluation eval = match.evaluation();
        int requiredCount = eval.matchedRequired().size() + eval.missingRequired().size();

        StringBuilder sb = new StringBuilder(1024);
        sb.append(ExplanationPrompts.INTRO).append("\n\n");
        sb.append(ExplanationPrompts.FACTS_HEADER).append('\n');
        sb.append("Job: ").append(line(job.title())).append(" at ").append(line(job.company())).append('\n');
        sb.append("Candidate: ").append(line(match.candidateName())).append('\n');
        appendFacts(sb, match, eval, requiredCount);
        sb.append(ExplanationPrompts.BACKGROUND_HEADER).append('\n');
        appendTagged(sb, ExplanationPrompts.JOB_DESCRIPTION_TAG, job.description());
        sb.append('\n');
        appendTagged(sb, ExplanationPrompts.CANDIDATE_SUMMARY_TAG, match.candidateSummary());
        return sb.toString();
    }

    /**
     * The exact seeker user message (Phase 6 §4.4): the same MATCH FACTS lines as {@link #build}
     * without the candidate line, and the summary in {@code <profile_summary>}. The candidate's name
     * and email are never part of it.
     */
    public String buildSeeker(JobContext job, MatchContext match) {
        MatchEvaluation eval = match.evaluation();
        int requiredCount = eval.matchedRequired().size() + eval.missingRequired().size();

        StringBuilder sb = new StringBuilder(1024);
        sb.append(SeekerExplanationPrompts.INTRO).append("\n\n");
        sb.append(ExplanationPrompts.FACTS_HEADER).append('\n');
        sb.append("Job: ").append(line(job.title())).append(" at ").append(line(job.company())).append('\n');
        appendFacts(sb, match, eval, requiredCount);
        sb.append(ExplanationPrompts.BACKGROUND_HEADER).append('\n');
        appendTagged(sb, ExplanationPrompts.JOB_DESCRIPTION_TAG, job.description());
        sb.append('\n');
        appendTagged(sb, SeekerExplanationPrompts.PROFILE_SUMMARY_TAG, match.candidateSummary());
        return sb.toString();
    }

    /** Score, the four skill lines and the summary line, then a blank line (shared by both audiences). */
    private static void appendFacts(StringBuilder sb, MatchContext match, MatchEvaluation eval, int requiredCount) {
        sb.append("Score: ").append(match.scorePercent()).append("% (")
                .append(eval.earnedPoints()).append(" of ").append(eval.maxPoints())
                .append(" points; required skills weigh more than nice-to-have skills)\n");
        sb.append("Matched required skills: ")
                .append(requiredCount == 0 ? ExplanationPrompts.NO_REQUIRED_SKILLS : matched(eval.matchedRequired()))
                .append('\n');
        sb.append("Missing required skills: ").append(missing(eval.missingRequired())).append('\n');
        sb.append("Matched nice-to-have skills: ").append(matched(eval.matchedNiceToHave())).append('\n');
        sb.append("Missing nice-to-have skills: ").append(missing(eval.missingNiceToHave())).append('\n');
        sb.append("Summary: ").append(line(eval.summary())).append("\n\n");
    }

    /** Skill with years, e.g. {@code Java (5 years)}, {@code Java (1 year)} or {@code Java}. */
    static String formatHit(SkillHit hit) {
        String name = line(hit.name());
        Integer years = hit.yearsExperience();
        if (years == null) {
            return name;
        }
        return name + " (" + years + (years == 1 ? " year)" : " years)");
    }

    private void appendTagged(StringBuilder sb, String tag, String text) {
        sb.append('<').append(tag).append(">\n");
        sb.append(block(text));
        sb.append("\n</").append(tag).append('>');
    }

    private static String matched(List<SkillHit> hits) {
        if (hits.isEmpty()) {
            return ExplanationPrompts.NONE;
        }
        return hits.stream().map(ExplanationPromptBuilder::formatHit).collect(Collectors.joining(", "));
    }

    private static String missing(List<SkillHit> hits) {
        if (hits.isEmpty()) {
            return ExplanationPrompts.NONE;
        }
        return hits.stream().map(h -> line(h.name())).collect(Collectors.joining(", "));
    }

    /** Multi-line untrusted text: sanitized, trimmed, truncated; "(none)" when absent. */
    private String block(String text) {
        if (text == null) {
            return ExplanationPrompts.NO_TEXT;
        }
        String clean = sanitize(text).strip();
        if (clean.isEmpty()) {
            return ExplanationPrompts.NO_TEXT;
        }
        return truncate(clean, maxContextChars);
    }

    /** Single-line field: delegates to {@link PromptText#line}. */
    static String line(String text) {
        return PromptText.line(text);
    }

    /** Delegates to {@link PromptText#sanitize}. */
    static String sanitize(String text) {
        return PromptText.sanitize(text);
    }

    /** Delegates to {@link PromptText#truncate}. */
    static String truncate(String text, int max) {
        return PromptText.truncate(text, max);
    }
}
