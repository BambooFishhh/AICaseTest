package com.testagent.common;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public class BusinessException extends RuntimeException {

    private final int code;
    private final HttpStatus httpStatus;

    public BusinessException(int code, String message, HttpStatus httpStatus) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public static BusinessException notFound(String message) {
        return new BusinessException(40401, message, HttpStatus.NOT_FOUND);
    }

    public static BusinessException invalidParam(String message) {
        return new BusinessException(40001, message, HttpStatus.BAD_REQUEST);
    }

    public static BusinessException pathNotFound(String message) {
        return new BusinessException(40402, message, HttpStatus.NOT_FOUND);
    }

    public static BusinessException invalidState(String message) {
        return new BusinessException(40901, message, HttpStatus.CONFLICT);
    }
}
