package com.github.lisama569.ordermatchingenginejava.model;

/**
 * 订单生命周期状态：
 * NEW 已受理未成交；PARTIALLY_FILLED 部分成交仍在簿；FILLED 全部成交；
 * CANCELLED 已撤销（含撤销剩余量）；REJECTED 下单即被拒（不入簿）。
 */
public enum OrderStatus {
    NEW,
    PARTIALLY_FILLED,
    FILLED,
    CANCELLED,
    REJECTED
}
