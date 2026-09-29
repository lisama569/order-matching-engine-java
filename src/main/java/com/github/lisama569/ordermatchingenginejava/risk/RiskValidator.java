package com.github.lisama569.ordermatchingenginejava.risk;

import com.github.lisama569.ordermatchingenginejava.model.ModifyOrderRequest;
import com.github.lisama569.ordermatchingenginejava.model.Order;
import com.github.lisama569.ordermatchingenginejava.model.OrderType;
import com.github.lisama569.ordermatchingenginejava.model.RejectReason;
import com.github.lisama569.ordermatchingenginejava.model.SubmitOrderRequest;

/**
 * 静态风控校验：价格刻度、数量整手、必填参数等。
 * 资金 / 持仓 / 敞口等动态校验在 {@code AccountLedger} 中完成。
 *
 * 拒因可区分：
 * INVALID_PRICE / INVALID_PROTECTION_PRICE / INVALID_QUANTITY / INVALID_ARGUMENT。
 */
public final class RiskValidator {

    private final long tickSize;
    private final long lotSize;

    public RiskValidator(long tickSize, long lotSize) {
        if (tickSize <= 0 || lotSize <= 0) {
            throw new IllegalArgumentException("tickSize/lotSize must be positive");
        }
        this.tickSize = tickSize;
        this.lotSize = lotSize;
    }

    public long tickSize() { return tickSize; }
    public long lotSize() { return lotSize; }

    /** 返回拒因；通过返回 null。 */
    public RejectReason validate(SubmitOrderRequest req) {
        if (req == null) {
            return RejectReason.INVALID_ARGUMENT;
        }
        if (isBlank(req.clientOrderId()) || isBlank(req.accountId()) || isBlank(req.symbol())
                || req.side() == null || req.type() == null) {
            return RejectReason.INVALID_ARGUMENT;
        }
        if (req.quantity() <= 0) {
            return RejectReason.INVALID_QUANTITY;
        }
        if (req.quantity() % lotSize != 0) {
            return RejectReason.INVALID_QUANTITY;
        }
        if (req.type() == OrderType.LIMIT) {
            Long p = req.limitPrice();
            if (p == null || p <= 0 || p % tickSize != 0) {
                return RejectReason.INVALID_PRICE;
            }
        } else if (req.type() == OrderType.MARKET) {
            Long p = req.protectionPrice();
            if (p == null || p <= 0 || p % tickSize != 0) {
                return RejectReason.INVALID_PROTECTION_PRICE;
            }
        } else {
            return RejectReason.INVALID_ARGUMENT;
        }
        return null;
    }

    /**
     * 改单静态校验。价格刻度按订单类型区分（限价=INVALID_PRICE，
     * 市价=INVALID_PROTECTION_PRICE）；数量必须为正、整手且大于已成交量。
     * 返回拒因；通过返回 null。
     */
    public RejectReason validateModify(ModifyOrderRequest req, Order order) {
        if (req == null || order == null
                || isBlank(req.amendId()) || isBlank(req.accountId())
                || isBlank(req.clientOrderId())) {
            return RejectReason.INVALID_ARGUMENT;
        }
        if (req.newPrice() == null && req.newQuantity() == null) {
            return RejectReason.MODIFY_NOTHING;
        }
        if (req.newPrice() != null) {
            long p = req.newPrice();
            if (p <= 0 || p % tickSize != 0) {
                return order.getType() == OrderType.MARKET
                        ? RejectReason.INVALID_PROTECTION_PRICE
                        : RejectReason.INVALID_PRICE;
            }
        }
        if (req.newQuantity() != null) {
            long q = req.newQuantity();
            if (q <= 0 || q % lotSize != 0) {
                return RejectReason.INVALID_QUANTITY;
            }
            if (q <= order.getFilledQty()) {
                return RejectReason.MODIFY_QTY_BELOW_FILLED;
            }
        }
        return null;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
