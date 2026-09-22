package com.github.lisama569.ordermatchingenginejava.api;

import com.github.lisama569.ordermatchingenginejava.api.dto.ErrorView;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 把参数异常映射为 4xx + 可区分原因。 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorView> handle(ApiException ex) {
        return ResponseEntity.status(ex.getStatus())
                .body(new ErrorView("API_ERROR", ex.getStatus().name(), ex.getMessage()));
    }
}
