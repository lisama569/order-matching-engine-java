package com.github.lisama569.ordermatchingenginejava.tests;

import com.github.lisama569.ordermatchingenginejava.model.CancelRequest;
import com.github.lisama569.ordermatchingenginejava.model.OrderRequest;
import com.github.lisama569.ordermatchingenginejava.model.OrderResponse;
import com.github.lisama569.ordermatchingenginejava.model.OrderType;
import com.github.lisama569.ordermatchingenginejava.model.RejectReason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.github.lisama569.ordermatchingenginejava.model.Side.BUY;
import static com.github.lisama569.ordermatchingenginejava.model.Side.SELL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 非法输入/超量/不可撤销状态必须被拒，且失败原因可区分；
 * 被拒操作不得影响其他账户状态。
 */
class RejectionTest extends AbstractEngineTest {

    private static final Logger log = LoggerFactory.getLogger(RejectionTest.class);
    private static final String S = "600000";

    @BeforeEach
    void init() {
        setUp();
        account("A", 500_000L);
        account("B", 500_000L);
        shares("B", S, 20);
    }

    @Test
    void distinguishableRejectReasons() {
        assertReason(new OrderRequest("r1", "A", S, BUY, OrderType.LIMIT, 0L, 10),
                RejectReason.INVALID_PRICE, "限价为0");
        assertReason(new OrderRequest("r2", "A", S, BUY, OrderType.LIMIT, -5L, 10),
                RejectReason.INVALID_PRICE, "限价为负");
        assertReason(new OrderRequest("r3", "A", S, BUY, OrderType.LIMIT, 10_000L, 0),
                RejectReason.INVALID_QUANTITY, "数量为0");
        assertReason(new OrderRequest("r4", "A", S, BUY, OrderType.LIMIT, 10_000L, -1),
                RejectReason.INVALID_QUANTITY, "数量为负");
        assertReason(new OrderRequest("r5", "A", S, BUY, OrderType.MARKET, 10_000L, 1),
                RejectReason.INVALID_PRICE, "市价单带价");
        assertReason(new OrderRequest("r6", "A", S, null, OrderType.LIMIT, 10_000L, 1),
                RejectReason.INVALID_PARAMETER, "方向为空");
        assertReason(new OrderRequest("r7", "A", "999999", BUY, OrderType.LIMIT, 10_000L, 1),
                RejectReason.UNKNOWN_SYMBOL, "标的不存在");
        assertReason(new OrderRequest("r8", "GHOST", S, BUY, OrderType.LIMIT, 10_000L, 1),
                RejectReason.UNKNOWN_ACCOUNT, "账户不存在");
        assertReason(new OrderRequest("r9", "A", S, BUY, OrderType.LIMIT, 100_000L, 10),
                RejectReason.INSUFFICIENT_CASH, "买入超资金");
        assertReason(new OrderRequest("r10", "A", S, SELL, OrderType.LIMIT, 10_000L, 1),
                RejectReason.INSUFFICIENT_POSITION, "卖出超持仓");
    }

    @Test
    void marketOrderWithoutReferencePriceRejected() {
        // 全新标的且从无行情：市价单无法确定保护价
        service.registerSymbol("NEW");
        assertReason(market("m1", "A", "NEW", BUY, 1), RejectReason.NO_LIQUIDITY,
                "无行情参考的市价单");
    }

    @Test
    void rejectedOrderDoesNotAffectOthers() {
        long cashA = service.snapshot("A").cash();
        long cashB = service.snapshot("B").cash();
        var bad = service.placeOrder(
                new OrderRequest("x1", "A", S, BUY, OrderType.LIMIT, 100_000L, 100), now());
        assertFalse(bad.isAccepted());
        assertEquals(RejectReason.INSUFFICIENT_CASH, bad.getRejectReason());

        // B 账户的正常卖单与资金完全不受 A 被拒影响
        var good = service.placeOrder(limit("ok", "B", S, SELL, 10_000, 5), now());
        assertTrue(good.isAccepted());
        assertEquals(cashA, service.snapshot("A").cash());
        assertEquals(cashB, service.snapshot("B").cash());
        assertEquals(0, service.snapshot("A").reservedCash(), "拒单零占用");
        log.info("A 超量被拒 reason={} 后，B 正常下单 orderId={} status={} 两账户余额不变",
                bad.getRejectReason(), good.getOrderId(), good.getStatus());
    }

    @Test
    void cancelNonCancellableStatesRejected() {
        var filled = service.placeOrder(limit("f1", "B", S, SELL, 10_000, 1), now());
        service.placeOrder(limit("f2", "A", S, BUY, 10_000, 1), now());
        // 撤销已全部成交的订单
        var r1 = service.cancel(new CancelRequest("k1", "B", filled.getOrderId()), now());
        assertFalse(r1.success());
        assertEquals(RejectReason.ORDER_NOT_CANCELLABLE, r1.rejectReason());
        log.info("撤销已成交订单 orderId={} 判定依据={}", filled.getOrderId(), r1.rejectReason());

        var r2 = service.cancel(new CancelRequest("k2", "A", "NOT-EXIST"), now());
        assertEquals(RejectReason.ORDER_NOT_FOUND, r2.rejectReason());
        log.info("撤销不存在订单 判定依据={}", r2.rejectReason());

        var r3 = service.cancel(new CancelRequest("k3", "B", filled.getOrderId()), now());
        // 第二次是新的幂等键：仍按不可撤销拒绝（不会因为上一次拒绝而变成“成功”）
        assertEquals(RejectReason.ORDER_NOT_CANCELLABLE, r3.rejectReason());
    }

    private void assertReason(OrderRequest req, RejectReason expected, String desc) {
        OrderResponse r = service.placeOrder(req, now());
        assertFalse(r.isAccepted(), desc);
        assertEquals(expected, r.getRejectReason(), desc + " 原因可区分");
        log.info("拒单用例[{}] 输入 side={} type={} limit={} qty={} -> 判定依据={}",
                desc, req.side(), req.type(), req.limitPrice(), req.orderQty(),
                r.getRejectReason());
    }
}
