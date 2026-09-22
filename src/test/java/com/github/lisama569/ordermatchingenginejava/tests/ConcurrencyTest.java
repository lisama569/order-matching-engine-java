package com.github.lisama569.ordermatchingenginejava.tests;

import com.github.lisama569.ordermatchingenginejava.account.AccountLedger;
import com.github.lisama569.ordermatchingenginejava.engine.MatchingEngine;
import com.github.lisama569.ordermatchingenginejava.model.MarketTick;
import com.github.lisama569.ordermatchingenginejava.model.OrderStatus;
import com.github.lisama569.ordermatchingenginejava.model.OrderType;
import com.github.lisama569.ordermatchingenginejava.model.Side;
import com.github.lisama569.ordermatchingenginejava.model.SubmitOrderRequest;
import com.github.lisama569.ordermatchingenginejava.model.SubmitOrderResult;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发安全测试：
 * 1) 同账户并发下单，敞口上限严格不被击穿，成功数×单笔名义金额 == 最终占用；
 * 2) 同一 clientOrderId 多线程重复提交，恰好一笔成交占用；
 * 3) 买卖双方在行情线程并发下，现金+持仓名义守恒。
 */
class ConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrencyTest.class);

    private MatchingEngine newEngine(AccountLedger ledger) {
        MatchingEngine engine = new MatchingEngine(ledger, 1, 1);
        engine.registerSymbol("AAPL");
        return engine;
    }

    @Test
    void concurrentBuysNeverExceedLimit() throws Exception {
        int threads = 32;
        long perOrderNotional = 100; // 10 股 × 10
        long limit = 1000;           // 至多 10 笔成功
        AccountLedger ledger = new AccountLedger();
        ledger.seedCash("alice", 1_000_000);
        ledger.setExposureLimit("alice", "AAPL", limit);
        MatchingEngine engine = newEngine(ledger);

        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        List<SubmitOrderResult> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    SubmitOrderResult r = engine.submit(new SubmitOrderRequest(
                            "c-" + idx, "alice", "AAPL", Side.BUY, OrderType.LIMIT, 10L, null, 10));
                    synchronized (results) {
                        results.add(r);
                    }
                    if (r.accepted()) {
                        accepted.incrementAndGet();
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

        long exposure = ledger.snapshot("alice", "AAPL").exposure();
        long reserved = ledger.snapshot("alice", "AAPL").cashReserved();
        log.info("[case] 32线程并发买单(单笔100, 上限1000): accepted={} rejected={} 最终敞口={} 冻结={}",
                accepted.get(), rejected.get(), exposure, reserved);
        assertEquals(10, accepted.get(), "成功笔数必须恰好等于上限/单笔");
        assertEquals(22, rejected.get());
        assertEquals(limit, exposure, "敞口不得击穿上限");
        assertEquals(limit, reserved, "冻结资金必须等于成功占用");
        assertTrue(results.stream().filter(SubmitOrderResult::accepted).count() == 10);
    }

    @Test
    void concurrentDuplicateSubmissionsProduceSingleReservation() throws Exception {
        int threads = 16;
        AccountLedger ledger = new AccountLedger();
        ledger.seedCash("alice", 1_000_000);
        ledger.setExposureLimit("alice", "AAPL", 1_000_000);
        MatchingEngine engine = newEngine(ledger);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger acceptedFirst = new AtomicInteger();
        AtomicInteger duplicates = new AtomicInteger();
        SubmitOrderRequest req = new SubmitOrderRequest(
                "same-id", "alice", "AAPL", Side.BUY, OrderType.LIMIT, 10L, null, 10);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    SubmitOrderResult r = engine.submit(req);
                    if (r.duplicate()) {
                        duplicates.incrementAndGet();
                    } else if (r.accepted()) {
                        acceptedFirst.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        log.info("[case] 16线程重复提交同一 clientOrderId: 首次接受={}, 幂等命中={}, 冻结={}",
                acceptedFirst.get(), duplicates.get(),
                ledger.snapshot("alice", "AAPL").cashReserved());
        assertEquals(1, acceptedFirst.get());
        assertEquals(15, duplicates.get());
        assertEquals(100, ledger.snapshot("alice", "AAPL").cashReserved());
    }

    @Test
    void concurrentTwoSidedTradingConservesValue() throws Exception {
        // 每个买家/卖家并发挂单，随后单线程按连续行情撮合；校验现金+持仓名义总量守恒
        int n = 20;
        AccountLedger ledger = new AccountLedger();
        for (int i = 0; i < n; i++) {
            ledger.seedCash("buyer-" + i, 1_000_000);
            ledger.seedCash("seller-" + i, 1_000_000);
            ledger.seedPosition("seller-" + i, "AAPL", 10_000);
            ledger.setExposureLimit("buyer-" + i, "AAPL", 1_000_000);
            ledger.setExposureLimit("seller-" + i, "AAPL", 1_000_000);
        }
        MatchingEngine engine = newEngine(ledger);

        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        for (int i = 0; i < n; i++) {
            int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    engine.submit(new SubmitOrderRequest("b-" + idx, "buyer-" + idx, "AAPL",
                            Side.BUY, OrderType.LIMIT, 100L, null, 10));
                    engine.submit(new SubmitOrderRequest("s-" + idx, "seller-" + idx, "AAPL",
                            Side.SELL, OrderType.LIMIT, 100L, null, 10));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        // 初始总现金、总持仓
        long cashBefore = 0;
        for (int i = 0; i < n; i++) {
            cashBefore += ledger.snapshot("buyer-" + i, "AAPL").cashBalance()
                    + ledger.snapshot("seller-" + i, "AAPL").cashBalance();
        }

        // 连续 20 条行情，全部 100，确保 200 笔订单全部有机会撮合
        for (long seq = 1; seq <= 20; seq++) {
            engine.onTick(new MarketTick(seq, "AAPL", 100));
        }

        long cashAfter = 0;
        long positionAfter = 0;
        long exposureAfter = 0;
        for (int i = 0; i < n; i++) {
            var b = ledger.snapshot("buyer-" + i, "AAPL");
            var s = ledger.snapshot("seller-" + i, "AAPL");
            cashAfter += b.cashBalance() + s.cashBalance();
            positionAfter += b.position() + s.position();
            exposureAfter += b.exposure() + s.exposure();
        }
        log.info("[case] 双边并发200单+20条行情: 成交数={} 现金前={} 现金后={} 总持仓={} 残余敞口={}",
                engine.fills().size(), cashBefore, cashAfter, positionAfter, exposureAfter);
        assertEquals(cashBefore, cashAfter, "成交价等于挂单价，现金总量应不变");
        assertEquals(20L * 10_000, positionAfter, "持仓只在账户间转移，总量不变");
        assertEquals(0, exposureAfter, "全部成交后不得残留任何占用");
        // 20 买单 × 10股 与 20 卖单 × 10股 完全撮合 => 20 笔成交、共 200 股
        assertEquals(20, engine.fills().size());
        assertEquals(200, engine.fills().stream().mapToLong(f -> f.qty()).sum());
        // 每个订单最终 FILLED
        for (int i = 0; i < n; i++) {
            assertEquals(OrderStatus.FILLED, engine.findByClientOrderId("b-" + i).getStatus());
            assertEquals(OrderStatus.FILLED, engine.findByClientOrderId("s-" + i).getStatus());
        }
    }
}
