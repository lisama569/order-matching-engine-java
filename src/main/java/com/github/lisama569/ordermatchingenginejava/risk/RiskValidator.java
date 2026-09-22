package com.github.lisama569.ordermatchingenginejava.risk;

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

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
