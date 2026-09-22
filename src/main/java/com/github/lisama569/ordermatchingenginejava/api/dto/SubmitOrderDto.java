package com.github.lisama569.ordermatchingenginejava.api.dto;

/** 下单请求体。 */
public record SubmitOrderDto(String clientOrderId, String accountId, String symbol,
                             String side, String type, Long limitPrice,
                             Long protectionPrice, Long quantity) {
}
