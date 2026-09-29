package com.github.lisama569.ordermatchingenginejava.tests;

import com.github.lisama569.ordermatchingenginejava.account.AccountLedger;
import com.github.lisama569.ordermatchingenginejava.engine.MatchingEngine;
import com.github.lisama569.ordermatchingenginejava.model.MarketTick;
import com.github.lisama569.ordermatchingenginejava.model.ModifyOrderRequest;
import com.github.lisama569.ordermatchingenginejava.model.ModifyOrderResult;
import com.github.lisama569.ordermatchingenginejava.model.Order;
import com.github.lisama569.ordermatchingenginejava.model.OrderStatus;
import com.github.lisama569.ordermatchingenginejava.model.OrderType;
import com.github.lisama569.ordermatchingenginejava.model.RejectReason;
import com.github.lisama569.ordermatchingenginejava.model.Side;
import com.github.lisama569.ordermatchingenginejava.model.SubmitOrderRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 改单引擎单测：改价/改量、可区分拒因、占用增减守恒、幂等一次生效、
 * 改价失去时间优先 / 仅改量保留排队、市价单保护价修改、部分成交后改单。
 */
class ModifyOrderEngineTest {

    private static final Logger log = LoggerFactory.getLogger(ModifyOrderEngineTest.class);

    private AccountLedger ledger;
    private MatchingEngine engine;

    @BeforeEach
    void setUp() {
        ledger = new AccountLedger();
        ledger.seedCash("alice", 1_000_000);
        ledger.seedCash("bob", 1_000_000);
        ledger.seedPosition("alice", "AAPL", 10_000);
        ledger.seedPosition("bob", "AAPL", 10_000);
        ledger.seedPosition("carol", "AAPL", 10_000);
        ledger.setExposureLimit("alice", "AAPL", 1_000_000);
        ledger.setExposureLimit("bob", "AAPL", 1_000_000);
        ledger.seedCash("carol", 1_000_000);
        ledger.setExposureLimit("carol", "AAPL", 1_000_000);
        engine = new MatchingEngine(ledger, 1, 1);
        engine.registerSymbol("AAPL");
    }

    private SubmitOrderRequest limit(String cid, String account, Side side, long price, long qty) {
        return new SubmitOrderRequest(cid, account, "AAPL", side, OrderType.LIMIT, price, null, qty);
    }

    private SubmitOrderRequest market(String cid, String account, Side side, long protection, long qty) {
        return new SubmitOrderRequest(cid, account, "AAPL", side, OrderType.MARKET, null, protection, qty);
    }

    private ModifyOrderRequest mod(String amendId, String account, String cid,
                                   Long newPrice, Long newQty) {
        return new ModifyOrderRequest(amendId, account, cid, newPrice, newQty);
    }

    @Test
    void modifyPriceRequeuesAndLosesTimePriority() {
        // 两笔同价买单 a、b（a 时间优先），随后 a 改价 => a 重新发号排在 b 之后
        engine.submit(limit("ma", "alice", Side.BUY, 100, 10));
        engine.submit(limit("mb", "bob", Side.BUY, 100, 10));
        engine.submit(limit("mc", "carol", Side.SELL, 100, 10));

        Order a = engine.findByClientOrderId("ma");
        Order b = engine.findByClientOrderId("mb");
        long seqABefore = a.getEnqueueSeq();
        assertTrue(seqABefore < b.getEnqueueSeq());

        // 100->101 会重新排队；再 101->100 再次重排，最终 a 的排队序号晚于 b
        assertTrue(engine.modify(mod("am-1", "alice", "ma", 101L, null)).success());
        ModifyOrderResult r = engine.modify(mod("am-2", "alice", "ma", 100L, null));
        assertTrue(r.success());
        log.info("[case] 两次改价后重排队: a.enqueueSeq {}->{}, b.enqueueSeq={}",
                seqABefore, a.getEnqueueSeq(), b.getEnqueueSeq());
        assertTrue(a.getEnqueueSeq() > b.getEnqueueSeq(), "改价后必须失去原时间优先");

        // 行情 100 撮合 10 股：b 应作为先成交的买单（maker）
        var tick = engine.onTick(new MarketTick(1, "AAPL", 100));
        assertEquals(1, tick.fills().size());
        long makerId = tick.fills().get(0).makerOrderId();
        log.info("[case] 撮合 makerOrderId={} (期望 b 的 {}), a 状态={}, b 状态={}",
                makerId, b.getOrderId(), a.getStatus(), b.getStatus());
        assertEquals(b.getOrderId(), makerId);
        assertEquals(OrderStatus.NEW, a.getStatus());
        assertEquals(OrderStatus.FILLED, b.getStatus());
    }

    @Test
    void quantityOnlyDecreaseKeepsQueuePosition() {
        engine.submit(limit("qa", "alice", Side.BUY, 100, 10));
        engine.submit(limit("qb", "bob", Side.BUY, 100, 10));
        engine.submit(limit("qc", "carol", Side.SELL, 100, 5));

        Order a = engine.findByClientOrderId("qa");
        long seqBefore = a.getEnqueueSeq();
        ModifyOrderResult r = engine.modify(mod("am-dec", "alice", "qa", null, 8L));
        assertTrue(r.success());
        assertEquals(8, r.quantity());
        assertEquals(seqBefore, a.getEnqueueSeq(), "只减量必须保留原排队序号");

        var tick = engine.onTick(new MarketTick(1, "AAPL", 100));
        long makerId = tick.fills().get(0).makerOrderId();
        log.info("[case] 仅减量后 a 仍先成交 makerOrderId={} (期望 a 的 {}), 剩余={}",
                makerId, a.getOrderId(), a.remainingQty());
        assertEquals(a.getOrderId(), makerId);
        assertEquals(3, a.remainingQty());
    }

    @Test
    void modifyRejectionReasonsAreDistinct() {
        engine.submit(limit("r1", "alice", Side.BUY, 100, 10));
        engine.submit(limit("r2", "alice", Side.BUY, 100, 10));

        ModifyOrderResult notFound = engine.modify(mod("x1", "alice", "ghost", 101L, null));
        ModifyOrderResult owner = engine.modify(mod("x2", "bob", "r1", 101L, null));
        ModifyOrderResult nothing = engine.modify(mod("x3", "alice", "r1", null, null));
        ModifyOrderResult badPrice = engine.modify(mod("x4", "alice", "r1", 0L, null));
        ModifyOrderResult badQty = engine.modify(mod("x5", "alice", "r1", null, 0L));

        // 新数量不大于已成交量：在 MSFT 上先让 r-pf 部分成交 4，再尝试把总量改成 4
        ledger.seedPosition("alice", "MSFT", 10_000);
        ledger.seedPosition("carol", "MSFT", 10_000);
        ledger.setExposureLimit("alice", "MSFT", 1_000_000);
        ledger.setExposureLimit("carol", "MSFT", 1_000_000);
        engine.registerSymbol("MSFT");
        engine.submit(new SubmitOrderRequest("r-pf", "alice", "MSFT", Side.BUY,
                OrderType.LIMIT, 100L, null, 10));
        engine.submit(new SubmitOrderRequest("r-pf-ask", "carol", "MSFT", Side.SELL,
                OrderType.LIMIT, 100L, null, 4));
        engine.onTick(new MarketTick(1, "MSFT", 100));
        ModifyOrderResult belowFilled = engine.modify(mod("x6", "alice", "r-pf", null, 4L));

        log.info("[case] 改单拒因: notFound={}, owner={}, nothing={}, badPrice={}, badQty={}, belowFilled={}",
                notFound.rejectReason(), owner.rejectReason(), nothing.rejectReason(),
                badPrice.rejectReason(), badQty.rejectReason(), belowFilled.rejectReason());

        assertEquals(RejectReason.ORDER_NOT_FOUND, notFound.rejectReason());
        assertEquals(RejectReason.ORDER_OWNER_MISMATCH, owner.rejectReason());
        assertEquals(RejectReason.MODIFY_NOTHING, nothing.rejectReason());
        assertEquals(RejectReason.INVALID_PRICE, badPrice.rejectReason());
        assertEquals(RejectReason.INVALID_QUANTITY, badQty.rejectReason());
        assertEquals(RejectReason.MODIFY_QTY_BELOW_FILLED, belowFilled.rejectReason());

        // r1 全部成交后改单 => ORDER_NOT_MODIFIABLE；撤单后同样不可改
        engine.submit(limit("r-ask", "carol", Side.SELL, 100, 10));
        engine.onTick(new MarketTick(1, "AAPL", 100));
        ModifyOrderResult filled = engine.modify(mod("x7", "alice", "r1", 101L, null));
        assertEquals(RejectReason.ORDER_NOT_MODIFIABLE, filled.rejectReason());
        engine.cancel("alice", "r2");
        ModifyOrderResult cancelled = engine.modify(mod("x8", "alice", "r2", 101L, null));
        assertEquals(RejectReason.ORDER_NOT_MODIFIABLE, cancelled.rejectReason());
        log.info("[case] 已成交拒因={} 已撤销拒因={}", filled.rejectReason(), cancelled.rejectReason());
    }

    @Test
    void modifyBuyReservationIncreaseAndAtomicReject() {
        // alice 现金 1_000_000，敞口上限收紧到 1500：10@100 占用1000
        ledger.setExposureLimit("alice", "AAPL", 1_500);
        engine.submit(limit("i1", "alice", Side.BUY, 100, 10));
        assertEquals(1000, ledger.snapshot("alice", "AAPL").cashReserved());

        // 改价 100->120 => 需要 1200，额度够
        ModifyOrderResult ok = engine.modify(mod("i-up", "alice", "i1", 120L, null));
        assertTrue(ok.success());
        assertEquals(1200, ledger.snapshot("alice", "AAPL").cashReserved());
        assertEquals(1200, ledger.snapshot("alice", "AAPL").exposure());

        // 改价 120->200 => 需要 2000 > 可用(现金剩... 总现金1000000足够)但敞口只剩300 => 整体拒绝
        long priceBefore = engine.findByClientOrderId("i1").getPrice();
        ModifyOrderResult fail = engine.modify(mod("i-over", "alice", "i1", 200L, null));
        assertFalse(fail.success());
        assertEquals(RejectReason.EXPOSURE_LIMIT_EXCEEDED, fail.rejectReason());
        Order o = engine.findByClientOrderId("i1");
        assertEquals(priceBefore, o.getPrice());
        assertEquals(1200, ledger.snapshot("alice", "AAPL").cashReserved(),
                "改单失败占用必须原样不变");
        assertEquals(1200, ledger.snapshot("alice", "AAPL").exposure());
        log.info("[case] 超额改单被拒: 价格保持={}, 冻结保持=1200, 敞口保持=1200", o.getPrice());

        // 改量 10->20 @120 => 需要2400，同样超敞口 => 拒绝且零改动
        ModifyOrderResult qtyFail = engine.modify(mod("i-qover", "alice", "i1", null, 20L));
        assertEquals(RejectReason.EXPOSURE_LIMIT_EXCEEDED, qtyFail.rejectReason());
        assertEquals(10, engine.findByClientOrderId("i1").getOriginalQty());
        assertEquals(1200, ledger.snapshot("alice", "AAPL").cashReserved());
    }

    @Test
    void modifyBuyReservationDecreaseReleasesImmediately() {
        engine.submit(limit("d1", "alice", Side.BUY, 100, 10)); // 冻结1000
        // 减量 10->6：释放 4*100=400
        ModifyOrderResult dec = engine.modify(mod("d-dec", "alice", "d1", null, 6L));
        assertTrue(dec.success());
        var snap = ledger.snapshot("alice", "AAPL");
        assertEquals(600, snap.cashReserved());
        assertEquals(600, snap.exposure());
        assertEquals(1_000_000 - 600, snap.cashAvailable());

        // 降价 100->90：释放 6*10=60
        ModifyOrderResult cheaper = engine.modify(mod("d-price", "alice", "d1", 90L, null));
        assertTrue(cheaper.success());
        assertEquals(540, ledger.snapshot("alice", "AAPL").cashReserved());
        assertEquals(540, ledger.snapshot("alice", "AAPL").exposure());
        log.info("[case] 减量+降价释放: 冻结1000->600->540, 可用={}",
                ledger.snapshot("alice", "AAPL").cashAvailable());
    }

    @Test
    void modifySellAdjustsPositionAndNotional() {
        // bob 持仓 10000，卖单 10@100：冻结持仓10、敞口1000
        engine.submit(limit("s1", "bob", Side.SELL, 100, 10));
        var mid = ledger.snapshot("bob", "AAPL");
        assertEquals(10, mid.positionReserved());
        assertEquals(1000, mid.exposure());

        // 减量到 6：释放持仓4、敞口400
        engine.modify(mod("s-dec", "bob", "s1", null, 6L));
        assertEquals(6, ledger.snapshot("bob", "AAPL").positionReserved());
        assertEquals(600, ledger.snapshot("bob", "AAPL").exposure());

        // 提价 100->120：敞口增至 720（持仓冻结不变）
        engine.modify(mod("s-up", "bob", "s1", 120L, null));
        assertEquals(6, ledger.snapshot("bob", "AAPL").positionReserved());
        assertEquals(720, ledger.snapshot("bob", "AAPL").exposure());

        // 增量到 12：需要额外持仓6，可用充足
        ModifyOrderResult grow = engine.modify(mod("s-grow", "bob", "s1", null, 12L));
        assertTrue(grow.success());
        assertEquals(12, ledger.snapshot("bob", "AAPL").positionReserved());
        assertEquals(1440, ledger.snapshot("bob", "AAPL").exposure());
        log.info("[case] 卖单改量改价: 持仓冻结 10->6->6->12, 敞口 1000->600->720->1440");

        // 持仓不足的增量（dave 仅 10 股可用）
        ledger.seedCash("dave", 0);
        ledger.seedPosition("dave", "AAPL", 10);
        ledger.setExposureLimit("dave", "AAPL", 1_000_000);
        engine.submit(limit("s2", "dave", Side.SELL, 100, 8));
        ModifyOrderResult noPos = engine.modify(mod("s-noPos", "dave", "s2", null, 20L));
        assertEquals(RejectReason.EXPOSURE_LIMIT_EXCEEDED, noPos.rejectReason());
        assertEquals(8, engine.findByClientOrderId("s2").getOriginalQty());
        assertEquals(8, ledger.snapshot("dave", "AAPL").positionReserved());
    }

    @Test
    void sameAmendIdAppliesExactlyOnce() {
        engine.submit(limit("id1", "alice", Side.BUY, 100, 10));
        ModifyOrderRequest req = mod("same-amend", "alice", "id1", 110L, null);
        ModifyOrderResult first = engine.modify(req);
        ModifyOrderResult second = engine.modify(req);
        ModifyOrderResult third = engine.modify(req);

        assertTrue(first.success());
        assertFalse(first.duplicate());
        assertTrue(second.duplicate());
        assertTrue(third.duplicate());
        // 只占用一次改单后的量：10*110=1100
        assertEquals(1100, ledger.snapshot("alice", "AAPL").cashReserved());
        assertEquals(110, engine.findByClientOrderId("id1").getPrice());

        // 不同 amendId 的第二次改单正常生效
        ModifyOrderResult again = engine.modify(mod("other-amend", "alice", "id1", 120L, null));
        assertTrue(again.success());
        assertFalse(again.duplicate());
        assertEquals(1200, ledger.snapshot("alice", "AAPL").cashReserved());
        log.info("[case] 同 amendId 三次只生效一次: duplicate={},{}; 冻结=1200",
                second.duplicate(), third.duplicate());
    }

    @Test
    void marketOrderProtectionPriceModifyAndBound() {
        // 市价买单保护价 100，冻结 1000；改保护价到 90，释放 100
        engine.submit(market("mk1", "alice", Side.BUY, 100, 10));
        ModifyOrderResult r = engine.modify(mod("mk-am", "alice", "mk1", 90L, null));
        assertTrue(r.success());
        assertEquals(900, ledger.snapshot("alice", "AAPL").cashReserved());

        // 行情 95：超过新保护价90，不得成交
        var noCross = engine.onTick(new MarketTick(1, "AAPL", 95));
        assertTrue(noCross.fills().isEmpty());
        assertEquals(OrderStatus.NEW, engine.findByClientOrderId("mk1").getStatus());

        // 行情 90：允许成交，且改价后重新排队仍可撮合
        engine.submit(limit("mk-ask", "bob", Side.SELL, 90, 10));
        var cross = engine.onTick(new MarketTick(2, "AAPL", 90));
        assertEquals(1, cross.fills().size());
        log.info("[case] 市价单改保护价: tick95 不成交, tick90 成交 {} 笔", cross.fills().size());
    }

    @Test
    void modifyAfterPartialFillUsesRemainingForReservation() {
        engine.submit(limit("pf-buy", "alice", Side.BUY, 100, 10));
        engine.submit(limit("pf-sell", "bob", Side.SELL, 100, 4));
        engine.onTick(new MarketTick(1, "AAPL", 100));
        // 已成交4，剩余6，冻结600
        Order buy = engine.findByClientOrderId("pf-buy");
        assertEquals(4, buy.getFilledQty());

        // 总量改到 8（剩余4）：释放 2*100
        ModifyOrderResult r = engine.modify(mod("pf-dec", "alice", "pf-buy", null, 8L));
        assertTrue(r.success());
        assertEquals(400, ledger.snapshot("alice", "AAPL").cashReserved());
        assertEquals(4, buy.remainingQty());

        // 新总量不大于已成交量 => 拒绝
        ModifyOrderResult below = engine.modify(mod("pf-below", "alice", "pf-buy", null, 4L));
        assertEquals(RejectReason.MODIFY_QTY_BELOW_FILLED, below.rejectReason());

        // 提到 200：剩余4 * 200 = 800，需增加 400
        ModifyOrderResult up = engine.modify(mod("pf-up", "alice", "pf-buy", 200L, null));
        assertTrue(up.success());
        assertEquals(800, ledger.snapshot("alice", "AAPL").cashReserved());
        log.info("[case] 部分成交后改单: 冻结600->400->800, 原订单号不变={}", up.orderId());
    }
}
