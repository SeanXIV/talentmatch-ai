package com.talentmatch.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.talentmatch.notify.Channel;
import com.talentmatch.notify.EmailNotifier;
import com.talentmatch.notify.NotificationSettingsService;
import java.util.List;

/**
 * {@code GET} / {@code PUT /api/notifications/settings} (§5.4). Never contains the SMTP username or
 * password; {@code from} and {@code to} are masked (D5).
 */
public record NotificationSettingsResponse(boolean enabled, Channel channel, double minScore, int maxPerHour,
                                           Email email) {

    /**
     * Configured: {@code {configured:true, host, port, security, from, to, authenticated}}. Not configured:
     * {@code {configured:false, missing:[…]}}.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Email(Boolean configured, String host, Integer port, String security, String from, String to,
                        Boolean authenticated, List<String> missing) {
    }

    public static NotificationSettingsResponse of(NotificationSettingsService.SettingsView view) {
        EmailNotifier.EmailInfo e = view.email();
        Email email = e == null ? new Email(false, null, null, null, null, null, null, List.of())
                : new Email(e.configured(), e.host(), e.port(), e.security(), e.from(), e.to(), e.authenticated(),
                        e.missing());
        return new NotificationSettingsResponse(view.enabled(), view.channel(), view.minScore(), view.maxPerHour(),
                email);
    }
}
