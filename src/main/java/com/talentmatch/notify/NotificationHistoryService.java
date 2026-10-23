package com.talentmatch.notify;

import com.talentmatch.service.Paging;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@code GET /api/notifications} (§5.4): the notification history, newest first. */
@Service
public class NotificationHistoryService {

    private final NotificationRepository notifications;

    public NotificationHistoryService(NotificationRepository notifications) {
        this.notifications = notifications;
    }

    @Transactional(readOnly = true)
    public Page<NotificationRepository.Row> list(int page, int size) {
        Pageable pageable = Paging.of(page, size);
        long total = notifications.count();
        List<NotificationRepository.Row> rows = total <= pageable.getOffset() ? List.of()
                : notifications.findPage(pageable.getOffset(), pageable.getPageSize());
        return new PageImpl<>(rows, pageable, total);
    }
}
