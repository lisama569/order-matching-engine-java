package com.github.lisama569.ordermatchingenginejava.api.dto;

/** 统一错误响应。 */
public record ErrorView(String error, String reason, String message) {
}
