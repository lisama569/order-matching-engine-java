package com.github.lisama569.ordermatchingenginejava.market;

import com.github.lisama569.ordermatchingenginejava.model.MarketTick;
import com.github.lisama569.ordermatchingenginejava.model.TickResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 行情流门面：为每个标的记录下一个期望序号（供查询/监控），
 * 实际的重复 / 乱序 / 断层判定与撮合由 MatchingEngine 完成，
 * 保证判定结论唯一且与重放结果一致。
 *
 * 行情命令同样走 WAL：先持久化、后执行；重启回放后期望序号自动恢复。
 */
public final class MarketDataService {

    private static final Logger log = LoggerFactory.getLogger(MarketDataService.class);

    private final Map<String, Long> expectedSeq = new ConcurrentHashMap<>();
    private volatile Function<MarketTick, TickResult> tickHandler = t ->
            new TickResult(false, false, expectedSeq.getOrDefault(t.symbol(), 1L), t.seq(),
                    com.github.lisama569.ordermatchingenginejava.model.RejectReason.MARKET_CLOSED,
                    java.util.List.of());

    public MarketDataService() {
    }

    /** 绑定真实处理器（OrderService 初始化时绑定到"WAL + 引擎"）。 */
    public void setTickHandler(Function<MarketTick, TickResult> handler) {
        this.tickHandler = handler;
    }

    public void resetExpectedSeq(String symbol, long next) {
        expectedSeq.put(symbol, next);
    }

    public long expectedSeq(String symbol) {
        return expectedSeq.getOrDefault(symbol, 1L);
    }

    /** 接收行情：重复 / 乱序 / 断层行情被拒绝并返回明确拒因，绝不静默丢弃。 */
    public TickResult accept(MarketTick tick) {
        TickResult result = tickHandler.apply(tick);
        if (result.accepted()) {
            expectedSeq.put(tick.symbol(), result.expectedSeq());
        }
        log.info("[market] tick in: symbol={} seq={} accepted={} duplicate={} reason={} fills={}",
                tick.symbol(), tick.seq(), result.accepted(), result.duplicate(),
                result.rejectReason(), result.fills().size());
        return result;
    }
}
