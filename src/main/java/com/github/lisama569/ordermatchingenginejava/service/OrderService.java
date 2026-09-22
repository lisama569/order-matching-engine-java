package com.github.lisama569.ordermatchingenginejava.service;

import com.github.lisama569.ordermatchingenginejava.account.AccountLedger;
import com.github.lisama569.ordermatchingenginejava.account.AccountSnapshot;
import com.github.lisama569.ordermatchingenginejava.engine.MatchingEngine;
import com.github.lisama569.ordermatchingenginejava.journal.EventJournal;
import com.github.lisama569.ordermatchingenginejava.journal.JournalEvent;
import com.github.lisama569.ordermatchingenginejava.market.MarketDataService;
import com.github.lisama569.ordermatchingenginejava.market.SimulatedMarketFeeder;
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

/**
 * 应用编排层。
 *
 * <p>写路径（在线）：命令<b>先追加 WAL、后执行</b>（write-ahead）。
 * 重复提交 / 非法行情等会被幂等表或序号校验拦截，重放时产生完全相同的拦截结论，
 * 因此"先写日志再拒绝"不会改变任何状态，保证重放确定性。
 *
 * <p>读路径：订单 / 成交 / 占用快照直接读引擎与台账。
 *
 * <p>重放：启动时按 WAL 原序重新执行全部命令，重建订单簿、资金/持仓占用、
 * 成交流水与行情期望序号；回放过程不再追加日志。
 */
public final class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final MatchingEngine engine;
    private final EventJournal journal;
    private final MarketDataService marketDataService;
    private final SimulatedMarketFeeder feeder;
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
        journal.append(JournalEvent.submit(req));
        SubmitOrderResult result = engine.submit(req);
        log.info("[service] submit clientOrderId={} accepted={} duplicate={} reason={}",
                req.clientOrderId(), result.accepted(), result.duplicate(), result.rejectReason());
        return result;
    }

    public CancelOrderResult cancel(String accountId, String clientOrderId) {
        journal.append(JournalEvent.cancel(accountId, clientOrderId));
        CancelOrderResult result = engine.cancel(accountId, clientOrderId);
        log.info("[service] cancel account={} clientOrderId={} success={} duplicate={} reason={}",
                accountId, clientOrderId, result.success(), result.duplicate(), result.rejectReason());
        return result;
    }

    /** 行情入口（由 MarketDataService 回调）：先持久化、后撮合。 */
    public TickResult acceptTick(MarketTick tick) {
        if (!replaying) {
            journal.append(JournalEvent.tick(tick));
        }
        return engine.onTick(tick);
    }

    /**
     * 按既有流水回放。必须在接收任何在线命令之前完成。
     *
     * @return 回放的命令条数
     */
    public int replay() {
        if (replaying) {
            throw new IllegalStateException("replay already in progress");
        }
        replaying = true;
        try {
            List<JournalEvent> events = journal.readAll();
            int submits = 0, cancels = 0, ticks = 0;
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
                    case TICK -> {
                        TickResult r = engine.onTick(e.tick());
                        marketDataService.resetExpectedSeq(e.tick().symbol(), r.expectedSeq());
                        ticks++;
                    }
                }
            }
            log.info("[service] replay done events={} (submits={}, cancels={}, ticks={})",
                    events.size(), submits, cancels, ticks);
            return events.size();
        } finally {
            replaying = false;
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
