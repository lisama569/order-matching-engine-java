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
import com.github.lisama569.ordermatchingenginejava.model.Side;
import com.github.lisama569.ordermatchingenginejava.model.SubmitOrderRequest;
import com.github.lisama569.ordermatchingenginejava.model.SubmitOrderResult;
import com.github.lisama569.ordermatchingenginejava.model.TickResult;
import com.github.lisama569.ordermatchingenginejava.service.OrderService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发混打 + 重启回放一致性测试（回归 bugfix-b）。
 *
 * <p>旧实现里 OrderService 的"追加 WAL"与"应用到引擎"是两步、没有共同的锁：
 * 引擎内部锁只串行化执行，无法阻止两个线程在 追加↔执行 之间互相穿插，
 * 于是并发下可能出现 A 先落盘却后生效、B 后落盘却先生效。在线状态按执行顺序演化，
 * 而重启严格按 WAL 顺序回放，两边必然分叉（并发改价重排队时尤其明显）。
 *
 * <p>本用例真实地用多线程把 下单 / 撤单 / 改单 / 行情 同时打进同一份历史，
 * 再用一个全新实例按同一份 WAL 回放，逐项比对：
 * 成交流水、每张订单的价/量/状态/入队序号、盘口排队先后、双方账户占用、
 * 行情期望序号；并把 WAL 逐条喂给两个独立引擎比对每条命令的接受/拒绝/幂等结论。
 * 同一用例连跑多轮，且对同一份 WAL 连续回放多遍，结果都必须稳定一致。
 */
class ConcurrentReplayConsistencyTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrentReplayConsistencyTest.class);

    private static final String[] SYMBOLS = {"AAPL", "MSFT"};
    private static final String[] ACCOUNTS = {"alice", "bob", "carol", "dave"};
    private static final long EXPOSURE_LIMIT = 200_000;

    @TempDir
    Path dir;

    private record Ctx(OrderService service, AccountLedger ledger,
                       MarketDataService market, MatchingEngine engine) {
    }

    private Ctx newSystem(Path journalFile) {
        AccountLedger ledger = new AccountLedger();
        for (String a : ACCOUNTS) {
            ledger.seedCash(a, 500_000);
            for (String s : SYMBOLS) {
                ledger.setExposureLimit(a, s, EXPOSURE_LIMIT);
                ledger.seedPosition(a, s, 100_000);
            }
        }
        // 一个额度极紧的账户：并发下单在此必然确定性触发 EXPOSURE_LIMIT_EXCEEDED
        ledger.seedCash("poor", 1_000);
        for (String s : SYMBOLS) {
            ledger.setExposureLimit("poor", s, 500);
        }
        MatchingEngine engine = new MatchingEngine(ledger, 1, 1);
        for (String s : SYMBOLS) {
            engine.registerSymbol(s);
        }
        MarketDataService market = new MarketDataService();
        SimulatedMarketFeeder feeder = new SimulatedMarketFeeder(market);
        OrderService service = new OrderService(engine, new FileEventJournal(journalFile), market, feeder);
        return new Ctx(service, ledger, market, engine);
    }

    private static SubmitOrderRequest limit(String cid, String account, String symbol,
                                            Side side, long price, long qty) {
        return new SubmitOrderRequest(cid, account, symbol, side, OrderType.LIMIT, price, null, qty);
    }

    /** 行情价按 seq 确定性生成，在线与回放一致；在 100 附近游走以制造/中断成交。 */
    private static long tickPrice(long seq) {
        long p = 100 + ((seq % 5) - 2); // 98..102
        return p;
    }

    private enum Kind { SUBMIT, CANCEL, AMEND, TICK }

    private record Task(Kind kind, Object payload) {
    }

    @Test
    void concurrentMixedCommandsReplayIdenticallyAcrossRuns() throws Exception {
        int runs = 5;
        for (int run = 1; run <= runs; run++) {
            Path journalFile = dir.resolve("mixed-" + run + ".jsonl");
            runOneScenario(run, journalFile);
        }
        log.info("[case] 并发混打+重启回放 连跑 {} 轮全部逐项一致", runs);
    }

    private void runOneScenario(int run, Path journalFile) throws Exception {
        Ctx live = newSystem(journalFile);
        OrderService s1 = live.service();

        // ---- 先单线程铺一批底单（建立可被并发改/撤/成交的在簿订单）----
        List<String> resting = new ArrayList<>();
        long cid = 0;
        // 每个标的每个账户：买单两档（100/99），卖单两档（100/101）
        for (String symbol : SYMBOLS) {
            for (String a : ACCOUNTS) {
                String b1 = "base-" + (cid++);
                s1.submit(limit(b1, a, symbol, Side.BUY, 100, 10));
                resting.add(b1);
                String b2 = "base-" + (cid++);
                s1.submit(limit(b2, a, symbol, Side.BUY, 99, 10));
                resting.add(b2);
                String k1 = "base-" + (cid++);
                s1.submit(limit(k1, a, symbol, Side.SELL, 100, 10));
                resting.add(k1);
                String k2 = "base-" + (cid++);
                s1.submit(limit(k2, a, symbol, Side.SELL, 101, 10));
                resting.add(k2);
            }
        }

        // ---- 组装确定的并发任务集（每个 run 的种子固定，保证可重复）----
        List<Task> tasks = buildConcurrentTasks(run, resting);
        int tickTasks = (int) tasks.stream().filter(t -> t.kind == Kind.TICK).count();
        log.info("[stage1 run={}] 底单={} 并发任务={} (其中行情={})",
                run, resting.size(), tasks.size(), tickTasks);

        // ---- 真正并发：所有线程在同一栅栏后同时开跑 ----
        int workers = 12;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(tasks.size());
        for (Task t : tasks) {
            pool.submit(() -> {
                try {
                    start.await();
                    runTask(s1, t);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS), "并发任务应在超时前全部完成");
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        List<JournalEvent> wal = s1.journal().readAll();
        log.info("[stage1 run={}] 全部命令落盘 {} 条；在线成交={} 笔",
                run, wal.size(), s1.fills().size());

        // ---- 第一次回放：全新实例按同一份 WAL 重建，逐项比对最终状态 ----
        Ctx replay1 = newSystem(journalFile);
        int replayed = replay1.service().replay();
        assertEquals(wal.size(), replayed);
        compareFinalState("replay1", live, replay1);

        // ---- 第二次回放：同一份历史再放一遍，必须与第一次、在线三方一致 ----
        Ctx replay2 = newSystem(journalFile);
        replay2.service().replay();
        compareFinalState("replay2", live, replay2);

        // ---- 逐条命令比对决策：两个全新引擎按 WAL 原序重放，每条结论必须相同 ----
        comparePerEventDecisions(wal);

        log.info("[stage2 run={}] 回放×2 与停机前逐项一致，且逐命令决策一致（fills={}）",
                run, live.service().fills().size());
    }

    private void runTask(OrderService s, Task t) {
        switch (t.kind) {
            case SUBMIT -> s.submit((SubmitOrderRequest) t.payload());
            case CANCEL -> {
                String[] ac = (String[]) t.payload();
                s.cancel(ac[0], ac[1]);
            }
            case AMEND -> s.amend((AmendOrderRequest) t.payload());
            case TICK -> s.acceptTick((MarketTick) t.payload());
        }
    }

    private List<Task> buildConcurrentTasks(int run, List<String> resting) {
        Random rnd = new Random(9527L * run + 31);
        List<Task> tasks = new ArrayList<>();

        // 1) 对同一张挂单：多笔不同 amendId 的并发改价（打在同一张单 / 相邻单上），
        //    谁最后生效由命令锁顺序唯一决定，回放必须复现同一最终价与排队先后。
        for (int i = 0; i < 32; i++) {
            String target = resting.get(rnd.nextInt(resting.size()));
            String account = accountOfBase(target);
            long[] prices = {98, 99, 100, 101, 102};
            long price = prices[rnd.nextInt(prices.length)];
            tasks.add(new Task(Kind.AMEND,
                    new AmendOrderRequest("amend-price-" + i, account, target, price, null)));
        }
        // 2) 并发只改量（保留排队先后）
        for (int i = 0; i < 24; i++) {
            String target = resting.get(rnd.nextInt(resting.size()));
            String account = accountOfBase(target);
            long[] qtys = {8, 12, 16, 20};
            tasks.add(new Task(Kind.AMEND,
                    new AmendOrderRequest("amend-qty-" + i, account, target, null, qtys[rnd.nextInt(qtys.length)])));
        }
        // 3) 并发既改价又改量
        for (int i = 0; i < 16; i++) {
            String target = resting.get(rnd.nextInt(resting.size()));
            String account = accountOfBase(target);
            tasks.add(new Task(Kind.AMEND,
                    new AmendOrderRequest("amend-both-" + i, account, target,
                            98L + rnd.nextInt(5), (long) (6 + 2 * rnd.nextInt(8)))));
        }
        // 4) 同一 amendId 被多个任务并发重复提交（恰好生效一次）
        for (int i = 0; i < 8; i++) {
            String target = resting.get(rnd.nextInt(resting.size()));
            String account = accountOfBase(target);
            AmendOrderRequest dup = new AmendOrderRequest("amend-dup-" + i, account, target, 100L, 14L);
            tasks.add(new Task(Kind.AMEND, dup));
            tasks.add(new Task(Kind.AMEND, dup));
        }
        // 5) 必被拒绝的改单：不存在 / 非属主 / 已终结由随机性触达；这里固定构造非法价量与无变化
        for (int i = 0; i < 8; i++) {
            String target = resting.get(rnd.nextInt(resting.size()));
            String account = accountOfBase(target);
            tasks.add(new Task(Kind.AMEND,
                    new AmendOrderRequest("amend-badprice-" + i, account, target, 7L, null))); // 非刻度
            tasks.add(new Task(Kind.AMEND,
                    new AmendOrderRequest("amend-noop-" + i, account, target, null, null)));  // 无变化
            tasks.add(new Task(Kind.AMEND,
                    new AmendOrderRequest("amend-ghost-" + i, account, "no-such-order", 100L, 5L)));
        }
        // 6) 并发撤单（含对同一单重复撤、撤不存在、非属主撤）
        for (int i = 0; i < 24; i++) {
            String target = resting.get(rnd.nextInt(resting.size()));
            tasks.add(new Task(Kind.CANCEL, new String[]{accountOfBase(target), target}));
        }
        for (int i = 0; i < 8; i++) {
            String target = resting.get(rnd.nextInt(resting.size()));
            tasks.add(new Task(Kind.CANCEL, new String[]{accountOfBase(target), target})); // 重复撤
            tasks.add(new Task(Kind.CANCEL, new String[]{"alice", "ghost-" + i}));        // 不存在
            String other = accountOfBase(target).equals("alice") ? "bob" : "alice";
            tasks.add(new Task(Kind.CANCEL, new String[]{other, target}));                // 非属主
        }
        // 7) 并发新下单（合法、含被额度拒绝的 poor 账户、含重复 clientOrderId）
        for (int i = 0; i < 48; i++) {
            String symbol = SYMBOLS[rnd.nextInt(SYMBOLS.length)];
            String a = ACCOUNTS[rnd.nextInt(ACCOUNTS.length)];
            Side side = rnd.nextBoolean() ? Side.BUY : Side.SELL;
            long price = 98 + rnd.nextInt(5);
            long qty = 5 + rnd.nextInt(10);
            tasks.add(new Task(Kind.SUBMIT,
                    limit("new-" + i, a, symbol, side, price, qty)));
        }
        for (int i = 0; i < 6; i++) {
            SubmitOrderRequest poor = limit("poor-" + i, "poor", SYMBOLS[i % 2], Side.BUY, 100, 10);
            tasks.add(new Task(Kind.SUBMIT, poor)); // 10*100=1000 > 500 上限 => 确定性拒绝
        }
        for (int i = 0; i < 8; i++) {
            SubmitOrderRequest dup = limit("dup-order-" + i, "carol", SYMBOLS[i % 2], Side.BUY, 100, 8);
            tasks.add(new Task(Kind.SUBMIT, dup));
            tasks.add(new Task(Kind.SUBMIT, dup)); // 并发重复提交恰好占用一次
        }
        // 8) 真正并发地推行情：每个标的一个专门线程风格的连续 seq 任务流，
        //    与上面的命令线程真正同时运行，触发真实成交、部分成交与改价后排队效果。
        for (String symbol : SYMBOLS) {
            for (long seq = 1; seq <= 40; seq++) {
                tasks.add(new Task(Kind.TICK, new MarketTick(seq, symbol, tickPrice(seq))));
            }
        }
        // 打乱任务派发顺序（命令内容仍确定），加大交错强度
        java.util.Collections.shuffle(tasks, rnd);
        return tasks;
    }

    private static String accountOfBase(String baseId) {
        // 底单按下标顺序每 4 张属于一个账户：见铺底循环
        long n = Long.parseLong(baseId.substring("base-".length()));
        int block = (int) ((n / 4) % ACCOUNTS.length);
        return ACCOUNTS[block];
    }

    // ---- 最终状态逐项比对 ----

    private void compareFinalState(String tag, Ctx live, Ctx replay) {
        OrderService s1 = live.service();
        OrderService s2 = replay.service();

        // 成交流水逐条一致（seq/tickSeq/价/量/maker/taker 内部单号）
        List<Fill> f1 = s1.fills();
        List<Fill> f2 = s2.fills();
        assertEquals(f1.size(), f2.size(), tag + " fill count");
        for (int i = 0; i < f1.size(); i++) {
            assertEquals(f1.get(i), f2.get(i), tag + " fill " + i + " 不一致");
        }

        // 订单簿：每个标的每个方向的排队先后一致，且每张订单字段一致
        for (String symbol : SYMBOLS) {
            for (Side side : Side.values()) {
                List<String> q1 = s1.engine().restingClientOrderIds(symbol, side);
                List<String> q2 = s2.engine().restingClientOrderIds(symbol, side);
                assertEquals(q1, q2, tag + " 盘口排队顺序不一致 " + symbol + " " + side);
            }
        }
        List<JournalEvent> wal = s1.journal().readAll();
        for (JournalEvent e : wal) {
            if (e.type() == com.github.lisama569.ordermatchingenginejava.journal.EventType.SUBMIT) {
                String cid = e.submit().clientOrderId();
                Order o1 = s1.getOrder(cid);
                Order o2 = s2.getOrder(cid);
                assertEquals(o1 == null, o2 == null, tag + " order presence " + cid);
                if (o1 != null) {
                    assertEquals(o1.getPrice(), o2.getPrice(), tag + " price " + cid);
                    assertEquals(o1.getOriginalQty(), o2.getOriginalQty(), tag + " qty " + cid);
                    assertEquals(o1.getFilledQty(), o2.getFilledQty(), tag + " filled " + cid);
                    assertEquals(o1.getStatus(), o2.getStatus(), tag + " status " + cid);
                    assertEquals(o1.getEnqueueSeq(), o2.getEnqueueSeq(),
                            tag + " 入队序号（改价重排队）" + cid);
                    assertEquals(o1.getOrderId(), o2.getOrderId(), tag + " 内部订单号 " + cid);
                }
            }
        }

        // 台账：每个账户-标的的现金/持仓/冻结/可用/敞口/上限逐项一致
        for (String a : ACCOUNTS) {
            for (String symbol : SYMBOLS) {
                assertSnapshotEquals(tag + " " + a + "/" + symbol,
                        live.ledger().snapshot(a, symbol),
                        replay.ledger().snapshot(a, symbol));
            }
        }
        assertSnapshotEquals(tag + " poor/AAPL",
                live.ledger().snapshot("poor", "AAPL"),
                replay.ledger().snapshot("poor", "AAPL"));

        // 行情进度：每个标的的期望序号一致，且下一条 seq 在两边同为重复
        for (String symbol : SYMBOLS) {
            long e1 = live.market().expectedSeq(symbol);
            long e2 = replay.market().expectedSeq(symbol);
            assertEquals(e1, e2, tag + " 行情期望序号 " + symbol);
            TickResult d1 = s1.acceptTick(new MarketTick(e1 - 1, symbol, 100));
            TickResult d2 = s2.acceptTick(new MarketTick(e2 - 1, symbol, 100));
            assertEquals(d1.rejectReason(), d2.rejectReason(), tag + " 进度后重复判定 " + symbol);
        }
    }

    private static void assertSnapshotEquals(String where, AccountSnapshot a, AccountSnapshot b) {
        assertEquals(a.cashBalance(), b.cashBalance(), where + " cash");
        assertEquals(a.cashReserved(), b.cashReserved(), where + " cashReserved");
        assertEquals(a.cashAvailable(), b.cashAvailable(), where + " cashAvailable");
        assertEquals(a.position(), b.position(), where + " position");
        assertEquals(a.positionReserved(), b.positionReserved(), where + " positionReserved");
        assertEquals(a.positionAvailable(), b.positionAvailable(), where + " positionAvailable");
        assertEquals(a.exposure(), b.exposure(), where + " exposure");
        assertEquals(a.exposureLimit(), b.exposureLimit(), where + " limit");
    }

    // ---- 用两个全新引擎按 WAL 原序逐条重放，比对每条命令的决策结论 ----

    private void comparePerEventDecisions(List<JournalEvent> wal) {
        Ctx a = newSystem(dir.resolve("decide-a-" + System.nanoTime() + ".jsonl"));
        Ctx b = newSystem(dir.resolve("decide-b-" + System.nanoTime() + ".jsonl"));
        for (JournalEvent e : wal) {
            switch (e.type()) {
                case SUBMIT -> {
                    SubmitOrderResult ra = a.engine().submit(e.submit());
                    SubmitOrderResult rb = b.engine().submit(e.submit());
                    assertEquals(ra.accepted(), rb.accepted(), "SUBMIT accepted " + e.submit().clientOrderId());
                    assertEquals(ra.duplicate(), rb.duplicate(), "SUBMIT duplicate " + e.submit().clientOrderId());
                    assertEquals(ra.status(), rb.status(), "SUBMIT status " + e.submit().clientOrderId());
                    assertEquals(ra.rejectReason(), rb.rejectReason(),
                            "SUBMIT reason " + e.submit().clientOrderId());
                    assertEquals(ra.orderId(), rb.orderId(), "SUBMIT orderId " + e.submit().clientOrderId());
                }
                case CANCEL -> {
                    CancelOrderResult ra = a.engine().cancel(e.cancelAccountId(), e.cancelClientOrderId());
                    CancelOrderResult rb = b.engine().cancel(e.cancelAccountId(), e.cancelClientOrderId());
                    assertEquals(ra.success(), rb.success(), "CANCEL success " + e.cancelClientOrderId());
                    assertEquals(ra.duplicate(), rb.duplicate(), "CANCEL duplicate " + e.cancelClientOrderId());
                    assertEquals(ra.status(), rb.status(), "CANCEL status " + e.cancelClientOrderId());
                    assertEquals(ra.rejectReason(), rb.rejectReason(),
                            "CANCEL reason " + e.cancelClientOrderId());
                }
                case AMEND -> {
                    AmendOrderResult ra = a.engine().amend(e.amend());
                    AmendOrderResult rb = b.engine().amend(e.amend());
                    AmendOrderRequest q = e.amend();
                    assertEquals(ra.success(), rb.success(), "AMEND success " + q.amendId());
                    assertEquals(ra.duplicate(), rb.duplicate(), "AMEND duplicate " + q.amendId());
                    assertEquals(ra.status(), rb.status(), "AMEND status " + q.amendId());
                    assertEquals(ra.rejectReason(), rb.rejectReason(), "AMEND reason " + q.amendId());
                    assertEquals(ra.price(), rb.price(), "AMEND price " + q.amendId());
                    assertEquals(ra.quantity(), rb.quantity(), "AMEND qty " + q.amendId());
                }
                case TICK -> {
                    TickResult ra = a.engine().onTick(e.tick());
                    TickResult rb = b.engine().onTick(e.tick());
                    assertEquals(ra.accepted(), rb.accepted(), "TICK accepted " + e.tick().seq());
                    assertEquals(ra.duplicate(), rb.duplicate(), "TICK duplicate " + e.tick().seq());
                    assertEquals(ra.rejectReason(), rb.rejectReason(), "TICK reason " + e.tick().seq());
                    assertEquals(ra.fills(), rb.fills(), "TICK fills " + e.tick().seq());
                }
            }
        }
    }
}
