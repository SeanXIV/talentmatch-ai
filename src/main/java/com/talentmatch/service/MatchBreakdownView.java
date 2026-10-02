package com.talentmatch.service;

import com.talentmatch.domain.scoring.SkillHit;
import java.util.List;

/** Per-skill breakdown of a match score (computed at read time, not stored). */
public record MatchBreakdownView(
        int earnedPoints,
        int maxPoints,
        List<SkillHit> matchedRequired,
        List<SkillHit> matchedNiceToHave,
        List<SkillHit> missingRequired,
        List<SkillHit> missingNiceToHave) {
}
