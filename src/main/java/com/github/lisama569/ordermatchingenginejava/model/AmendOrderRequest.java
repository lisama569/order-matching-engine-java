package com.github.lisama569.ordermatchingenginejava.model;

/**
 * 改单请求（服务层）。对尚未终结的挂单直接改价或改量，无需先撤再下。
 *
 * @param amendId       改单请求自身的幂等键，非空；同一请求重复提交只生效一次
 * @param accountId     发起账户（必须与订单归属一致）
 * @param clientOrderId 待改订单的客户订单号
 * @param newPrice      新参考价（限价单=限价，市价单=保护价）；null 表示不改价
 * @param newQuantity   新的订单总数量（含已成交部分，必须大于已成交数量）；null 表示不改量
 */
public record AmendOrderRequest(String amendId, String accountId, String clientOrderId,
                                Long newPrice, Long newQuantity) {
}
