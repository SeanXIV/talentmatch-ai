package com.talentmatch.service.exception;

import com.talentmatch.web.error.ErrorCode;
import org.springframework.http.HttpStatus;

/** 409 when {@code POST /api/notifications/test} is called while email isn't configured. */
public class NotificationNotConfiguredException extends ApiException {

    private static final long serialVersionUID = 1L;

    /** Also the {@code enabled} field error of {@code PUT /api/notifications/settings} (§5.4). */
    public static final String MESSAGE = "Email isn't configured: set SMTP_HOST, NOTIFY_EMAIL_FROM and NOTIFY_EMAIL_TO "
            + "(plus SMTP_USERNAME and SMTP_PASSWORD if your server needs a login), then restart.";

    public NotificationNotConfiguredException() {
        super(ErrorCode.NOTIFICATION_NOT_CONFIGURED, HttpStatus.CONFLICT, MESSAGE);
    }
}
