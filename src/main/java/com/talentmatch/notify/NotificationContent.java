package com.talentmatch.notify;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The job facts an email may contain (§4.9): only the job's own data and the names of its skills split
 * into the ones the owner has and lacks. No CV data: no name, contact details, profile text, experience
 * or years. Plain types only, so {@code notify} needs nothing from {@code feed} or {@code preferences}.
 *
 * @param workplace       the workplace enum name (REMOTE, HYBRID, ONSITE, UNKNOWN) or null
 * @param summary         the scoring summary, or null
 * @param matchedRequired required skills the owner has (names)
 * @param missingRequired required skills the owner lacks (names)
 * @param flags           preference flag names (e.g. REMOTE_ELIGIBILITY_UNKNOWN)
 * @param sourceKinds     display names of the job's open postings' providers ("Greenhouse", "Adzuna")
 * @param adzunaAttached  an Adzuna posting is attached (attribution "Jobs by Adzuna")
 */
public record NotificationContent(UUID jobId, String title, String company, String workplace, String locationText,
                                  List<String> countryCodes, Instant postedAt, Instant firstSeenAt, String primaryUrl,
                                  String summary, List<String> matchedRequired, List<String> missingRequired,
                                  List<String> flags, List<String> sourceKinds, boolean adzunaAttached) {

    public NotificationContent {
        countryCodes = countryCodes == null ? List.of() : List.copyOf(countryCodes);
        matchedRequired = matchedRequired == null ? List.of() : List.copyOf(matchedRequired);
        missingRequired = missingRequired == null ? List.of() : List.copyOf(missingRequired);
        flags = flags == null ? List.of() : List.copyOf(flags);
        sourceKinds = sourceKinds == null ? List.of() : List.copyOf(sourceKinds);
    }
}
