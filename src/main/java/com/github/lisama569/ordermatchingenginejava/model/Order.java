package com.github.lisama569.ordermatchingenginejava.model;

/**
 * 订单实体。
 *
 * <p>价格单位为「分」，数量为整数股。
 *
 * <p>{@code reservedAmount} 是下单时该订单实际占用的资金/标的数量：
 * <ul>
 *   <li>买单：单位为「分」，等于 limitPrice * orderQty（市价单按下单时刻涨停保护价预占，
 *       成交价低于预占时在成交回报中释放差额）；</li>
 *   <li>卖单：单位为「股」，等于 orderQty。</li>
 * </ul>
 *
 * <p>{@code arrivalSeq} 是引擎内全局单调递增的到达序号，用于在价格相同时
 * 实现「时间优先」（不依赖墙钟时间，保证重放确定性）。
 */
public class Order {

    private String orderId;
    private String clientRequestId;
    private String accountId;
    private String symbol;
    private Side side;
    private OrderType type;
    /** 限价单价格（分）；市价单为 0。 */
    private long limitPrice;
    private long orderQty;
    private long filledQty;
    /** 累计成交额（分），用于精确计算均价，避免整除丢精度。 */
    private long filledNotional;
    private long avgFillPrice; // 成交量加权平均价（分），仅在 filledQty>0 时有意义

    private OrderStatus status;
    private long arrivalSeq;
    private long createTime;
    private long updateTime;
    /** 本单下单时的实际占用量（买单=分，卖单=股）。 */
    private long reservedAmount;
    /** 拒单原因（仅 status=REJECTED 时有值）。 */
    private RejectReason rejectReason;

    public Order() {
    }

    public boolean isActive() {
        return status == OrderStatus.NEW || status == OrderStatus.PARTIALLY_FILLED;
    }

    public long getRemainingQty() {
        return orderQty - filledQty;
    }

    /** 累计成交额（分）。 */
    public long getFilledNotional() { return filledNotional; }
    public void setFilledNotional(long filledNotional) { this.filledNotional = filledNotional; }

    /** 成交量加权平均价（分）。 */
    public long getWeightedAvgPrice() {
        return filledQty == 0 ? 0 : filledNotional / filledQty;
    }

    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }
    public String getClientRequestId() { return clientRequestId; }
    public void setClientRequestId(String clientRequestId) { this.clientRequestId = clientRequestId; }
    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }
    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public Side getSide() { return side; }
    public void setSide(Side side) { this.side = side; }
    public OrderType getType() { return type; }
    public void setType(OrderType type) { this.type = type; }
    public long getLimitPrice() { return limitPrice; }
    public void setLimitPrice(long limitPrice) { this.limitPrice = limitPrice; }
    public long getOrderQty() { return orderQty; }
    public void setOrderQty(long orderQty) { this.orderQty = orderQty; }
    public long getFilledQty() { return filledQty; }
    public void setFilledQty(long filledQty) { this.filledQty = filledQty; }
    public long getAvgFillPrice() { return avgFillPrice; }
    public void setAvgFillPrice(long avgFillPrice) { this.avgFillPrice = avgFillPrice; }
    public OrderStatus getStatus() { return status; }
    public void setStatus(OrderStatus status) { this.status = status; }
    public long getArrivalSeq() { return arrivalSeq; }
    public void setArrivalSeq(long arrivalSeq) { this.arrivalSeq = arrivalSeq; }
    public long getCreateTime() { return createTime; }
    public void setCreateTime(long createTime) { this.createTime = createTime; }
    public long getUpdateTime() { return updateTime; }
    public void setUpdateTime(long updateTime) { this.updateTime = updateTime; }
    public long getReservedAmount() { return reservedAmount; }
    public void setReservedAmount(long reservedAmount) { this.reservedAmount = reservedAmount; }
    public RejectReason getRejectReason() { return rejectReason; }
    public void setRejectReason(RejectReason rejectReason) { this.rejectReason = rejectReason; }
}
