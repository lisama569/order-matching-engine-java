package com.github.lisama569.ordermatchingenginejava.service;

import com.github.lisama569.ordermatchingenginejava.account.Account;
import com.github.lisama569.ordermatchingenginejava.account.AccountManager;
import com.github.lisama569.ordermatchingenginejava.journal.EventJournal;
import com.github.lisama569.ordermatchingenginejava.matching.MatchingEngine;
import com.github.lisama569.ordermatchingenginejava.model.AccountSnapshot;
import com.github.lisama569.ordermatchingenginejava.model.CancelRequest;
import com.github.lisama569.ordermatchingenginejava.model.CancelResponse;
import com.github.lisama569.ordermatchingenginejava.model.MarketTick;
import com.github.lisama569.ordermatchingenginejava.model.Order;
import com.github.lisama569.ordermatchingenginejava.model.OrderRequest;
import com.github.lisama569.ordermatchingenginejava.model.OrderResponse;
import com.github.lisama569.ordermatchingenginejava.model.OrderStatus;
import com.github.lisama569.ordermatchingenginejava.model.OrderType;
import com.github.lisama569.ordermatchingenginejava.model.RejectReason;
import com.github.lisama569.ordermatchingenginejava.model.Side;
import com.github.lisama569.ordermatchingenginejava.model.Trade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 交易门面：校验、资金/持仓占用与释放、幂等、成交结算与流水重放的统一入口。
 *
 * <p>并发模型：所有改变状态的操作在单把 ReentrantLock 下原子完成（下单的占用校验
 * 与簿记、撤单释放、行情扫簿结算），因此单账户单标的敞口上限在并发下被严格遵守，
 * 不会出现双花/漏放。锁内只做内存计算与一次 fsync 级别的流水追加，操作很短。
 */
public class TradingService {

    private static final Logger log = LoggerFactory.getLogger(TradingService.class);

    private final EventJournal journal;
    private final AccountManager accountManager;
    private final MatchingEngine engine;

    /** 全局状态锁。 */
    private final ReentrantLock lock = new ReentrantLock();
    /** 已注册标的（白名单）。 */
    private final Set<String> symbols = ConcurrentHashMap.newKeySet();
    /** 幂等表：accountId + '|' + clientRequestId -> 首次下单结论（订单实体）。 */
    private final Map<String, Order> orderIndex = new HashMap<>();
    /** 幂等表：撤单 -> 首次撤单结论。 */
    private final Map<String, CancelResponse> cancelIndex = new HashMap<>();
    /** 敞口：accountId -> symbol -> 当前在簿订单的剩余占用金额（分）。 */
    private final Map<String, Map<String, Long>> exposure = new HashMap<>();

    private final long defaultExposureLimit;
    /** 市价单保护价上限（相对最近价的倍数，分子/分母）。 */
    private final long marketProtectionNum;
    private final long marketProtectionDen;

    private final AtomicLong orderIdSeq = new AtomicLong();
    /** 各标的最近成交价（行情驱动），用于市价单保护价。 */
    private final Map<String, Long> lastPrice = new ConcurrentHashMap<>();
    private boolean replaying;

    public TradingService(EventJournal journal, AccountManager accountManager,
                          MatchingEngine engine,
                          long defaultExposureLimit,
                          long marketProtectionNum, long marketProtectionDen,
                          Set<String> initialSymbols) {
        this.journal = journal;
        this.accountManager = accountManager;
        this.engine = engine;
        this.defaultExposureLimit = defaultExposureLimit;
        this.marketProtectionNum = marketProtectionNum;
        this.marketProtectionDen = marketProtectionDen;
        this.symbols.addAll(initialSymbols);
    }

    // ============================ 账户/标的 ============================

    public Account createAccount(String accountId, long initialCash) {
        lock.lock();
        try {
            Account a = accountManager.createAccount(accountId, initialCash);
            journal.logAccount(accountId, initialCash);
            log.info("ACCOUNT 开户/入金 accountId={} cash={} 结果=已登记流水", accountId, initialCash);
            return a;
        } finally {
            lock.unlock();
        }
    }

    public void registerSymbol(String symbol) {
        symbols.add(symbol);
    }

    /** 划入持仓（测试/初始化用）。 */
    public void depositPosition(String accountId, String symbol, long qty) {
        lock.lock();
        try {
            Account a = accountManager.get(accountId);
            if (a == null) {
                throw new IllegalArgumentException("unknown account: " + accountId);
            }
            a.depositPosition(symbol, qty);
            journal.logPosition(accountId, symbol, qty);
            log.info("ACCOUNT 划入持仓 accountId={} symbol={} qty={}", accountId, symbol, qty);
        } finally {
            lock.unlock();
        }
    }

    public AccountSnapshot snapshot(String accountId) {
        lock.lock();
        try {
            Account a = accountManager.get(accountId);
            if (a == null) {
                return null;
            }
            return a.snapshot(exposure.getOrDefault(accountId, Map.of()));
        } finally {
            lock.unlock();
        }
    }

    // ============================ 下单 ============================

    public OrderResponse placeOrder(OrderRequest req) {
        return placeOrder(req, System.currentTimeMillis(), false, null, 0L);
    }

    /** 指定事件时间（测试确定性场景使用）。 */
    public OrderResponse placeOrder(OrderRequest req, long eventTime) {
        return placeOrder(req, eventTime, false, null, 0L);
    }

    /**
     * @param deterministic 回放模式：使用给定 orderId/arrivalSeq/eventTime，不做资金校验
     *                      （首次落盘时已校验），不重新落盘
     */
    OrderResponse placeOrder(OrderRequest req, long eventTime, boolean deterministic,
                             String fixedOrderId, long fixedArrivalSeq) {
        lock.lock();
        try {
            String idemKey = idemKey(req.accountId(), req.clientRequestId());
            Order existing = orderIndex.get(idemKey);
            if (existing != null) {
                log.info("ORDER 幂等命中 accountId={} clientRequestId={} orderId={} status={} -> 重复请求不产生新成交/占用",
                        req.accountId(), req.clientRequestId(), existing.getOrderId(), existing.getStatus());
                return OrderResponse.of(existing, List.of(), true);
            }

            // ---- 参数校验 ----
            RejectReason invalid = validateOrderRequest(req);
            if (invalid != null) {
                log.info("ORDER 拒绝 accountId={} clientRequestId={} 判定依据={}",
                        req.accountId(), req.clientRequestId(), invalid);
                return persistRejection(req, eventTime, invalid, deterministic);
            }
            Account account = accountManager.get(req.accountId());

            // ---- 占用校验（买入资金 / 卖出持仓 / 敞口上限） ----
            long reserved = computeReserved(req, account);
            RejectReason reserveFailure = checkReserve(req, account, reserved);
            if (reserveFailure != null) {
                log.info("ORDER 占用拒绝 accountId={} symbol={} side={} 需占用={} 判定依据={}",
                        req.accountId(), req.symbol(), req.side(), reserved, reserveFailure);
                return persistRejection(req, eventTime, reserveFailure, deterministic);
            }

            // ---- 构建订单并实际占用 ----
            Order order = new Order();
            if (deterministic) {
                order.setOrderId(fixedOrderId);
                order.setArrivalSeq(fixedArrivalSeq);
            } else {
                order.setOrderId("O" + (orderIdSeq.incrementAndGet()));
                order.setArrivalSeq(engine.nextArrivalSeq());
            }
            order.setClientRequestId(req.clientRequestId());
            order.setAccountId(req.accountId());
            order.setSymbol(req.symbol());
            order.setSide(req.side());
            order.setType(req.type());
            order.setLimitPrice(req.type() == OrderType.LIMIT ? req.limitPrice() : 0L);
            order.setOrderQty(req.orderQty());
            order.setFilledQty(0L);
            order.setFilledNotional(0L);
            order.setStatus(OrderStatus.NEW);
            order.setCreateTime(eventTime);
            order.setUpdateTime(eventTime);
            order.setReservedAmount(reserved);

            applyReserve(account, req, reserved);
            addExposure(req.accountId(), req.symbol(), reserved);

            if (!deterministic) {
                // 先落盘再撮合：崩溃后可依据流水重建
                journal.logOrder(req, order.getOrderId(), order.getArrivalSeq(), eventTime,
                        OrderStatus.NEW, null, reserved);
            }

            // ---- 撮合 + 逐笔结算 ----
            List<Trade> trades = engine.matchIncoming(order, eventTime);
            settleTrades(trades);

            // 市价单未成交完的剩余：簿引擎已置 CANCELLED/REJECTED，释放对应占用并出清敞口
            if (order.getType() == OrderType.MARKET && order.getRemainingQty() > 0) {
                long released = releaseReservationForRemaining(order);
                if (order.getFilledQty() == 0) {
                    // 零成交 = 没有可成交对手盘
                    order.setStatus(OrderStatus.REJECTED);
                    order.setRejectReason(RejectReason.NO_LIQUIDITY);
                } else {
                    order.setStatus(OrderStatus.CANCELLED);
                }
                log.info("MARKET 剩余处理 orderId={} filled={} remaining={} released={} status={} reason={}",
                        order.getOrderId(), order.getFilledQty(), order.getRemainingQty(),
                        released, order.getStatus(), order.getRejectReason());
            }
            recomputeExposure(req.accountId());
            for (Trade t : trades) {
                if (!AccountManager.MARKET_ACCOUNT.equals(t.makerAccountId())) {
                    recomputeExposure(t.makerAccountId());
                }
            }
            if (!deterministic) {
                journal.logCounters(engine.getArrivalCounter(), engine.getTradeCounter());
            }

            orderIndex.put(idemKey, order);
            recomputeExposure(req.accountId());
            log.info("ORDER 受理完成 orderId={} account={} symbol={} side={} type={} limit={} qty={} filled={} status={} trades={}",
                    order.getOrderId(), req.accountId(), req.symbol(), req.side(), req.type(),
                    req.limitPrice(), req.orderQty(), order.getFilledQty(), order.getStatus(),
                    trades.size());
            return OrderResponse.of(order, trades, false);
        } finally {
            lock.unlock();
        }
    }

    // ============================ 校验与占用 ============================

    private RejectReason validateOrderRequest(OrderRequest req) {
        if (req == null) {
            return RejectReason.INVALID_PARAMETER;
        }
        if (isBlank(req.accountId()) || isBlank(req.clientRequestId())
                || isBlank(req.symbol()) || req.side() == null || req.type() == null) {
            return RejectReason.INVALID_PARAMETER;
        }
        if (!symbols.contains(req.symbol())) {
            return RejectReason.UNKNOWN_SYMBOL;
        }
        if (req.orderQty() <= 0) {
            return RejectReason.INVALID_QUANTITY;
        }
        if (req.type() == OrderType.LIMIT) {
            if (req.limitPrice() == null || req.limitPrice() <= 0) {
                return RejectReason.INVALID_PRICE;
            }
        } else {
            // 市价单不允许带价格
            if (req.limitPrice() != null) {
                return RejectReason.INVALID_PRICE;
            }
            // 市价单需要最近价作为保护价依据
            if (lastPrice.get(req.symbol()) == null) {
                return RejectReason.NO_LIQUIDITY;
            }
        }
        return null;
    }

    /** 计算下单应占用的资源量：买单=资金（分），卖单=标的数量（股）。 */
    private long computeReserved(OrderRequest req, Account account) {
        if (req.type() == OrderType.LIMIT) {
            return req.side() == Side.BUY
                    ? req.limitPrice() * req.orderQty()
                    : req.orderQty();
        }
        long px = lastPrice.get(req.symbol());
        long capPx = px * marketProtectionNum / marketProtectionDen;
        return req.side() == Side.BUY ? capPx * req.orderQty() : req.orderQty();
    }

    private RejectReason checkReserve(OrderRequest req, Account account, long reserved) {
        if (account == null) {
            return RejectReason.UNKNOWN_ACCOUNT;
        }
        if (req.side() == Side.BUY) {
            if (account.getAvailableCash() < reserved) {
                return RejectReason.INSUFFICIENT_CASH;
            }
        } else {
            if (account.getAvailablePosition(req.symbol()) < req.orderQty()) {
                return RejectReason.INSUFFICIENT_POSITION;
            }
        }
        long current = exposure.getOrDefault(req.accountId(), Map.of())
                .getOrDefault(req.symbol(), 0L);
        if (current + reserved > defaultExposureLimit) {
            return RejectReason.EXPOSURE_LIMIT_EXCEEDED;
        }
        return null;
    }

    private void applyReserve(Account account, OrderRequest req, long reserved) {
        if (req.side() == Side.BUY) {
            if (!account.reserveCash(reserved)) {
                // 理论上不可达：上面已校验；抛出以暴露并发缺陷而非静默错账
                throw new IllegalStateException("reserveCash race detected for " + req.accountId());
            }
        } else {
            if (!account.reservePosition(req.symbol(), req.orderQty())) {
                throw new IllegalStateException("reservePosition race detected for " + req.accountId());
            }
        }
    }

    private void addExposure(String accountId, String symbol, long amount) {
        exposure.computeIfAbsent(accountId, k -> new HashMap<>())
                .merge(symbol, amount, Long::sum);
    }

    /** 订单当前剩余占用（买单=分，卖单=股；市价买单按保护价口径）。 */
    private long remainingReserved(Order order) {
        if (!order.isActive()) {
            return 0L;
        }
        if (order.getSide() == Side.SELL) {
            return order.getRemainingQty();
        }
        long px = order.getType() == OrderType.LIMIT ? order.getLimitPrice()
                : (lastPrice.getOrDefault(order.getSymbol(), 0L)
                        * marketProtectionNum / marketProtectionDen);
        return px * order.getRemainingQty();
    }

    private OrderResponse persistRejection(OrderRequest req, long eventTime,
                                           RejectReason reason, boolean deterministic) {
        // 拒单也落盘（带原因），保证重放后幂等返回同样结论
        if (!deterministic) {
            journal.logOrder(req, null, engine.nextArrivalSeq(), eventTime,
                    OrderStatus.REJECTED, reason, 0L);
        }
        Order ghost = new Order();
        ghost.setClientRequestId(req.clientRequestId());
        ghost.setAccountId(req.accountId());
        ghost.setSymbol(req.symbol());
        ghost.setSide(req.side());
        ghost.setType(req.type());
        ghost.setOrderQty(req.orderQty());
        ghost.setStatus(OrderStatus.REJECTED);
        ghost.setRejectReason(reason);
        orderIndex.put(idemKey(req.accountId(), req.clientRequestId()), ghost);
        return OrderResponse.rejected(req.clientRequestId(), reason, List.of(), false);
    }

    // ============================ 成交结算 ============================

    private void settleTrades(List<Trade> trades) {
        for (Trade t : trades) {
            Account taker = accountManager.get(t.takerAccountId());
            Account maker = accountManager.get(t.makerAccountId());
            if (taker == null || maker == null) {
                throw new IllegalStateException("settle account missing: " + t);
            }
            settleOneSide(taker, t.takerSide(), t, t.takerOrderId());
            settleOneSide(maker, t.takerSide().opposite(), t, t.makerOrderId());
            log.info("TRADE 成交 id={} symbol={} price={} qty={} taker={}({}) maker={}({})",
                    t.tradeId(), t.symbol(), t.price(), t.qty(),
                    t.takerAccountId(), t.takerSide(),
                    t.makerAccountId(), t.takerSide().opposite());
        }
    }

    private void settleOneSide(Account account, Side side, Trade t, String orderId) {
        if (account.getAccountId().equals(AccountManager.MARKET_ACCOUNT)) {
            // 内部对手盘仅做现金/持仓镜像记账，不做冻结约束
            settleMarketAccount(account, side, t);
            return;
        }
        Order order = engine.getOrder(orderId);
        if (side == Side.BUY) {
            long release = buyReservationPortion(order, t);
            account.settleBuy(t.symbol(), t.qty(), t.price(), release);
        } else {
            account.settleSell(t.symbol(), t.qty(), t.price(), t.qty());
        }
    }

    /** 该笔买单成交应释放的预占资金：市价单按保护价口径，限价单按成交价口径之外的差额一并释放。 */
    private long buyReservationPortion(Order order, Trade t) {
        if (order != null && order.getType() == OrderType.LIMIT) {
            return order.getLimitPrice() * t.qty();
        }
        if (order != null) {
            long capPx = lastPrice.getOrDefault(order.getSymbol(), t.price())
                    * marketProtectionNum / marketProtectionDen;
            return capPx * t.qty();
        }
        return t.price() * t.qty();
    }

    private void settleMarketAccount(Account market, Side side, Trade t) {
        if (side == Side.BUY) {
            market.mirrorBuy(t.symbol(), t.qty(), t.price());
        } else {
            market.mirrorSell(t.symbol(), t.qty(), t.price());
        }
    }

    /** 市价单剩余量被取消：释放其剩余占用与敞口。 */
    private long releaseReservationForRemaining(Order order) {
        Account account = accountManager.get(order.getAccountId());
        long remaining = order.getRemainingQty();
        if (order.getSide() == Side.BUY) {
            long release = buyReservationPortion(order,
                    new Trade(null, order.getSymbol(),
                            lastPrice.getOrDefault(order.getSymbol(), 0L), remaining, 0L,
                            null, null, Side.BUY, null, null));
            account.releaseCash(release);
            return release;
        } else {
            account.releasePosition(order.getSymbol(), remaining);
            return remaining;
        }
    }

    // ============================ 撤单 ============================

    public CancelResponse cancel(CancelRequest req) {
        return cancel(req, System.currentTimeMillis(), false);
    }

    /** 指定事件时间（测试确定性场景使用）。 */
    public CancelResponse cancel(CancelRequest req, long eventTime) {
        return cancel(req, eventTime, false);
    }

    CancelResponse cancel(CancelRequest req, long eventTime, boolean deterministic) {
        lock.lock();
        try {
            if (isBlank(req.accountId()) || isBlank(req.clientRequestId())
                    || isBlank(req.orderId())) {
                return CancelResponse.fail(false, req.clientRequestId(), req.orderId(),
                        RejectReason.INVALID_PARAMETER);
            }
            String idemKey = idemKey(req.accountId(), req.clientRequestId());
            CancelResponse existing = cancelIndex.get(idemKey);
            if (existing != null) {
                log.info("CANCEL 幂等命中 accountId={} clientRequestId={} orderId={} -> 重复撤单不重复释放",
                        req.accountId(), req.clientRequestId(), req.orderId());
                return new CancelResponse(existing.success(), true, existing.clientRequestId(),
                        existing.orderId(), existing.cancelledQty(), existing.releasedCash(),
                        existing.releasedPosition(), existing.rejectReason());
            }

            Order order = engine.getOrder(req.orderId());
            if (order == null || !order.getAccountId().equals(req.accountId())) {
                CancelResponse r = CancelResponse.fail(false, req.clientRequestId(),
                        req.orderId(), RejectReason.ORDER_NOT_FOUND);
                cancelIndex.put(idemKey, r);
                if (!deterministic) {
                    journal.logCancel(req, eventTime);
                }
                log.info("CANCEL 拒绝 orderId={} 判定依据=ORDER_NOT_FOUND", req.orderId());
                return r;
            }
            if (!order.isActive()) {
                CancelResponse r = CancelResponse.fail(false, req.clientRequestId(),
                        req.orderId(), RejectReason.ORDER_NOT_CANCELLABLE);
                cancelIndex.put(idemKey, r);
                if (!deterministic) {
                    journal.logCancel(req, eventTime);
                }
                log.info("CANCEL 拒绝 orderId={} status={} 判定依据=ORDER_NOT_CANCELLABLE",
                        req.orderId(), order.getStatus());
                return r;
            }

            long cancelledQty = order.getRemainingQty();
            long releasedCash = 0L;
            long releasedPosition = 0L;
            Account account = accountManager.get(req.accountId());
            if (order.getSide() == Side.BUY) {
                releasedCash = remainingReserved(order);
                account.releaseCash(releasedCash);
            } else {
                releasedPosition = cancelledQty;
                account.releasePosition(order.getSymbol(), releasedPosition);
            }
            engine.cancelFromBook(req.orderId());
            recomputeExposure(req.accountId());
            order.setUpdateTime(eventTime);

            if (!deterministic) {
                journal.logCancel(req, eventTime);
            }
            CancelResponse r = CancelResponse.ok(false, req.clientRequestId(), req.orderId(),
                    cancelledQty, releasedCash, releasedPosition);
            cancelIndex.put(idemKey, r);
            log.info("CANCEL 成功 orderId={} cancelledQty={} releasedCash={} releasedPosition={}",
                    req.orderId(), cancelledQty, releasedCash, releasedPosition);
            return r;
        } finally {
            lock.unlock();
        }
    }

    // ============================ 行情 ============================

    /** 每个标的期望的下一个行情序号（从 1 开始）。 */
    private final Map<String, Long> expectedTickSeq = new ConcurrentHashMap<>();

    public long getExpectedTickSeq(String symbol) {
        return expectedTickSeq.getOrDefault(symbol, 1L);
    }

    public long getLastPrice(String symbol) {
        return lastPrice.getOrDefault(symbol, 0L);
    }

    /**
     * 注入行情。只有 seq 恰好等于期望序号且价格合法才接受并驱动扫簿；
     * 重复（seq == 已处理最大序号）、乱序（更小）、断层（更大）分别给出可区分原因。
     */
    public com.github.lisama569.ordermatchingenginejava.model.TickResponse acceptTick(
            MarketTick tick) {
        return acceptTick(tick, false);
    }

    com.github.lisama569.ordermatchingenginejava.model.TickResponse acceptTick(
            MarketTick tick, boolean deterministic) {
        lock.lock();
        try {
            if (tick == null || isBlank(tick.symbol())) {
                return com.github.lisama569.ordermatchingenginejava.model.TickResponse
                        .rejected(tick, 1L, RejectReason.INVALID_PARAMETER);
            }
            long expected = getExpectedTickSeq(tick.symbol());
            if (tick.price() <= 0) {
                log.info("TICK 拒绝 symbol={} seq={} price={} 判定依据=TICK_INVALID_PRICE",
                        tick.symbol(), tick.seq(), tick.price());
                return com.github.lisama569.ordermatchingenginejava.model.TickResponse
                        .rejected(tick, expected, RejectReason.TICK_INVALID_PRICE);
            }
            RejectReason seqReason;
            if (tick.seq() == expected - 1 && expected > 1) {
                seqReason = RejectReason.TICK_DUPLICATE;
            } else if (tick.seq() < expected) {
                seqReason = RejectReason.TICK_OUT_OF_ORDER;
            } else if (tick.seq() > expected) {
                seqReason = RejectReason.TICK_GAP;
            } else {
                seqReason = null;
            }
            if (seqReason != null) {
                log.info("TICK 拒绝 symbol={} seq={} expected={} 判定依据={}",
                        tick.symbol(), tick.seq(), expected, seqReason);
                return com.github.lisama569.ordermatchingenginejava.model.TickResponse
                        .rejected(tick, expected, seqReason);
            }

            // 接受：先落盘（仅接受的行情进入流水），再更新价态并扫簿结算
            if (!deterministic) {
                journal.logTick(tick);
            }
            lastPrice.put(tick.symbol(), tick.price());
            expectedTickSeq.put(tick.symbol(), expected + 1);
            List<Trade> trades = engine.onTick(tick);
            settleTrades(trades);
            // 成交终结的挂单其占用已在成交时逐笔释放；以订单簿为准重算相关账户敞口
            Set<String> touchedAccounts = new HashSet<>();
            for (Trade t : trades) {
                touchedAccounts.add(t.makerAccountId());
            }
            touchedAccounts.forEach(this::recomputeExposure);
            if (!deterministic) {
                journal.logCounters(engine.getArrivalCounter(), engine.getTradeCounter());
            }
            log.info("TICK 接受 symbol={} seq={} price={} 触发成交={} 笔, nextExpected={}",
                    tick.symbol(), tick.seq(), tick.price(), trades.size(), expected + 1);
            return com.github.lisama569.ordermatchingenginejava.model.TickResponse
                    .accepted(tick, trades);
        } finally {
            lock.unlock();
        }
    }

    // ============================ 重放 ============================

    /**
     * 按既有流水重放：重建账户、订单簿与行情序号，成交结论与重启前逐笔一致。
     * 重放期间不落新流水、不做资金校验（首次执行时已经校验并持久化了结论）。
     */
    public void replay() {
        lock.lock();
        try {
            replaying = true;
            List<EventJournal.Event> events = journal.replayAll();
            log.info("REPLAY 开始 事件数={}", events.size());
            for (EventJournal.Event e : events) {
                switch (e.kind()) {
                    case "ACCOUNT" -> {
                        String[] f = e.fields();
                        accountManager.createAccount(f[0], Long.parseLong(f[1]));
                    }
                    case "POSITION" -> {
                        String[] f = e.fields();
                        Account a = accountManager.get(f[0]);
                        if (a != null) {
                            a.depositPosition(f[1], Long.parseLong(f[2]));
                        }
                    }
                    case "PRICE" -> {
                        String[] f = e.fields();
                        lastPrice.put(f[0], Long.parseLong(f[1]));
                        expectedTickSeq.put(f[0], Long.parseLong(f[2]));
                    }
                    case "ORDER" -> replayOrder(e);
                    case "CANCEL" -> {
                        CancelRequest cr = EventJournal.toCancelRequest(e);
                        cancel(cr, EventJournal.cancelEventTimeOf(e), true);
                    }
                    case "TICK" -> acceptTick(EventJournal.toTick(e), true);
                    case "SNAP" -> {
                        String[] f = e.fields();
                        engine.restoreCounters(Long.parseLong(f[0]), Long.parseLong(f[1]));
                    }
                    default -> log.warn("REPLAY 未知事件 kind={}", e.kind());
                }
            }
            orderIdSeq.set(engine.getArrivalCounter());
            log.info("REPLAY 完成 arrivalSeq={} tradeSeq={} orders={}",
                    engine.getArrivalCounter(), engine.getTradeCounter(), orderIndex.size());
        } finally {
            replaying = false;
            lock.unlock();
        }
    }

    private void replayOrder(EventJournal.Event e) {
        OrderRequest req = EventJournal.toOrderRequest(e);
        String orderId = EventJournal.orderIdOf(e);
        long arrivalSeq = EventJournal.arrivalSeqOf(e);
        long eventTime = EventJournal.eventTimeOf(e);
        OrderStatus status = EventJournal.statusOf(e);
        RejectReason reason = EventJournal.reasonOf(e);
        long reserved = EventJournal.reservedAmountOf(e);
        orderIdSeq.set(Math.max(orderIdSeq.get(), arrivalSeq));

        if (status == OrderStatus.REJECTED) {
            persistRejection(req, eventTime, reason, true);
            log.info("REPLAY ORDER 拒单 clientRequestId={} reason={}", req.clientRequestId(), reason);
            return;
        }

        Order order = new Order();
        order.setOrderId(orderId);
        order.setArrivalSeq(arrivalSeq);
        order.setClientRequestId(req.clientRequestId());
        order.setAccountId(req.accountId());
        order.setSymbol(req.symbol());
        order.setSide(req.side());
        order.setType(req.type());
        order.setLimitPrice(req.type() == OrderType.LIMIT ? req.limitPrice() : 0L);
        order.setOrderQty(req.orderQty());
        order.setCreateTime(eventTime);
        order.setUpdateTime(eventTime);
        order.setReservedAmount(reserved);

        // 重建首次下单时的占用，再按流水顺序重放撮合与结算，得到与首次一致的成交
        Account account = accountManager.get(req.accountId());
        applyReserve(account, req, reserved);
        addExposure(req.accountId(), req.symbol(), reserved);

        List<Trade> trades = engine.matchIncoming(order, eventTime);
        settleTrades(trades);
        if (order.getType() == OrderType.MARKET && order.getRemainingQty() > 0) {
            releaseReservationForRemaining(order);
        }
        recomputeExposure(req.accountId());
        orderIndex.put(idemKey(req.accountId(), req.clientRequestId()), order);
        log.info("REPLAY ORDER 重建 orderId={} status={} filled={} trades={}",
                orderId, order.getStatus(), order.getFilledQty(), trades.size());
    }

    /** 测试/重放辅助：设置某标的最近价与序号水位（不产生成交，但进入流水保证重放一致）。 */
    public void seedLastPrice(String symbol, long price, long nextSeq) {
        lock.lock();
        try {
            lastPrice.put(symbol, price);
            expectedTickSeq.put(symbol, nextSeq);
            journal.logPrice(symbol, price, nextSeq);
            log.info("PRICE 设置基准价 symbol={} price={} nextSeq={}", symbol, price, nextSeq);
        } finally {
            lock.unlock();
        }
    }

    public boolean isReplaying() {
        return replaying;
    }

    /**
     * 以订单簿中该账户全部活跃订单为基准重算敞口，
     * 保证「成交/撤单/拒单后占用释放守恒」，杜绝双花或漏放。
     */
    private void recomputeExposure(String accountId) {
        Map<String, Long> m = exposure.computeIfAbsent(accountId, k -> new HashMap<>());
        m.clear();
        for (Order o : engine.allOrders()) {
            if (o.getAccountId().equals(accountId) && o.isActive()) {
                long rem;
                if (o.getSide() == Side.SELL) {
                    rem = o.getRemainingQty();
                } else {
                    long px = o.getType() == OrderType.LIMIT ? o.getLimitPrice()
                            : lastPrice.getOrDefault(o.getSymbol(), 0L)
                                    * marketProtectionNum / marketProtectionDen;
                    rem = px * o.getRemainingQty();
                }
                m.merge(o.getSymbol(), rem, Long::sum);
            }
        }
    }

    private static String idemKey(String accountId, String clientRequestId) {
        return accountId + "|" + clientRequestId;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}

