package com.talentmatch.notify;

import java.util.Objects;

/**
 * One rendered notification (§4.9).
 *
 * @param subject   one line, control characters already removed (header-injection safe), at most 150 chars
 * @param textBody  plain text, at most 4000 chars
 * @param messageId the stable message key, {@code feed-{notificationId}} or {@code test-{uuid}}. It is a
 *                  key, not the RFC 5322 form: the email notifier renders {@code <key@from-domain>}, so the
 *                  formatter needs no mail configuration (§7 D16).
 */
public record NotificationMessage(String subject, String textBody, String messageId) {

    public NotificationMessage {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(textBody, "textBody");
        Objects.requireNonNull(messageId, "messageId");
    }
}
