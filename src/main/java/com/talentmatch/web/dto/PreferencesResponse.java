package com.talentmatch.web.dto;

import com.talentmatch.preferences.JobPreferences;
import com.talentmatch.preferences.PreferencesRepository;
import java.time.Instant;

/**
 * The owner's saved job preferences.
 *
 * @param version     1 on the first save, +1 on every save
 * @param preferences the normalized preferences, with defaults filled in
 */
public record PreferencesResponse(int version, JobPreferences preferences, Instant updatedAt) {

    public static PreferencesResponse of(PreferencesRepository.Stored stored) {
        return new PreferencesResponse(stored.version(), stored.preferences(), stored.updatedAt());
    }
}
