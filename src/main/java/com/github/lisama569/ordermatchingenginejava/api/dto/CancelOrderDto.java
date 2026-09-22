package com.github.lisama569.ordermatchingenginejava.api.dto;

/** 撤单请求体。 */
public record CancelOrderDto(String accountId, String clientOrderId) {
}
