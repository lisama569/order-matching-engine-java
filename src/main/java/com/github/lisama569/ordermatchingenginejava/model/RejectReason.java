package com.github.lisama569.ordermatchingenginejava.model;

/**
 * 可区分的拒单 / 操作失败原因。
 */
public enum RejectReason {
    /** 限价单价格非法（<=0 或超过最小报价单位刻度）。 */
    INVALID_PRICE,
    /** 市价单缺少保护价，或保护价非法。 */
    INVALID_PROTECTION_PRICE,
    /** 数量非法（<=0 或超过最小数量单位）。 */
    INVALID_QUANTITY,
    /** 订单类型 / 方向等参数不支持。 */
    INVALID_ARGUMENT,
    /** 账户或标的不存在。 */
    UNKNOWN_ACCOUNT_OR_SYMBOL,
    /** 可用资金或可用持仓不足（含敞口上限被击穿的情况）。 */
    EXPOSURE_LIMIT_EXCEEDED,
    /** 订单不存在。 */
    ORDER_NOT_FOUND,
    /** 订单属于其他账户，无权撤销。 */
    ORDER_OWNER_MISMATCH,
    /** 订单已成交/已撤销/已拒单，不可撤销。 */
    ORDER_NOT_CANCELLABLE,
    /** 订单已全部成交/已撤销/已拒单，不可改单。 */
    ORDER_NOT_MODIFIABLE,
    /** 改单未提供新价格与新数量（至少要改一项）。 */
    MODIFY_NOTHING,
    /** 改单后的新数量不大于已成交量（剩余数量必须为正）。 */
    MODIFY_QTY_BELOW_FILLED,
    /** 行情序号重复。 */
    TICK_DUPLICATE,
    /** 行情序号乱序（小于下一个期望序号）。 */
    TICK_OUT_OF_ORDER,
    /** 行情序号断层（大于下一个期望序号）。 */
    TICK_SEQUENCE_GAP,
    /** 行情价格非法。 */
    TICK_INVALID_PRICE,
    /** 行情服务被禁用（例如回放未完成时）。 */
    MARKET_CLOSED
}
