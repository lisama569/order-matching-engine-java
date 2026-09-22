package com.github.lisama569.ordermatchingenginejava.tests;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.github.lisama569.ordermatchingenginejava.model.OrderStatus.CANCELLED;
import static com.github.lisama569.ordermatchingenginejava.model.OrderStatus.REJECTED;
import static com.github.lisama569.ordermatchingenginejava.model.RejectReason.NO_LIQUIDITY;
import static com.github.lisama569.ordermatchingenginejava.model.Side.BUY;
import static com.github.lisama569.ordermatchingenginejava.model.Side.SELL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 市价单：需要行情价作保护价；无对手盘拒绝（NO_LIQUIDITY）；
 * 深度不足时能成交多少成交多少，剩余撤销且预占差额归还，不挂簿。
 */
class MarketOrderTest extends AbstractEngineTest {

    private static final Logger log = LoggerFactory.getLogger(MarketOrderTest.class);
    private static final String S = "600000";

    @BeforeEach
    void init() {
        setUp();
        account("A", 10_000_000L);
        account("B", 10_000_000L);
        shares("B", S, 100);
        seedPrice(S, 10_000); // 建立最近价，市价单保护价 = 11000
    }

    @Test
    void noLiquidityRejectsMarketOrder() {
        var r = service.placeOrder(market("m1", "A", S, BUY, 10), now());
        assertFalse(r.isAccepted());
        assertEquals(NO_LIQUIDITY, r.getRejectReason());
        assertEquals(0, service.snapshot("A").reservedCash());
        log.info("空簿市价买单 -> {}, 零占用", r.getRejectReason());
    }

    @Test
    void partialMarketFillCancelsRemainderAndRefundsReservation() {
        service.placeOrder(limit("s1", "B", S, SELL, 9_900, 4), now());
        // 市价买 10 股：只成交 4 股 @9900，剩余 6 股撤销（不挂簿）
        var r = service.placeOrder(market("m2", "A", S, BUY, 10), now());
        assertEquals(4, r.getFilledQty());
        assertEquals(6, r.getRemainingQty());
        assertEquals(CANCELLED, r.getStatus());

        var snap = service.snapshot("A");
        // 预占 11000*10=110000；成交扣 9900*4=39600；剩余 6 股按保护价 66000 释放
        assertEquals(10_000_000L - 39_600L, snap.cash());
        assertEquals(0, snap.reservedCash(), "剩余市价单撤销后预占清零");
        log.info("市价部分成交: filled=4@9900 剩余=6 撤销, cash={} reserved=0 判定依据=不挂簿+差额归还",
                snap.cash());
    }

    @Test
    void marketSellFreesRemainingPosition() {
        // A 只有 5 股，对手买盘只要 3 股 @10100
        shares("A", S, 5);
        service.placeOrder(limit("b1", "B", S, BUY, 10_100, 3), now());
        var r = service.placeOrder(market("m3", "A", S, SELL, 5), now());
        assertEquals(3, r.getFilledQty());
        assertEquals(CANCELLED, r.getStatus());
        var snap = service.snapshot("A");
        // 3 股已交割，2 股解冻可用（从未被扣走）
        assertEquals(2, snap.positions().get(S));
        assertEquals(0, snap.reservedPositions().getOrDefault(S, 0L));
        log.info("市价卖: 成交3 撤销2, 剩余持仓={} 冻结=0", snap.positions().get(S));
    }

    @Test
    void fullyFilledMarketOrderHasNoResidue() {
        service.placeOrder(limit("s1", "B", S, SELL, 10_000, 5), now());
        var r = service.placeOrder(market("m4", "A", S, BUY, 5), now());
        assertTrue(r.isAccepted());
        assertEquals(5, r.getFilledQty());
        var snap = service.snapshot("A");
        assertEquals(10_000_000L - 50_000L, snap.cash());
        assertEquals(0, snap.reservedCash());
        log.info("市价全成: cash={} reserved=0 敞口={}", snap.cash(),
                snap.openExposure().getOrDefault(S, 0L));
    }
}
