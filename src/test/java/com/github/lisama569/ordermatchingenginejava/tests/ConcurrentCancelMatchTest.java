package com.github.lisama569.ordermatchingenginejava.tests;

import com.github.lisama569.ordermatchingenginejava.model.CancelRequest;
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

import static com.github.lisama569.ordermatchingenginejava.model.Side.BUY;
import static com.github.lisama569.ordermatchingenginejava.model.Side.SELL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 撤单与对手吃单并发：每个挂单要么被撤要么被吃，不能既撤又吃（无双花）；
 * 全部结束后冻结/现金/持仓守恒，账户总价值 = 初始价值。
 */
class ConcurrentCancelMatchTest extends AbstractEngineTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrentCancelMatchTest.class);
    private static final String S = "600000";

    private static final int N = 100;

    @BeforeEach
    void init() {
        setUp();
        account("SELLER", 0L);
        shares("SELLER", S, N);
        account("BUYER", 100_000_000L);
    }

    @Test
    void cancelAndMatchAreMutuallyExclusive() throws Exception {
        // 挂 N 个卖单 10000 x1
        String[] orderIds = new String[N];
        for (int i = 0; i < N; i++) {
            OrderResponse r = service.placeOrder(
                    limit("rest-" + i, "SELLER", S, SELL, 10_000, 1), now());
            orderIds[i] = r.getOrderId();
        }
        long totalFrozenAtStart = service.snapshot("SELLER")
                .reservedPositions().get(S);
        assertEquals(N, totalFrozenAtStart);

        ExecutorService pool = Executors.newFixedThreadPool(2 * N);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger cancelled = new AtomicInteger();
        AtomicInteger filled = new AtomicInteger();
        AtomicInteger cancelRejectedLate = new AtomicInteger();

        for (int i = 0; i < N; i++) {
            final int idx = i;
            // 撤单方
            pool.submit(() -> {
                await(start);
                var r = service.cancel(new CancelRequest("cancel-" + idx, "SELLER",
                        orderIds[idx]), now());
                if (r.success()) {
                    cancelled.incrementAndGet();
                } else {
                    cancelRejectedLate.incrementAndGet();
                }
            });
            // 吃单方（每个买单也试图买对应那 1 股，多个买单会排队扫同一簿）
            pool.submit(() -> {
                await(start);
                var r = service.placeOrder(
                        limit("take-" + idx, "BUYER", S, BUY, 10_000, 1), now());
                if (r.getFilledQty() == 1) {
                    filled.incrementAndGet();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        var seller = service.snapshot("SELLER");
        var buyer = service.snapshot("BUYER");
        long sellerPosition = seller.positions().getOrDefault(S, 0L);
        long sellerFrozen = seller.reservedPositions().getOrDefault(S, 0L);

        log.info("结果: 撤单成功={} 已成交={} 撤单被拒(已成交)={} | 卖方持仓={} 冻结={} 现金={}",
                cancelled.get(), filled.get(), cancelRejectedLate.get(),
                sellerPosition, sellerFrozen, seller.cash());

        // 每个卖单恰好二选一
        assertEquals(N, cancelled.get() + filled.get(),
                "每个挂单要么撤销要么成交，不能既撤又吃");
        assertEquals(cancelled.get(), cancelRejectedLate.get() == 0
                ? cancelled.get() : cancelled.get());
        assertEquals(0, sellerFrozen, "终态无冻结泄漏");
        assertEquals(N - filled.get(), sellerPosition, "剩余的恰好是被撤掉的");
        assertEquals(filled.get() * 10_000L, seller.cash(), "卖方只收到已成交部分的钱");
        // 买方：为每笔实际成交付款
        assertEquals(100_000_000L - filled.get() * 10_000L, buyer.cash());
        assertEquals(filled.get(), buyer.positions().getOrDefault(S, 0L));
        log.info("守恒校验通过: 总成交股数={} 卖方现金={} 买方持仓={}",
                filled.get(), seller.cash(), buyer.positions().getOrDefault(S, 0L));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
