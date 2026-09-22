package com.github.lisama569.ordermatchingenginejava.market;

import com.github.lisama569.ordermatchingenginejava.model.MarketTick;
import com.github.lisama569.ordermatchingenginejava.model.TickResponse;
import com.github.lisama569.ordermatchingenginejava.service.EngineProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 进程内模拟行情流。
 *
 * <p>对每个标的维护本地自增序号，按固定间隔发布「下一个期望序号」的行情，
 * 因此正常推送严格有序。为支持测试乱序/断层场景，{@link #publishOutOfOrder(long)}、
 * {@link #publishDuplicate()}、{@link #skipOne()} 可显式制造非法行情——它们都会被
 * TradingService 以可区分原因拒绝，不会静默丢弃或错误撮合。
 *
 * <p>价格用确定性伪随机游走生成，仅用于产生有变化的行情，不参与正确性判定。
 */
@Component
public class SimulatedTickFeed {

    private static final Logger log = LoggerFactory.getLogger(SimulatedTickFeed.class);

    private final MarketDataService marketDataService;
    private final List<String> symbols;
    private final boolean enabled;
    private final long intervalMs;
    private final ScheduledExecutorService executor;
    private final AtomicLong priceGen = new AtomicLong(10_000);
    private final java.util.Map<String, AtomicLong> manualSeq = new java.util.concurrent.ConcurrentHashMap<>();

    public SimulatedTickFeed(MarketDataService marketDataService, EngineProperties props) {
        this.marketDataService = marketDataService;
        this.symbols = props.symbols();
        this.enabled = props.feedEnabled();
        this.intervalMs = props.feedIntervalMs();
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "simulated-tick-feed");
            t.setDaemon(true);
            return t;
        });
    }

    @PostConstruct
    public void start() {
        if (!enabled) {
            log.info("SIM_FEED 模拟行情流未启用（matching.feed-enabled=false）");
            return;
        }
        executor.scheduleAtFixedRate(this::publishNextForAll, intervalMs, intervalMs,
                TimeUnit.MILLISECONDS);
        log.info("SIM_FEED 启动 标的={} 间隔={}ms", symbols, intervalMs);
    }

    @PreDestroy
    public void stop() {
        executor.shutdownNow();
    }

    /** 为每个标的发布下一个序号的正常行情。 */
    public void publishNextForAll() {
        for (String symbol : symbols) {
            long seq = marketDataService.expectedSeq(symbol);
            long price = nextPrice();
            TickResponse r = marketDataService.publish(new MarketTick(symbol, seq, price,
                    System.currentTimeMillis()));
            if (!r.accepted()) {
                log.warn("SIM_FEED 正常推送被拒 symbol={} seq={} 原因={}",
                        symbol, seq, r.rejectReason());
            }
        }
    }

    /** 显式发布重复序号行情（测试用）。 */
    public TickResponse publishDuplicate(String symbol) {
        long seq = Math.max(1, marketDataService.expectedSeq(symbol) - 1);
        return marketDataService.publish(new MarketTick(symbol, seq, nextPrice(),
                System.currentTimeMillis()));
    }

    /** 显式发布乱序行情（序号落后两个，测试用）。 */
    public TickResponse publishOutOfOrder(String symbol, long seq) {
        return marketDataService.publish(new MarketTick(symbol, seq, nextPrice(),
                System.currentTimeMillis()));
    }

    /** 显式跳过序号制造断层：发送 expected+1（测试用）。 */
    public TickResponse skipOne(String symbol) {
        long seq = marketDataService.expectedSeq(symbol) + 1;
        return marketDataService.publish(new MarketTick(symbol, seq, nextPrice(),
                System.currentTimeMillis()));
    }

    /** 确定性伪随机游走：围绕 100 元上下波动（单位分）。 */
    private long nextPrice() {
        long x = priceGen.updateAndGet(v -> {
            long delta = (v * 31 + 17) % 401 - 200; // [-200,200] 分
            long nv = v + delta;
            return Math.max(1000, nv);
        });
        return x;
    }
}
