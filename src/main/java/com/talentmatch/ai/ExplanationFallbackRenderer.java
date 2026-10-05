package com.talentmatch.ai;

import com.talentmatch.domain.scoring.MatchEvaluation;
import com.talentmatch.domain.scoring.SkillHit;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Deterministic template explanation built from the skill breakdown (pure; no AI). Used whenever
 * an AI explanation is not READY, so every match always carries a useful explanation.
 */
public final class ExplanationFallbackRenderer {

    static final int MAX_ITEMS = 5;

    private ExplanationFallbackRenderer() {
    }

    /**
     * @param candidateName candidate's display name
     * @param scorePercent  score as a whole percentage (0..100)
     * @param eval          the deterministic breakdown
     * @param reason        why a template is shown
     * @param stale         true if an older AI explanation exists but is out of date
     * @param topN          configured top N (used in the NOT_IN_TOP_N note)
     */
    public static ExplanationView render(String candidateName, int scorePercent, MatchEvaluation eval,
                                         ExplanationReason reason, boolean stale, int topN) {
        String name = candidateName == null || candidateName.isBlank() ? "The candidate" : candidateName.strip();
        List<SkillHit> mr = eval.matchedRequired();
        List<SkillHit> xr = eval.missingRequired();
        List<SkillHit> mn = eval.matchedNiceToHave();
        List<SkillHit> xn = eval.missingNiceToHave();
        int r = mr.size();
        int bigR = r + xr.size();
        int n = mn.size();
        int bigN = n + xn.size();

        String counts = bigR > 0
                ? r + " of " + bigR + " required " + noun(bigR)
                : n + " of " + bigN + " nice-to-have " + noun(bigN);
        String headline = band(scorePercent) + ": " + counts;

        String s1;
        if (bigR > 0) {
            if (r == bigR) {
                s1 = bigR == 1
                        ? name + " has the required skill: " + matched(mr) + "."
                        : name + " has all " + bigR + " required skills: " + matched(mr) + ".";
            } else if (r == 0) {
                s1 = name + " has none of the required skills (missing: " + missing(xr) + ").";
            } else {
                s1 = name + " has " + r + " of " + bigR + " required skills: " + matched(mr)
                        + "; missing: " + missing(xr) + ".";
            }
        } else {
            s1 = name + " was scored on nice-to-have skills only.";
        }

        String s2 = "";
        if (bigN > 0) {
            if (n == bigN) {
                s2 = "Nice-to-have skills: has " + matched(mn) + ".";
            } else if (n == 0) {
                s2 = "Nice-to-have skills: missing " + missing(xn) + ".";
            } else {
                s2 = "Nice-to-have skills: has " + matched(mn) + "; missing " + missing(xn) + ".";
            }
        }
        String text = s2.isEmpty() ? s1 : s1 + " " + s2;

        List<String> strengths = new ArrayList<>(MAX_ITEMS);
        for (SkillHit h : mr) {
            addCapped(strengths, formatHit(h));
        }
        for (SkillHit h : mn) {
            addCapped(strengths, formatHit(h));
        }
        List<String> gaps = new ArrayList<>(MAX_ITEMS);
        for (SkillHit h : xr) {
            addCapped(gaps, h.name() + " (required)");
        }
        for (SkillHit h : xn) {
            addCapped(gaps, h.name() + " (nice-to-have)");
        }

        return new ExplanationView(ExplanationSource.TEMPLATE, headline, text, strengths, gaps,
                null, null, reason, reason == null ? null : reason.note(topN, stale));
    }

    /** "Strong match" (&gt;= 80), "Partial match" (50..79), "Weak match" (1..49), "No skill overlap" (0). */
    static String band(int scorePercent) {
        if (scorePercent >= 80) {
            return "Strong match";
        }
        if (scorePercent >= 50) {
            return "Partial match";
        }
        if (scorePercent >= 1) {
            return "Weak match";
        }
        return "No skill overlap";
    }

    /** {@code Java (5 years)}, {@code Java (1 year)}, {@code Java (0 years)} or {@code Java}. */
    static String formatHit(SkillHit hit) {
        Integer years = hit.yearsExperience();
        if (years == null) {
            return hit.name();
        }
        return hit.name() + " (" + years + (years == 1 ? " year)" : " years)");
    }

    private static String matched(List<SkillHit> hits) {
        return hits.stream().map(ExplanationFallbackRenderer::formatHit).collect(Collectors.joining(", "));
    }

    private static String missing(List<SkillHit> hits) {
        return hits.stream().map(SkillHit::name).collect(Collectors.joining(", "));
    }

    private static String noun(int denominator) {
        return denominator == 1 ? "skill" : "skills";
    }

    private static void addCapped(List<String> list, String item) {
        if (list.size() < MAX_ITEMS) {
            list.add(item);
        }
    }
}
