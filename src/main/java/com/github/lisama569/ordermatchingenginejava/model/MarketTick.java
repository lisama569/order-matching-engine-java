package com.github.lisama569.ordermatchingenginejava.model;

/**
 * 行情快照（模拟行情流的一条消息）。
 *
 * @param seq    单调递增序号，从 1 开始，必须连续
 * @param symbol 标的
 * @param price  最新成交价 / 可成交价（最小价格单位的整数倍）
 */
public record MarketTick(long seq, String symbol, long price) {
}
