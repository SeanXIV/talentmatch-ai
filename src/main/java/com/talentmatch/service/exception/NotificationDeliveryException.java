package com.talentmatch.service.exception;

import com.talentmatch.web.error.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * 502 when the test email couldn't be delivered (the only 502 in the API). The message comes from the
 * classified SMTP error: host:port and the SMTP code, never the server's reply text or a credential.
 */
public class NotificationDeliveryException extends ApiException {

    private static final long serialVersionUID = 1L;

    public NotificationDeliveryException(String message) {
        super(ErrorCode.NOTIFICATION_DELIVERY_FAILED, HttpStatus.BAD_GATEWAY, message);
    }
}
