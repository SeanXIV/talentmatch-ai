package com.talentmatch.service.exception;

import com.talentmatch.web.error.ErrorCode;
import com.talentmatch.web.error.FieldErrorDto;
import java.util.List;
import org.springframework.http.HttpStatus;

/** 400 INVALID_PARAMETER for a query parameter checked in code (e.g. against configured limits). */
public class InvalidParameterException extends ApiException {

    private static final long serialVersionUID = 1L;

    public InvalidParameterException(String parameter, String message) {
        super(ErrorCode.INVALID_PARAMETER, HttpStatus.BAD_REQUEST, message,
                List.of(new FieldErrorDto(parameter, message)));
    }
}
