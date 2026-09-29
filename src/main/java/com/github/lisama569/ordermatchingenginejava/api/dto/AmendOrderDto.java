package com.github.lisama569.ordermatchingenginejava.api.dto;

/**
 * 改单请求体。{@code newPrice} / {@code newQuantity} 至少给一个；
 * 价格对限价单是限价、对市价单是保护价；数量为订单新的总数量（必须大于已成交量）。
 */
public record AmendOrderDto(String amendId, String accountId, String clientOrderId,
                            Long newPrice, Long newQuantity) {
}
