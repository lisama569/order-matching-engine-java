package com.github.lisama569.ordermatchingenginejava.model;

/** 订单生命周期状态。 */
public enum OrderStatus {
    /** 已进入订单簿，等待成交。 */
    NEW,
    /** 部分成交，剩余数量仍在订单簿。 */
    PARTIALLY_FILLED,
    /** 全部成交。 */
    FILLED,
    /** 已撤销。 */
    CANCELLED,
    /** 下单即被拒（未占用资金/持仓）。 */
    REJECTED
}
