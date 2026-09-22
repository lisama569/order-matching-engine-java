package com.github.lisama569.ordermatchingenginejava.api.dto;

/** 行情推送请求体。 */
public record TickDto(Long seq, String symbol, Long price) {
}
