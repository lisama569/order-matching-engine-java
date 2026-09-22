package com.github.lisama569.ordermatchingenginejava.tests;

import com.github.lisama569.ordermatchingenginejava.account.AccountLedger;
import com.github.lisama569.ordermatchingenginejava.engine.MatchingEngine;
import com.github.lisama569.ordermatchingenginejava.journal.FileEventJournal;
import com.github.lisama569.ordermatchingenginejava.market.MarketDataService;
import com.github.lisama569.ordermatchingenginejava.market.SimulatedMarketFeeder;
import com.github.lisama569.ordermatchingenginejava.model.MarketTick;
import com.github.lisama569.ordermatchingenginejava.model.OrderType;
import com.github.lisama569.ordermatchingenginejava.model.RejectReason;
import com.github.lisama569.ordermatchingenginejava.model.Side;
import com.github.lisama569.ordermatchingenginejava.model.SubmitOrderRequest;
import com.github.lisama569.ordermatchingenginejava.model.TickResult;
import com.github.lisama569.ordermatchingenginejava.service.OrderService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 重放语义测试：制造一段含成交、部分成交、撤单、重复提交、
 * 乱序/断层行情的命令历史；用同一 WAL 重建服务，比较：
 * 成交流水、订单状态、双方资金/持仓/占用、行情期望序号完全一致。
 */
class ReplayTest {

    private static final Logger log = LoggerFactory.getLogger(ReplayTest.class);

    @TempDir
    Path dir;

    /** 测试上下文：服务 + 其内部台账。 */
    private record Ctx(OrderService service, AccountLedger ledger) {
    }

    private Ctx newSystem(Path journalFile) {
        AccountLedger ledger = new AccountLedger();
        ledger.seedCash("alice", 1_000_000);
        ledger.seedCash("bob", 1_000_000);
        ledger.seedPosition("bob", "AAPL", 10_000);
        ledger.setExposureLimit("alice", "AAPL", 1_000_000);
        ledger.setExposureLimit("bob", "AAPL", 1_000_000);
        MatchingEngine engine = new MatchingEngine(ledger, 1, 1);
        engine.registerSymbol("AAPL");
        MarketDataService market = new MarketDataService();
        SimulatedMarketFeeder feeder = new SimulatedMarketFeeder(market);
        OrderService service = new OrderService(engine, new FileEventJournal(journalFile), market, feeder);
        return new Ctx(service, ledger);
    }

    private SubmitOrderRequest limit(String cid, String account, Side side, long price, long qty) {
        return new SubmitOrderRequest(cid, account, "AAPL", side, OrderType.LIMIT, price, null, qty);
    }

    @Test
    void replayRebuildsIdenticalState() {
        Path journalFile = dir.resolve("journal.jsonl");

        // ---- 第一阶段：制造历史 ----
        Ctx first = newSystem(journalFile);
        OrderService s1 = first.service();

        s1.submit(limit("buy-1", "alice", Side.BUY, 100, 10));
        s1.submit(limit("buy-1", "alice", Side.BUY, 100, 10));           // 重复提交
        s1.submit(limit("sell-1", "bob", Side.SELL, 100, 4));
        s1.submit(limit("sell-cancel", "bob", Side.SELL, 101, 6));

        TickResult t1 = s1.acceptTick(new MarketTick(1, "AAPL", 100));
        TickResult dup = s1.acceptTick(new MarketTick(1, "AAPL", 100));  // 重复
        TickResult gap = s1.acceptTick(new MarketTick(3, "AAPL", 100));  // 断层
        s1.cancel("bob", "sell-cancel");
        s1.cancel("bob", "sell-cancel");                                 // 重复撤单
        TickResult t2 = s1.acceptTick(new MarketTick(2, "AAPL", 100));

        log.info("[stage1] tick1.accepted={}, dup={}, gap={}, tick2.accepted={}, fills={}",
                t1.accepted(), dup.rejectReason(), gap.rejectReason(), t2.accepted(),
                s1.fills().size());
        assertEquals(RejectReason.TICK_DUPLICATE, dup.rejectReason());
        assertEquals(RejectReason.TICK_SEQUENCE_GAP, gap.rejectReason());

        var a1 = first.ledger().snapshot("alice", "AAPL");
        var b1 = first.ledger().snapshot("bob", "AAPL");
        log.info("[stage1] alice: cash={} position={} reserved={} exposure={}",
                a1.cashBalance(), a1.position(), a1.cashReserved(), a1.exposure());
        log.info("[stage1] bob:   cash={} position={} reserved={} exposure={}",
                b1.cashBalance(), b1.position(), b1.positionReserved(), b1.exposure());

        // ---- 第二阶段：重启，仅靠 WAL 回放 ----
        Ctx second = newSystem(journalFile);
        OrderService s2 = second.service();
        int replayed = s2.replay();
        log.info("[stage2] replay events={}", replayed);

        var a2 = second.ledger().snapshot("alice", "AAPL");
        var b2 = second.ledger().snapshot("bob", "AAPL");

        // 成交流水逐条一致（seq/tickSeq/price/qty/买卖双方订单内部号）
        assertEquals(s1.fills().size(), s2.fills().size());
        for (int i = 0; i < s1.fills().size(); i++) {
            assertEquals(s1.fills().get(i), s2.fills().get(i), "fill " + i + " mismatch");
        }
        // 订单状态与已成交量一致
        assertEquals(s1.getOrder("buy-1").getStatus(), s2.getOrder("buy-1").getStatus());
        assertEquals(s1.getOrder("sell-1").getStatus(), s2.getOrder("sell-1").getStatus());
        assertEquals(s1.getOrder("sell-cancel").getStatus(), s2.getOrder("sell-cancel").getStatus());
        assertEquals(s1.getOrder("buy-1").getFilledQty(), s2.getOrder("buy-1").getFilledQty());
        // 台账逐项一致
        assertEquals(a1.cashBalance(), a2.cashBalance());
        assertEquals(a1.position(), a2.position());
        assertEquals(a1.cashReserved(), a2.cashReserved());
        assertEquals(a1.exposure(), a2.exposure());
        assertEquals(b1.cashBalance(), b2.cashBalance());
        assertEquals(b1.position(), b2.position());
        assertEquals(b1.positionReserved(), b2.positionReserved());
        assertEquals(b1.exposure(), b2.exposure());
        log.info("[stage2] 回放后成交流水/订单状态/台账逐项一致");

        // 回放后行情期望序号已恢复：seq=2 重复、seq=3 正常
        TickResult replayDup = s2.acceptTick(new MarketTick(2, "AAPL", 100));
        assertEquals(RejectReason.TICK_DUPLICATE, replayDup.rejectReason());
        TickResult t3 = s2.acceptTick(new MarketTick(3, "AAPL", 100));
        assertTrue(t3.accepted());
        log.info("[stage2] 回放后 seq2=>{} seq3.accepted={} (判定: 期望序号恢复为3)",
                replayDup.rejectReason(), t3.accepted());

        // 回放后重复提交仍是幂等命中，不产生第二笔冻结
        long reservedBefore = second.ledger().snapshot("alice", "AAPL").cashReserved();
        var dupSubmit = s2.submit(limit("buy-1", "alice", Side.BUY, 100, 10));
        long reservedAfter = second.ledger().snapshot("alice", "AAPL").cashReserved();
        assertTrue(dupSubmit.duplicate());
        assertEquals(reservedBefore, reservedAfter);
        log.info("[stage2] 回放后重复提交 buy-1 duplicate={} 冻结不变={}",
                dupSubmit.duplicate(), reservedAfter);
    }
}
