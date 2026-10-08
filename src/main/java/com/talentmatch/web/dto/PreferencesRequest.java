package com.talentmatch.web.dto;

import com.talentmatch.preferences.PreferencesInput;

/** Body of {@code PUT /api/preferences}: the full set of job preferences (replaces the saved ones). */
public record PreferencesRequest(PreferencesInput preferences) {
}
