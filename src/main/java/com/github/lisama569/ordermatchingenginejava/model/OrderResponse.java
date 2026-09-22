package com.github.lisama569.ordermatchingenginejava.model;

import java.util.List;

/**
 * 下单结果。
 *
 * <p>{@code accepted=false} 时 {@code rejectReason} 给出可区分的失败原因；
 * {@code duplicate=true} 表示这是重复请求，返回的是首次请求的结论（不会重复成交/占用）。
 * {@code trades} 为本次下单同步撮合产生的成交（按时间先后）。
 */
public class OrderResponse {

    private boolean accepted;
    private boolean duplicate;
    private String orderId;
    private String clientRequestId;
    private OrderStatus status;
    private long filledQty;
    private long remainingQty;
    private long avgFillPrice;
    private RejectReason rejectReason;
    private List<Trade> trades;

    public static OrderResponse of(Order order, List<Trade> trades, boolean duplicate) {
        OrderResponse r = new OrderResponse();
        r.accepted = order.getStatus() != OrderStatus.REJECTED;
        r.duplicate = duplicate;
        r.orderId = order.getOrderId();
        r.clientRequestId = order.getClientRequestId();
        r.status = order.getStatus();
        r.filledQty = order.getFilledQty();
        r.remainingQty = order.getRemainingQty();
        r.avgFillPrice = order.getWeightedAvgPrice();
        r.rejectReason = order.getRejectReason();
        r.trades = trades == null ? List.of() : List.copyOf(trades);
        return r;
    }

    /** 合成一个「首次拒单」响应（没有订单实体时使用，例如账户未知）。 */
    public static OrderResponse rejected(String clientRequestId, RejectReason reason,
                                         List<Trade> trades, boolean duplicate) {
        OrderResponse r = new OrderResponse();
        r.accepted = false;
        r.duplicate = duplicate;
        r.clientRequestId = clientRequestId;
        r.status = OrderStatus.REJECTED;
        r.rejectReason = reason;
        r.trades = trades == null ? List.of() : List.copyOf(trades);
        return r;
    }

    public boolean isAccepted() { return accepted; }
    public boolean isDuplicate() { return duplicate; }
    public String getOrderId() { return orderId; }
    public String getClientRequestId() { return clientRequestId; }
    public OrderStatus getStatus() { return status; }
    public long getFilledQty() { return filledQty; }
    public long getRemainingQty() { return remainingQty; }
    public long getAvgFillPrice() { return avgFillPrice; }
    public RejectReason getRejectReason() { return rejectReason; }
    public List<Trade> getTrades() { return trades; }
}
