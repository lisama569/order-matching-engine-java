package com.github.lisama569.ordermatchingenginejava.api;

import org.springframework.http.HttpStatus;

/** 可映射为 HTTP 响应的业务参数异常。 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
