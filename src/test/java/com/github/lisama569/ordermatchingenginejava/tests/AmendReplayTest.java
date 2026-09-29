package com.github.lisama569.ordermatchingenginejava.tests;

import com.github.lisama569.ordermatchingenginejava.account.AccountLedger;
import com.github.lisama569.ordermatchingenginejava.engine.MatchingEngine;
import com.github.lisama569.ordermatchingenginejava.journal.FileEventJournal;
import com.github.lisama569.ordermatchingenginejava.market.MarketDataService;
import com.github.lisama569.ordermatchingenginejava.market.SimulatedMarketFeeder;
import com.github.lisama569.ordermatchingenginejava.model.AmendOrderRequest;
import com.github.lisama569.ordermatchingenginejava.model.MarketTick;
import com.github.lisama569.ordermatchingenginejava.model.Order;
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
 * 改单重放测试：制造一段含改价（重排队）、改量（保优先级）、占用增减、
 * 被拒改单、重复改单、成交与撤单的命令历史；用同一份 WAL 重建服务后，
 * 订单价格/数量/状态/入队序号、双方资金/持仓/占用、成交流水逐项一致。
 */
class AmendReplayTest {

    private static final Logger log = LoggerFactory.getLogger(AmendReplayTest.class);

    @TempDir
    Path dir;

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

    private AmendOrderRequest amend(String aid, String account, String cid, Long price, Long qty) {
        return new AmendOrderRequest(aid, account, cid, price, qty);
    }

    @Test
    void replayRebuildsAmendedStateIdentically() {
        Path journalFile = dir.resolve("amend-journal.jsonl");

        // ---- 第一阶段：制造含改单的历史 ----
        Ctx first = newSystem(journalFile);
        OrderService s1 = first.service();

        // 两张同价买单 a、b 建立时间优先；a 显式改价到 100 重新排队，失去时间优先
        s1.submit(limit("a", "alice", Side.BUY, 100, 10));
        s1.submit(limit("b", "alice", Side.BUY, 100, 10));
        s1.amend(amend("am-a-requeue", "alice", "a", 100L, null));

        // b 只改量（10->12），保留时间优先；a 改量（10->8）
        s1.amend(amend("am-b-qty", "alice", "b", null, 12L));
        s1.amend(amend("am-a-qty", "alice", "a", null, 8L));

        // 重复改单只生效一次
        var dupAmend = s1.amend(amend("am-b-qty", "alice", "b", null, 12L));
        assertTrue(dupAmend.duplicate());

        // 卖单：一张可成交、一张远价阻断
        s1.submit(limit("s1", "bob", Side.SELL, 100, 12));
        s1.submit(limit("s-block", "bob", Side.SELL, 101, 100));

        // 一个会被拒的改单（超占用），WAL 保留但状态零改动
        var rejected = s1.amend(amend("am-b-too-big", "alice", "b", null, 1_000_000L));
        assertEquals(RejectReason.EXPOSURE_LIMIT_EXCEEDED, rejected.rejectReason());

        TickResult t1 = s1.acceptTick(new MarketTick(1, "AAPL", 100));
        assertTrue(t1.accepted());

        // b 时间优先生效：与 s1 成交 12 股全成；a 改价后排其后且 s-block 阻断，仍挂单 8 股
        Order b1 = s1.getOrder("b");
        Order a1 = s1.getOrder("a");
        long aEnq1 = a1.getEnqueueSeq();
        log.info("[stage1] 成交数={} b.status={} a.status={} a.enqueueSeq={} a.price={} a.qty={}",
                s1.fills().size(), b1.getStatus(), a1.getStatus(), aEnq1, a1.getPrice(), a1.getOriginalQty());

        var alice1 = first.ledger().snapshot("alice", "AAPL");
        var bob1 = first.ledger().snapshot("bob", "AAPL");

        // ---- 第二阶段：仅靠 WAL 回放重建 ----
        Ctx second = newSystem(journalFile);
        OrderService s2 = second.service();
        int replayed = s2.replay();
        log.info("[stage2] replay events={}", replayed);

        // 成交流水逐条一致
        assertEquals(s1.fills().size(), s2.fills().size());
        for (int i = 0; i < s1.fills().size(); i++) {
            assertEquals(s1.fills().get(i), s2.fills().get(i), "fill " + i + " mismatch");
        }

        // 订单价格/数量/状态/入队序号一致（改价重排队的优先级被恢复）
        Order a2 = s2.getOrder("a");
        Order b2 = s2.getOrder("b");
        assertEquals(a1.getPrice(), a2.getPrice());
        assertEquals(a1.getOriginalQty(), a2.getOriginalQty());
        assertEquals(a1.getStatus(), a2.getStatus());
        assertEquals(aEnq1, a2.getEnqueueSeq(), "改价后的排队序号必须被回放恢复");
        assertEquals(b1.getStatus(), b2.getStatus());
        assertEquals(b1.getOriginalQty(), b2.getOriginalQty());
        assertEquals(b1.getFilledQty(), b2.getFilledQty());
        log.info("[stage2] 回放后 a: price={} qty={} status={} enqueueSeq={}; b: status={} qty={}",
                a2.getPrice(), a2.getOriginalQty(), a2.getStatus(), a2.getEnqueueSeq(),
                b2.getStatus(), b2.getOriginalQty());

        // 台账逐项一致
        var alice2 = second.ledger().snapshot("alice", "AAPL");
        var bob2 = second.ledger().snapshot("bob", "AAPL");
        assertEquals(alice1.cashBalance(), alice2.cashBalance());
        assertEquals(alice1.cashReserved(), alice2.cashReserved());
        assertEquals(alice1.exposure(), alice2.exposure());
        assertEquals(bob1.cashBalance(), bob2.cashBalance());
        assertEquals(bob1.position(), bob2.position());
        assertEquals(bob1.positionReserved(), bob2.positionReserved());
        assertEquals(bob1.exposure(), bob2.exposure());

        // 行情期望序号恢复
        TickResult dupTick = s2.acceptTick(new MarketTick(1, "AAPL", 100));
        assertEquals(RejectReason.TICK_DUPLICATE, dupTick.rejectReason());

        // 回放后继续撮合：撤掉阻断单，行情 100 让 a 成交 8 股，两个系统结果一致
        s1.cancel("bob", "s-block");
        s2.cancel("bob", "s-block");
        s1.submit(limit("s2", "bob", Side.SELL, 100, 8));
        s2.submit(limit("s2", "bob", Side.SELL, 100, 8));
        s1.acceptTick(new MarketTick(2, "AAPL", 100));
        s2.acceptTick(new MarketTick(2, "AAPL", 100));
        assertEquals(s1.getOrder("a").getStatus(), s2.getOrder("a").getStatus());
        assertEquals(s1.fills().size(), s2.fills().size());
        assertEquals(s1.fills().get(s1.fills().size() - 1), s2.fills().get(s2.fills().size() - 1));
        log.info("[stage2] 回放后继续撮合 a.status={} 末笔成交一致", s2.getOrder("a").getStatus());
    }
}
