package com.talentmatch.service.exception;

import com.talentmatch.web.error.ErrorCode;
import org.springframework.http.HttpStatus;

/** 409 for a natural-key conflict (email, title + company, skill name). */
public class ConflictException extends ApiException {

    private static final long serialVersionUID = 1L;

    public ConflictException(ErrorCode code, String message) {
        super(code, HttpStatus.CONFLICT, message);
    }
}
