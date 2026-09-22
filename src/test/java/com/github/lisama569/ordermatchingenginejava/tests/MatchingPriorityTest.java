package com.github.lisama569.ordermatchingenginejava.tests;

import com.github.lisama569.ordermatchingenginejava.model.OrderResponse;
import com.github.lisama569.ordermatchingenginejava.model.Side;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.github.lisama569.ordermatchingenginejava.model.OrderStatus.FILLED;
import static com.github.lisama569.ordermatchingenginejava.model.OrderStatus.PARTIALLY_FILLED;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 价格优先、时间优先撮合：
 * 卖盘价格低者先成交；同价时先挂的先成交；成交价取挂单价。
 */
class MatchingPriorityTest extends AbstractEngineTest {

    private static final Logger log = LoggerFactory.getLogger(MatchingPriorityTest.class);

    private static final String S = "600000";

    @BeforeEach
    void init() {
        setUp();
        account("A", 1_000_000L);
        account("B", 1_000_000L);
        account("C", 1_000_000L);
        account("BUYER", 1_000_000L);
        shares("A", S, 100);
        shares("B", S, 100);
        shares("C", S, 100);
    }

    @Test
    void lowerPriceAndEarlierTimeWin() {
        // 时间：B(10100) 早于 C(10000) 早于 A(10100)
        var obB = service.placeOrder(limit("s-b", "B", S, Side.SELL, 10_100, 10), now());
        var obC = service.placeOrder(limit("s-c", "C", S, Side.SELL, 10_000, 10), now());
        var obA = service.placeOrder(limit("s-a", "A", S, Side.SELL, 10_100, 10), now());
        log.info("输入卖单: B@10100x10 时间1, C@10000x10 时间2, A@10100x10 时间3");
        assertTrue(obB.isAccepted() && obC.isAccepted() && obA.isAccepted());

        // 买方来 15 股，限价 10100：应先吃 C 的 10000 x10（价格优先），
        // 再吃 B 的 10100 x5（同价时间优先），A 不动。
        var buy = service.placeOrder(limit("b1", "BUYER", S, Side.BUY, 10_100, 15), now());
        log.info("判定依据: 价格优先 -> C@10000 先成交 10 股; 同价时间优先 -> B@10100 先于 A 成交 5 股");
        log.info("买方结论 status={} filled={} trades={}", buy.getStatus(), buy.getFilledQty(),
                buy.getTrades());

        assertEquals(FILLED, buy.getStatus());
        assertEquals(15, buy.getFilledQty());
        assertEquals(2, buy.getTrades().size());
        assertEquals(10_000, buy.getTrades().get(0).price());
        assertEquals(10, buy.getTrades().get(0).qty());
        assertEquals("C", buy.getTrades().get(0).makerAccountId());
        assertEquals(10_100, buy.getTrades().get(1).price());
        assertEquals(5, buy.getTrades().get(1).qty());
        assertEquals("B", buy.getTrades().get(1).makerAccountId());

        assertEquals(FILLED, engine.getOrder(obC.getOrderId()).getStatus());
        assertEquals(PARTIALLY_FILLED, engine.getOrder(obB.getOrderId()).getStatus());
        assertEquals(5, engine.getOrder(obB.getOrderId()).getRemainingQty());
        var orderA = engine.getOrder(obA.getOrderId());
        assertEquals(10, orderA.getRemainingQty(), "A 与 B 同价但更晚，15 股买不到 A");
        assertNotEquals(obB.getOrderId(), obA.getOrderId());
    }

    @Test
    void fillsAtRestingPrice() {
        var sell = service.placeOrder(limit("s1", "B", S, Side.SELL, 9_900, 5), now());
        var buy = service.placeOrder(limit("b1", "A", S, Side.BUY, 10_500, 5), now());
        log.info("输入: 卖单挂 9900, 买单 10500; 判定依据: 成交价取挂单价 9900");
        assertEquals(FILLED, buy.getStatus());
        assertEquals(9_900, buy.getTrades().get(0).price(),
                "买单更优价也必须按挂单价成交");
        // 买方现金：初始 1_000_000，按 10500 预占 52500，成交 49500，释放 3000
        var snap = service.snapshot("A");
        log.info("买方快照 cash={} reserved={} available={}", snap.cash(), snap.reservedCash(),
                snap.availableCash());
        assertEquals(1_000_000L - 9_900 * 5, snap.cash());
        assertEquals(0, snap.reservedCash());
    }
}
