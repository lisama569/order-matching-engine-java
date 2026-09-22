package com.github.lisama569.ordermatchingenginejava.model;

/**
 * 一笔成交记录。
 *
 * <p>taker 为主动方（新到订单或行情触发方），maker 为订单簿上的挂单；
 * 当行情价与挂单成交时，takerAccountId 为内部对手盘账户 {@code MARKET}，
 * takerOrderId 以 "TICK-" 前缀标记，便于解释成交来源。
 *
 * <p>成交价取「挂单价」（resting maker 价格），这是价格优先撮合的常规约定，
 * 也保证谁先挂单谁定价，结论在重放时确定。
 *
 * @param tradeId        全局唯一成交编号
 * @param symbol         标的
 * @param price          成交价（分）
 * @param qty            成交数量（股）
 * @param tradeTime      成交时间（毫秒）
 * @param takerOrderId   主动方订单号（行情触发时为 TICK-seq）
 * @param takerAccountId 主动方账户（行情触发时为 MARKET）
 * @param takerSide      主动方方向（maker 方向恒为其反方向）
 * @param makerOrderId   被动方挂单号
 * @param makerAccountId 被动方账户
 */
public record Trade(String tradeId,
                    String symbol,
                    long price,
                    long qty,
                    long tradeTime,
                    String takerOrderId,
                    String takerAccountId,
                    Side takerSide,
                    String makerOrderId,
                    String makerAccountId) {
}
