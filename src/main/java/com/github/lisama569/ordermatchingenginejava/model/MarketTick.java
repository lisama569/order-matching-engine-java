package com.github.lisama569.ordermatchingenginejava.model;

/**
 * 行情快照。
 *
 * <p>每个标的（symbol）有独立的、从 1 开始严格递增的序号 {@code seq}：
 * 行情服务只接受 seq == 期望序号 的行情，重复/乱序/断层一律拒绝。
 *
 * <p>价格单位为「分」（0.01 元），全程使用 long 整数，避免浮点误差导致
 * 重放后撮合结论不一致。
 *
 * @param symbol    标的代码
 * @param seq       该标的的行情序号（每个标的独立，从 1 开始）
 * @param price     最新价（单位：分，必须为正）
 * @param eventTime 行情事件时间（毫秒，仅用于展示与日志，不参与撮合确定性）
 */
public record MarketTick(String symbol, long seq, long price, long eventTime) {
}
