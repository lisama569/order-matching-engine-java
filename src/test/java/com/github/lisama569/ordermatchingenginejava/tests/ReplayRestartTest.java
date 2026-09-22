package com.github.lisama569.ordermatchingenginejava.tests;

import com.github.lisama569.ordermatchingenginejava.account.AccountManager;
import com.github.lisama569.ordermatchingenginejava.journal.EventJournal;
import com.github.lisama569.ordermatchingenginejava.matching.MatchingEngine;
import com.github.lisama569.ordermatchingenginejava.model.MarketTick;
import com.github.lisama569.ordermatchingenginejava.model.OrderResponse;
import com.github.lisama569.ordermatchingenginejava.service.TradingService;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.github.lisama569.ordermatchingenginejava.model.Side.BUY;
import static com.github.lisama569.ordermatchingenginejava.model.Side.SELL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 服务重启并按既有流水回放：订单簿档位、账户余额/冻结、每笔成交价量、
 * 行情序号水位、幂等结论都必须与重启前完全一致。
 */
class ReplayRestartTest extends AbstractEngineTest {

    private static final Logger log = LoggerFactory.getLogger(ReplayRestartTest.class);
    private static final String S = "600000";

    private static final class State {
        String buyerCash;
        String sellerCash;
        String buyerPos;
        String sellerPos;
        String buyerReserved;
        String sellerReservedPos;
        String bids;
        String asks;
        String expectedTick;
        List<String> orderStates = new ArrayList<>();
        List<String> idempotentHits = new ArrayList<>();
    }

    @Test
    void replayReproducesSameBookTradesAndBalances() {
        setUp(5_000_000L);
        // 首次生命周期
        service.createAccount("BUYER", 2_000_000L);
        service.createAccount("SELLER", 2_000_000L);
        service.depositPosition("SELLER", S, 100);

        // 行情 1：10000（建立最近价）
        assertTrue(service.acceptTick(new MarketTick(S, 1, 10_000, now())).accepted());

        // 部分即时成交 + 剩余挂簿
        service.placeOrder(limit("s-rest", "SELLER", S, SELL, 10_200, 20), now());
        service.placeOrder(limit("s-cross", "SELLER", S, SELL, 9_900, 5), now());
        OrderResponse buy = service.placeOrder(limit("b-1", "BUYER", S, BUY, 10_000, 15), now());
        assertEquals(5, buy.getFilledQty(), "先即时成交 5 股");
        assertEquals(10, buy.getRemainingQty());

        // 撤一笔，再用行情触发挂簿单成交
        var restingSell2 = service.placeOrder(limit("s-cancel", "SELLER", S, SELL, 10_500, 7),
                now());
        service.cancel(new com.github.lisama569.ordermatchingenginejava.model.CancelRequest(
                "cancel-1", "SELLER", restingSell2.getOrderId()), now());

        // 行情 2=10300 触发 10200 的卖单 x20 全部被 MARKET 买（买方挂的是 10000 买，低于10300不触发）
        var tick2 = service.acceptTick(new MarketTick(S, 2, 10_300, now()));
        assertEquals(1, tick2.trades().size());

        // 行情 3=9800 触发买方 10000 挂单剩余 10 股被 MARKET 卖
        var tick3 = service.acceptTick(new MarketTick(S, 3, 9_800, now()));
        assertEquals(1, tick3.trades().size());

        // 一笔拒单也要可重放
        var rejected = service.placeOrder(limit("b-poor", "BUYER", S, BUY, 100_000, 100),
                now());
        assertFalse(rejected.isAccepted());

        State before = capture();
        log.info("重启前快照: buyerCash={} sellerCash={} bids={} asks={} expectedTick={}",
                before.buyerCash, before.sellerCash, before.bids, before.asks,
                before.expectedTick);

        // ===== 模拟重启：用同一流水目录构造全新组件并回放 =====
        EventJournal journal2 = new EventJournal(tempDir);
        AccountManager am2 = new AccountManager();
        MatchingEngine engine2 = new MatchingEngine();
        TradingService restarted = new TradingService(journal2, am2, engine2, 5_000_000L,
                110L, 100L, Set.of("600000", "BTCUSDT"));
        restarted.replay();

        State after = captureFrom(restarted, engine2);
        log.info("重启后快照: buyerCash={} sellerCash={} bids={} asks={} expectedTick={}",
                after.buyerCash, after.sellerCash, after.bids, after.asks, after.expectedTick);

        assertEquals(before.buyerCash, after.buyerCash, "买方现金一致");
        assertEquals(before.sellerCash, after.sellerCash, "卖方现金一致");
        assertEquals(before.buyerPos, after.buyerPos, "买方持仓一致");
        assertEquals(before.sellerPos, after.sellerPos, "卖方持仓一致");
        assertEquals(before.buyerReserved, after.buyerReserved, "买方预占一致");
        assertEquals(before.sellerReservedPos, after.sellerReservedPos, "卖方冻结一致");
        assertEquals(before.bids, after.bids, "买簿档位一致");
        assertEquals(before.asks, after.asks, "卖簿档位一致");
        assertEquals(before.expectedTick, after.expectedTick, "行情序号水位一致");
        assertEquals(before.orderStates, after.orderStates, "全部订单状态逐笔一致");

        // 重放后重复请求仍幂等
        var dup = restarted.placeOrder(limit("b-1", "BUYER", S, BUY, 10_000, 15), now());
        assertTrue(dup.isDuplicate(), "回放后旧 clientRequestId 仍命中幂等");
        assertEquals(buy.getOrderId(), dup.getOrderId());

        var dupReject = restarted.placeOrder(limit("b-poor", "BUYER", S, BUY, 100_000, 100),
                now());
        assertTrue(dupReject.isDuplicate());
        assertFalse(dupReject.isAccepted());
        log.info("回放后幂等结论保持: b-1 -> orderId={}, b-poor -> rejected duplicate={}",
                dup.getOrderId(), dupReject.isDuplicate());

        // 重放后序号水位继续严格递增
        var gap = restarted.acceptTick(new MarketTick(S, 99, 10_000, now()));
        assertFalse(gap.accepted());
        var next = restarted.acceptTick(new MarketTick(S, 4, 10_000, now()));
        assertTrue(next.accepted());
        log.info("回放后行情继续: seq=99 被拒, seq=4 被接受, nextExpected={}",
                restarted.getExpectedTickSeq(S));
    }

    private State capture() {
        return captureFrom(service, engine);
    }

    private State captureFrom(TradingService svc, MatchingEngine eng) {
        State st = new State();
        var b = svc.snapshot("BUYER");
        var s = svc.snapshot("SELLER");
        st.buyerCash = String.valueOf(b.cash());
        st.sellerCash = String.valueOf(s.cash());
        st.buyerPos = String.valueOf(b.positions().getOrDefault(S, 0L));
        st.sellerPos = String.valueOf(s.positions().getOrDefault(S, 0L));
        st.buyerReserved = String.valueOf(b.reservedCash());
        st.sellerReservedPos = String.valueOf(s.reservedPositions().getOrDefault(S, 0L));
        st.bids = eng.getBidDepth(S).toString();
        st.asks = eng.getAskDepth(S).toString();
        st.expectedTick = String.valueOf(svc.getExpectedTickSeq(S));
        for (var o : eng.orderView()) {
            st.orderStates.add(String.format("%s|%s|%s|filled=%d|remain=%d|avg=%d|reason=%s",
                    o.getOrderId(), o.getSide(), o.getStatus(), o.getFilledQty(),
                    o.getRemainingQty(), o.getWeightedAvgPrice(), o.getRejectReason()));
        }
        java.util.Collections.sort(st.orderStates);
        return st;
    }
}
