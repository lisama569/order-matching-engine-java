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
import java.util.concurrent.locks.ReentrantLock;

/**
 * 应用编排层。
 *
 * <p>写路径（在线）：每条命令在<b>同一把命令锁</b>内完成「先追加 WAL、后执行引擎」
 * 两个动作，二者对外是一个不可分割的线性化点。这样 WAL 中命令的先后次序
 * 与引擎实际执行次序（进而订单号 / 入队序号 / 成交序号 / 占用差额的归属次序）
 * <b>严格一致</b>：并发命令无论怎样交错，重启后按 WAL 原序回放都能逐项复现
 * 停机前的订单簿、排队先后、账户占用、成交流水与行情进度。
 * 若把两步放在不同的锁里，线程 A 可能先抢到日志锁、线程 B 先抢到引擎锁，
 * 造成"日志里 A 在 B 前、执行上 B 在 A 前"，回放必然分叉——这正是本锁要消除的。
 *
 * <p>命令锁内会做一次文件 IO（WAL append + flush）。撮合引擎自身的锁内
 * 仍然不做 IO；命令锁是编排层串行点，本进程单机模型下吞吐足够，且保证了
 * WAL 与状态的因果一致。
 *
 * <p>读路径：订单 / 成交 / 占用快照直接读引擎与台账（引擎内部自带读锁）。
 *
 * <p>重放：启动时（在线命令进入前）在同一把命令锁内按 WAL 原序重新执行全部命令，
 * 重建订单簿、资金/持仓占用、成交流水与行情期望序号；回放过程不再追加日志。
 */
public final class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final MatchingEngine engine;
    private final EventJournal journal;
    private final MarketDataService marketDataService;
    private final SimulatedMarketFeeder feeder;

    /**
     * 命令线性化锁：WAL append 与引擎执行必须在同一持锁区间内完成。
     * 可重入，便于回放/编排路径复用同一串行化保证。
     */
    private final ReentrantLock commandLock = new ReentrantLock();

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
        commandLock.lock();
        try {
            journal.append(JournalEvent.submit(req));
            SubmitOrderResult result = engine.submit(req);
            log.info("[service] submit clientOrderId={} accepted={} duplicate={} reason={}",
                    req.clientOrderId(), result.accepted(), result.duplicate(), result.rejectReason());
            return result;
        } finally {
            commandLock.unlock();
        }
    }

    public CancelOrderResult cancel(String accountId, String clientOrderId) {
        commandLock.lock();
        try {
            journal.append(JournalEvent.cancel(accountId, clientOrderId));
            CancelOrderResult result = engine.cancel(accountId, clientOrderId);
            log.info("[service] cancel account={} clientOrderId={} success={} duplicate={} reason={}",
                    accountId, clientOrderId, result.success(), result.duplicate(), result.rejectReason());
            return result;
        } finally {
            commandLock.unlock();
        }
    }

    /** 改单入口：命令锁内先持久化、后执行（重放阶段不经过本入口，不重复追加日志）。 */
    public AmendOrderResult amend(AmendOrderRequest req) {
        commandLock.lock();
        try {
            journal.append(JournalEvent.amend(req));
            AmendOrderResult result = engine.amend(req);
            log.info("[service] amend amendId={} account={} clientOrderId={} success={} duplicate={} reason={}",
                    req.amendId(), req.accountId(), req.clientOrderId(),
                    result.success(), result.duplicate(), result.rejectReason());
            return result;
        } finally {
            commandLock.unlock();
        }
    }

    /** 行情入口（由 MarketDataService 回调）：命令锁内先持久化、后撮合。 */
    public TickResult acceptTick(MarketTick tick) {
        commandLock.lock();
        try {
            journal.append(JournalEvent.tick(tick));
            return engine.onTick(tick);
        } finally {
            commandLock.unlock();
        }
    }

    /**
     * 按既有流水回放。必须在接收任何在线命令之前完成；与在线命令共用命令锁，
     * 因此回放期间不会有命令插入，回放结果与停机前逐项一致。
     *
     * @return 回放的命令条数
     */
    public int replay() {
        commandLock.lock();
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
                        TickResult r = engine.onTick(e.tick());
                        marketDataService.resetExpectedSeq(e.tick().symbol(), r.expectedSeq());
                        feeder.syncSeq(e.tick().symbol(), r.expectedSeq());
                        ticks++;
                    }
                }
            }
            log.info("[service] replay done events={} (submits={}, cancels={}, amends={}, ticks={})",
                    events.size(), submits, cancels, amends, ticks);
            return events.size();
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
