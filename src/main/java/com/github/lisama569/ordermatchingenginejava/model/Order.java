package com.github.lisama569.ordermatchingenginejava.model;

/**
 * 订单实体。线程安全性由 {@code MatchingEngine} 的全局锁保证，
 * 字段不加 volatile / 原子类，仅在锁内访问。
 */
public final class Order {

    private final long orderId;
    private final String clientOrderId;
    private final String accountId;
    private final String symbol;
    private final Side side;
    private final OrderType type;
    /** 限价单：限价；市价单：客户提供的保护价（买单冻结资金 / 卖单防穿仓）。改价时更新。 */
    private long price;
    private long originalQty;
    private long filledQty;
    private OrderStatus status;
    private RejectReason rejectReason;
    /**
     * 进入订单簿的逻辑序号，决定同一价格档位内的时间优先。
     * 改价（含市价单改保护价）后按改单时刻重新发号，失去原时间优先；
     * 仅减量/增量不改价时保持原序号，保留原排队先后。
     */
    private long enqueueSeq;

    public Order(long orderId, String clientOrderId, String accountId, String symbol, Side side,
                 OrderType type, long price, long originalQty, long enqueueSeq) {
        this.orderId = orderId;
        this.clientOrderId = clientOrderId;
        this.accountId = accountId;
        this.symbol = symbol;
        this.side = side;
        this.type = type;
        this.price = price;
        this.originalQty = originalQty;
        this.filledQty = 0;
        this.status = OrderStatus.NEW;
        this.enqueueSeq = enqueueSeq;
    }

    public long remainingQty() {
        return originalQty - filledQty;
    }

    public long getOrderId() { return orderId; }
    public String getClientOrderId() { return clientOrderId; }
    public String getAccountId() { return accountId; }
    public String getSymbol() { return symbol; }
    public Side getSide() { return side; }
    public OrderType getType() { return type; }
    public long getPrice() { return price; }
    public long getOriginalQty() { return originalQty; }
    public long getFilledQty() { return filledQty; }
    public void setFilledQty(long v) { this.filledQty = v; }
    public OrderStatus getStatus() { return status; }
    public void setStatus(OrderStatus status) { this.status = status; }
    public RejectReason getRejectReason() { return rejectReason; }
    public void setRejectReason(RejectReason rejectReason) { this.rejectReason = rejectReason; }
    public long getEnqueueSeq() { return enqueueSeq; }
    public void setEnqueueSeq(long enqueueSeq) { this.enqueueSeq = enqueueSeq; }
    /** 改价（限价单改限价 / 市价单改保护价），仅由引擎在改单成功时调用。 */
    public void setPrice(long price) { this.price = price; }
    /** 改量：更新订单总数量，调用方需保证 newOriginalQty > filledQty。 */
    public void setOriginalQty(long originalQty) { this.originalQty = originalQty; }
}
