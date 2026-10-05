package com.talentmatch.ai;

import com.talentmatch.domain.scoring.MatchEvaluation;
import com.talentmatch.domain.scoring.SkillHit;
import java.util.List;
import java.util.regex.Pattern;
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

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final String ELLIPSIS = "…";

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

    /** The exact user message sent to the model for this match. */
    public String build(JobContext job, MatchContext match) {
        MatchEvaluation eval = match.evaluation();
        int requiredCount = eval.matchedRequired().size() + eval.missingRequired().size();

        StringBuilder sb = new StringBuilder(1024);
        sb.append(ExplanationPrompts.INTRO).append("\n\n");
        sb.append(ExplanationPrompts.FACTS_HEADER).append('\n');
        sb.append("Job: ").append(line(job.title())).append(" at ").append(line(job.company())).append('\n');
        sb.append("Candidate: ").append(line(match.candidateName())).append('\n');
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
        sb.append(ExplanationPrompts.BACKGROUND_HEADER).append('\n');
        appendTagged(sb, ExplanationPrompts.JOB_DESCRIPTION_TAG, job.description());
        sb.append('\n');
        appendTagged(sb, ExplanationPrompts.CANDIDATE_SUMMARY_TAG, match.candidateSummary());
        return sb.toString();
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

    /** Single-line field: sanitized with whitespace (including newlines) collapsed to one space. */
    static String line(String text) {
        if (text == null) {
            return "";
        }
        return WHITESPACE.matcher(sanitize(text)).replaceAll(" ").strip();
    }

    /**
     * Escapes {@code & < >}, breaks up {@code {{ }}}, normalizes line breaks and tabs, and strips
     * every other control character.
     */
    static String sanitize(String text) {
        if (text == null) {
            return "";
        }
        String s = text.replace("\r\n", "\n").replace('\r', '\n').replace('\t', ' ');
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n') {
                sb.append(c);
            } else if (Character.isISOControl(c)) {
                continue;
            } else if (c == '&') {
                sb.append("&amp;");
            } else if (c == '<') {
                sb.append("&lt;");
            } else if (c == '>') {
                sb.append("&gt;");
            } else {
                sb.append(c);
            }
        }
        String out = sb.toString();
        while (out.contains("{{")) {
            out = out.replace("{{", "{ {");
        }
        while (out.contains("}}")) {
            out = out.replace("}}", "} }");
        }
        return out;
    }

    /**
     * Cuts {@code text} to at most {@code max} characters at the last whitespace at or before
     * the limit (hard cut if there is none) and appends an ellipsis.
     */
    static String truncate(String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        int cut = -1;
        for (int i = Math.min(max, text.length() - 1); i > 0; i--) {
            if (Character.isWhitespace(text.charAt(i))) {
                cut = i;
                break;
            }
        }
        String head = cut > 0 ? text.substring(0, cut) : text.substring(0, max);
        return head.stripTrailing() + ELLIPSIS;
    }
}
