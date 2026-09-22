package com.github.lisama569.ordermatchingenginejava.model;

/**
 * 下单请求（服务层）。
 *
 * @param clientOrderId 客户提供的幂等键，非空
 * @param accountId     账户
 * @param symbol        标的
 * @param side          买卖方向
 * @param type          LIMIT / MARKET
 * @param limitPrice    限价（限价单必填，最小价格单位整数倍）；市价单忽略
 * @param protectionPrice 市价单保护价（买入据此冻结资金，必须为正）；限价单忽略
 * @param quantity      数量（最小数量单位的整数倍）
 */
public record SubmitOrderRequest(String clientOrderId, String accountId, String symbol, Side side,
                                 OrderType type, Long limitPrice, Long protectionPrice, long quantity) {
}
