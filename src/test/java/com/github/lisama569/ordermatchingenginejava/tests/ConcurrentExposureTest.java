package com.github.lisama569.ordermatchingenginejava.tests;

import com.github.lisama569.ordermatchingenginejava.model.OrderResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.github.lisama569.ordermatchingenginejava.model.RejectReason.EXPOSURE_LIMIT_EXCEEDED;
import static com.github.lisama569.ordermatchingenginejava.model.RejectReason.INSUFFICIENT_CASH;
import static com.github.lisama569.ordermatchingenginejava.model.Side.BUY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发下单：单账户单标的敞口上限必须被严格遵守。
 * 大量买单同时打到同一账户，最终在簿敞口 + 现金扣减守恒，且超出部分必被拒
 * （EXPOSURE_LIMIT_EXCEEDED 或 INSUFFICIENT_CASH，二者皆为明确的可区分拒绝）。
 */
class ConcurrentExposureTest extends AbstractEngineTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrentExposureTest.class);
    private static final String S = "600000";

    private static final int THREADS = 32;
    private static final int PER_THREAD = 20;

    @BeforeEach
    void init() {
        // 敞口上限 1,000,000 分；现金故意设得更大，让敞口成为先触顶的约束
        setUp(1_000_000L);
        account("A", 50_000_000L);
    }

    @Test
    void exposureLimitNeverExceededUnderConcurrentSubmission() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger exposureRejected = new AtomicInteger();
        AtomicInteger otherRejected = new AtomicInteger();

        // 每单 10 股 @10000 = 100000 分敞口；上限 1,000,000 -> 最多 10 单在簿
        for (int t = 0; t < THREADS; t++) {
            final int base = t * PER_THREAD;
            pool.submit(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int i = 0; i < PER_THREAD; i++) {
                    String clientId = "cli-" + (base + i);
                    OrderResponse r = service.placeOrder(
                            limit(clientId, "A", S, BUY, 10_000, 10), now());
                    if (r.isAccepted()) {
                        accepted.incrementAndGet();
                    } else if (r.getRejectReason() == EXPOSURE_LIMIT_EXCEEDED) {
                        exposureRejected.incrementAndGet();
                    } else {
                        otherRejected.incrementAndGet();
                    }
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        var snap = service.snapshot("A");
        long openExposure = snap.openExposure().getOrDefault(S, 0L);
        log.info("并发结束: 受理={} 敞口超限拒绝={} 其他拒绝={} 在簿敞口={} 预占资金={}",
                accepted.get(), exposureRejected.get(), otherRejected.get(),
                openExposure, snap.reservedCash());

        assertEquals(openExposure, snap.reservedCash(), "敞口金额必须等于实际预占资金（守恒）");
        assertTrue(openExposure <= 1_000_000L, "敞口绝不能超过上限，实际=" + openExposure);
        assertEquals(1_000_000L, openExposure, "现金充足时应有恰好 10 单顶满上限");
        assertEquals(10, accepted.get(), "恰好顶满上限的订单被接受，其余拒绝");
        assertEquals(THREADS * PER_THREAD - 10,
                exposureRejected.get() + otherRejected.get(), "其余全部被明确拒绝");
        assertTrue(exposureRejected.get() > 0, "必须存在 EXPOSURE_LIMIT_EXCEEDED 拒绝");
        assertEquals(50_000_000L - openExposure, snap.availableCash());
        log.info("判定依据: 上限=1000000 分, 在簿敞口={}, 现金={} = 可用{} + 预占{}",
                openExposure, snap.cash(), snap.availableCash(), snap.reservedCash());
    }
}
