package com.talentmatch.web.dto;

import com.talentmatch.notify.Channel;
import com.talentmatch.notify.NotificationTestService;

/** {@code POST /api/notifications/test} (§5.4): {@code to} is masked; {@code messageId} is the RFC form. */
public record TestNotificationResponse(Channel channel, boolean delivered, String to, String messageId) {

    public static TestNotificationResponse of(NotificationTestService.TestResult r) {
        return new TestNotificationResponse(r.channel(), r.delivered(), r.to(), r.messageId());
    }
}
