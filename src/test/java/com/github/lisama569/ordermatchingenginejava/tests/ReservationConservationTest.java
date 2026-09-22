package com.github.lisama569.ordermatchingenginejava.tests;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.github.lisama569.ordermatchingenginejava.model.Side.BUY;
import static com.github.lisama569.ordermatchingenginejava.model.Side.SELL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 资金/持仓占用与释放守恒：
 * cash = available + reserved；position = available + reserved；
 * 成交后预占差额释放、撤单全额释放；买卖双方账务闭合。
 */
class ReservationConservationTest extends AbstractEngineTest {

    private static final Logger log = LoggerFactory.getLogger(ReservationConservationTest.class);
    private static final String S = "600000";

    @BeforeEach
    void init() {
        setUp();
        account("BUYER", 1_000_000L);
        account("SELLER", 1_000_000L);
        shares("SELLER", S, 100);
    }

    @Test
    void reserveOnPlaceReleaseOnCancel_buySide() {
        var r = service.placeOrder(limit("b1", "BUYER", S, BUY, 10_000, 10), now());
        var snap = service.snapshot("BUYER");
        assertEquals(1_000_000L, snap.cash());
        assertEquals(100_000L, snap.reservedCash());
        assertEquals(900_000L, snap.availableCash());
        log.info("买单下单后: cash={} reserved={} available={} 判定依据=限价*数量预占",
                snap.cash(), snap.reservedCash(), snap.availableCash());

        var c = service.cancel(new com.github.lisama569.ordermatchingenginejava.model.CancelRequest(
                "c1", "BUYER", r.getOrderId()), now());
        snap = service.snapshot("BUYER");
        assertEquals(100_000L, c.releasedCash());
        assertEquals(0, snap.reservedCash());
        assertEquals(1_000_000L, snap.availableCash());
        log.info("撤单后: reserved={} available={} 判定依据=全额释放，现金不变",
                snap.reservedCash(), snap.availableCash());
    }

    @Test
    void partialFillReleasesExcessReservation_buyerPaysFillPrice() {
        // 卖单 5@9800；买单 10@10000（预占 100000），成交 5 股，剩余 5 股在簿
        service.placeOrder(limit("s1", "SELLER", S, SELL, 9_800, 5), now());
        var buy = service.placeOrder(limit("b1", "BUYER", S, BUY, 10_000, 10), now());
        assertEquals(5, buy.getFilledQty());

        var buyer = service.snapshot("BUYER");
        // 成交扣 49000；剩余 5 股仍按 10000 预占 50000
        assertEquals(1_000_000L - 49_000L, buyer.cash());
        assertEquals(50_000L, buyer.reservedCash());
        assertEquals(buyer.cash() - buyer.reservedCash(), buyer.availableCash());
        assertEquals(5, buyer.positions().getOrDefault(S, 0L));

        var seller = service.snapshot("SELLER");
        assertEquals(1_000_000L + 49_000L, seller.cash());
        assertEquals(100 - 5, seller.positions().getOrDefault(S, 0L));
        // 卖单已全成，无冻结
        assertEquals(0, seller.reservedPositions().getOrDefault(S, 0L));
        log.info("部分成交后买方 cash={} reserved={} 持仓={}; 卖方 cash={} 持仓={} 守恒成立",
                buyer.cash(), buyer.reservedCash(), buyer.positions().get(S),
                seller.cash(), seller.positions().get(S));

        // 撤销买方剩余 5 股：释放 50000
        service.cancel(new com.github.lisama569.ordermatchingenginejava.model.CancelRequest(
                "c2", "BUYER", buy.getOrderId()), now());
        buyer = service.snapshot("BUYER");
        assertEquals(0, buyer.reservedCash());
        assertEquals(1_000_000L - 49_000L, buyer.cash());
        log.info("买方撤余单后 cash={} reserved=0 判定依据=剩余预占全额释放", buyer.cash());
    }

    @Test
    void sellSideFreezesAndReleasesPosition() {
        var r = service.placeOrder(limit("s1", "SELLER", S, SELL, 10_000, 30), now());
        var snap = service.snapshot("SELLER");
        assertEquals(100, snap.positions().get(S));
        assertEquals(30, snap.reservedPositions().get(S));
        assertEquals(70, snap.positions().getOrDefault(S,0L)-snap.reservedPositions().getOrDefault(S,0L));
        log.info("卖单下单后 持仓={} 冻结={} 可用={}",
                snap.positions().get(S), snap.reservedPositions().get(S),
                snap.positions().getOrDefault(S,0L)-snap.reservedPositions().getOrDefault(S,0L));

        service.cancel(new com.github.lisama569.ordermatchingenginejava.model.CancelRequest(
                "c3", "SELLER", r.getOrderId()), now());
        snap = service.snapshot("SELLER");
        assertEquals(0L, snap.reservedPositions().getOrDefault(S, 0L));
        assertEquals(100, snap.positions().getOrDefault(S,0L)-snap.reservedPositions().getOrDefault(S,0L));
        log.info("卖单撤销后 冻结=0 可用={} 判定依据=冻结持仓全额归还", snap.positions().getOrDefault(S,0L)-snap.reservedPositions().getOrDefault(S,0L));
    }

    @Test
    void exposureGoesToZeroAfterAllClose() {
        service.placeOrder(limit("s1", "SELLER", S, SELL, 10_000, 20), now());
        var buy = service.placeOrder(limit("b1", "BUYER", S, BUY, 10_000, 20), now());
        assertTrue(buy.getTrades().size() >= 1);
        var buyer = service.snapshot("BUYER");
        var seller = service.snapshot("SELLER");
        assertEquals(0L, buyer.openExposure().getOrDefault(S, 0L), "全部成交后买方敞口归零");
        assertEquals(0, seller.openExposure().getOrDefault(S, 0L), "全部成交后卖方敞口归零");
        log.info("全部成交后双方敞口均为 0；买方现金={} 卖方现金={}",
                buyer.cash(), seller.cash());
    }
}
