package com.github.lisama569.ordermatchingenginejava.market;

import com.github.lisama569.ordermatchingenginejava.model.MarketTick;
import com.github.lisama569.ordermatchingenginejava.model.TickResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 进程内模拟行情源。
 *
 * <ul>
 *   <li>{@link #feed} 可直接喂入任意构造的行情（含重复/乱序/断层，供测试验证拒绝路径）；</li>
 *   <li>{@link #feedRandomWalk} 按随机游走生成连续序号行情；</li>
 *   <li>{@link #startAutoFeed} 使用守护线程定时推送，关停时自动结束。</li>
 * </ul>
 */
public final class SimulatedMarketFeeder {

    private static final Logger log = LoggerFactory.getLogger(SimulatedMarketFeeder.class);

    private final MarketDataService marketDataService;
    private final Map<String, AtomicLong> seqBySymbol = new java.util.concurrent.ConcurrentHashMap<>();
    private ScheduledExecutorService scheduler;

    public SimulatedMarketFeeder(MarketDataService marketDataService) {
        this.marketDataService = marketDataService;
    }

    /** 推送指定行情（序号由调用方给定），返回行情处理结果。 */
    public com.github.lisama569.ordermatchingenginejava.model.TickResult feed(MarketTick tick) {
        log.info("[feeder] feed tick symbol={} seq={} price={}", tick.symbol(), tick.seq(), tick.price());
        return marketDataService.accept(tick);
    }

    private long nextSeq(String symbol) {
        return seqBySymbol.computeIfAbsent(symbol, s -> new AtomicLong(0)).incrementAndGet();
    }

    /**
     * 回放后把本地序号推进到 {@code nextExpected - 1}，使随后 {@link #nextSeq}
     * 生成的第一条行情恰好是引擎期望的序号。必须在喂任何新行情之前调用。
     */
    public void syncSeq(String symbol, long nextExpected) {
        seqBySymbol.compute(symbol, (s, cur) -> {
            long target = nextExpected - 1;
            if (cur == null || cur.get() < target) {
                return new AtomicLong(target);
            }
            return cur;
        });
    }

    /** 按起始价+随机游走推送 n 条连续序号行情。 */
    public List<MarketTick> feedRandomWalk(String symbol, long startPrice, int n, long step) {
        List<MarketTick> ticks = new ArrayList<>(n);
        long price = startPrice;
        for (int i = 0; i < n; i++) {
            long delta = (Math.random() < 0.5 ? -step : step);
            price = Math.max(step, price + delta);
            MarketTick tick = new MarketTick(nextSeq(symbol), symbol, price);
            ticks.add(tick);
            marketDataService.accept(tick);
        }
        return ticks;
    }

    /** 与 {@link #feedRandomWalk} 相同，但同时返回每条行情的处理结果。 */
    public List<TickResult> feedRandomWalkWithResults(String symbol, long startPrice, int n, long step) {
        List<TickResult> results = new ArrayList<>(n);
        long price = startPrice;
        for (int i = 0; i < n; i++) {
            long delta = (Math.random() < 0.5 ? -step : step);
            price = Math.max(step, price + delta);
            results.add(marketDataService.accept(new MarketTick(nextSeq(symbol), symbol, price)));
        }
        return results;
    }

    public void startAutoFeed(long intervalMillis, String symbol, long startPrice) {
        stopAutoFeed();
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "market-feeder-" + symbol);
            t.setDaemon(true);
            return t;
        });
        AtomicLong price = new AtomicLong(startPrice);
        scheduler.scheduleAtFixedRate(() -> {
            long p = Math.max(1, price.addAndGet(Math.random() < 0.5 ? -1 : 1));
            marketDataService.accept(new MarketTick(nextSeq(symbol), symbol, p));
        }, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
        log.info("[feeder] auto-feed started symbol={} intervalMs={}", symbol, intervalMillis);
    }

    public void stopAutoFeed() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }
}
