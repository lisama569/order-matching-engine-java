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
import com.github.lisama569.ordermatchingenginejava.model.Side;
import com.github.lisama569.ordermatchingenginejava.model.SubmitOrderRequest;
import com.github.lisama569.ordermatchingenginejava.service.OrderService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 改单并发测试：
 * 1) 同一 amendId 多线程重复改单恰好生效一次，占用不双花；
 * 2) 同一订单并发的不同改单（提价/降价/加量/减量）串行化后，
 *    冻结额恒等于"最终剩余量×最终价"，无多占/漏放；
 * 3) 下单/撤单/改单/行情并发打进同一份 WAL 后重启回放，结果与停止前逐项一致。
 */
class ModifyConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(ModifyConcurrencyTest.class);

    @Test
    void duplicateModifyConcurrentlyAppliesOnce() throws Exception {
        int threads = 16;
        AccountLedger ledger = new AccountLedger();
        ledger.seedCash("alice", 1_000_000);
        ledger.setExposureLimit("alice", "AAPL", 1_000_000);
        MatchingEngine engine = new MatchingEngine(ledger, 1, 1);
        engine.registerSymbol("AAPL");
        engine.submit(new SubmitOrderRequest("o1", "alice", "AAPL", Side.BUY,
                OrderType.LIMIT, 100L, null, 10));

        ModifyOrderRequest req = new ModifyOrderRequest(
                "same-amend", "alice", "o1", 110L, null);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger firstOk = new AtomicInteger();
        AtomicInteger duplicates = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    ModifyOrderResult r = engine.modify(req);
                    if (r.duplicate()) {
                        duplicates.incrementAndGet();
                    } else if (r.success()) {
                        firstOk.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        long reserved = ledger.snapshot("alice", "AAPL").cashReserved();
        log.info("[case] 16线程同一改单: 首次成功={} 幂等命中={} 冻结={}(期望1100)",
                firstOk.get(), duplicates.get(), reserved);
        assertEquals(1, firstOk.get());
        assertEquals(threads - 1, duplicates.get());
        assertEquals(1100, reserved);
    }

    @Test
    void concurrentDistinctModifiesKeepReservationConsistent() throws Exception {
        // 初始 10@100 => 冻结1000。多种改单并发，最终冻结必须精确等于 剩余量×最终价
        AccountLedger ledger = new AccountLedger();
        ledger.seedCash("alice", 1_000_000);
        ledger.setExposureLimit("alice", "AAPL", 1_000_000);
        MatchingEngine engine = new MatchingEngine(ledger, 1, 1);
        engine.registerSymbol("AAPL");
        engine.submit(new SubmitOrderRequest("o2", "alice", "AAPL", Side.BUY,
                OrderType.LIMIT, 100L, null, 10));

        // 4 个不同方向的改单各重复若干次（同方向同 amendId 幂等），共 32 个并发请求
        ModifyOrderRequest[] mods = {
                new ModifyOrderRequest("up-price", "alice", "o2", 150L, null),
                new ModifyOrderRequest("down-price", "alice", "o2", 80L, null),
                new ModifyOrderRequest("up-qty", "alice", "o2", null, 20L),
                new ModifyOrderRequest("down-qty", "alice", "o2", null, 5L),
        };
        int per = 8;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger duplicate = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        for (int i = 0; i < mods.length * per; i++) {
            ModifyOrderRequest m = mods[i % mods.length];
            pool.submit(() -> {
                try {
                    start.await();
                    ModifyOrderResult r = engine.modify(m);
                    if (r.duplicate()) {
                        duplicate.incrementAndGet();
                    } else if (r.success()) {
                        success.incrementAndGet();
                    } else {
                        rejected.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        // 每个不同的 amendId 至多成功一次：成功数 == 4，其余均为幂等命中，无额度拒绝
        log.info("[case] 32个并发改单(4种各8次): 首次成功={} 幂等={} 拒绝={}",
                success.get(), duplicate.get(), rejected.get());
        assertEquals(4, success.get());
        assertEquals(32 - 4, duplicate.get());
        assertEquals(0, rejected.get());

        var o = engine.findByClientOrderId("o2");
        long expected = o.remainingQty() * o.getPrice();
        long reserved = ledger.snapshot("alice", "AAPL").cashReserved();
        long exposure = ledger.snapshot("alice", "AAPL").exposure();
        log.info("[case] 并发改单后订单: price={} totalQty={} 剩余={} 冻结={} 敞口={} 期望占用={}",
                o.getPrice(), o.getOriginalQty(), o.remainingQty(), reserved, exposure, expected);
        assertEquals(expected, reserved, "冻结必须精确等于剩余量×最终价，无多占/漏放");
        assertEquals(expected, exposure);
    }

    /**
     * 完整并发场景：多个账户并发下单/改单/撤单，行情线程同时推送；
     * 停机后用同一 WAL 在全新引擎上回放，成交流水、订单簿、台账逐项一致。
     */
    @Test
    void concurrentMixedCommandsThenReplayIsIdentical(@TempDir Path dir) throws Exception {
        Path journalFile = dir.resolve("mixed.jsonl");

        AccountLedger ledger = new AccountLedger();
        String[] accounts = {"alice", "bob", "carol", "dave"};
        for (String a : accounts) {
            ledger.seedCash(a, 1_000_000);
            ledger.seedPosition(a, "AAPL", 10_000);
            ledger.setExposureLimit(a, "AAPL", 1_000_000);
        }
        MatchingEngine engine = new MatchingEngine(ledger, 1, 1);
        engine.registerSymbol("AAPL");
        MarketDataService market = new MarketDataService();
        SimulatedMarketFeeder feeder = new SimulatedMarketFeeder(market);
        OrderService service = new OrderService(engine, new FileEventJournal(journalFile), market, feeder);

        int accountsN = accounts.length;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);

        // 每个账户先挂一批买卖单（也并发）
        for (int i = 0; i < accountsN; i++) {
            String acc = accounts[i];
            pool.submit(() -> {
                try {
                    start.await();
                    for (int j = 0; j < 6; j++) {
                        service.submit(new SubmitOrderRequest(
                                acc + "-b" + j, acc, "AAPL", Side.BUY, OrderType.LIMIT,
                                (long) (95 + j), null, 10));
                        service.submit(new SubmitOrderRequest(
                                acc + "-s" + j, acc, "AAPL", Side.SELL, OrderType.LIMIT,
                                (long) (100 + j), null, 10));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        // 改单线程：对每笔买单尝试提价（部分会因敞口竞争失败，属正常）
        for (int i = 0; i < accountsN; i++) {
            String acc = accounts[i];
            pool.submit(() -> {
                try {
                    start.await();
                    for (int j = 0; j < 6; j++) {
                        service.modify(new ModifyOrderRequest(
                                acc + "-amend-b" + j, acc, acc + "-b" + j,
                                (long) (100 + j), null));
                        service.modify(new ModifyOrderRequest(
                                acc + "-amend-s" + j, acc, acc + "-s" + j,
                                null, 6L));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        // 撤单线程：撤掉每个账户的 s5
        for (int i = 0; i < accountsN; i++) {
            String acc = accounts[i];
            pool.submit(() -> {
                try {
                    start.await();
                    service.cancel(acc, acc + "-s5");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        // 重复改单脉冲：同一 amendId 从多个线程同时打
        CountDownLatch dupStart = new CountDownLatch(1);
        for (int i = 0; i < 8; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    dupStart.await();
                    service.modify(new ModifyOrderRequest(
                            "race-amend", "alice", "alice-b0", 102L, null));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        start.countDown();
        // 行情线程：在命令并发期间连续推送 30 条 100 的行情
        for (long seq = 1; seq <= 30; seq++) {
            service.acceptTick(new MarketTick(seq, "AAPL", 100));
            if (seq == 3) {
                dupStart.countDown();
            }
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        // ---- 停机前快照 ----
        int fillsBefore = service.fills().size();
        long totalReservedBefore = 0;
        long totalExposureBefore = 0;
        long cashBefore = 0;
        for (String a : accounts) {
            var s = ledger.snapshot(a, "AAPL");
            totalReservedBefore += s.cashReserved() + s.positionReserved();
            totalExposureBefore += s.exposure();
            cashBefore += s.cashBalance();
        }
        log.info("[stage1] 并发混合命令结束: fills={} 总冻结={} 总敞口={} 总现金={}",
                fillsBefore, totalReservedBefore, totalExposureBefore, cashBefore);

        // 每个存活订单的占用加总必须与台账一致（占用守恒的直接校验）
        long restingBuyNotional = 0;
        long restingSellNotional = 0;
        for (String a : accounts) {
            for (int j = 0; j < 6; j++) {
                var b = engine.findByClientOrderId(a + "-b" + j);
                var s = engine.findByClientOrderId(a + "-s" + j);
                if (b != null && (b.getStatus().name().equals("NEW")
                        || b.getStatus().name().equals("PARTIALLY_FILLED"))) {
                    restingBuyNotional += b.remainingQty() * b.getPrice();
                }
                if (s != null && (s.getStatus().name().equals("NEW")
                        || s.getStatus().name().equals("PARTIALLY_FILLED"))) {
                    restingSellNotional += s.remainingQty() * s.getPrice();
                }
            }
        }
        assertEquals(restingBuyNotional + restingSellNotional, totalExposureBefore,
                "所有挂单名义金额之和必须等于台账总敞口");

        // ---- 全新进程回放同一 WAL ----
        AccountLedger ledger2 = new AccountLedger();
        for (String a : accounts) {
            ledger2.seedCash(a, 1_000_000);
            ledger2.seedPosition(a, "AAPL", 10_000);
            ledger2.setExposureLimit(a, "AAPL", 1_000_000);
        }
        MatchingEngine engine2 = new MatchingEngine(ledger2, 1, 1);
        engine2.registerSymbol("AAPL");
        MarketDataService market2 = new MarketDataService();
        SimulatedMarketFeeder feeder2 = new SimulatedMarketFeeder(market2);
        OrderService service2 = new OrderService(engine2,
                new FileEventJournal(journalFile), market2, feeder2);
        int events = service2.replay();
        log.info("[stage2] 回放事件数={}", events);

        // 成交流水逐条一致
        assertEquals(service.fills().size(), service2.fills().size());
        for (int i = 0; i < service.fills().size(); i++) {
            assertEquals(service.fills().get(i), service2.fills().get(i), "fill " + i);
        }
        // 每个订单的状态/价/量/已成交/排队序号一致
        for (String a : accounts) {
            for (int j = 0; j < 6; j++) {
                for (String cid : new String[]{a + "-b" + j, a + "-s" + j}) {
                    var o1 = service.getOrder(cid);
                    var o2 = service2.getOrder(cid);
                    assertEquals(o1.getStatus(), o2.getStatus(), cid + " status");
                    assertEquals(o1.getPrice(), o2.getPrice(), cid + " price");
                    assertEquals(o1.getOriginalQty(), o2.getOriginalQty(), cid + " qty");
                    assertEquals(o1.getFilledQty(), o2.getFilledQty(), cid + " filled");
                    assertEquals(o1.getEnqueueSeq(), o2.getEnqueueSeq(), cid + " seq");
                }
            }
        }
        // 台账一致
        for (String a : accounts) {
            var s1 = ledger.snapshot(a, "AAPL");
            var s2 = ledger2.snapshot(a, "AAPL");
            assertEquals(s1.cashBalance(), s2.cashBalance(), a + " cash");
            assertEquals(s1.cashReserved(), s2.cashReserved(), a + " cashReserved");
            assertEquals(s1.position(), s2.position(), a + " position");
            assertEquals(s1.positionReserved(), s2.positionReserved(), a + " posReserved");
            assertEquals(s1.exposure(), s2.exposure(), a + " exposure");
        }
        log.info("[stage2] 并发混合命令+行情后重启回放: 成交流水/订单簿/排队序号/台账逐项一致");
    }
}
