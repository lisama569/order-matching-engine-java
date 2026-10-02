package com.github.lisama569.ordermatchingenginejava.engine;

import com.github.lisama569.ordermatchingenginejava.account.AccountLedger;
import com.github.lisama569.ordermatchingenginejava.model.AmendOrderRequest;
import com.github.lisama569.ordermatchingenginejava.model.AmendOrderResult;
import com.github.lisama569.ordermatchingenginejava.model.CancelOrderResult;
import com.github.lisama569.ordermatchingenginejava.model.Fill;
import com.github.lisama569.ordermatchingenginejava.model.MarketTick;
import com.github.lisama569.ordermatchingenginejava.model.Order;
import com.github.lisama569.ordermatchingenginejava.model.OrderStatus;
import com.github.lisama569.ordermatchingenginejava.model.OrderType;
import com.github.lisama569.ordermatchingenginejava.model.RejectReason;
import com.github.lisama569.ordermatchingenginejava.model.Side;
import com.github.lisama569.ordermatchingenginejava.model.SubmitOrderRequest;
import com.github.lisama569.ordermatchingenginejava.model.SubmitOrderResult;
import com.github.lisama569.ordermatchingenginejava.model.TickResult;
import com.github.lisama569.ordermatchingenginejava.risk.RiskValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.concurrent.locks.ReentrantLock;


/**
 * 撮合与执行核心。
 *
 * <p>线程模型：单把锁串行化所有状态变更（下单 / 撤单 / 改单 / 行情 / 重放），
 * 保证并发下的占用守恒与成交确定性。锁内不做 IO（日志写入在 OrderService
 * 层于调用前完成），避免临界区阻塞。
 *
 * <p>幂等：clientOrderId 是下单幂等键、(accountId,clientOrderId) 是撤单幂等键、
 * (accountId,clientOrderId,amendId) 是改单幂等键；重复请求返回首次结果，
 * 不产生任何额外占用、占用调整或成交。
 *
 * <p>改单：改价（含市价单改保护价）按改单时刻刷新入队序号并重新排队、失去原时间优先；
 * 只改量不改价保留原排队先后。占用按剩余数量做原子差额调整，追加不足则整体零改动。
 *
 * <p>确定性：订单入队序号（含改价重排队的新序号）、成交流水序号、每个标的的行情期望序号
 * 均为单调计数；启动时按 WAL 原序回放即可得到与停机前一致的订单簿、台账与成交结果。
 */
public final class MatchingEngine {

    private static final Logger log = LoggerFactory.getLogger(MatchingEngine.class);

    private final ReentrantLock lock = new ReentrantLock();
    private final AccountLedger ledger;
    private final RiskValidator risk;
    private final long tickSize;

    private final Map<String, OrderBook> books = new HashMap<>();
    private final Map<String, Order> byClientOrderId = new HashMap<>();
    private final Map<String, SubmitOrderResult> submitResults = new HashMap<>();
    private final Map<String, CancelOrderResult> cancelResults = new HashMap<>();
    private final Map<String, AmendOrderResult> amendResults = new HashMap<>();
    /** symbol -> 下一个期望行情序号（首个接受的序号必须为 1）。 */
    private final Map<String, Long> expectedTickSeq = new HashMap<>();
    private final List<Fill> fills = new ArrayList<>();
    private final AtomicLong enqueueSeq = new AtomicLong(0);
    private final AtomicLong orderSeq = new AtomicLong(0);
    private final AtomicLong fillSeq = new AtomicLong(0);
    private Consumer<Fill> fillListener = f -> { };

    public MatchingEngine(AccountLedger ledger, long tickSize, long lotSize) {
        this.ledger = ledger;
        this.risk = new RiskValidator(tickSize, lotSize);
        this.tickSize = tickSize;
    }

    public void setFillListener(Consumer<Fill> listener) {
        lock.lock();
        try {
            this.fillListener = listener == null ? f -> { } : listener;
        } finally {
            lock.unlock();
        }
    }

    private OrderBook book(String symbol) {
        return books.computeIfAbsent(symbol, OrderBook::new);
    }

    /**
     * 下单（命令已由上层写入 WAL）。
     * 幂等返回首次结果；拒单不产生任何占用。
     */
    public SubmitOrderResult submit(SubmitOrderRequest req) {
        lock.lock();
        try {
            SubmitOrderResult first = submitResults.get(req.clientOrderId());
            if (first != null) {
                log.info("[submit] idempotent-hit clientOrderId={} accepted={} orderId={} reason={}",
                        req.clientOrderId(), first.accepted(), first.orderId(), first.rejectReason());
                return new SubmitOrderResult(first.accepted(), true, first.orderId(),
                        first.status(), first.rejectReason(), first.message());
            }

            RejectReason invalid = risk.validate(req);
            if (invalid != null) {
                return rejectSubmit(req, invalid, "static risk check failed");
            }
            if (!ledger.accountExists(req.accountId()) || !bookExists(req.symbol())) {
                return rejectSubmit(req, RejectReason.UNKNOWN_ACCOUNT_OR_SYMBOL,
                        "account or symbol not registered");
            }

            long refPrice = req.type() == OrderType.LIMIT
                    ? req.limitPrice() : req.protectionPrice();
            long notional = req.quantity() * refPrice;

            boolean reserved = req.side() == Side.BUY
                    ? ledger.tryReserveBuy(req.accountId(), req.symbol(), notional)
                    : ledger.tryReserveSell(req.accountId(), req.symbol(), req.quantity(), notional);
            if (!reserved) {
                return rejectSubmit(req, RejectReason.EXPOSURE_LIMIT_EXCEEDED,
                        "insufficient cash/position or exposure limit exceeded");
            }

            Order order = new Order(orderSeq.incrementAndGet(), req.clientOrderId(), req.accountId(),
                    req.symbol(), req.side(), req.type(), refPrice, req.quantity(),
                    enqueueSeq.incrementAndGet());
            book(req.symbol()).add(order);
            byClientOrderId.put(req.clientOrderId(), order);

            SubmitOrderResult ok = new SubmitOrderResult(true, false, order.getOrderId(),
                    OrderStatus.NEW, null, "order accepted and resting");
            submitResults.put(req.clientOrderId(), ok);
            log.info("[submit] accepted clientOrderId={} orderId={} account={} symbol={} side={} type={} refPrice={} qty={}",
                    req.clientOrderId(), order.getOrderId(), req.accountId(), req.symbol(),
                    req.side(), req.type(), refPrice, req.quantity());
            return ok;
        } finally {
            lock.unlock();
        }
    }

    private SubmitOrderResult rejectSubmit(SubmitOrderRequest req, RejectReason reason, String message) {
        SubmitOrderResult r = SubmitOrderResult.rejected(reason, message);
        submitResults.put(req.clientOrderId(), r);
        log.warn("[submit] rejected clientOrderId={} account={} symbol={} side={} type={} limitPrice={} protectionPrice={} qty={} reason={} because={}",
                req.clientOrderId(), req.accountId(), req.symbol(), req.side(), req.type(),
                req.limitPrice(), req.protectionPrice(), req.quantity(), reason, message);
        return r;
    }

    private boolean bookExists(String symbol) {
        return books.containsKey(symbol);
    }

    /** 登记标的（种子行情后即可交易，未登记标的的行情/下单会被拒绝）。 */
    public void registerSymbol(String symbol) {
        lock.lock();
        try {
            book(symbol);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 撤单（命令已由上层写入 WAL）。幂等：重复撤单返回首次结果。
     * 已成交 / 已撤销 / 已拒单的订单返回 ORDER_NOT_CANCELLABLE。
     */
    public CancelOrderResult cancel(String accountId, String clientOrderId) {
        lock.lock();
        try {
            String key = accountId + '|' + clientOrderId;
            CancelOrderResult first = cancelResults.get(key);
            if (first != null) {
                log.info("[cancel] idempotent-hit account={} clientOrderId={} status={} reason={}",
                        accountId, clientOrderId, first.status(), first.rejectReason());
                return new CancelOrderResult(first.success(), true, first.orderId(),
                        first.status(), first.rejectReason(), first.message());
            }

            Order order = byClientOrderId.get(clientOrderId);
            if (order == null) {
                return rejectCancel(key, null, OrderStatus.REJECTED, RejectReason.ORDER_NOT_FOUND,
                        "order not found", accountId, clientOrderId);
            }
            if (!order.getAccountId().equals(accountId)) {
                return rejectCancel(key, order.getOrderId(), order.getStatus(),
                        RejectReason.ORDER_OWNER_MISMATCH, "order belongs to another account",
                        accountId, clientOrderId);
            }
            if (order.getStatus() != OrderStatus.NEW && order.getStatus() != OrderStatus.PARTIALLY_FILLED) {
                return rejectCancel(key, order.getOrderId(), order.getStatus(),
                        RejectReason.ORDER_NOT_CANCELLABLE,
                        "order status " + order.getStatus() + " is not cancellable",
                        accountId, clientOrderId);
            }

            book(order.getSymbol()).remove(order);
            releaseRemainder(order);
            order.setStatus(OrderStatus.CANCELLED);

            CancelOrderResult ok = new CancelOrderResult(true, false, order.getOrderId(),
                    OrderStatus.CANCELLED, null, "order cancelled, reservation released");
            cancelResults.put(key, ok);
            log.info("[cancel] accepted orderId={} account={} clientOrderId={} remainingQty={}",
                    order.getOrderId(), accountId, clientOrderId, order.remainingQty());
            return ok;
        } finally {
            lock.unlock();
        }
    }

    private CancelOrderResult rejectCancel(String key, Long orderId, OrderStatus status,
                                           RejectReason reason, String message,
                                           String accountId, String clientOrderId) {
        CancelOrderResult r = new CancelOrderResult(false, false, orderId, status, reason, message);
        cancelResults.put(key, r);
        log.warn("[cancel] rejected account={} clientOrderId={} reason={} because={}",
                accountId, clientOrderId, reason, message);
        return r;
    }

    /**
     * 改单（命令已由上层写入 WAL）。
     *
     * <p>幂等：以 {@code (accountId, clientOrderId, amendId)} 为键，重复请求返回首次结果。
     *
     * <p>占用守恒：按"剩余数量"比较新旧需求。需要追加占用时若可用现金/持仓/敞口不足，
     * 整体拒绝，订单价格、数量、已占用额度均不变；需求减少时立即释放多占用部分。
     *
     * <p>排队：改价（含市价单改保护价）按改单时刻刷新入队序号并重新排队，失去原时间优先；
     * 只减量不改价保留原排队先后。
     */
    public AmendOrderResult amend(AmendOrderRequest req) {
        lock.lock();
        try {
            String key = req.accountId() + '|' + req.clientOrderId() + '|' + req.amendId();
            AmendOrderResult first = amendResults.get(key);
            if (first != null) {
                log.info("[amend] idempotent-hit amendId={} account={} clientOrderId={} success={} reason={}",
                        req.amendId(), req.accountId(), req.clientOrderId(),
                        first.success(), first.rejectReason());
                return new AmendOrderResult(first.success(), true, first.orderId(), first.status(),
                        first.price(), first.quantity(), first.rejectReason(), first.message());
            }

            if (req.amendId() == null || req.amendId().isBlank()
                    || req.accountId() == null || req.accountId().isBlank()
                    || req.clientOrderId() == null || req.clientOrderId().isBlank()) {
                return rejectAmend(key, null, OrderStatus.REJECTED,
                        RejectReason.INVALID_ARGUMENT, "amend request missing required fields");
            }

            Order order = byClientOrderId.get(req.clientOrderId());
            if (order == null) {
                return rejectAmend(key, null, OrderStatus.REJECTED,
                        RejectReason.ORDER_NOT_FOUND, "order not found");
            }
            if (!order.getAccountId().equals(req.accountId())) {
                return rejectAmend(key, order.getOrderId(), order.getStatus(),
                        RejectReason.ORDER_OWNER_MISMATCH, "order belongs to another account");
            }
            if (order.getStatus() != OrderStatus.NEW && order.getStatus() != OrderStatus.PARTIALLY_FILLED) {
                return rejectAmend(key, order.getOrderId(), order.getStatus(),
                        RejectReason.ORDER_NOT_AMENDABLE,
                        "order status " + order.getStatus() + " is not amendable");
            }

            RejectReason invalid = risk.validateAmend(req, order.getType(), order.getFilledQty());
            if (invalid != null) {
                return rejectAmend(key, order.getOrderId(), order.getStatus(), invalid,
                        "amend static risk check failed");
            }

            long oldPrice = order.getPrice();
            long oldTotal = order.getOriginalQty();
            long newPrice = req.newPrice() != null ? req.newPrice() : oldPrice;
            long oldRemaining = order.remainingQty();
            long newTotal = req.newQuantity() != null ? req.newQuantity() : oldTotal;
            long newRemaining = newTotal - order.getFilledQty();

            long oldNotional = oldRemaining * oldPrice;
            long newNotional = newRemaining * newPrice;

            // 原子调整占用：任何追加额度不足都返回 false，台账零改动
            boolean adjusted = order.getSide() == Side.BUY
                    ? ledger.adjustBuy(order.getAccountId(), order.getSymbol(), newNotional - oldNotional)
                    : ledger.adjustSell(order.getAccountId(), order.getSymbol(),
                            newRemaining - oldRemaining, newNotional - oldNotional);
            if (!adjusted) {
                return rejectAmend(key, order.getOrderId(), order.getStatus(),
                        RejectReason.EXPOSURE_LIMIT_EXCEEDED,
                        "insufficient cash/position or exposure limit for amended order");
            }

            // 占用调整成功后再更新订单：改价重排队，改量原地保留优先级。
            // 只要请求显式带了新价格（即使数值与原价相同）即视为改价，按改单时刻重新排队。
            // 必须先更新价格/入队序号字段，再从堆中移除并以新排序键重新入堆。
            boolean priceChanged = req.newPrice() != null;
            if (priceChanged) {
                long newEnqueueSeq = enqueueSeq.incrementAndGet();
                order.applyPriceChange(newPrice, newEnqueueSeq);
                book(order.getSymbol()).requeue(order);
            }
            if (req.newQuantity() != null) {
                order.applyQuantityChange(newTotal);
            }

            AmendOrderResult ok = new AmendOrderResult(true, false, order.getOrderId(),
                    order.getStatus(), order.getPrice(), order.getOriginalQty(), null,
                    priceChanged ? "order amended, re-queued at new price"
                            : "order amended, time priority retained");
            amendResults.put(key, ok);
            log.info("[amend] accepted amendId={} orderId={} account={} clientOrderId={} price {}->{} totalQty {}->{} filled={} remaining={} requeued={}",
                    req.amendId(), order.getOrderId(), req.accountId(), req.clientOrderId(),
                    oldPrice, order.getPrice(), oldTotal, order.getOriginalQty(),
                    order.getFilledQty(), order.remainingQty(), priceChanged);
            return ok;
        } finally {
            lock.unlock();
        }
    }

    private AmendOrderResult rejectAmend(String key, Long orderId, OrderStatus status,
                                         RejectReason reason, String message) {
        AmendOrderResult r = new AmendOrderResult(false, false, orderId, status,
                null, null, reason, message);
        amendResults.put(key, r);
        log.warn("[amend] rejected key={} orderId={} reason={} because={}", key, orderId, reason, message);
        return r;
    }

    /** 按订单剩余数量释放挂单时的冻结与敞口。 */
    private void releaseRemainder(Order order) {
        long remaining = order.remainingQty();
        long notional = remaining * order.getPrice();
        if (order.getSide() == Side.BUY) {
            ledger.releaseBuy(order.getAccountId(), order.getSymbol(), notional);
        } else {
            ledger.releaseSell(order.getAccountId(), order.getSymbol(), remaining, notional);
        }
    }

    /**
     * 处理行情（命令已由上层写入 WAL）。
     * 序号校验：重复 → TICK_DUPLICATE；小于期望值 → TICK_OUT_OF_ORDER；
     * 大于期望值 → TICK_SEQUENCE_GAP；均不静默丢弃、不撮合。
     */
    public TickResult onTick(MarketTick tick) {
        lock.lock();
        try {
            long expected = expectedTickSeq.getOrDefault(tick.symbol(), 1L);
            if (tick.seq() < expected) {
                RejectReason reason = tick.seq() == expected - 1
                        ? RejectReason.TICK_DUPLICATE : RejectReason.TICK_OUT_OF_ORDER;
                log.warn("[tick] rejected symbol={} receivedSeq={} expectedSeq={} reason={}",
                        tick.symbol(), tick.seq(), expected, reason);
                return new TickResult(false, false, expected, tick.seq(), reason, List.of());
            }
            if (tick.seq() > expected) {
                log.warn("[tick] rejected symbol={} receivedSeq={} expectedSeq={} reason=TICK_SEQUENCE_GAP",
                        tick.symbol(), tick.seq(), expected);
                return new TickResult(false, false, expected, tick.seq(),
                        RejectReason.TICK_SEQUENCE_GAP, List.of());
            }
            if (tick.price() <= 0 || tick.price() % tickSize != 0) {
                log.warn("[tick] rejected symbol={} seq={} price={} reason=TICK_INVALID_PRICE",
                        tick.symbol(), tick.seq(), tick.price());
                return new TickResult(false, false, expected, tick.seq(),
                        RejectReason.TICK_INVALID_PRICE, List.of());
            }

            expectedTickSeq.put(tick.symbol(), expected + 1);
            List<Fill> produced = book(tick.symbol()).matchAtTick(tick.price(), tick.seq(), fillSeq);
            for (Fill f : produced) {
                settle(f);
                fills.add(f);
                fillListener.accept(f);
            }
            log.info("[tick] accepted symbol={} seq={} price={} fills={}",
                    tick.symbol(), tick.seq(), tick.price(), produced.size());
            return new TickResult(true, false, expected + 1, tick.seq(), null,
                    List.copyOf(produced));
        } finally {
            lock.unlock();
        }
    }

    /** 依据成交记录在台账上完成双方资金/持仓结算与冻结核销。 */
    private void settle(Fill f) {
        Order maker = byInternalId(f.makerOrderId());
        Order taker = byInternalId(f.takerOrderId());
        settleOne(maker, f.qty(), f.price());
        settleOne(taker, f.qty(), f.price());
        log.info("[fill] seq={} tickSeq={} symbol={} price={} qty={} maker={}({}) taker={}({})",
                f.seq(), f.tickSeq(), f.symbol(), f.price(), f.qty(),
                maker.getSide(), f.makerOrderId(), taker.getSide(), f.takerOrderId());
    }

    private void settleOne(Order order, long qty, long price) {
        long cost = qty * price;
        if (order.getSide() == Side.BUY) {
            ledger.settleBuy(order.getAccountId(), order.getSymbol(), qty, cost, order.getPrice());
        } else {
            ledger.settleSell(order.getAccountId(), order.getSymbol(), qty, cost, order.getPrice());
        }
    }

    private Order byInternalId(long internalId) {
        for (Order o : byClientOrderId.values()) {
            if (o.getOrderId() == internalId) {
                return o;
            }
        }
        throw new IllegalStateException("fill references unknown order id: " + internalId);
    }

    public Order findByClientOrderId(String clientOrderId) {
        lock.lock();
        try {
            return byClientOrderId.get(clientOrderId);
        } finally {
            lock.unlock();
        }
    }

    public List<Fill> fills() {
        lock.lock();
        try {
            return Collections.unmodifiableList(new ArrayList<>(fills));
        } finally {
            lock.unlock();
        }
    }

    /** 某标的下一个期望行情序号（引擎权威值，首个接受序号为 1）。 */
    public long expectedTickSeq(String symbol) {
        lock.lock();
        try {
            return expectedTickSeq.getOrDefault(symbol, 1L);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 返回某标的某方向在簿挂单的 clientOrderId，按当前撮合优先级排列
     * （市价优先、价格优先、时间优先；含改价重排队后的先后）。
     * 供快照/测试在引擎锁内读取一致的排队顺序。
     */
    public List<String> restingClientOrderIds(String symbol, Side side) {
        lock.lock();
        try {
            List<String> ids = new ArrayList<>();
            if (books.containsKey(symbol)) {
                for (Order o : books.get(symbol).snapshotResting(side)) {
                    ids.add(o.getClientOrderId());
                }
            }
            return List.copyOf(ids);
        } finally {
            lock.unlock();
        }
    }
}
