package com.talentmatch.ai;

import com.talentmatch.domain.scoring.MatchEvaluation;
import com.talentmatch.domain.scoring.SkillHit;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Deterministic seeker-facing template explanation (Phase 6 §4.6), written in the second person
 * and built only from the skill breakdown (pure; no AI, no name). Same score bands, skill format
 * and caps as {@link ExplanationFallbackRenderer}; the note uses the seeker stale prefix.
 */
public final class SeekerFallbackRenderer {

    static final int MAX_ITEMS = ExplanationFallbackRenderer.MAX_ITEMS;

    private SeekerFallbackRenderer() {
    }

    /**
     * @param scorePercent score as a whole percentage (0..100)
     * @param eval         the deterministic breakdown
     * @param reason       why a template is shown (null: no reason and no note)
     * @param stale        true if an older AI explanation exists but is out of date
     * @param topN         configured top N (used in the NOT_IN_TOP_N note)
     */
    public static ExplanationView render(int scorePercent, MatchEvaluation eval, ExplanationReason reason,
                                         boolean stale, int topN) {
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
        String headline = ExplanationFallbackRenderer.band(scorePercent) + ": you have " + counts;

        String s1;
        if (bigR > 0) {
            if (r == bigR) {
                s1 = bigR == 1
                        ? "You have the required skill: " + matched(mr) + "."
                        : "You have all " + bigR + " required skills: " + matched(mr) + ".";
            } else if (r == 0) {
                s1 = "You have none of the required skills (missing: " + missing(xr) + ").";
            } else {
                s1 = "You have " + r + " of " + bigR + " required skills: " + matched(mr)
                        + "; you're missing " + missing(xr) + ".";
            }
        } else {
            s1 = "This job lists only nice-to-have skills.";
        }

        String s2 = "";
        if (bigN > 0) {
            if (n == bigN) {
                s2 = "Nice-to-have skills: you have " + matched(mn) + ".";
            } else if (n == 0) {
                s2 = "Nice-to-have skills: you're missing " + missing(xn) + ".";
            } else {
                s2 = "Nice-to-have skills: you have " + matched(mn) + "; you're missing " + missing(xn) + ".";
            }
        }
        String text = s2.isEmpty() ? s1 : s1 + " " + s2;

        List<String> strengths = new ArrayList<>(MAX_ITEMS);
        for (SkillHit h : mr) {
            addCapped(strengths, ExplanationFallbackRenderer.formatHit(h));
        }
        for (SkillHit h : mn) {
            addCapped(strengths, ExplanationFallbackRenderer.formatHit(h));
        }
        List<String> gaps = new ArrayList<>(MAX_ITEMS);
        for (SkillHit h : xr) {
            addCapped(gaps, h.name() + " (required)");
        }
        for (SkillHit h : xn) {
            addCapped(gaps, h.name() + " (nice-to-have)");
        }

        return new ExplanationView(ExplanationSource.TEMPLATE, headline, text, strengths, gaps,
                null, null, reason, reason == null ? null : reason.note(topN, stale, Audience.SEEKER));
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
