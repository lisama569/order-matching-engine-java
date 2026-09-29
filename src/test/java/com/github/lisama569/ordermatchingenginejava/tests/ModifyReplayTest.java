package com.github.lisama569.ordermatchingenginejava.tests;

import com.github.lisama569.ordermatchingenginejava.account.AccountLedger;
import com.github.lisama569.ordermatchingenginejava.engine.MatchingEngine;
import com.github.lisama569.ordermatchingenginejava.journal.FileEventJournal;
import com.github.lisama569.ordermatchingenginejava.market.MarketDataService;
import com.github.lisama569.ordermatchingenginejava.market.SimulatedMarketFeeder;
import com.github.lisama569.ordermatchingenginejava.model.MarketTick;
import com.github.lisama569.ordermatchingenginejava.model.ModifyOrderRequest;
import com.github.lisama569.ordermatchingenginejava.model.ModifyOrderResult;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 改单重放测试：命令历史包含下单、改价、改量（增/减）、超额拒绝改单、
 * 重复改单、撤单、行情与部分成交；用同一 WAL 重建后比较订单簿状态
 * （价格/数量/排队序号）、成交流水、台账占用、行情期望序号逐项一致。
 */
class ModifyReplayTest {

    private static final Logger log = LoggerFactory.getLogger(ModifyReplayTest.class);

    @TempDir
    Path dir;

    private record Ctx(OrderService service, AccountLedger ledger) {
    }

    private Ctx newSystem(Path journalFile) {
        AccountLedger ledger = new AccountLedger();
        ledger.seedCash("alice", 1_000_000);
        ledger.seedCash("bob", 1_000_000);
        ledger.seedPosition("alice", "AAPL", 10_000);
        ledger.seedPosition("bob", "AAPL", 10_000);
        ledger.setExposureLimit("alice", "AAPL", 2_000);
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

    private ModifyOrderRequest mod(String amendId, String account, String cid, Long price, Long qty) {
        return new ModifyOrderRequest(amendId, account, cid, price, qty);
    }

    @Test
    void replayRebuildsIdenticalStateAfterModifies() {
        Path journalFile = dir.resolve("journal.jsonl");

        // ---- 第一阶段：制造含改单的历史 ----
        Ctx first = newSystem(journalFile);
        OrderService s1 = first.service();

        s1.submit(limit("m-buy", "alice", Side.BUY, 100, 10));    // 买单占用1000
        s1.submit(limit("m-sell", "bob", Side.SELL, 100, 4));     // 卖单4股
        s1.submit(limit("m-rest", "alice", Side.BUY, 100, 6));    // 另一笔买单，占用600

        // 行情100：m-buy 部分成交4
        TickResult t1 = s1.acceptTick(new MarketTick(1, "AAPL", 100));
        assertEquals(1, t1.fills().size());

        // m-buy 改价 100->120（剩余6，占用 600->720），改价重排队，排在 m-rest 之后
        ModifyOrderResult priceUp = s1.modify(mod("am-price-up", "alice", "m-buy", 120L, null));
        assertTrue(priceUp.success());

        // m-rest 减量 6->3（释放 300）
        ModifyOrderResult qtyDown = s1.modify(mod("am-qty-down", "alice", "m-rest", null, 3L));
        assertTrue(qtyDown.success());

        // m-buy 再增量 总量10->12（剩余8 @120 = 960，alice 敞口=960+300=1260 <=2000）
        ModifyOrderResult qtyUp = s1.modify(mod("am-qty-up", "alice", "m-buy", null, 12L));
        assertTrue(qtyUp.success());

        // 超额改单：m-rest 提到 2000（3*2000 超敞口/现金） => 整体拒绝，零改动
        ModifyOrderResult over = s1.modify(mod("am-over", "alice", "m-rest", 2000L, null));
        assertFalse(over.success());
        assertEquals(RejectReason.EXPOSURE_LIMIT_EXCEEDED, over.rejectReason());

        // 同一改单重复提交：幂等命中，不产生第二效果
        ModifyOrderResult dup1 = s1.modify(mod("am-price-up", "alice", "m-buy", 120L, null));
        assertTrue(dup1.duplicate());

        // 不存在 / 属主不符 / 终结态拒因也进 WAL，重放须得到相同结论
        assertEquals(RejectReason.ORDER_NOT_FOUND,
                s1.modify(mod("am-missing", "alice", "ghost", 101L, null)).rejectReason());
        assertEquals(RejectReason.ORDER_OWNER_MISMATCH,
                s1.modify(mod("am-owner", "bob", "m-buy", 101L, null)).rejectReason());

        // 撤掉 m-rest，再改它 => ORDER_NOT_MODIFIABLE（历史中记录）
        s1.cancel("alice", "m-rest");
        assertEquals(RejectReason.ORDER_NOT_MODIFIABLE,
                s1.modify(mod("am-after-cancel", "alice", "m-rest", 101L, null)).rejectReason());

        // 无效行情进 WAL：seq3 断层，随后 seq2 正常
        assertEquals(RejectReason.TICK_SEQUENCE_GAP,
                s1.acceptTick(new MarketTick(3, "AAPL", 120)).rejectReason());
        TickResult t2 = s1.acceptTick(new MarketTick(2, "AAPL", 120));

        var a1 = first.ledger().snapshot("alice", "AAPL");
        var b1 = first.ledger().snapshot("bob", "AAPL");
        long buySeq1 = s1.getOrder("m-buy").getEnqueueSeq();
        log.info("[stage1] fills={} tick2.fills={} alice(cash={},reserved={},exposure={}) bob(exposure={}) mbuy(price={},qty={},filled={},seq={})",
                s1.fills().size(), t2.fills().size(), a1.cashBalance(), a1.cashReserved(), a1.exposure(),
                b1.exposure(), s1.getOrder("m-buy").getPrice(), s1.getOrder("m-buy").getOriginalQty(),
                s1.getOrder("m-buy").getFilledQty(), buySeq1);

        // ---- 第二阶段：重启，仅靠 WAL 回放 ----
        Ctx second = newSystem(journalFile);
        OrderService s2 = second.service();
        int replayed = s2.replay();
        log.info("[stage2] replay events={}", replayed);

        var a2 = second.ledger().snapshot("alice", "AAPL");
        var b2 = second.ledger().snapshot("bob", "AAPL");

        // 成交流水逐条一致
        assertEquals(s1.fills().size(), s2.fills().size());
        for (int i = 0; i < s1.fills().size(); i++) {
            assertEquals(s1.fills().get(i), s2.fills().get(i), "fill " + i + " mismatch");
        }

        // 订单簿/订单字段一致（含改价后的价、改量后的总量、已成交、排队序号、状态）
        for (String cid : new String[]{"m-buy", "m-sell", "m-rest"}) {
            var o1 = s1.getOrder(cid);
            var o2 = s2.getOrder(cid);
            assertEquals(o1.getStatus(), o2.getStatus(), cid + " status");
            assertEquals(o1.getPrice(), o2.getPrice(), cid + " price");
            assertEquals(o1.getOriginalQty(), o2.getOriginalQty(), cid + " qty");
            assertEquals(o1.getFilledQty(), o2.getFilledQty(), cid + " filled");
            assertEquals(o1.getEnqueueSeq(), o2.getEnqueueSeq(), cid + " enqueueSeq");
        }

        // 台账逐项一致（占用守恒 + 改单释放/追加全部复现）
        assertEquals(a1.cashBalance(), a2.cashBalance());
        assertEquals(a1.cashReserved(), a2.cashReserved());
        assertEquals(a1.exposure(), a2.exposure());
        assertEquals(a1.position(), a2.position());
        assertEquals(b1.cashBalance(), b2.cashBalance());
        assertEquals(b1.positionReserved(), b2.positionReserved());
        assertEquals(b1.exposure(), b2.exposure());
        log.info("[stage2] 改单回放后订单价/量/排队序号/状态/成交流水/台账逐项一致");

        // 行情期望序号恢复：seq=2 重复、seq=3 正常
        assertEquals(RejectReason.TICK_DUPLICATE,
                s2.acceptTick(new MarketTick(2, "AAPL", 120)).rejectReason());
        assertTrue(s2.acceptTick(new MarketTick(3, "AAPL", 120)).accepted());

        // 回放后重复改单仍是幂等命中，台账不再变化
        long reservedBefore = second.ledger().snapshot("alice", "AAPL").cashReserved();
        ModifyOrderResult replayDup = s2.modify(mod("am-price-up", "alice", "m-buy", 120L, null));
        assertTrue(replayDup.duplicate());
        assertEquals(reservedBefore, second.ledger().snapshot("alice", "AAPL").cashReserved());

        // 回放后已终结订单改单仍被拒，且占用不变
        long exposureBefore = a2.exposure();
        assertEquals(RejectReason.ORDER_NOT_MODIFIABLE,
                s2.modify(mod("am-after-cancel-2", "alice", "m-rest", 99L, null)).rejectReason());
        assertEquals(exposureBefore, second.ledger().snapshot("alice", "AAPL").exposure());
    }
}
