package com.github.lisama569.ordermatchingenginejava.tests;

import com.github.lisama569.ordermatchingenginejava.account.AccountLedger;
import com.github.lisama569.ordermatchingenginejava.account.AccountSnapshot;
import com.github.lisama569.ordermatchingenginejava.engine.MatchingEngine;
import com.github.lisama569.ordermatchingenginejava.journal.FileEventJournal;
import com.github.lisama569.ordermatchingenginejava.journal.JournalEvent;
import com.github.lisama569.ordermatchingenginejava.market.MarketDataService;
import com.github.lisama569.ordermatchingenginejava.market.SimulatedMarketFeeder;
import com.github.lisama569.ordermatchingenginejava.model.AmendOrderRequest;
import com.github.lisama569.ordermatchingenginejava.model.AmendOrderResult;
import com.github.lisama569.ordermatchingenginejava.model.CancelOrderResult;
import com.github.lisama569.ordermatchingenginejava.model.Fill;
import com.github.lisama569.ordermatchingenginejava.model.MarketTick;
import com.github.lisama569.ordermatchingenginejava.model.Order;
import com.github.lisama569.ordermatchingenginejava.model.OrderType;
import com.github.lisama569.ordermatchingenginejava.model.RejectReason;
import com.github.lisama569.ordermatchingenginejava.model.Side;
import com.github.lisama569.ordermatchingenginejava.model.SubmitOrderRequest;
import com.github.lisama569.ordermatchingenginejava.model.SubmitOrderResult;
import com.github.lisama569.ordermatchingenginejava.model.TickResult;
import com.github.lisama569.ordermatchingenginejava.service.OrderService;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发回放一致性测试（本次修复新增）。
 *
 * <p>既有 {@link AmendConcurrencyTest} / {@link ConcurrencyTest} 直接打引擎、
 * 没有真正推行情、也没有接"重启回放"；本用例真正通过 OrderService 的写路径
 * （先追加 WAL 后执行）把下单 / 撤单 / 改单 / 行情<b>并发混打</b>进同一份历史，
 * 然后用<b>全新实例</b>按历史回放，逐笔比对：
 * <ul>
 *   <li>成交流水（seq / tickSeq / price / qty / maker / taker）；</li>
 *   <li>每张订单的价、总量、已成交量、状态、入队序号；</li>
 *   <li>买卖盘排队先后（盘口 clientOrderId 序列）；</li>
 *   <li>每个账户-标的的现金 / 持仓 / 冻结 / 敞口（并独立验证占用守恒）；</li>
 *   <li>行情期望序号（行情进度）；</li>
 *   <li>每条命令的接受 / 拒绝 / 幂等结论（按命令多重集聚合）。</li>
 * </ul>
 * 同一份历史再用第二个全新实例回放（验证连续回放稳定），并对第三个空历史实例
 * 走"在线公开入口"重放一遍（验证公开写路径与回放路径结论一致）；
 * 整个用例连跑多轮（每轮独立 WAL 文件），不允许偶发分叉。
 */
class ConcurrentReplayTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrentReplayTest.class);

    private static final String[] SYMBOLS = {"AAPL", "MSFT"};
    private static final String[] ACCOUNTS = {"alice", "bob"};
    private static final int ROUNDS = 12;
    private static final int WORKERS = 10;

    private record OrderView(long orderId, long price, long originalQty, long filledQty,
                             String status, long enqueueSeq) {
        static OrderView of(Order o) {
            return new OrderView(o.getOrderId(), o.getPrice(), o.getOriginalQty(),
                    o.getFilledQty(), o.getStatus().name(), o.getEnqueueSeq());
        }
    }

    private record AccountView(long cashBalance, long cashReserved, long cashAvailable,
                               long position, long positionReserved, long exposure,
                               long exposureLimit) {
        static AccountView of(AccountSnapshot s) {
            return new AccountView(s.cashBalance(), s.cashReserved(), s.cashAvailable(),
                    s.position(), s.positionReserved(), s.exposure(), s.exposureLimit());
        }
    }

    private record Snapshot(List<Fill> fills,
                            Map<String, OrderView> orders,
                            List<String> bidsAAPL, List<String> asksAAPL,
                            List<String> bidsMSFT, List<String> asksMSFT,
                            Map<String, AccountView> accounts,
                            long expectedAAPL, long expectedMSFT) {
    }

    private record Ctx(OrderService service, AccountLedger ledger,
                       MarketDataService market, Path journalFile) {
    }

    // ------------------------------------------------------------------
    // 装配
    // ------------------------------------------------------------------

    private Ctx newSystem(Path journalFile) {
        AccountLedger ledger = new AccountLedger();
        ledger.seedCash("alice", 1_000_000);
        ledger.seedCash("bob", 1_000_000);
        ledger.seedPosition("bob", "AAPL", 10_000);
        ledger.seedPosition("alice", "MSFT", 10_000);
        for (String a : ACCOUNTS) {
            for (String s : SYMBOLS) {
                ledger.setExposureLimit(a, s, 1_000_000);
            }
        }
        MatchingEngine engine = new MatchingEngine(ledger, 1, 1);
        engine.registerSymbol("AAPL");
        engine.registerSymbol("MSFT");
        MarketDataService market = new MarketDataService();
        SimulatedMarketFeeder feeder = new SimulatedMarketFeeder(market);
        OrderService service = new OrderService(
                engine, new FileEventJournal(journalFile), market, feeder);
        return new Ctx(service, ledger, market, journalFile);
    }

    private SubmitOrderRequest limit(String cid, String account, String symbol,
                                     Side side, long price, long qty) {
        return new SubmitOrderRequest(cid, account, symbol, side,
                OrderType.LIMIT, price, null, qty);
    }

    private AmendOrderRequest amend(String aid, String account, String cid,
                                    Long price, Long qty) {
        return new AmendOrderRequest(aid, account, cid, price, qty);
    }

    // ------------------------------------------------------------------
    // 结论规范化
    // ------------------------------------------------------------------

    private String outcome(JournalEvent e, SubmitOrderResult r) {
        return "SUBMIT " + e.submit().clientOrderId()
                + " accepted=" + r.accepted() + " dup=" + r.duplicate()
                + " status=" + r.status() + " reason=" + r.rejectReason();
    }

    private String outcome(JournalEvent e, CancelOrderResult r) {
        return "CANCEL " + e.cancelAccountId() + "|" + e.cancelClientOrderId()
                + " success=" + r.success() + " dup=" + r.duplicate()
                + " status=" + r.status() + " reason=" + r.rejectReason();
    }

    private String outcome(JournalEvent e, AmendOrderResult r) {
        return "AMEND " + e.amend().accountId() + "|" + e.amend().clientOrderId()
                + "|" + e.amend().amendId()
                + " success=" + r.success() + " dup=" + r.duplicate()
                + " status=" + r.status() + " price=" + r.price()
                + " qty=" + r.quantity() + " reason=" + r.rejectReason();
    }

    private String outcome(JournalEvent e, TickResult r) {
        return "TICK " + e.tick().symbol() + "#" + e.tick().seq()
                + " accepted=" + r.accepted() + " dup=" + r.duplicate()
                + " expected=" + r.expectedSeq() + " reason=" + r.rejectReason()
                + " fills=" + r.fills().size();
    }

    /** 引擎直驱（回放路径，不追加 WAL）。 */
    private String runOne(Ctx ctx, JournalEvent e) {
        switch (e.type()) {
            case SUBMIT:
                return outcome(e, ctx.service().engine().submit(e.submit()));
            case CANCEL:
                return outcome(e, ctx.service().engine()
                        .cancel(e.cancelAccountId(), e.cancelClientOrderId()));
            case AMEND:
                return outcome(e, ctx.service().engine().amend(e.amend()));
            case TICK:
                TickResult r = ctx.service().engine().onTick(e.tick());
                ctx.market().resetExpectedSeq(e.tick().symbol(), r.expectedSeq());
                ctx.service().feeder().syncSeq(e.tick().symbol(), r.expectedSeq());
                return outcome(e, r);
            default:
                throw new IllegalStateException("unknown event type");
        }
    }

    /** 在线公开入口（先写自己的 WAL 再执行）。 */
    private String serveOne(Ctx ctx, JournalEvent e) {
        switch (e.type()) {
            case SUBMIT:
                return outcome(e, ctx.service().submit(e.submit()));
            case CANCEL:
                return outcome(e, ctx.service()
                        .cancel(e.cancelAccountId(), e.cancelClientOrderId()));
            case AMEND:
                return outcome(e, ctx.service().amend(e.amend()));
            case TICK:
                return outcome(e, ctx.service().acceptTick(e.tick()));
            default:
                throw new IllegalStateException("unknown event type");
        }
    }

    // ------------------------------------------------------------------
    // 快照 / 比对 / 守恒
    // ------------------------------------------------------------------

    private List<String> trackedCids() {
        List<String> cids = new ArrayList<>(List.of(
                "s-b1", "s-s1", "s-b2", "s-s2", "s-s3",
                "race-buy", "race-sell", "race-ms"));
        for (int i = 0; i < WORKERS; i++) {
            cids.add("w" + i + "-o");
        }
        return cids;
    }

    private Snapshot takeSnapshot(Ctx ctx) {
        MatchingEngine engine = ctx.service().engine();
        Map<String, OrderView> orders = new TreeMap<>();
        for (String cid : trackedCids()) {
            Order o = engine.findByClientOrderId(cid);
            if (o != null) {
                orders.put(cid, OrderView.of(o));
            }
        }
        Map<String, AccountView> accounts = new TreeMap<>();
        for (String a : ACCOUNTS) {
            for (String s : SYMBOLS) {
                accounts.put(a + "|" + s, AccountView.of(ctx.ledger().snapshot(a, s)));
            }
        }
        return new Snapshot(
                engine.fills(),
                orders,
                queueCids(engine, "AAPL", Side.BUY),
                queueCids(engine, "AAPL", Side.SELL),
                queueCids(engine, "MSFT", Side.BUY),
                queueCids(engine, "MSFT", Side.SELL),
                accounts,
                engine.expectedTickSeq("AAPL"),
                engine.expectedTickSeq("MSFT"));
    }

    private List<String> queueCids(MatchingEngine engine, String symbol, Side side) {
        return engine.restingOrders(symbol, side).stream()
                .map(Order::getClientOrderId)
                .toList();
    }

    private void assertSnapshotsEqual(Snapshot want, Snapshot got, String where) {
        assertEquals(want.fills(), got.fills(), where + ": 成交流水逐笔不一致");

        assertEquals(want.orders().keySet(), got.orders().keySet(),
                where + ": 存在的订单集合不一致");
        for (String cid : want.orders().keySet()) {
            assertEquals(want.orders().get(cid), got.orders().get(cid),
                    where + ": 订单 " + cid + " 的价/量/状态/入队序号不一致");
        }

        assertEquals(want.bidsAAPL(), got.bidsAAPL(), where + ": AAPL 买盘排队不一致");
        assertEquals(want.asksAAPL(), got.asksAAPL(), where + ": AAPL 卖盘排队不一致");
        assertEquals(want.bidsMSFT(), got.bidsMSFT(), where + ": MSFT 买盘排队不一致");
        assertEquals(want.asksMSFT(), got.asksMSFT(), where + ": MSFT 卖盘排队不一致");

        assertEquals(want.accounts(), got.accounts(), where + ": 账户台账不一致");
        assertEquals(want.expectedAAPL(), got.expectedAAPL(), where + ": AAPL 行情进度不一致");
        assertEquals(want.expectedMSFT(), got.expectedMSFT(), where + ": MSFT 行情进度不一致");
    }

    /**
     * 占用守恒（独立于回放比对直接在当前实例上验证）：
     * 现金冻结按账户全局统计（台账语义）== Σ该账户全部在簿买单 剩余量×价；
     * 持仓冻结按账户-标的 == Σ在簿卖单剩余量；
     * 敞口按账户-标的 == Σ在簿买单名义 + Σ在簿卖单名义。
     */
    private void assertReservationsExplainable(Ctx ctx, String where) {
        MatchingEngine engine = ctx.service().engine();
        for (String account : ACCOUNTS) {
            long buyCashReserved = 0;
            for (String symbol : SYMBOLS) {
                long buyNotional = 0;
                long sellQty = 0;
                long sellNotional = 0;
                for (Side side : Side.values()) {
                    for (Order o : engine.restingOrders(symbol, side)) {
                        if (!o.getAccountId().equals(account)) {
                            continue;
                        }
                        long rem = o.remainingQty();
                        if (side == Side.BUY) {
                            buyNotional += rem * o.getPrice();
                        } else {
                            sellQty += rem;
                            sellNotional += rem * o.getPrice();
                        }
                    }
                }
                buyCashReserved += buyNotional;
                AccountSnapshot s = ctx.ledger().snapshot(account, symbol);
                assertEquals(sellQty, s.positionReserved(),
                        where + ": " + account + "/" + symbol + " 卖单冻结持仓与在簿需求不符");
                assertEquals(buyNotional + sellNotional, s.exposure(),
                        where + ": " + account + "/" + symbol + " 敞口与在簿名义不符");
                assertTrue(s.exposure() <= s.exposureLimit(),
                        where + ": " + account + "/" + symbol + " 敞口击穿上限");
            }
            long actualCashReserved =
                    ctx.ledger().snapshot(account, SYMBOLS[0]).cashReserved();
            assertEquals(buyCashReserved, actualCashReserved,
                    where + ": " + account + " 买单冻结现金与全部在簿买单需求不符");
        }
    }

    // ------------------------------------------------------------------
    // 阶段 A：单线程基线历史（走公开写路径，会写 WAL）
    // ------------------------------------------------------------------

    private List<JournalEvent> singleThreadedHistory(Ctx ctx, List<String> liveOutcomes) {
        List<JournalEvent> h = new ArrayList<>();

        add(h, liveOutcomes, JournalEvent.submit(
                limit("s-b1", "alice", "AAPL", Side.BUY, 100, 20)), ctx);
        add(h, liveOutcomes, JournalEvent.submit(
                limit("s-s1", "bob", "AAPL", Side.SELL, 100, 8)), ctx);
        add(h, liveOutcomes, JournalEvent.submit(
                limit("s-b2", "alice", "AAPL", Side.BUY, 99, 10)), ctx);
        add(h, liveOutcomes, JournalEvent.submit(
                limit("s-s2", "bob", "AAPL", Side.SELL, 102, 10)), ctx);
        add(h, liveOutcomes, JournalEvent.submit(
                limit("s-s3", "bob", "AAPL", Side.SELL, 100, 5)), ctx);
        // 各类将被拒的改单
        add(h, liveOutcomes, JournalEvent.amend(
                amend("s-bad-owner", "bob", "s-b1", 98L, null)), ctx);
        add(h, liveOutcomes, JournalEvent.amend(
                amend("s-missing", "alice", "nope", 98L, null)), ctx);
        add(h, liveOutcomes, JournalEvent.amend(
                amend("s-nochange", "alice", "s-b2", null, null)), ctx);
        add(h, liveOutcomes, JournalEvent.amend(
                amend("s-badprice", "alice", "s-b2", 97L, null)), ctx);
        add(h, liveOutcomes, JournalEvent.amend(
                amend("s-badqty", "alice", "s-b2", null, 0L)), ctx);
        // 正常改单：只改量保优先级 / 改价重排队 / 同一 amendId 重复
        add(h, liveOutcomes, JournalEvent.amend(
                amend("s-a1", "alice", "s-b2", null, 12L)), ctx);
        add(h, liveOutcomes, JournalEvent.amend(
                amend("s-a2", "bob", "s-s2", 101L, null)), ctx);
        add(h, liveOutcomes, JournalEvent.amend(
                amend("s-a1", "alice", "s-b2", null, 12L)), ctx);
        // 行情：成交、重复、断层、补序成交
        add(h, liveOutcomes, JournalEvent.tick(new MarketTick(1, "AAPL", 100)), ctx);
        add(h, liveOutcomes, JournalEvent.tick(new MarketTick(1, "AAPL", 100)), ctx);
        add(h, liveOutcomes, JournalEvent.tick(new MarketTick(3, "AAPL", 100)), ctx);
        add(h, liveOutcomes, JournalEvent.tick(new MarketTick(2, "AAPL", 100)), ctx);
        // 撤单：成功、重复、非属主、不存在（s-s3 此时已 FILLED => NOT_CANCELLABLE）
        add(h, liveOutcomes, JournalEvent.cancel("bob", "s-s3"), ctx);
        add(h, liveOutcomes, JournalEvent.cancel("bob", "s-s3"), ctx);
        add(h, liveOutcomes, JournalEvent.cancel("alice", "s-s1"), ctx);
        add(h, liveOutcomes, JournalEvent.cancel("alice", "missing-cid"), ctx);
        // MSFT 基线：只有卖单挂着，行情推进但不成交
        add(h, liveOutcomes, JournalEvent.submit(
                limit("race-ms", "alice", "MSFT", Side.SELL, 200, 6)), ctx);
        add(h, liveOutcomes, JournalEvent.tick(new MarketTick(1, "MSFT", 200)), ctx);
        // 公共竞争单：所有工作线程随后用不同 amendId 并发改这两张单
        add(h, liveOutcomes, JournalEvent.submit(
                limit("race-buy", "alice", "AAPL", Side.BUY, 100, 10)), ctx);
        add(h, liveOutcomes, JournalEvent.submit(
                limit("race-sell", "bob", "AAPL", Side.SELL, 100, 10)), ctx);
        return h;
    }

    private void add(List<JournalEvent> h, List<String> outcomes,
                     JournalEvent e, Ctx ctx) {
        h.add(e);
        outcomes.add(serveOne(ctx, e));
    }

    // ------------------------------------------------------------------
    // 阶段 B：并发混打
    // ------------------------------------------------------------------

    /**
     * 命令结果收集器：在线阶段每条命令执行后记录其规范化结论。
     * 按"命令身份"分组保留全部结论（含重复提交的首次/幂等两条），
     * 与回放阶段按 WAL 位置切分后的多重集做比较。
     */
    private final class ConcurrentRun {
        final List<JournalEvent> submitted =
                java.util.Collections.synchronizedList(new ArrayList<>());
        final Map<String, List<String>> outcomes = new ConcurrentHashMap<>();

        void record(JournalEvent e, String outcome) {
            outcomes.computeIfAbsent(baseKey(e), k ->
                    java.util.Collections.synchronizedList(new ArrayList<>())).add(outcome);
            submitted.add(e);
        }
    }

    private void submitThroughService(Ctx ctx, ConcurrentRun run, JournalEvent e) {
        String result = switch (e.type()) {
            case SUBMIT -> outcome(e, ctx.service().submit(e.submit()));
            case CANCEL -> outcome(e, ctx.service()
                    .cancel(e.cancelAccountId(), e.cancelClientOrderId()));
            case AMEND -> outcome(e, ctx.service().amend(e.amend()));
            case TICK -> outcome(e, ctx.service().acceptTick(e.tick()));
        };
        run.record(e, result);
    }

    private List<Runnable> concurrentTasks(Ctx ctx, ConcurrentRun run) {
        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < WORKERS; i++) {
            int wi = i;
            boolean alice = wi % 2 == 0;
            String account = alice ? "alice" : "bob";
            Side side = alice ? Side.BUY : Side.SELL;
            long ownPrice = 100;
            tasks.add(() -> {
                // 1) 自己的新单（alice 买 / bob 卖），同一 clientOrderId 重复提交竞争
                SubmitOrderRequest own = limit("w" + wi + "-o", account, "AAPL",
                        side, ownPrice, 4);
                submitThroughService(ctx, run, JournalEvent.submit(own));
                submitThroughService(ctx, run, JournalEvent.submit(own));
                // 2) 改自己的单：只改量保排队
                submitThroughService(ctx, run, JournalEvent.amend(
                        amend("own-qty-" + wi, account, "w" + wi + "-o", null, 8L)));
                // 3) 并发改价打在公共挂单上（不同 amendId；打在同一张单上）
                String target = alice ? "race-buy" : "race-sell";
                submitThroughService(ctx, run, JournalEvent.amend(
                        amend("race-price-" + wi, account, target,
                                alice ? 98L + (wi % 4) : 100L + (wi % 3), null)));
                // 4) 并发只改量打在同一张单上
                submitThroughService(ctx, run, JournalEvent.amend(
                        amend("race-qty-" + wi, account, target,
                                null, 6L + (wi % 5) * 2L)));
                // 5) 并发同时改价改量打在相邻挂单上
                String neighbour = alice ? "race-sell" : "race-buy";
                submitThroughService(ctx, run, JournalEvent.amend(
                        amend("cross-both-" + wi, account, neighbour,
                                alice ? 100L + (wi % 2) : 100L - (wi % 2),
                                9L)));
                // 6) 撤自己的单 + 重复撤竞争（可能已被行情成交 => NOT_CANCELLABLE）
                submitThroughService(ctx, run,
                        JournalEvent.cancel(account, "w" + wi + "-o"));
                submitThroughService(ctx, run,
                        JournalEvent.cancel(account, "w" + wi + "-o"));
                // 7) 已被叫停的命令：任何交错下都不得改动状态
                submitThroughService(ctx, run, JournalEvent.amend(
                        amend("dead-" + wi, account, "s-s1", 100L, null)));
                submitThroughService(ctx, run, JournalEvent.amend(
                        amend("ghost-" + wi, account, "no-such-order", 100L, null)));
                submitThroughService(ctx, run, JournalEvent.amend(
                        amend("badprice-" + wi, account, target, -7L, null)));
            });
        }
        // 行情驱动线程：真正推行情，与命令线程交错
        tasks.add(() -> {
            for (long seq = 3; seq <= 10; seq++) {
                submitThroughService(ctx, run, JournalEvent.tick(
                        new MarketTick(seq, "AAPL", (seq % 2 == 0) ? 100 : 99)));
            }
            // seq=3 的重复行情：与任何命令交错都必须稳定判重
            submitThroughService(ctx, run, JournalEvent.tick(
                    new MarketTick(3, "AAPL", 99)));
            for (long seq = 2; seq <= 6; seq++) {
                submitThroughService(ctx, run, JournalEvent.tick(
                        new MarketTick(seq, "MSFT", (seq % 2 == 0) ? 200 : 199)));
            }
        });
        return tasks;
    }

    // ------------------------------------------------------------------
    // 命令键 / 结论聚合
    // ------------------------------------------------------------------

    private String baseKey(JournalEvent e) {
        return switch (e.type()) {
            case SUBMIT -> "SUBMIT|" + e.submit().clientOrderId();
            case CANCEL -> "CANCEL|" + e.cancelAccountId() + "|" + e.cancelClientOrderId();
            case AMEND -> "AMEND|" + e.amend().accountId() + "|"
                    + e.amend().clientOrderId() + "|" + e.amend().amendId();
            case TICK -> "TICK|" + e.tick().symbol() + "|" + e.tick().seq();
        };
    }

    /** 跳过阶段 A 的命令，按命令身份分组回放结论。 */
    private Map<String, List<String>> groupedOutcomes(List<JournalEvent> events,
                                                      List<String> outcomes, int skip) {
        Map<String, List<String>> result = new TreeMap<>();
        for (int i = skip; i < events.size(); i++) {
            result.computeIfAbsent(baseKey(events.get(i)), k -> new ArrayList<>())
                    .add(outcomes.get(i));
        }
        return result;
    }

    private String canonical(JournalEvent e) {
        return switch (e.type()) {
            case SUBMIT -> "SUBMIT " + e.submit();
            case CANCEL -> "CANCEL " + e.cancelAccountId() + " " + e.cancelClientOrderId();
            case AMEND -> "AMEND " + e.amend();
            case TICK -> "TICK " + e.tick();
        };
    }

    // ------------------------------------------------------------------
    // 主用例
    // ------------------------------------------------------------------

    @Test
    void concurrentMixedCommandsReplayIdenticallyAcrossRestarts() throws Exception {
        Path root = Files.createTempDirectory("concurrent-replay-");
        for (int round = 0; round < ROUNDS; round++) {
            Path liveJournal = root.resolve("live-" + round + ".jsonl");
            Files.deleteIfExists(liveJournal);

            // ===== 在线实例：阶段 A（单线程基线）=====
            Ctx live = newSystem(liveJournal);
            List<String> liveSingle = new ArrayList<>();
            List<JournalEvent> phaseA = singleThreadedHistory(live, liveSingle);

            // ===== 在线实例：阶段 B（并发混打）=====
            ConcurrentRun run = new ConcurrentRun();
            List<Runnable> tasks = concurrentTasks(live, run);
            ExecutorService pool = Executors.newFixedThreadPool(WORKERS + 1);
            CountDownLatch start = new CountDownLatch(1);
            for (Runnable task : tasks) {
                pool.submit(() -> {
                    try {
                        start.await();
                        task.run();
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS),
                    "并发命令必须在超时前全部完成");

            Snapshot liveSnapshot = takeSnapshot(live);
            assertReservationsExplainable(live, "round " + round + " live");

            // 权威历史以 WAL 文件为准（重启时读到什么就回放什么）
            List<JournalEvent> wal = new FileEventJournal(liveJournal).readAll();
            int submittedCount = phaseA.size() + run.submitted.size();
            assertEquals(submittedCount, wal.size(),
                    "WAL 条数应等于全部提交条数（含被拒命令）");

            // ===== 回放实例 1：全新实例引擎直驱回放 =====
            Ctx r1 = newSystem(root.resolve("replay1-" + round + ".jsonl"));
            List<String> out1 = new ArrayList<>();
            for (JournalEvent e : wal) {
                out1.add(runOne(r1, e));
            }
            Snapshot snap1 = takeSnapshot(r1);
            assertSnapshotsEqual(liveSnapshot, snap1, "round " + round + " replay#1");
            assertReservationsExplainable(r1, "round " + round + " replay#1");
            for (int i = 0; i < phaseA.size(); i++) {
                assertEquals(liveSingle.get(i), out1.get(i),
                        "round " + round + ": 阶段A命令 " + i + " 的回放结论分叉");
            }

            // ===== 回放实例 2：同一份历史再回放一次，必须稳定 =====
            Ctx r2 = newSystem(root.resolve("replay2-" + round + ".jsonl"));
            List<String> out2 = new ArrayList<>();
            for (JournalEvent e : wal) {
                out2.add(runOne(r2, e));
            }
            Snapshot snap2 = takeSnapshot(r2);
            assertSnapshotsEqual(liveSnapshot, snap2, "round " + round + " replay#2");
            assertReservationsExplainable(r2, "round " + round + " replay#2");
            assertEquals(out1, out2, "round " + round + ": 同一份历史连续回放结论必须稳定");

            // 并发阶段结论：按命令身份分组的结论多重集必须一致
            // （"谁最后生效"由 WAL 线性序唯一确定，故每条命令的结论集合确定）
            Map<String, List<String>> replayPhaseB =
                    groupedOutcomes(wal, out1, phaseA.size());
            assertEquals(new TreeSet<>(run.outcomes.keySet()),
                    new TreeSet<>(replayPhaseB.keySet()),
                    "round " + round + ": 并发命令身份集合不一致");
            for (String key : run.outcomes.keySet()) {
                List<String> liveSorted = new ArrayList<>(run.outcomes.get(key));
                List<String> replaySorted = new ArrayList<>(replayPhaseB.get(key));
                liveSorted.sort(Comparator.naturalOrder());
                replaySorted.sort(Comparator.naturalOrder());
                assertEquals(liveSorted, replaySorted,
                        "round " + round + ": 命令 " + key + " 的结论多重集不一致（并发回放分叉）");
            }

            // ===== 回放实例 3：空历史新实例走公开写路径重放 =====
            Path journal3 = root.resolve("replay3-" + round + ".jsonl");
            Ctx r3 = newSystem(journal3);
            List<String> out3 = new ArrayList<>();
            for (JournalEvent e : wal) {
                out3.add(serveOne(r3, e));
            }
            Snapshot snap3 = takeSnapshot(r3);
            assertSnapshotsEqual(liveSnapshot, snap3, "round " + round + " replay#3(public)");
            assertReservationsExplainable(r3, "round " + round + " replay#3(public)");
            assertEquals(out1, out3,
                    "round " + round + ": 引擎回放与公开写路径重放结论不一致");
            List<JournalEvent> wal3 = new FileEventJournal(journal3).readAll();
            assertEquals(wal.size(), wal3.size(),
                    "round " + round + ": 重放产生的 WAL 条数不一致");
            for (int i = 0; i < wal.size(); i++) {
                assertEquals(canonical(wal.get(i)), canonical(wal3.get(i)),
                        "round " + round + ": 重放产生的 WAL 与原历史不一致 @" + i);
            }

            // 回放后行情可继续推进且重复判定稳定
            long next = snap2.expectedAAPL();
            TickResult cont = r2.service().acceptTick(new MarketTick(next, "AAPL", 100));
            TickResult contDup = r2.service().acceptTick(new MarketTick(next, "AAPL", 100));
            assertTrue(cont.accepted(), "回放后下一条行情应被接受");
            assertEquals(RejectReason.TICK_DUPLICATE, contDup.rejectReason());
            // 直接接受的行情也要把模拟行情源的本地序号对齐（与真实部署里 tick 均经 feeder 一致）
            r2.service().feeder().syncSeq("AAPL", next + 1);

            // 模拟行情源的本地序号已在回放后对齐：feedRandomWalk 产生的行情必须全部被接受，
            // 而不是因序号回退被判 TICK_OUT_OF_ORDER/TICK_DUPLICATE（旧实现会从 1 重新编号）
            List<TickResult> walkResults =
                    r2.service().feeder().feedRandomWalkWithResults("AAPL", 100, 2, 2);
            assertEquals(2, walkResults.size());
            for (int wi2 = 0; wi2 < walkResults.size(); wi2++) {
                TickResult wr = walkResults.get(wi2);
                assertTrue(wr.accepted(),
                        "回放后 feeder 第 " + wi2 + " 条行情必须被接受，实际拒因=" + wr.rejectReason());
                assertTrue(wr.receivedSeq() >= next + 1,
                        "模拟行情序号应在回放进度之后继续");
            }
            // 再喂更早的序号必须被拒绝（判重/乱序均可），证明进度确实已推进
            TickResult walkDup = r2.service().feeder().feed(
                    new MarketTick(walkResults.get(0).receivedSeq(), "AAPL", 100));
            assertTrue(!walkDup.accepted()
                            && (walkDup.rejectReason() == RejectReason.TICK_DUPLICATE
                            || walkDup.rejectReason() == RejectReason.TICK_OUT_OF_ORDER),
                    "feeder 回放后序号应对齐：旧序号必须被拒绝，实际=" + walkDup.rejectReason());

            // OrderService.replay() 自身也必须把 feeder 本地序号对齐（重启后自动行情的入口）：
            // 把同一份 WAL 复制到新实例的路径，走真实的 OrderService.replay() 启动回放路径，
            // 然后用"本地计数生成序号"的 feedRandomWalk 推行情。
            // 旧实现回放后 feeder 会从 1 重新编号，生成的行情序号落在引擎期望序号之后而被拒。
            Path journal4 = root.resolve("replay4-" + round + ".jsonl");
            Files.copy(liveJournal, journal4, StandardCopyOption.REPLACE_EXISTING);
            Ctx r4 = newSystem(journal4);
            int replayedCount = r4.service().replay();
            assertEquals(wal.size(), replayedCount);
            long expAAPL = r4.service().engine().expectedTickSeq("AAPL");
            assertTrue(expAAPL > 1, "回放后 AAPL 行情进度应已恢复，实际=" + expAAPL);
            List<TickResult> autoWalk =
                    r4.service().feeder().feedRandomWalkWithResults("AAPL", 100, 1, 2);
            assertEquals(expAAPL, autoWalk.get(0).receivedSeq(),
                    "feeder 回放后生成的首条 AAPL 行情序号必须等于引擎期望序号");
            // 该行情必须已被引擎消费：再喂同序号应判重复
            TickResult autoVerdict = r4.service().engine().onTick(
                    new MarketTick(autoWalk.get(0).receivedSeq(), "AAPL", 100));
            assertEquals(RejectReason.TICK_DUPLICATE, autoVerdict.rejectReason(),
                    "feeder 首条行情必须已被引擎消费（再喂同序号应判重复）");
            // MSFT 进度也应对齐
            long expMSFT = r4.service().engine().expectedTickSeq("MSFT");
            assertTrue(expMSFT > 1, "回放后 MSFT 行情进度应已恢复，实际=" + expMSFT);
            List<TickResult> autoWalkMsft =
                    r4.service().feeder().feedRandomWalkWithResults("MSFT", 200, 1, 2);
            assertEquals(expMSFT, autoWalkMsft.get(0).receivedSeq(),
                    "feeder 回放后生成的首条 MSFT 行情序号必须等于引擎期望序号");

            OrderView rb = liveSnapshot.orders().get("race-buy");
            OrderView rs = liveSnapshot.orders().get("race-sell");
            log.info("[round {}] {} 条命令并发混打后重启回放：成交={} AAPL买盘={} AAPL卖盘={} "
                            + "race-buy(p={},q={},enq={}) race-sell(p={},q={},enq={}) 三轮回放逐项一致",
                    round, wal.size(), liveSnapshot.fills().size(),
                    liveSnapshot.bidsAAPL(), liveSnapshot.asksAAPL(),
                    rb.price(), rb.originalQty(), rb.enqueueSeq(),
                    rs.price(), rs.originalQty(), rs.enqueueSeq());
        }
    }
}
