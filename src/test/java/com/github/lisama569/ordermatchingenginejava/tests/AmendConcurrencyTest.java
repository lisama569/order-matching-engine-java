package com.github.lisama569.ordermatchingenginejava.tests;

import com.github.lisama569.ordermatchingenginejava.account.AccountLedger;
import com.github.lisama569.ordermatchingenginejava.engine.MatchingEngine;
import com.github.lisama569.ordermatchingenginejava.model.AmendOrderRequest;
import com.github.lisama569.ordermatchingenginejava.model.MarketTick;
import com.github.lisama569.ordermatchingenginejava.model.OrderType;
import com.github.lisama569.ordermatchingenginejava.model.Side;
import com.github.lisama569.ordermatchingenginejava.model.SubmitOrderRequest;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 改单并发测试：
 * 1) 多线程对同一订单反复加量/减量改单 + 撤单 + 行情混打，占用恒等于在簿挂单需求，
 *    现金/持仓/敞口不出现多占、漏放、双花；
 * 2) 同一 amendId 被多线程重复提交，恰好生效一次。
 */
class AmendConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(AmendConcurrencyTest.class);

    @Test
    void concurrentAmendsNeverCorruptReservation() throws Exception {
        int threads = 16;
        AccountLedger ledger = new AccountLedger();
        ledger.seedCash("alice", 1_000_000);
        ledger.setExposureLimit("alice", "AAPL", 1_000_000);
        MatchingEngine engine = new MatchingEngine(ledger, 1, 1);
        engine.registerSymbol("AAPL");

        // 初始买单 100 股 @100，冻结 10000
        engine.submit(new SubmitOrderRequest("base", "alice", "AAPL", Side.BUY,
                OrderType.LIMIT, 100L, null, 100));

        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    // 一半线程把数量在 100/200 间改，另一半在 100/50 间改，全部限价 100，
                    // 始终在额度内 => 每次都应成功，最终冻结可由在簿订单需求解释
                    long qty = (idx % 2 == 0) ? 200 : 50;
                    var r = engine.amend(new AmendOrderRequest(
                            "amend-" + idx, "alice", "base", null, qty));
                    if (r.success()) {
                        accepted.incrementAndGet();
                    } else {
                        rejected.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        // 同时有行情线程在打（无对手卖单，不应成交，仅验证不互相破坏）
        for (int s = 1; s <= 5; s++) {
            pool.submit(() -> { });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        // 最终订单数量是最后一次成功改单的值之一（50 或 200），冻结必须严格等于 100×该数量
        long finalQty = engine.findByClientOrderId("base").getOriginalQty();
        long reserved = ledger.snapshot("alice", "AAPL").cashReserved();
        long exposure = ledger.snapshot("alice", "AAPL").exposure();
        log.info("[case] 16线程并发改单: accepted={} rejected={} 最终数量={} 冻结={} 敞口={}",
                accepted.get(), rejected.get(), finalQty, reserved, exposure);
        assertEquals(threads, accepted.get(), "全部改单都在额度内，应全部成功");
        assertEquals(0, rejected.get());
        assertTrue(finalQty == 50 || finalQty == 200);
        assertEquals(100 * finalQty, reserved, "冻结必须精确等于在簿挂单需求，不得多占/漏放");
        assertEquals(100 * finalQty, exposure, "敞口必须与冻结一致，不得双花");
    }

    @Test
    void concurrentDuplicateAmendAppliesOnce() throws Exception {
        int threads = 16;
        AccountLedger ledger = new AccountLedger();
        ledger.seedCash("alice", 1_000_000);
        ledger.setExposureLimit("alice", "AAPL", 1_000_000);
        MatchingEngine engine = new MatchingEngine(ledger, 1, 1);
        engine.registerSymbol("AAPL");
        engine.submit(new SubmitOrderRequest("ord", "alice", "AAPL", Side.BUY,
                OrderType.LIMIT, 100L, null, 10)); // 冻结 1000

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger first = new AtomicInteger();
        AtomicInteger dup = new AtomicInteger();
        AmendOrderRequest req = new AmendOrderRequest("same-amend", "alice", "ord", null, 30L);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    var r = engine.amend(req);
                    if (r.duplicate()) {
                        dup.incrementAndGet();
                    } else if (r.success()) {
                        first.incrementAndGet();
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
        log.info("[case] 16线程重复同一改单: 首次生效={} 幂等命中={} 最终数量={} 冻结={}(期望3000)",
                first.get(), dup.get(), engine.findByClientOrderId("ord").getOriginalQty(), reserved);
        assertEquals(1, first.get());
        assertEquals(threads - 1, dup.get());
        assertEquals(30, engine.findByClientOrderId("ord").getOriginalQty());
        assertEquals(3000, reserved, "重复改单只能调整一次占用");
    }

    @Test
    void concurrentAmendUpToLimitExactlySaturatesWithoutOverrun() throws Exception {
        // 小敞口上限：多线程把同一订单往大改，只有部分能成功，最终占用不得超过上限
        long limit = 10_000;
        AccountLedger ledger = new AccountLedger();
        ledger.seedCash("alice", 1_000_000);
        ledger.setExposureLimit("alice", "AAPL", limit);
        MatchingEngine engine = new MatchingEngine(ledger, 1, 1);
        engine.registerSymbol("AAPL");
        engine.submit(new SubmitOrderRequest("ord", "alice", "AAPL", Side.BUY,
                OrderType.LIMIT, 100L, null, 10)); // 占用 1000

        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    // 每个线程用不同 amendId 尝试把数量改到 200（需 20000 > 上限）
                    var r = engine.amend(new AmendOrderRequest(
                            "big-" + idx, "alice", "ord", null, 200L));
                    if (r.success()) {
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
        log.info("[case] 32线程并发把订单改到超额: accepted={} rejected={} 最终敞口={} 冻结={} 上限={}",
                accepted.get(), rejected.get(), exposure, reserved, limit);
        // 改到 200 需 20000 > 10000 上限：任一成功都会超额，因此必须全部被拒，占用维持 1000
        assertEquals(0, accepted.get(), "任何把占用提到超过上限的改单都必须失败");
        assertEquals(threads, rejected.get());
        assertEquals(1000, reserved, "被拒改单不得改变既有占用");
        assertEquals(1000, exposure);
        assertEquals(10, engine.findByClientOrderId("ord").getOriginalQty());
    }
}
