package com.talentmatch.preferences;

import java.util.List;

/**
 * Whether a remote posting is open to someone in the owner's countries, judged from its location
 * text (pure, best-effort: free text, no provider field for hiring regions).
 */
public final class RemoteEligibility {

    public enum Result {
        /** The text names one of the owner's countries or an eligible keyword ("worldwide", "EMEA"). */
        ELIGIBLE,
        /** The text names other places only ("US only", "Remote - Europe"). */
        NOT_ELIGIBLE,
        /** The text names no known place. */
        UNKNOWN
    }

    private RemoteEligibility() {
    }

    public static Result evaluate(String locationText, List<String> countries, List<String> remoteLocationKeywords) {
        if (locationText == null || locationText.isBlank()) {
            return Result.UNKNOWN;
        }
        for (String keyword : remoteLocationKeywords) {
            if (Gazetteer.containsPhrase(locationText, keyword)) {
                return Result.ELIGIBLE;
            }
        }
        for (String country : countries) {
            if (Gazetteer.namesCountry(locationText, country)) {
                return Result.ELIGIBLE;
            }
        }
        return Gazetteer.namesPlace(locationText) ? Result.NOT_ELIGIBLE : Result.UNKNOWN;
    }
}
