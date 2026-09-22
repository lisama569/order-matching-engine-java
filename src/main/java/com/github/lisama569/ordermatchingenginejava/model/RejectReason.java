package com.github.lisama569.ordermatchingenginejava.model;

/**
 * 可区分的失败原因。
 */
public enum RejectReason {
    /** 价格非法（<=0、精度非法；市价单却带价等）。 */
    INVALID_PRICE,
    /** 数量非法（<=0 等）。 */
    INVALID_QUANTITY,
    /** 参数缺失/非法（账户、标的、方向、类型等）。 */
    INVALID_PARAMETER,
    /** 账户不存在。 */
    UNKNOWN_ACCOUNT,
    /** 标的不存在。 */
    UNKNOWN_SYMBOL,
    /** 买入资金不足，无法完成下单占用。 */
    INSUFFICIENT_CASH,
    /** 卖出持仓不足，无法完成下单冻结。 */
    INSUFFICIENT_POSITION,
    /** 超过单账户单标的的敞口上限。 */
    EXPOSURE_LIMIT_EXCEEDED,
    /** 市价单没有可成交对手盘（簿为空/深度不足）。 */
    NO_LIQUIDITY,
    /** 市价单只被部分成交，剩余部分按规则撤销。 */
    MARKET_PARTIAL_CANCELLED,
    /** 撤单：订单不存在。 */
    ORDER_NOT_FOUND,
    /** 撤单：订单已终结（全部成交/已撤/已拒），不可撤销。 */
    ORDER_NOT_CANCELLABLE,
    /** 行情序号重复。 */
    TICK_DUPLICATE,
    /** 行情乱序（序号小于期望序号）。 */
    TICK_OUT_OF_ORDER,
    /** 行情序号断层（序号大于期望序号）。 */
    TICK_GAP,
    /** 行情价格非法。 */
    TICK_INVALID_PRICE
}
