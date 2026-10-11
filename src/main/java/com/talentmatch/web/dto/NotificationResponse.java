package com.talentmatch.web.dto;

import com.talentmatch.notify.Channel;
import com.talentmatch.notify.NotificationRepository;
import java.time.Instant;
import java.util.UUID;

/**
 * One entry of {@code GET /api/notifications} (§5.4). {@code lastError} is sanitized text only
 * ("SMTP 421 (transient)", "connect timeout", "expired").
 */
public record NotificationResponse(UUID id, UUID jobId, String title, String company, Channel channel, String status,
                                   double score, int attempts, String lastError, Instant createdAt, Instant sentAt) {

    public static NotificationResponse of(NotificationRepository.Row r) {
        return new NotificationResponse(r.id(), r.jobId(), r.title(), r.company(), r.channel(), r.status(), r.score(),
                r.attempts(), r.lastError(), r.createdAt(), r.sentAt());
    }
}
