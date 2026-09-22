package com.github.lisama569.ordermatchingenginejava.model;

import java.util.List;

/**
 * 行情注入结果。
 *
 * @param accepted    是否被接受（序号正确且价格合法）
 * @param symbol      标的
 * @param seq         行情序号
 * @param expectedSeq 期望的下一个序号（用于解释拒绝原因）
 * @param price       行情价（分）
 * @param trades      该行情驱动产生的成交
 * @param rejectReason TICK_DUPLICATE / TICK_OUT_OF_ORDER / TICK_GAP / TICK_INVALID_PRICE
 */
public record TickResponse(boolean accepted,
                           String symbol,
                           long seq,
                           long expectedSeq,
                           long price,
                           List<Trade> trades,
                           RejectReason rejectReason) {

    public static TickResponse accepted(MarketTick tick, List<Trade> trades) {
        return new TickResponse(true, tick.symbol(), tick.seq(), tick.seq() + 1, tick.price(),
                trades == null ? List.of() : List.copyOf(trades), null);
    }

    public static TickResponse rejected(MarketTick tick, long expectedSeq, RejectReason reason) {
        return new TickResponse(false, tick.symbol(), tick.seq(), expectedSeq, tick.price(),
                List.of(), reason);
    }
}
