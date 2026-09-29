package com.github.lisama569.ordermatchingenginejava.api.dto;

/**
 * 改单请求体。
 * newPrice：限价单=新限价，市价单=新保护价；不传表示不改价。
 * newQuantity：新的订单总数量（须大于已成交量）；不传表示不改量。
 */
public record ModifyOrderDto(String amendId, String accountId, String clientOrderId,
                             Long newPrice, Long newQuantity) {
}
