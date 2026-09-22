package com.github.lisama569.ordermatchingenginejava.model;

/**
 * 下单请求。
 *
 * @param clientRequestId 客户端幂等键：同一账户下重复提交必须返回首次结果，
 *                        不得产生重复成交或重复占用
 * @param accountId       账户 ID
 * @param symbol          标的代码
 * @param side            BUY / SELL
 * @param type            LIMIT / MARKET
 * @param limitPrice      限价（分）；LIMIT 必填且为正，MARKET 必须为空（带价即非法）
 * @param orderQty        委托数量（股，正整数）
 */
public record OrderRequest(String clientRequestId,
                           String accountId,
                           String symbol,
                           Side side,
                           OrderType type,
                           Long limitPrice,
                           long orderQty) {
}
