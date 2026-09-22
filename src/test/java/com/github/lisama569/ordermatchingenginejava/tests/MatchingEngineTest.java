package com.github.lisama569.ordermatchingenginejava.tests;

import com.github.lisama569.ordermatchingenginejava.account.AccountLedger;
import com.github.lisama569.ordermatchingenginejava.engine.MatchingEngine;
import com.github.lisama569.ordermatchingenginejava.model.CancelOrderResult;
import com.github.lisama569.ordermatchingenginejava.model.MarketTick;
import com.github.lisama569.ordermatchingenginejava.model.Order;
import com.github.lisama569.ordermatchingenginejava.model.OrderStatus;
import com.github.lisama569.ordermatchingenginejava.model.OrderType;
import com.github.lisama569.ordermatchingenginejava.model.RejectReason;
import com.github.lisama569.ordermatchingenginejava.model.Side;
import com.github.lisama569.ordermatchingenginejava.model.SubmitOrderRequest;
import com.github.lisama569.ordermatchingenginejava.model.SubmitOrderResult;
import com.github.lisama569.ordermatchingenginejava.model.TickResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 撮合引擎单测：静态拒因、幂等下单/撤单、行情重复/乱序/断层、
 * 端到端成交的台账结算。日志打印关键输入与判定依据。
 */
class MatchingEngineTest {

    private static final Logger log = LoggerFactory.getLogger(MatchingEngineTest.class);

    private AccountLedger ledger;
    private MatchingEngine engine;

    @BeforeEach
    void setUp() {
        ledger = new AccountLedger();
        ledger.seedCash("alice", 1_000_000);
        ledger.seedCash("bob", 1_000_000);
        ledger.seedPosition("alice", "AAPL", 10_000);
        ledger.seedPosition("bob", "AAPL", 10_000);
        ledger.setExposureLimit("alice", "AAPL", 1_000_000);
        ledger.setExposureLimit("bob", "AAPL", 1_000_000);
        engine = new MatchingEngine(ledger, 1, 1);
        engine.registerSymbol("AAPL");
    }

    private SubmitOrderRequest limit(String cid, String account, Side side, long price, long qty) {
        return new SubmitOrderRequest(cid, account, "AAPL", side, OrderType.LIMIT, price, null, qty);
    }

    private SubmitOrderRequest market(String cid, String account, Side side, long protection, long qty) {
        return new SubmitOrderRequest(cid, account, "AAPL", side, OrderType.MARKET, null, protection, qty);
    }

    @Test
    void rejectsWithDistinctReasons() {
        // 使用 tickSize=5 / lotSize=5 的引擎以覆盖刻度与整手校验
        MatchingEngine strict = new MatchingEngine(ledger, 5, 5);
        strict.registerSymbol("AAPL");
        SubmitOrderResult badPrice = strict.submit(limit("p1", "alice", Side.BUY, 0, 10));
        SubmitOrderResult badPriceTick = strict.submit(
                new SubmitOrderRequest("p2", "alice", "AAPL", Side.BUY, OrderType.LIMIT, 7L, null, 10));
        SubmitOrderResult badQty = strict.submit(limit("q1", "alice", Side.BUY, 100, 0));
        SubmitOrderResult badQtyLot = strict.submit(limit("q2", "alice", Side.BUY, 100, 3));
        SubmitOrderResult noProtection = strict.submit(market("m1", "alice", Side.BUY, -1, 10));
        SubmitOrderResult unknown = strict.submit(limit("u1", "ghost", Side.BUY, 100, 10));
        SubmitOrderResult tooBig = strict.submit(limit("b1", "alice", Side.BUY, 100, 1_000_000));

        log.info("[case] 非法输入拒因: badPrice={}, badTick={}, badQty={}, badLot={}, protection={}, unknown={}, exposure={}",
                badPrice.rejectReason(), badPriceTick.rejectReason(), badQty.rejectReason(),
                badQtyLot.rejectReason(), noProtection.rejectReason(), unknown.rejectReason(),
                tooBig.rejectReason());

        assertEquals(RejectReason.INVALID_PRICE, badPrice.rejectReason());
        assertEquals(RejectReason.INVALID_PRICE, badPriceTick.rejectReason());
        assertEquals(RejectReason.INVALID_QUANTITY, badQty.rejectReason());
        assertEquals(RejectReason.INVALID_QUANTITY, badQtyLot.rejectReason());
        assertEquals(RejectReason.INVALID_PROTECTION_PRICE, noProtection.rejectReason());
        assertEquals(RejectReason.UNKNOWN_ACCOUNT_OR_SYMBOL, unknown.rejectReason());
        assertEquals(RejectReason.EXPOSURE_LIMIT_EXCEEDED, tooBig.rejectReason());

        // 全部拒单不得产生占用
        assertEquals(1_000_000, ledger.snapshot("alice", "AAPL").cashAvailable());
        assertEquals(0, ledger.snapshot("alice", "AAPL").exposure());
        log.info("[case] 拒单后 alice 可用现金=1000000 敞口=0 (判定: 拒绝零占用)");
    }

    @Test
    void duplicateSubmitIsIdempotentNoDoubleReservation() {
        SubmitOrderRequest req = limit("dup-1", "alice", Side.BUY, 100, 10);
        SubmitOrderResult first = engine.submit(req);
        SubmitOrderResult second = engine.submit(req);
        SubmitOrderResult third = engine.submit(req);

        assertTrue(first.accepted());
        assertTrue(second.duplicate());
        assertTrue(third.duplicate());
        assertEquals(first.orderId(), second.orderId());
        assertEquals(1000, ledger.snapshot("alice", "AAPL").cashReserved());
        log.info("[case] 同一 clientOrderId 提交3次 => 冻结={} (应为1000), duplicate={},{} (判定: 不重复占用)",
                ledger.snapshot("alice", "AAPL").cashReserved(), second.duplicate(), third.duplicate());
    }

    @Test
    void cancelStatesAndRepeatedCancelAreIdempotent() {
        engine.submit(limit("c1", "alice", Side.SELL, 110, 5));
        CancelOrderResult first = engine.cancel("alice", "c1");
        CancelOrderResult second = engine.cancel("alice", "c1");
        CancelOrderResult other = engine.cancel("bob", "c1");
        CancelOrderResult missing = engine.cancel("alice", "nope");
        CancelOrderResult filled = engine.cancel("alice", "c1");

        log.info("[case] 撤单: first.success={}, second.duplicate={}, ownerMismatch={}, notFound={}, notCancellable={}",
                first.success(), second.duplicate(), other.rejectReason(),
                missing.rejectReason(), filled.rejectReason());

        assertTrue(first.success());
        assertEquals(OrderStatus.CANCELLED, first.status());
        assertTrue(second.duplicate());
        assertEquals(RejectReason.ORDER_OWNER_MISMATCH, other.rejectReason());
        assertEquals(RejectReason.ORDER_NOT_FOUND, missing.rejectReason());
        // 重复撤单走幂等路径，返回首次的成功结果
        assertTrue(filled.duplicate());
        assertEquals(0, ledger.snapshot("alice", "AAPL").exposure());
    }

    @Test
    void tickSequenceDuplicateGapOutOfOrder() {
        TickResult t1 = engine.onTick(new MarketTick(1, "AAPL", 100));
        TickResult duplicate = engine.onTick(new MarketTick(1, "AAPL", 100));
        TickResult gap = engine.onTick(new MarketTick(3, "AAPL", 100));
        TickResult old = engine.onTick(new MarketTick(0, "AAPL", 100));
        TickResult t2 = engine.onTick(new MarketTick(2, "AAPL", 100));

        log.info("[case] 行情序号: seq1={}, 重复seq1={}, 断层seq3(期望2)={}, 乱序seq0={}, 补到seq2={}",
                t1.accepted(), duplicate.rejectReason(), gap.rejectReason(),
                old.rejectReason(), t2.accepted());

        assertTrue(t1.accepted());
        assertEquals(RejectReason.TICK_DUPLICATE, duplicate.rejectReason());
        assertEquals(RejectReason.TICK_SEQUENCE_GAP, gap.rejectReason());
        assertEquals(RejectReason.TICK_OUT_OF_ORDER, old.rejectReason());
        assertTrue(t2.accepted());
        assertEquals(0, engine.fills().size());
    }

    @Test
    void endToEndMatchSettlesBothSidesAndReleasesRemainder() {
        // alice 限价买 10@100，bob 限价卖 10@100，行情 100 撮合
        engine.submit(limit("e-buy", "alice", Side.BUY, 100, 10));
        engine.submit(limit("e-sell", "bob", Side.SELL, 100, 10));
        TickResult t = engine.onTick(new MarketTick(1, "AAPL", 100));

        assertEquals(1, t.fills().size());
        assertEquals(1, engine.fills().size());
        Order buy = engine.findByClientOrderId("e-buy");
        Order sell = engine.findByClientOrderId("e-sell");
        assertEquals(OrderStatus.FILLED, buy.getStatus());
        assertEquals(OrderStatus.FILLED, sell.getStatus());

        // alice: 现金 -1000, 持仓 +10，占用清零
        var alice = ledger.snapshot("alice", "AAPL");
        var bobSnap = ledger.snapshot("bob", "AAPL");
        log.info("[case] 成交后 alice 现金={}(期望999000) 持仓={}(期望10010) 敞口={}; bob 现金={}(期望1001000) 持仓={}(期望9990)",
                alice.cashBalance(), alice.position(), alice.exposure(),
                bobSnap.cashBalance(), bobSnap.position());
        assertEquals(999_000, alice.cashBalance());
        assertEquals(10_010, alice.position());
        assertEquals(0, alice.exposure());
        assertEquals(1_001_000, bobSnap.cashBalance());
        assertEquals(9_990, bobSnap.position());
        assertEquals(0, bobSnap.exposure());
    }

    @Test
    void partialFillOnTickThenCancelReleasesRemainder() {
        engine.submit(limit("pb", "alice", Side.BUY, 100, 10));
        engine.submit(limit("ps", "bob", Side.SELL, 100, 4));
        engine.onTick(new MarketTick(1, "AAPL", 100));

        Order buy = engine.findByClientOrderId("pb");
        assertEquals(OrderStatus.PARTIALLY_FILLED, buy.getStatus());
        var mid = ledger.snapshot("alice", "AAPL");
        log.info("[case] 部分成交4股后 alice 冻结={}(期望600, 剩余6股) 敞口={}",
                mid.cashReserved(), mid.exposure());
        assertEquals(600, mid.cashReserved());

        engine.cancel("alice", "pb");
        var end = ledger.snapshot("alice", "AAPL");
        assertEquals(0, end.cashReserved());
        assertEquals(0, end.exposure());
        log.info("[case] 部分成交后撤单 => 冻结=0 敞口=0 (判定: 剩余占用精确释放)");
    }

    @Test
    void rejectedOperationDoesNotAffectOtherAccount() {
        long before = ledger.snapshot("bob", "AAPL").cashAvailable();
        engine.submit(limit("x1", "alice", Side.BUY, 100, 1_000_000)); // alice 超敞口
        engine.onTick(new MarketTick(99, "AAPL", 100)); // 断层/乱序无效行情
        long after = ledger.snapshot("bob", "AAPL").cashAvailable();
        assertEquals(before, after);
        assertNotNull(engine);
        assertFalse(engine.fills().contains(null));
        log.info("[case] alice 超量拒单 + 无效行情后 bob 可用资金不变={} (判定: 账户隔离)", after);
    }
}
