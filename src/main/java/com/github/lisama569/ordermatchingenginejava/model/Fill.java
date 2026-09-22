package com.github.lisama569.ordermatchingenginejava.model;

/**
 * 一笔成交（对手双方共享同一 taker 视角下产生的两条 Fill 记录之一）。
 *
 * @param seq       全局成交逻辑序号（撮合顺序，重放后一致）
 * @param tickSeq   触发该成交的行情序号
 * @param symbol    标的
 * @param price     成交价（以行情价成交，允许价格改善）
 * @param qty       成交数量
 * @param makerOrderId 挂单方订单
 * @param takerOrderId 吃单方订单（同一行情同时撮合对手双方时，取后入队一方）
 */
public record Fill(long seq, long tickSeq, String symbol, long price, long qty,
                   Side makerSide, long makerOrderId, long takerOrderId) {
}
