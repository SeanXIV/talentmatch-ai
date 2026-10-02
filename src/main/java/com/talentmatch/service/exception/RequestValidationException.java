package com.talentmatch.service.exception;

import com.talentmatch.web.error.ErrorCode;
import com.talentmatch.web.error.FieldErrorDto;
import java.util.List;
import org.springframework.http.HttpStatus;

/** 400 VALIDATION_FAILED with one entry per invalid request-body field. */
public class RequestValidationException extends ApiException {

    private static final long serialVersionUID = 1L;

    public RequestValidationException(List<FieldErrorDto> fieldErrors) {
        super(ErrorCode.VALIDATION_FAILED, HttpStatus.BAD_REQUEST, summarize(fieldErrors), fieldErrors);
    }

    /** "1 field is invalid. Fix it and try again." / "2 fields are invalid. Fix them and try again." */
    public static String summarize(List<FieldErrorDto> fieldErrors) {
        long fields = fieldErrors == null ? 0 : fieldErrors.stream().map(FieldErrorDto::field).distinct().count();
        if (fields <= 1) {
            return "1 field is invalid. Fix it and try again.";
        }
        return fields + " fields are invalid. Fix them and try again.";
    }
}
