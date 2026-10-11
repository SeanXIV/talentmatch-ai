package com.talentmatch.web.dto;

import com.talentmatch.notify.Channel;
import com.talentmatch.notify.NotificationSettingsService;

/**
 * Body of {@code PUT /api/notifications/settings}: a full replacement. {@code enabled}, {@code minScore}
 * and {@code maxPerHour} are required (checked by the service, which reports every invalid field);
 * {@code channel} is optional and only {@code "EMAIL"} exists (anything else → 400 MALFORMED_REQUEST).
 */
public record NotificationSettingsRequest(Boolean enabled, Double minScore, Integer maxPerHour, Channel channel) {

    public NotificationSettingsService.SettingsInput toInput() {
        return new NotificationSettingsService.SettingsInput(enabled, minScore, maxPerHour, channel);
    }
}
