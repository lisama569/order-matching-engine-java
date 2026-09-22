package com.github.lisama569.ordermatchingenginejava.tests;

import com.github.lisama569.ordermatchingenginejava.model.CancelRequest;
import com.github.lisama569.ordermatchingenginejava.model.OrderResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.github.lisama569.ordermatchingenginejava.model.RejectReason.INSUFFICIENT_CASH;
import static com.github.lisama569.ordermatchingenginejava.model.Side.BUY;
import static com.github.lisama569.ordermatchingenginejava.model.Side.SELL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 幂等：同一账户同一 clientRequestId 重复提交，必须返回首次结论，
 * 不产生重复成交、不重复占用；撤单重复提交不重复释放。
 */
class IdempotencyTest extends AbstractEngineTest {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyTest.class);
    private static final String S = "600000";

    @BeforeEach
    void init() {
        setUp();
        account("A", 1_000_000L);
        account("B", 1_000_000L);
        shares("B", S, 100);
    }

    @Test
    void duplicateSubmissionDoesNotDoubleTradeOrReserve() {
        // 卖盘 5 股 @10000，买单 5 股 @10000 首次提交全部成交
        var sell = service.placeOrder(limit("s1", "B", S, SELL, 10_000, 5), now());
        var first = service.placeOrder(limit("dup-1", "A", S, BUY, 10_000, 5), now());
        long cashAfterFirst = service.snapshot("A").cash();
        long positionAfterFirst = service.snapshot("B").positions().getOrDefault(S, 0L);
        log.info("首次提交 orderId={} status={} trades={} A.cash={} B.position={}",
                first.getOrderId(), first.getStatus(), first.getTrades().size(),
                cashAfterFirst, positionAfterFirst);
        assertTrue(first.isAccepted());

        // 同一 clientRequestId 重复提交 3 次：必须命中幂等、同一订单号、无新成交
        for (int i = 0; i < 3; i++) {
            OrderResponse dup = service.placeOrder(limit("dup-1", "A", S, BUY, 10_000, 5),
                    now());
            assertTrue(dup.isDuplicate(), "第 " + (i + 2) + " 次提交必须标记 duplicate");
            assertEquals(first.getOrderId(), dup.getOrderId());
            assertEquals(0, dup.getTrades().size(), "重复请求不得产生新成交");
            log.info("重复提交 #{} 幂等命中 orderId={} duplicate={} 新成交=0",
                    i + 2, dup.getOrderId(), dup.isDuplicate());
        }
        assertEquals(cashAfterFirst, service.snapshot("A").cash(), "现金不得重复扣减");
        assertEquals(positionAfterFirst, service.snapshot("B").positions().getOrDefault(S, 0L),
                "卖方持仓不得重复扣减");
    }

    @Test
    void duplicateRejectedRequestReturnsSameReason() {
        // 超量买单：现金不足被拒；重复提交返回同一原因，且不占用任何资金
        var first = service.placeOrder(limit("poor-1", "A", S, BUY, 100_000, 100), now());
        assertFalse(first.isAccepted());
        assertEquals(INSUFFICIENT_CASH, first.getRejectReason());
        var snap = service.snapshot("A");
        log.info("首次拒单 reason={} reserved={} available={}",
                first.getRejectReason(), snap.reservedCash(), snap.availableCash());

        var dup = service.placeOrder(limit("poor-1", "A", S, BUY, 100_000, 100), now());
        assertTrue(dup.isDuplicate());
        assertEquals(INSUFFICIENT_CASH, dup.getRejectReason());
        assertEquals(snap.cash(), service.snapshot("A").cash());
        assertEquals(0, service.snapshot("A").reservedCash(), "拒单不得占用资金");
        log.info("重复拒单 duplicate={} reason={} 判定依据=返回首次原因且零占用",
                dup.isDuplicate(), dup.getRejectReason());
    }

    @Test
    void sameClientIdAcrossAccountsIsIndependent() {
        var a1 = service.placeOrder(limit("same", "A", S, BUY, 10_000, 1), now());
        var b1 = service.placeOrder(limit("same", "B", S, SELL, 10_000, 1), now());
        assertNotEquals(a1.getOrderId(), b1.getOrderId(),
                "幂等键作用域为 (账户, clientRequestId)，不同账户互不影响");
        log.info("相同 clientRequestId 不同账户: A->{} B->{} 各自独立",
                a1.getOrderId(), b1.getOrderId());
    }

    @Test
    void duplicateCancelReleasesOnce() {
        var resting = service.placeOrder(limit("rest", "A", S, BUY, 9_000, 10), now());
        long reserved = service.snapshot("A").reservedCash();
        var first = service.cancel(new CancelRequest("c-1", "A", resting.getOrderId()), now());
        assertTrue(first.success());
        assertEquals(90_000, first.releasedCash());

        var cancelDup = service.cancel(new CancelRequest("c-1", "A", resting.getOrderId()), now());
        assertTrue(cancelDup.duplicate());
        assertTrue(cancelDup.success());
        assertEquals(0, service.snapshot("A").reservedCash(), "重复撤单不重复释放");
        log.info("重复撤单 duplicate={} releasedCash(首次)={} 判定依据=幂等返回，释放守恒",
                cancelDup.duplicate(), first.releasedCash());
    }
}
