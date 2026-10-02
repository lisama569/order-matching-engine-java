package com.github.lisama569.ordermatchingenginejava.service;

import com.github.lisama569.ordermatchingenginejava.account.AccountLedger;
import com.github.lisama569.ordermatchingenginejava.account.AccountSnapshot;
import com.github.lisama569.ordermatchingenginejava.engine.MatchingEngine;
import com.github.lisama569.ordermatchingenginejava.journal.EventJournal;
import com.github.lisama569.ordermatchingenginejava.journal.JournalEvent;
import com.github.lisama569.ordermatchingenginejava.market.MarketDataService;
import com.github.lisama569.ordermatchingenginejava.market.SimulatedMarketFeeder;
import com.github.lisama569.ordermatchingenginejava.model.AmendOrderRequest;
import com.github.lisama569.ordermatchingenginejava.model.AmendOrderResult;
import com.github.lisama569.ordermatchingenginejava.model.CancelOrderResult;
import com.github.lisama569.ordermatchingenginejava.model.Fill;
import com.github.lisama569.ordermatchingenginejava.model.MarketTick;
import com.github.lisama569.ordermatchingenginejava.model.Order;
import com.github.lisama569.ordermatchingenginejava.model.SubmitOrderRequest;
import com.github.lisama569.ordermatchingenginejava.model.SubmitOrderResult;
import com.github.lisama569.ordermatchingenginejava.model.TickResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Supplier;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 应用编排层。
 *
 * <p>写路径（在线）：命令<b>先追加 WAL、后执行</b>（write-ahead），且"追加 + 执行"
 * 在同一把命令锁内作为一个原子单元完成。这样无论外部线程如何并发交错，
 * <b>WAL 中命令的先后顺序永远等于命令真正作用于引擎的先后顺序</b>：
 * 引擎内部锁只能保证状态变更被串行化，却无法约束"先写日志、后进引擎"两步之间
 * 被其他线程插入；若不在这里再串行化一层，就可能出现 A 先落盘却后执行、
 * B 后落盘却先执行的分叉——在线状态按 B→A 演化，而重启回放严格按 WAL 的 A→B 演化，
 * 最终订单簿挂单与排队先后（尤其是并发改价产生的重排队序号）、账户占用、
 * 成交流水、行情进度都会对不上。
 *
 * <p>重复提交 / 非法行情等会被幂等表或序号校验拦截，重放时产生完全相同的拦截结论，
 * 因此"先写日志再拒绝"不会改变任何状态，保证重放确定性。
 *
 * <p>读路径：订单 / 成交 / 占用快照直接读引擎与台账。
 *
 * <p>重放：启动时（在接收任何在线命令之前）在同一把命令锁内按 WAL 原序重新执行全部命令，
 * 重建订单簿、资金/持仓占用、成交流水与行情期望序号；回放过程不再追加日志。
 */
public final class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final MatchingEngine engine;
    private final EventJournal journal;
    private final MarketDataService marketDataService;
    private final SimulatedMarketFeeder feeder;

    /**
     * 命令锁：串行化所有"追加 WAL + 应用到引擎"的在线命令，以及整段重放。
     * 必须在进程接受任何在线命令之前完成重放（见 {@link #replay()}）。
     */
    private final ReentrantLock commandLock = new ReentrantLock();
    private volatile boolean replaying;

    public OrderService(MatchingEngine engine, EventJournal journal,
                        MarketDataService marketDataService,
                        SimulatedMarketFeeder feeder) {
        this.engine = engine;
        this.journal = journal;
        this.marketDataService = marketDataService;
        this.feeder = feeder;
        this.marketDataService.setTickHandler(this::acceptTick);
    }

    public SubmitOrderResult submit(SubmitOrderRequest req) {
        SubmitOrderResult result = commit(JournalEvent.submit(req), () -> engine.submit(req));
        log.info("[service] submit clientOrderId={} accepted={} duplicate={} reason={}",
                req.clientOrderId(), result.accepted(), result.duplicate(), result.rejectReason());
        return result;
    }

    public CancelOrderResult cancel(String accountId, String clientOrderId) {
        CancelOrderResult result = commit(JournalEvent.cancel(accountId, clientOrderId),
                () -> engine.cancel(accountId, clientOrderId));
        log.info("[service] cancel account={} clientOrderId={} success={} duplicate={} reason={}",
                accountId, clientOrderId, result.success(), result.duplicate(), result.rejectReason());
        return result;
    }

    /** 改单入口：先持久化、后执行（重放阶段不重复追加日志）。 */
    public AmendOrderResult amend(AmendOrderRequest req) {
        if (replaying) {
            return engine.amend(req);
        }
        AmendOrderResult result = commit(JournalEvent.amend(req), () -> engine.amend(req));
        log.info("[service] amend amendId={} account={} clientOrderId={} success={} duplicate={} reason={}",
                req.amendId(), req.accountId(), req.clientOrderId(),
                result.success(), result.duplicate(), result.rejectReason());
        return result;
    }

    /**
     * 行情入口（由 MarketDataService 回调）：先持久化、后撮合。
     *
     * <p>命令锁保证这条行情的"落盘 + 撮合"相对其他命令原子。处理后把门面
     * {@link MarketDataService} 的期望序号同步为引擎的权威值——即使本行情因
     * 并发交错而乱序/重复被拒绝（引擎期望序号未推进），门面也必须停在同一值上，
     * 这样在线与回放看到的"行情进度"始终一致。
     */
    public TickResult acceptTick(MarketTick tick) {
        if (replaying) {
            return engine.onTick(tick);
        }
        TickResult result = commit(JournalEvent.tick(tick), () -> engine.onTick(tick));
        marketDataService.resetExpectedSeq(tick.symbol(), engine.expectedTickSeq(tick.symbol()));
        return result;
    }

    /**
     * 在线命令的统一入口：在命令锁内<b>原子地</b>完成"追加 WAL + 应用到引擎"。
     *
     * <p>关键作用：保证落盘顺序 == 执行顺序。引擎自身的锁只能串行化执行段，
     * 若"追加"与"执行"之间不加同一把锁，并发下两个线程的追加与执行会相互穿插，
     * 导致在线状态的演化顺序与 WAL 记录顺序不同，重启回放必然分叉。
     * 锁内不做等待类操作；WAL 的 append 自身是带 flush 的快速本地 IO。
     */
    private <T> T commit(JournalEvent event, Supplier<T> apply) {
        commandLock.lock();
        try {
            journal.append(event);
            return apply.get();
        } finally {
            commandLock.unlock();
        }
    }

    /**
     * 按既有流水回放。必须在接收任何在线命令之前完成。
     *
     * <p>整段回放在命令锁内进行，因此回放期间任何在线"追加+执行"都必须等待，
     * 绝不会有命令插到两条历史之间。回放直接驱动引擎（跳过 WAL 追加）。
     *
     * @return 回放的命令条数
     */
    public int replay() {
        commandLock.lock();
        try {
            if (replaying) {
                throw new IllegalStateException("replay already in progress");
            }
            replaying = true;
            try {
                List<JournalEvent> events = journal.readAll();
                int submits = 0, cancels = 0, amends = 0, ticks = 0;
                for (JournalEvent e : events) {
                    switch (e.type()) {
                        case SUBMIT -> {
                            engine.submit(e.submit());
                            submits++;
                        }
                        case CANCEL -> {
                            engine.cancel(e.cancelAccountId(), e.cancelClientOrderId());
                            cancels++;
                        }
                        case AMEND -> {
                            engine.amend(e.amend());
                            amends++;
                        }
                        case TICK -> {
                            engine.onTick(e.tick());
                            // 与在线 acceptTick 完全一致：始终把门面期望序号同步为引擎权威值，
                            // 无论该行情接受还是因重复/乱序/断层/非法价被拒。
                            marketDataService.resetExpectedSeq(e.tick().symbol(),
                                    engine.expectedTickSeq(e.tick().symbol()));
                            ticks++;
                        }
                    }
                }
                log.info("[service] replay done events={} (submits={}, cancels={}, amends={}, ticks={})",
                        events.size(), submits, cancels, amends, ticks);
                return events.size();
            } finally {
                replaying = false;
            }
        } finally {
            commandLock.unlock();
        }
    }

    public Order getOrder(String clientOrderId) {
        return engine.findByClientOrderId(clientOrderId);
    }

    public List<Fill> fills() {
        return engine.fills();
    }

    public AccountSnapshot exposure(AccountLedger ledger, String accountId, String symbol) {
        return ledger.snapshot(accountId, symbol);
    }

    public MatchingEngine engine() {
        return engine;
    }

    public EventJournal journal() {
        return journal;
    }

    public SimulatedMarketFeeder feeder() {
        return feeder;
    }
}
