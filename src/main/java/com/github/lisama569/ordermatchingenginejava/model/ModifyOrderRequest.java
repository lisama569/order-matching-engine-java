package com.github.lisama569.ordermatchingenginejava.model;

/**
 * 改单请求（服务层）。对仍在订单簿中的挂单直接改价/改量，无需先撤再下。
 *
 * @param amendId       本次改单的幂等键，非空；同一键重复提交只生效一次
 * @param accountId     发起账户（必须与订单属主一致）
 * @param clientOrderId 要修改的订单的客户订单号
 * @param newPrice      新参考价：限价单=新限价，市价单=新保护价；null 表示不改价
 * @param newQuantity   新的订单总数量（必须大于已成交量）；null 表示不改量
 */
public record ModifyOrderRequest(String amendId, String accountId, String clientOrderId,
                                 Long newPrice, Long newQuantity) {
}
