package com.github.lisama569.ordermatchingenginejava.engine;

import com.github.lisama569.ordermatchingenginejava.account.AccountLedger;
import com.github.lisama569.ordermatchingenginejava.model.CancelOrderResult;
import com.github.lisama569.ordermatchingenginejava.model.Fill;
import com.github.lisama569.ordermatchingenginejava.model.MarketTick;
import com.github.lisama569.ordermatchingenginejava.model.ModifyOrderRequest;
import com.github.lisama569.ordermatchingenginejava.model.ModifyOrderResult;
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
 * <p>线程模型：单把锁串行化所有状态变更（下单 / 撤单 / 行情 / 重放），
 * 保证并发下的占用守恒与成交确定性。锁内不做 IO（日志写入在 OrderService
 * 层于调用前完成），避免临界区阻塞。
 *
 * <p>幂等：clientOrderId 是下单幂等键、(accountId,clientOrderId) 是撤单幂等键，
 * 重复请求返回首次结果，不产生任何额外占用或成交。
 *
 * <p>确定性：订单入队序号、成交流水序号、每个标的的行情期望序号均为单调计数；
 * 启动时按 WAL 原序回放即可得到与停机前一致的订单簿、台账与成交结果。
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
    /** 改单幂等表：键 = accountId|clientOrderId|amendId。 */
    private final Map<String, ModifyOrderResult> modifyResults = new HashMap<>();
    /** symbol -> 下一个期望行情序号（首个接受的序号必须为 1）。 */
    private final Map<String, Long> expectedTickSeq = new HashMap<>();
    private final List<Fill> fills = new ArrayList<>();
    private final AtomicLong enqueueSeq = new AtomicLong(0);
    private final AtomicLong orderSeq = new AtomicLong(0);
    private final AtomicLong fillSeq = new AtomicLong(0);
    private Consumer<Fill> fillListener = f -> { };
    /**
     * 在线写路径钩子：命令在锁内、首个状态判定点原子触发一次（追加 WAL）。
     * 为 null（引擎直调/回放）时不写日志。这样 WAL 顺序与锁内执行顺序严格一致，
     * 并发下回放才能复现相同的入队/改单排队序号。
     */
    private Runnable journalHook;

    /** 供 OrderService 使用：在同一把引擎锁内先持久化命令、再执行命令。 */
    public SubmitOrderResult submit(SubmitOrderRequest req, Runnable journalHook) {
        lock.lock();
        try {
            this.journalHook = journalHook;
            return submit(req);
        } finally {
            this.journalHook = null;
            lock.unlock();
        }
    }

    /** 供 OrderService 使用：撤单的"持久化 + 执行"原子化。 */
    public CancelOrderResult cancel(String accountId, String clientOrderId, Runnable journalHook) {
        lock.lock();
        try {
            this.journalHook = journalHook;
            return cancel(accountId, clientOrderId);
        } finally {
            this.journalHook = null;
            lock.unlock();
        }
    }

    /** 供 OrderService 使用：改单的"持久化 + 执行"原子化。 */
    public ModifyOrderResult modify(ModifyOrderRequest req, Runnable journalHook) {
        lock.lock();
        try {
            this.journalHook = journalHook;
            return modify(req);
        } finally {
            this.journalHook = null;
            lock.unlock();
        }
    }

    private void fireJournalHook() {
        if (journalHook != null) {
            journalHook.run();
        }
    }

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
            fireJournalHook();
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
            fireJournalHook();
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
     * <p>对仍在订单簿中的挂单直接改价 / 改量：
     * <ul>
     *   <li>幂等：键 {@code accountId|clientOrderId|amendId}，重复提交返回首次结果；</li>
     *   <li>拒因可区分：订单不存在 / 属主不符 / 已终结 / 静态非法 / 额度不足；</li>
     *   <li>占用守恒：增量申请原子校验，不足则原单价格、数量、占用一点不变；
     *       减量立即释放多占部分；</li>
     *   <li>排队：改了价（含市价保护价）按改单时刻重新发号入队，失去原时间优先；
     *       只改量（无论增减）保留原 enqueueSeq，排队先后不变。</li>
     * </ul>
     */
    public ModifyOrderResult modify(ModifyOrderRequest req) {
        lock.lock();
        try {
            fireJournalHook();
            String key = req.accountId() + '|' + req.clientOrderId() + '|' + req.amendId();
            ModifyOrderResult first = modifyResults.get(key);
            if (first != null) {
                log.info("[modify] idempotent-hit amendId={} clientOrderId={} success={} reason={}",
                        req.amendId(), req.clientOrderId(), first.success(), first.rejectReason());
                return new ModifyOrderResult(first.success(), true, first.orderId(), first.status(),
                        first.price(), first.quantity(), first.rejectReason(), first.message());
            }

            Order order = byClientOrderId.get(req.clientOrderId());
            if (order == null) {
                return rejectModify(key, null, OrderStatus.REJECTED, 0, 0,
                        RejectReason.ORDER_NOT_FOUND, "order not found", req);
            }
            if (!order.getAccountId().equals(req.accountId())) {
                return rejectModify(key, order.getOrderId(), order.getStatus(),
                        order.getPrice(), order.getOriginalQty(),
                        RejectReason.ORDER_OWNER_MISMATCH, "order belongs to another account", req);
            }
            if (order.getStatus() != OrderStatus.NEW && order.getStatus() != OrderStatus.PARTIALLY_FILLED) {
                return rejectModify(key, order.getOrderId(), order.getStatus(),
                        order.getPrice(), order.getOriginalQty(),
                        RejectReason.ORDER_NOT_MODIFIABLE,
                        "order status " + order.getStatus() + " is not modifiable", req);
            }

            RejectReason invalid = risk.validateModify(req, order);
            if (invalid != null) {
                return rejectModify(key, order.getOrderId(), order.getStatus(),
                        order.getPrice(), order.getOriginalQty(), invalid,
                        "static risk check failed", req);
            }

            long oldPrice = order.getPrice();
            long newPrice = req.newPrice() != null ? req.newPrice() : oldPrice;
            long oldRemaining = order.remainingQty();
            long newRemaining = req.newQuantity() != null
                    ? req.newQuantity() - order.getFilledQty() : oldRemaining;

            // 占用增量按"剩余部分"的名义金额计算；不足时台账零改动，改单整体拒绝
            boolean adjusted;
            if (order.getSide() == Side.BUY) {
                long delta = newRemaining * newPrice - oldRemaining * oldPrice;
                adjusted = ledger.adjustBuyReserve(order.getAccountId(), order.getSymbol(), delta);
            } else {
                long deltaQty = newRemaining - oldRemaining;
                long deltaNotional = newRemaining * newPrice - oldRemaining * oldPrice;
                adjusted = ledger.adjustSellReserve(order.getAccountId(), order.getSymbol(),
                        deltaQty, deltaNotional);
            }
            if (!adjusted) {
                return rejectModify(key, order.getOrderId(), order.getStatus(), oldPrice,
                        order.getOriginalQty(), RejectReason.EXPOSURE_LIMIT_EXCEEDED,
                        "insufficient cash/position or exposure limit exceeded", req);
            }

            // 占用调整完成后再动订单与队列：改价 => 先出队、改价、重新发号、重新入队；
            // 仅改量 => 原地更新字段，保持原排队位置（堆顺序与量无关）
            boolean priceChanged = newPrice != oldPrice;
            if (priceChanged) {
                book(order.getSymbol()).remove(order);
                order.setPrice(newPrice);
                order.setEnqueueSeq(enqueueSeq.incrementAndGet());
                book(order.getSymbol()).add(order);
            }
            if (req.newQuantity() != null) {
                order.setOriginalQty(req.newQuantity());
            }

            ModifyOrderResult ok = new ModifyOrderResult(true, false, order.getOrderId(),
                    order.getStatus(), newPrice, order.getOriginalQty(), null,
                    "order modified" + (priceChanged ? ", re-queued at tail priority"
                            : ", queue position preserved"));
            modifyResults.put(key, ok);
            log.info("[modify] accepted amendId={} orderId={} account={} clientOrderId={} price {}->{} totalQty={} remaining {}->{} priceChanged={} enqueueSeq={}",
                    req.amendId(), order.getOrderId(), req.accountId(), req.clientOrderId(),
                    oldPrice, newPrice, order.getOriginalQty(), oldRemaining, newRemaining,
                    priceChanged, order.getEnqueueSeq());
            return ok;
        } finally {
            lock.unlock();
        }
    }

    private ModifyOrderResult rejectModify(String key, Long orderId, OrderStatus status,
                                           long price, long quantity, RejectReason reason,
                                           String message, ModifyOrderRequest req) {
        ModifyOrderResult r = new ModifyOrderResult(false, false, orderId, status,
                price, quantity, reason, message);
        modifyResults.put(key, r);
        log.warn("[modify] rejected amendId={} account={} clientOrderId={} newPrice={} newQty={} reason={} because={}",
                req.amendId(), req.accountId(), req.clientOrderId(), req.newPrice(),
                req.newQuantity(), reason, message);
        return r;
    }

    /** 按订单剩余数量释放挂单时的冻结与敞口。 */
    private void releaseRemainder(Order order) {        long remaining = order.remainingQty();
        long notional = remaining * order.getPrice();
        if (order.getSide() == Side.BUY) {
            ledger.releaseBuy(order.getAccountId(), order.getSymbol(), notional);
        } else {
            ledger.releaseSell(order.getAccountId(), order.getSymbol(), remaining, notional);
        }
    }

    /** 供 OrderService 使用：行情的"持久化 + 撮合"原子化。 */
    public TickResult onTick(MarketTick tick, Runnable journalHook) {
        lock.lock();
        try {
            this.journalHook = journalHook;
            return onTick(tick);
        } finally {
            this.journalHook = null;
            lock.unlock();
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
            fireJournalHook();
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
}
