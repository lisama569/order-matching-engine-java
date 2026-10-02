package com.github.lisama569.ordermatchingenginejava.engine;

import com.github.lisama569.ordermatchingenginejava.model.Fill;
import com.github.lisama569.ordermatchingenginejava.model.Order;
import com.github.lisama569.ordermatchingenginejava.model.OrderStatus;
import com.github.lisama569.ordermatchingenginejava.model.OrderType;
import com.github.lisama569.ordermatchingenginejava.model.Side;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * 单标的订单簿：价格优先、时间优先。仅在 MatchingEngine 全局锁内访问。
 *
 * <p>队列排序规则：
 * <ul>
 *   <li>买盘：市价单（按入队时间）→ 限价单价格高者 → 同价时间早者；</li>
 *   <li>卖盘：市价单（按入队时间）→ 限价单价格低者 → 同价时间早者。</li>
 * </ul>
 *
 * <p>成交条件（行情价 P，订单参考价 R = 限价或市价保护价）：
 * 买单要求 P ≤ R，卖单要求 P ≥ R；否则继续挂单等待后续行情。
 * 这意味着市价单只在保护价允许的范围内成交，防止冻结被击穿。
 */
public final class OrderBook {

    private static final Comparator<Order> BID_CMP = (a, b) -> {
        int m = marketFirst(a, b);
        if (m != 0) {
            return m;
        }
        int byPrice = Long.compare(b.getPrice(), a.getPrice());
        return byPrice != 0 ? byPrice : Long.compare(a.getEnqueueSeq(), b.getEnqueueSeq());
    };

    private static final Comparator<Order> ASK_CMP = (a, b) -> {
        int m = marketFirst(a, b);
        if (m != 0) {
            return m;
        }
        int byPrice = Long.compare(a.getPrice(), b.getPrice());
        return byPrice != 0 ? byPrice : Long.compare(a.getEnqueueSeq(), b.getEnqueueSeq());
    };

    /** 市价单在时间优先基础上排在同方向限价单之前。 */
    private static int marketFirst(Order a, Order b) {
        boolean am = a.getType() == OrderType.MARKET;
        boolean bm = b.getType() == OrderType.MARKET;
        if (am == bm) {
            return 0;
        }
        return am ? -1 : 1;
    }

    private final String symbol;
    private final PriorityQueue<Order> bids = new PriorityQueue<>(BID_CMP);
    private final PriorityQueue<Order> asks = new PriorityQueue<>(ASK_CMP);

    public OrderBook(String symbol) {
        this.symbol = symbol;
    }

    public String symbol() {
        return symbol;
    }

    /** 订单入簿（NEW / PARTIALLY_FILLED 状态）。 */
    public void add(Order order) {
        queueOf(order.getSide()).add(order);
    }

    /** 显式移除（撤单）。堆删除为 O(n)，本地规模足够。 */
    public void remove(Order order) {
        queueOf(order.getSide()).remove(order);
    }

    /**
     * 改价后重新排队。<b>调用方必须先更新订单的价格/入队序号字段，再调用本方法</b>：
     * 先从原队列按身份移除（PriorityQueue.remove 用 equals，不依赖比较键），
     * 再以新的价格/入队序号重新入堆，从而按改单时刻失去原来的时间优先。
     * 只改量不改价时不应调用本方法（保留原 enqueueSeq 与队列位置）。
     */
    public void requeue(Order order) {
        PriorityQueue<Order> q = queueOf(order.getSide());
        q.remove(order);
        q.add(order);
    }

    private PriorityQueue<Order> queueOf(Side side) {
        return side == Side.BUY ? bids : asks;
    }

    private static boolean alive(Order o) {
        return (o.getStatus() == OrderStatus.NEW || o.getStatus() == OrderStatus.PARTIALLY_FILLED)
                && o.remainingQty() > 0;
    }

    /** 取盘口最优先的有效订单，顺带惰性清理已成交/已撤销订单。 */
    public Order best(Side side) {
        PriorityQueue<Order> q = queueOf(side);
        while (!q.isEmpty()) {
            Order o = q.peek();
            if (alive(o)) {
                return o;
            }
            q.poll();
        }
        return null;
    }

    /**
     * 弹出盘口最优先的有效订单。连续撮合时使用：
     * 头部订单一旦在本行情内被吃尽必须离开堆顶，否则会遮蔽后续档位。
     * 仍有余量（部分成交）的订单会重新入堆。
     */
    private Order pollBest(Side side) {
        PriorityQueue<Order> q = queueOf(side);
        while (!q.isEmpty()) {
            Order o = q.poll();
            if (alive(o)) {
                return o;
            }
        }
        return null;
    }

    private static boolean canCross(Order o, long marketPrice) {
        return o.getSide() == Side.BUY
                ? marketPrice <= o.getPrice()
                : marketPrice >= o.getPrice();
    }

    /**
     * 按行情价撮合：价格优先、时间优先，逐笔成交，
     * 直到双方无法成交或一侧耗尽。本方法只更新订单状态，
     * 资金/持仓结算由 MatchingEngine 依据返回的 Fill 列表执行。
     *
     * @param nextFillSeq 全局成交序号的当前值（本方法内自增，调用方持有同一计数）
     */
    public List<Fill> matchAtTick(long marketPrice, long tickSeq, java.util.concurrent.atomic.AtomicLong nextFillSeq) {
        List<Fill> fills = new ArrayList<>();
        while (true) {
            Order bid = pollBest(Side.BUY);
            Order ask = pollBest(Side.SELL);
            if (bid == null || ask == null) {
                requeueIfAlive(bid);
                requeueIfAlive(ask);
                break;
            }
            if (!canCross(bid, marketPrice) || !canCross(ask, marketPrice)) {
                requeueIfAlive(bid);
                requeueIfAlive(ask);
                break;
            }
            long qty = Math.min(bid.remainingQty(), ask.remainingQty());

            long fillSeq = nextFillSeq.incrementAndGet();
            boolean bidFirst = bid.getEnqueueSeq() == Math.min(bid.getEnqueueSeq(), ask.getEnqueueSeq());
            Order maker = bidFirst ? bid : ask;
            Order taker = bidFirst ? ask : bid;
            fills.add(new Fill(fillSeq, tickSeq, symbol, marketPrice, qty,
                    maker.getSide(), maker.getOrderId(), taker.getOrderId()));

            applyFill(bid, qty);
            applyFill(ask, qty);
            requeueIfAlive(bid);
            requeueIfAlive(ask);
        }
        return fills;
    }

    private void requeueIfAlive(Order o) {
        if (o != null && alive(o)) {
            queueOf(o.getSide()).add(o);
        }
    }

    private static void applyFill(Order o, long qty) {
        o.setFilledQty(o.getFilledQty() + qty);
        o.setStatus(o.remainingQty() == 0 ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED);
    }

    public int depth(Side side) {
        int n = 0;
        for (Order o : queueOf(side)) {
            if (alive(o)) {
                n++;
            }
        }
        return n;
    }

    /**
     * 按撮合优先级（市价优先 / 价格优先 / 时间优先）返回当前在簿有效订单。
     * 通过逐个弹出再回填实现，<b>仅在 MatchingEngine 全局锁内调用</b>，
     * 不改变任何订单状态与相对顺序（回填后堆结构与原堆等价）。
     * 用于快照 / 测试中精确比对排队先后。
     */
    public List<Order> snapshotResting(Side side) {
        PriorityQueue<Order> q = queueOf(side);
        List<Order> resting = new ArrayList<>();
        List<Order> taken = new ArrayList<>();
        while (!q.isEmpty()) {
            Order o = q.poll();
            taken.add(o);
            if (alive(o)) {
                resting.add(o);
            }
        }
        q.addAll(taken);
        return resting;
    }
}
