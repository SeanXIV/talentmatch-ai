package com.talentmatch.feed;

import com.talentmatch.preferences.JobPreferences;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The Adzuna searches the job preferences call for (§4.11, decision i; pure).
 * <ul>
 *   <li>One per target title, at most {@code adzuna.derived-queries}, in the owner's order
 *       (case-insensitive duplicates dropped).</li>
 *   <li>No target titles: the confirmed profile's most recent job title, if any (the owner confirmed
 *       it; never AI-made).</li>
 *   <li>Plus {@code what="remote"} when remote jobs are wanted.</li>
 * </ul>
 * Each search is {@code country} + {@code what}, no {@code where}. No preferences saved means
 * {@link JobPreferences#none()}: no titles, remote wanted.
 */
public final class DerivedQueries {

    static final String REMOTE = "remote";

    private DerivedQueries() {
    }

    /**
     * @param preferences       the saved preferences; null = none saved
     * @param latestProfileTitle the confirmed profile's {@code experience[0].title}; nullable
     * @param max               {@code adzuna.derived-queries}
     * @param country           {@code adzuna.country}
     * @return the stored options of each search, in a stable order
     */
    public static List<Map<String, String>> of(JobPreferences preferences, String latestProfileTitle, int max,
                                               String country) {
        JobPreferences prefs = preferences == null ? JobPreferences.none() : preferences;
        Set<String> seen = new LinkedHashSet<>();
        List<String> terms = new ArrayList<>();
        for (String title : prefs.targetTitles()) {
            if (terms.size() >= max) {
                break;
            }
            addTerm(terms, seen, title);
        }
        if (terms.isEmpty() && max > 0) {
            addTerm(terms, seen, latestProfileTitle);
        }
        if (prefs.regions().includeRemote()) {
            addTerm(terms, seen, REMOTE);
        }
        return terms.stream().map(what -> FeedSourceService.adzunaOptions(country, what, null)).toList();
    }

    private static void addTerm(List<String> terms, Set<String> seen, String raw) {
        String term = FeedSourceService.searchTerm(raw);
        if (term == null) {
            return;
        }
        if (term.length() > FeedSourceService.MAX_QUERY_LENGTH) {
            term = term.substring(0, FeedSourceService.MAX_QUERY_LENGTH).strip();
        }
        if (seen.add(term.toLowerCase(Locale.ROOT))) {
            terms.add(term);
        }
    }
}
