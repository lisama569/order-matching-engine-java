package com.github.lisama569.ordermatchingenginejava.tests;

import com.github.lisama569.ordermatchingenginejava.engine.OrderBook;
import com.github.lisama569.ordermatchingenginejava.model.Fill;
import com.github.lisama569.ordermatchingenginejava.model.Order;
import com.github.lisama569.ordermatchingenginejava.model.OrderType;
import com.github.lisama569.ordermatchingenginejava.model.Side;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 订单簿撮合优先级测试：
 * 价格优先 → 同价时间优先 → 市价单优先；保护价范围外的行情不得成交。
 */
class OrderBookTest {

    private static final Logger log = LoggerFactory.getLogger(OrderBookTest.class);

    private final AtomicLong fillSeq = new AtomicLong(0);

    private Order order(String cid, Side side, OrderType type, long price, long qty, long seq) {
        return new Order(seq, cid, "acc", "AAPL", side, type, price, qty, seq);
    }

    @Test
    void priceTimePriority_bestBidHitsFirst() {
        OrderBook book = new OrderBook("AAPL");
        Order lowBid = order("low", Side.BUY, OrderType.LIMIT, 99, 10, 1);
        Order highBid = order("high", Side.BUY, OrderType.LIMIT, 101, 10, 2);
        Order ask = order("ask", Side.SELL, OrderType.LIMIT, 100, 20, 3);
        book.add(lowBid);
        book.add(highBid);
        book.add(ask);

        List<Fill> fills = book.matchAtTick(100, 1, fillSeq);
        log.info("[case] tick=100, bids(99@t1,101@t2) ask=100@t3 => fills={} (判定: 高价买单101先成交, 99买单不满足P<=99)",
                fills.size());

        assertEquals(1, fills.size());
        assertEquals(highBid.getOrderId(), fills.get(0).makerOrderId());
        assertEquals(ask.getOrderId(), fills.get(0).takerOrderId());
        assertEquals(10, fills.get(0).qty());
        assertEquals(100, fills.get(0).price());
        assertEquals(10, highBid.getFilledQty());
        assertEquals(10, ask.getFilledQty());
        assertEquals(0, lowBid.getFilledQty());
    }

    @Test
    void samePrice_timePriority() {
        OrderBook book = new OrderBook("AAPL");
        Order bid1 = order("b1", Side.BUY, OrderType.LIMIT, 100, 5, 1);
        Order bid2 = order("b2", Side.BUY, OrderType.LIMIT, 100, 5, 2);
        Order ask = order("ask", Side.SELL, OrderType.LIMIT, 100, 5, 3);
        book.add(bid1);
        book.add(bid2);
        book.add(ask);

        List<Fill> fills = book.matchAtTick(100, 7, fillSeq);
        log.info("[case] 同价100两笔买单 t1/t2, 卖单t3 => makerOrderId={} (判定: t1 时间优先)",
                fills.get(0).makerOrderId());

        assertEquals(1, fills.size());
        assertEquals(bid1.getOrderId(), fills.get(0).makerOrderId());
    }

    @Test
    void marketOrderOutranksLimitRegardlessOfArrival() {
        OrderBook book = new OrderBook("AAPL");
        Order limitBid = order("lb", Side.BUY, OrderType.LIMIT, 1000, 10, 1);
        Order marketBid = order("mb", Side.BUY, OrderType.MARKET, 500, 10, 2);
        Order ask = order("ask", Side.SELL, OrderType.LIMIT, 400, 10, 3);
        book.add(limitBid);
        book.add(marketBid);
        book.add(ask);

        // 行情 400：市价买单保护价500允许成交，且优先于限价1000的买单
        List<Fill> fills = book.matchAtTick(400, 1, fillSeq);
        log.info("[case] 限价买1000@t1 vs 市价买(保护500)@t2, tick=400 => maker={} (判定: 市价单优先)",
                fills.get(0).makerOrderId());
        assertEquals(marketBid.getOrderId(), fills.get(0).makerOrderId());

        // tick=600：市价买单保护价500不允许(P<=500不满足)，不能成交
        List<Fill> none = book.matchAtTick(600, 2, fillSeq);
        log.info("[case] tick=600 超过市价买保护价500 => fills={} (判定: 市价单不得穿仓)", none.size());
        assertTrue(none.isEmpty());
    }

    @Test
    void partialAndContinuationFills() {
        OrderBook book = new OrderBook("AAPL");
        Order bid = order("bid", Side.BUY, OrderType.LIMIT, 100, 7, 1);
        Order ask1 = order("a1", Side.SELL, OrderType.LIMIT, 100, 3, 2);
        Order ask2 = order("a2", Side.SELL, OrderType.LIMIT, 100, 4, 3);
        book.add(bid);
        book.add(ask1);
        book.add(ask2);

        List<Fill> fills = book.matchAtTick(100, 1, fillSeq);
        log.info("[case] 买单7 对卖单3(t2)+4(t3) => fills={} qty={},{} (判定: 时间优先, 全部吃尽)",
                fills.size(), fills.get(0).qty(), fills.get(1).qty());
        assertEquals(2, fills.size());
        assertEquals(3, fills.get(0).qty());
        assertEquals(4, fills.get(1).qty());
        assertEquals(7, bid.getFilledQty());
    }

    @Test
    void noMatchWhenPriceOutsideProtection() {
        OrderBook book = new OrderBook("AAPL");
        Order bid = order("bid", Side.BUY, OrderType.LIMIT, 99, 10, 1);
        Order ask = order("ask", Side.SELL, OrderType.LIMIT, 101, 10, 2);
        book.add(bid);
        book.add(ask);

        List<Fill> at100 = book.matchAtTick(100, 1, fillSeq);
        log.info("[case] 买99/卖101 tick=100 => fills={} (判定: 两侧参考价均不满足, 继续挂单)",
                at100.size());
        assertTrue(at100.isEmpty());
        assertEquals(10, bid.remainingQty());
    }
}
