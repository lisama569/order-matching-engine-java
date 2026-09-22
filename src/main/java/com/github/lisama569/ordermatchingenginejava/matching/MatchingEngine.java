package com.github.lisama569.ordermatchingenginejava.matching;

import com.github.lisama569.ordermatchingenginejava.model.MarketTick;
import com.github.lisama569.ordermatchingenginejava.model.Order;
import com.github.lisama569.ordermatchingenginejava.model.OrderStatus;
import com.github.lisama569.ordermatchingenginejava.model.OrderType;
import com.github.lisama569.ordermatchingenginejava.model.Side;
import com.github.lisama569.ordermatchingenginejava.model.Trade;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.TreeMap;

/**
 * 撮合引擎：维护各标的订单簿，执行「价格优先、时间优先」撮合。
 *
 * <p>本类只负责簿记与成交结论，不做账户资金/持仓校验（由 TradingService 在调用前后处理），
 * 也不做持久化（由 EventJournal 在服务层处理）。线程安全由 TradingService 的全局锁保证，
 * 引擎方法本身不加锁。
 *
 * <p>撮合规则：
 * <ul>
 *   <li>新订单先与对手盘价格优先（买高卖低）、同价时间优先（arrivalSeq 小者先）成交，
 *       成交价取挂单价；限价单剩余进入簿，市价单剩余取消；</li>
 *   <li>行情到达时按行情价扫簿：卖一价 &le; 行情价则成交卖单，买一价 &ge; 行情价则成交买单，
 *       对手方为内部 MARKET 账户。</li>
 * </ul>
 * 所有序号/编号均为确定性整数，保证按流水重放后订单簿与成交逐笔一致。
 */
public class MatchingEngine {

    private final Map<String, OrderBook> books = new LinkedHashMap<>();
    private final Map<String, Order> ordersById = new LinkedHashMap<>();
    private long arrivalCounter;
    private long tradeCounter;

    private OrderBook book(String symbol) {
        return books.computeIfAbsent(symbol, k -> new OrderBook());
    }

    /** 分配全局到达序号（时间优先依据，确定性）。 */
    public long nextArrivalSeq() {
        return ++arrivalCounter;
    }

    public Order getOrder(String orderId) {
        return ordersById.get(orderId);
    }

    /** 全部已知订单（含终结状态），用于状态重算。 */
    public java.util.Collection<Order> allOrders() {
        return ordersById.values();
    }

    /** 回放恢复：直接登记既有订单（不撮合）。 */
    public void restoreOrder(Order order) {
        ordersById.put(order.getOrderId(), order);
        if (order.isActive()) {
            book(order.getSymbol()).add(order);
        }
        arrivalCounter = Math.max(arrivalCounter, order.getArrivalSeq());
    }

    /** 回放恢复：推进编号水位。 */
    public void restoreCounters(long maxArrivalSeq, long maxTradeSeq) {
        arrivalCounter = Math.max(arrivalCounter, maxArrivalSeq);
        tradeCounter = Math.max(tradeCounter, maxTradeSeq);
    }

    /** 新增订单入簿（受理后的挂单，不撮合）。 */
    public void addToBook(Order order) {
        ordersById.put(order.getOrderId(), order);
        book(order.getSymbol()).add(order);
    }

    /**
     * 新订单撮合：与对手盘逐笔成交，直到完成或无法继续。
     * 本方法只更新订单簿记状态并返回成交；调用方负责账户结算。
     *
     * @return 成交列表（按成交先后）
     */
    public List<Trade> matchIncoming(Order taker, long eventTime) {
        ordersById.put(taker.getOrderId(), taker);
        OrderBook b = book(taker.getSymbol());
        List<Trade> trades = new ArrayList<>();
        boolean oppositeSideEmptyAtStart = taker.getSide() == Side.BUY
                ? b.asks.isEmpty() : b.bids.isEmpty();
        if (oppositeSideEmptyAtStart) {
            return restOrCancelMarket(taker, b, trades, eventTime);
        }
        while (taker.getRemainingQty() > 0) {
            Order maker = b.bestOpposite(taker.getSide());
            if (maker == null || !canCross(taker, maker)) {
                break;
            }
            trades.add(execute(taker, maker, b, eventTime));
        }
        return restOrCancelMarket(taker, b, trades, eventTime);
    }

    private List<Trade> restOrCancelMarket(Order taker, OrderBook b, List<Trade> trades, long eventTime) {
        if (taker.getRemainingQty() > 0 && taker.getType() == OrderType.MARKET) {
            // 市价单不挂簿：剩余量取消（调用方按是否有成交区分 NO_LIQUIDITY / 部分成交撤余）。
            taker.setStatus(taker.getFilledQty() > 0
                    ? OrderStatus.CANCELLED : OrderStatus.REJECTED);
            taker.setUpdateTime(eventTime);
        } else if (taker.getRemainingQty() > 0) {
            taker.setStatus(taker.getFilledQty() > 0
                    ? OrderStatus.PARTIALLY_FILLED : OrderStatus.NEW);
            b.add(taker);
            taker.setUpdateTime(eventTime);
        }
        return trades;
    }

    private boolean canCross(Order taker, Order maker) {
        if (taker.getType() == OrderType.MARKET) {
            return true;
        }
        return taker.getSide() == Side.BUY
                ? taker.getLimitPrice() >= maker.getLimitPrice()
                : taker.getLimitPrice() <= maker.getLimitPrice();
    }

    private Trade execute(Order taker, Order maker, OrderBook b, long eventTime) {
        long qty = Math.min(taker.getRemainingQty(), maker.getRemainingQty());
        long price = maker.getLimitPrice(); // 挂单方定价
        applyFill(taker, qty, price, eventTime);
        applyFill(maker, qty, price, eventTime);
        if (maker.getRemainingQty() == 0) {
            b.removeBestOpposite(taker.getSide());
        }
        String tradeId = "T" + (++tradeCounter);
        Side takerSide = taker.getSide();
        return new Trade(tradeId, taker.getSymbol(), price, qty, eventTime,
                taker.getOrderId(), taker.getAccountId(), takerSide,
                maker.getOrderId(), maker.getAccountId());
    }

    private void applyFill(Order o, long qty, long price, long eventTime) {
        o.setFilledQty(o.getFilledQty() + qty);
        o.setFilledNotional(o.getFilledNotional() + qty * price);
        o.setAvgFillPrice(o.getWeightedAvgPrice());
        if (o.getRemainingQty() == 0) {
            o.setStatus(OrderStatus.FILLED);
        } else {
            o.setStatus(OrderStatus.PARTIALLY_FILLED);
        }
        o.setUpdateTime(eventTime);
    }

    /**
     * 行情驱动扫簿：先卖后买（确定性顺序），与内部 MARKET 对手盘成交。
     *
     * @return 成交列表（先卖侧后买侧，每侧价格优先时间优先）
     */
    public List<Trade> onTick(MarketTick tick) {
        OrderBook b = book(tick.symbol());
        List<Trade> trades = new ArrayList<>();
        String tickOrderId = "TICK-" + tick.symbol() + "-" + tick.seq();

        // 1) 卖盘：最低卖价 <= 行情价即可成交（MARKET 以行情价买入）
        while (true) {
            Order maker = b.bestAsk();
            if (maker == null || maker.getLimitPrice() > tick.price()) {
                break;
            }
            long qty = maker.getRemainingQty();
            applyFill(maker, qty, maker.getLimitPrice(), tick.eventTime());
            b.removeBestAsk();
            trades.add(new Trade("T" + (++tradeCounter), tick.symbol(), maker.getLimitPrice(),
                    qty, tick.eventTime(), tickOrderId,
                    com.github.lisama569.ordermatchingenginejava.account.AccountManager.MARKET_ACCOUNT,
                    Side.BUY, maker.getOrderId(), maker.getAccountId()));
        }
        // 2) 买盘：最高买价 >= 行情价即可成交（MARKET 以行情价卖出）
        while (true) {
            Order maker = b.bestBid();
            if (maker == null || maker.getLimitPrice() < tick.price()) {
                break;
            }
            long qty = maker.getRemainingQty();
            applyFill(maker, qty, maker.getLimitPrice(), tick.eventTime());
            b.removeBestBid();
            trades.add(new Trade("T" + (++tradeCounter), tick.symbol(), maker.getLimitPrice(),
                    qty, tick.eventTime(), tickOrderId,
                    com.github.lisama569.ordermatchingenginejava.account.AccountManager.MARKET_ACCOUNT,
                    Side.SELL, maker.getOrderId(), maker.getAccountId()));
        }
        return trades;
    }

    /**
     * 从簿中撤销活跃订单。
     *
     * @return 被撤订单；订单不存在返回 null；已不在簿（终结状态）返回该订单，由调用方判定。
     */
    public Order cancelFromBook(String orderId) {
        Order o = ordersById.get(orderId);
        if (o == null) {
            return null;
        }
        if (o.isActive()) {
            book(o.getSymbol()).remove(o);
            o.setStatus(OrderStatus.CANCELLED);
        }
        return o;
    }

    public long getTradeCounter() {
        return tradeCounter;
    }

    /** 查询订单（含已终结）。 */
    public java.util.Collection<Order> orderView() {
        return ordersById.values();
    }

    public boolean hasBook(String symbol) {
        return books.containsKey(symbol);
    }

    public long getArrivalCounter() {
        return arrivalCounter;
    }

    /** 簿档位视图：price -> 总剩余量，用于查询/解释。 */
    public Map<Long, Long> getBidDepth(String symbol) {
        return depth(symbol, Side.BUY);
    }

    public Map<Long, Long> getAskDepth(String symbol) {
        return depth(symbol, Side.SELL);
    }

    private Map<Long, Long> depth(String symbol, Side side) {
        OrderBook b = books.get(symbol);
        Comparator<Long> cmp = side == Side.BUY ? Comparator.reverseOrder()
                : Comparator.naturalOrder();
        Map<Long, Long> out = new TreeMap<>(cmp);
        if (b == null) {
            return out;
        }
        PriorityQueue<Order> q = side == Side.BUY ? b.bids : b.asks;
        for (Order o : q) {
            if (o.isActive()) {
                out.merge(o.getLimitPrice(), o.getRemainingQty(), Long::sum);
            }
        }
        return out;
    }

    /** 单个标的订单簿。 */
    private static class OrderBook {
        /** 卖盘：价格从低到高，同价到达序号小者在前。 */
        final PriorityQueue<Order> asks = new PriorityQueue<>(
                Comparator.comparingLong(Order::getLimitPrice)
                        .thenComparingLong(Order::getArrivalSeq));
        /** 买盘：价格从高到低，同价到达序号小者在前。 */
        final PriorityQueue<Order> bids = new PriorityQueue<>(
                Comparator.comparingLong(Order::getLimitPrice).reversed()
                        .thenComparingLong(Order::getArrivalSeq));

        void add(Order o) {
            (o.getSide() == Side.BUY ? bids : asks).add(o);
        }

        Order bestAsk() {
            return asks.peek();
        }

        Order bestBid() {
            return bids.peek();
        }

        Order bestOpposite(Side takerSide) {
            return takerSide == Side.BUY ? asks.peek() : bids.peek();
        }

        void removeBestAsk() {
            asks.poll();
        }

        void removeBestBid() {
            bids.poll();
        }

        void removeBestOpposite(Side takerSide) {
            if (takerSide == Side.BUY) {
                asks.poll();
            } else {
                bids.poll();
            }
        }

        void remove(Order o) {
            (o.getSide() == Side.BUY ? bids : asks).remove(o);
        }
    }
}
