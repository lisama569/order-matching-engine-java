package com.github.lisama569.ordermatchingenginejava.tests;

import com.github.lisama569.ordermatchingenginejava.model.MarketTick;
import com.github.lisama569.ordermatchingenginejava.model.TickResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.github.lisama569.ordermatchingenginejava.model.RejectReason.TICK_DUPLICATE;
import static com.github.lisama569.ordermatchingenginejava.model.RejectReason.TICK_GAP;
import static com.github.lisama569.ordermatchingenginejava.model.RejectReason.TICK_INVALID_PRICE;
import static com.github.lisama569.ordermatchingenginejava.model.RejectReason.TICK_OUT_OF_ORDER;
import static com.github.lisama569.ordermatchingenginejava.model.Side.BUY;
import static com.github.lisama569.ordermatchingenginejava.model.Side.SELL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 行情顺序与完整性：
 * 正常序号 1,2,3... 驱动扫簿；重复 -> TICK_DUPLICATE；乱序 -> TICK_OUT_OF_ORDER；
 * 断层 -> TICK_GAP；非法价 -> TICK_INVALID_PRICE。非法行情绝不静默丢弃或错误撮合。
 */
class MarketDataIntegrityTest extends AbstractEngineTest {

    private static final Logger log = LoggerFactory.getLogger(MarketDataIntegrityTest.class);
    private static final String S = "600000";

    @BeforeEach
    void init() {
        setUp();
        account("A", 10_000_000L);
        account("B", 10_000_000L);
        shares("A", S, 100);
    }

    private TickResponse tick(long seq, long price) {
        return service.acceptTick(new MarketTick(S, seq, price, now()));
    }

    @Test
    void duplicateOutOfOrderGapAreRejectedDistinguishably() {
        assertTrue(tick(1, 10_000).accepted());
        assertTrue(tick(2, 10_100).accepted());

        TickResponse dup = tick(2, 10_100);
        assertFalse(dup.accepted());
        assertEquals(TICK_DUPLICATE, dup.rejectReason());
        log.info("重复行情 seq=2 expected=3 -> {} (不静默丢弃)", dup.rejectReason());

        TickResponse old = tick(1, 10_000);
        assertEquals(TICK_OUT_OF_ORDER, old.rejectReason());
        log.info("乱序行情 seq=1 expected=3 -> {}", old.rejectReason());

        TickResponse gap = tick(5, 10_200);
        assertEquals(TICK_GAP, gap.rejectReason());
        assertEquals(3, gap.expectedSeq());
        log.info("断层行情 seq=5 expected=3 -> {} expectedSeq 回传={}", gap.rejectReason(),
                gap.expectedSeq());

        TickResponse badPrice = tick(3, 0);
        assertEquals(TICK_INVALID_PRICE, badPrice.rejectReason());
        log.info("非法价行情 seq=3 price=0 -> {}", badPrice.rejectReason());

        // 期望序号仍是 3：之前非法行情都没有推进序号
        assertEquals(3, service.getExpectedTickSeq(S));
        // 补齐 3 后继续正常
        assertTrue(tick(3, 10_200).accepted());
        assertTrue(tick(4, 10_300).accepted());
        log.info("补齐 seq=3,4 后恢复正常, nextExpected={}", service.getExpectedTickSeq(S));
    }

    @Test
    void tickDrivesMatchingAgainstBook() {
        // 构造不交叉的两侧挂单：卖一 10050（高于初始行情价），买一 9950（低于初始行情价）
        var ask = service.placeOrder(limit("a1", "A", S, SELL, 10_050, 10), now());
        var bid = service.placeOrder(limit("b1", "B", S, BUY, 9_950, 8), now());
        log.info("簿中: 卖 A@10050x10, 买 B@9950x8; 行情价=10000 到来 -> 卖侧可成交(10050<=10000 不成立, 改用行情10100)");

        // 行情 10100：卖单可被 MARKET 买（10050<=10100）；买单 9950<10100 不成交 -> 仅卖侧
        TickResponse r1 = tick(1, 10_100);
        assertTrue(r1.accepted());
        assertEquals(1, r1.trades().size());
        assertEquals(10_050, r1.trades().get(0).price());
        assertEquals("A", r1.trades().get(0).makerAccountId());

        // 行情回到 9900：买单可被 MARKET 卖（9950>=9900）-> 买侧成交
        TickResponse r2 = tick(2, 9_900);
        assertEquals(1, r2.trades().size());
        assertEquals(9_950, r2.trades().get(1 - 1).price());
        assertEquals("B", r2.trades().get(0).makerAccountId());

        assertEquals(10, engine.getOrder(ask.getOrderId()).getFilledQty());
        assertEquals(8, engine.getOrder(bid.getOrderId()).getFilledQty());
        log.info("两笔行情分别驱动卖侧@10050 与买侧@9950 成交，价格优先于行情价的挂单成交，成交价=挂单价");

        // 簿空 -> 再发行情无成交
        TickResponse r3 = tick(3, 10_000);
        assertEquals(0, r3.trades().size());
    }

    @Test
    void rejectedTickDoesNotTrade() {
        service.placeOrder(limit("a1", "A", S, SELL, 9_900, 10), now());
        // 直接发 seq=2（断层），即使价格可成交也绝不能成交
        TickResponse gap = tick(2, 10_000);
        assertEquals(TICK_GAP, gap.rejectReason());
        assertEquals(0, gap.trades().size());
        var snap = service.snapshot("A");
        assertEquals(10, snap.reservedPositions().get(S), "断层行情不得触发成交/释放");
        log.info("断层行情被拒 {}: 卖单冻结仍为 10，未发生错误撮合", gap.rejectReason());
    }
}
