package com.github.lisama569.ordermatchingenginejava.tests;

import com.github.lisama569.ordermatchingenginejava.account.AccountLedger;
import com.github.lisama569.ordermatchingenginejava.engine.MatchingEngine;
import com.github.lisama569.ordermatchingenginejava.model.AmendOrderRequest;
import com.github.lisama569.ordermatchingenginejava.model.AmendOrderResult;
import com.github.lisama569.ordermatchingenginejava.model.MarketTick;
import com.github.lisama569.ordermatchingenginejava.model.Order;
import com.github.lisama569.ordermatchingenginejava.model.OrderStatus;
import com.github.lisama569.ordermatchingenginejava.model.OrderType;
import com.github.lisama569.ordermatchingenginejava.model.RejectReason;
import com.github.lisama569.ordermatchingenginejava.model.Side;
import com.github.lisama569.ordermatchingenginejava.model.SubmitOrderRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 改单引擎测试：改价重排队失去时间优先、只改量保留优先级、占用增减守恒、
 * 增占用不足整体拒绝零改动、各种可区分拒因、幂等只生效一次。
 */
class AmendOrderTest {

    private static final Logger log = LoggerFactory.getLogger(AmendOrderTest.class);

    private AccountLedger ledger;
    private MatchingEngine engine;

    @BeforeEach
    void setUp() {
        ledger = new AccountLedger();
        ledger.seedCash("alice", 1_000_000);
        ledger.seedCash("bob", 1_000_000);
        ledger.seedPosition("alice", "AAPL", 10_000);
        ledger.seedPosition("bob", "AAPL", 10_000);
        ledger.setExposureLimit("alice", "AAPL", 1_000_000);
        ledger.setExposureLimit("bob", "AAPL", 1_000_000);
        ledger.seedCash("carol", 1_000_000);
        ledger.setExposureLimit("carol", "AAPL", 1_000_000);
        engine = new MatchingEngine(ledger, 1, 1);
        engine.registerSymbol("AAPL");
    }

    private SubmitOrderRequest limit(String cid, String account, Side side, long price, long qty) {
        return new SubmitOrderRequest(cid, account, "AAPL", side, OrderType.LIMIT, price, null, qty);
    }

    private SubmitOrderRequest market(String cid, String account, Side side, long protection, long qty) {
        return new SubmitOrderRequest(cid, account, "AAPL", side, OrderType.MARKET, null, protection, qty);
    }

    private AmendOrderRequest amend(String aid, String account, String cid, Long price, Long qty) {
        return new AmendOrderRequest(aid, account, cid, price, qty);
    }

    @Test
    void amendPriceRequeuesAndLosesTimePriority() {
        // 买单：a(先)、b(后) 同价 100 建立时间优先
        engine.submit(limit("a", "alice", Side.BUY, 100, 10));
        engine.submit(limit("b", "carol", Side.BUY, 100, 10));

        long aSeqBefore = engine.findByClientOrderId("a").getEnqueueSeq();
        // a 显式改价到 100，应重新排队、enqueueSeq 刷新到 b 之后
        AmendOrderResult r = engine.amend(amend("am-a", "alice", "a", 100L, null));
        assertTrue(r.success());
        long aSeqAfter = engine.findByClientOrderId("a").getEnqueueSeq();
        long bSeq = engine.findByClientOrderId("b").getEnqueueSeq();
        log.info("[case] 改价重排队: a.enqueueSeq {}->{} b.enqueueSeq={} (期望 a 失去时间优先)",
                aSeqBefore, aSeqAfter, bSeq);
        assertTrue(aSeqAfter > bSeq, "改价后 a 应排到 b 之后");

        // 改单之后再挂：s1@100 提供 10 股给买单队首；s-block@101 是限价卖单，
        // 行情价 100 时不满足"卖：P≥限价"，不可成交，占据卖盘次档并阻断后续撮合
        engine.submit(limit("s1", "bob", Side.SELL, 100, 10));
        engine.submit(limit("s-block", "bob", Side.SELL, 101, 100));

        engine.onTick(new MarketTick(1, "AAPL", 100));
        // b 保持买单盘口最优，先与 s1 成交 10 股全成；a 排其后，因卖盘剩 s-block@101 不可成交而留在簿中
        assertEquals(OrderStatus.FILLED, engine.findByClientOrderId("b").getStatus());
        assertEquals(OrderStatus.NEW, engine.findByClientOrderId("a").getStatus());
        log.info("[case] 改价后首笔行情: b=FILLED a=NEW (判定: b 时间优先先成交, a 失去优先级)");

        // 撤掉阻断单并挂入可成交卖单，第二笔行情 100 让 a 成交，证明 a 仍在簿中且可成交
        engine.cancel("bob", "s-block");
        engine.submit(limit("s2", "bob", Side.SELL, 100, 10));
        engine.onTick(new MarketTick(2, "AAPL", 100));
        assertEquals(OrderStatus.FILLED, engine.findByClientOrderId("a").getStatus());
        log.info("[case] 第二笔行情 a=FILLED (判定: a 仍在簿中, 改价后随其新排队位置正常成交)");
    }

    @Test
    void quantityOnlyAmendRetainsTimePriority() {
        engine.submit(limit("a", "alice", Side.BUY, 100, 10));
        engine.submit(limit("b", "bob", Side.BUY, 100, 10));
        engine.submit(limit("c", "bob", Side.SELL, 100, 10));

        long aSeqBefore = engine.findByClientOrderId("a").getEnqueueSeq();
        AmendOrderResult r = engine.amend(amend("am-a", "alice", "a", null, 5L));
        assertTrue(r.success());
        long aSeqAfter = engine.findByClientOrderId("a").getEnqueueSeq();
        log.info("[case] 只改量: a.enqueueSeq {}->{} (期望不变), 新总量=5", aSeqBefore, aSeqAfter);
        assertEquals(aSeqBefore, aSeqAfter, "只改量不改价应保留排队序号");
        assertEquals(5, engine.findByClientOrderId("a").getOriginalQty());

        engine.onTick(new MarketTick(1, "AAPL", 100));
        // a 仍时间优先，与 c 成交 5 股并全成
        assertEquals(OrderStatus.FILLED, engine.findByClientOrderId("a").getStatus());
        assertEquals(5, engine.fills().get(0).qty());
    }

    @Test
    void amendBuyQuantityUpAndDownAdjustsCashReservation() {
        engine.submit(limit("buy", "alice", Side.BUY, 100, 10)); // 冻结 1000
        assertEquals(1000, ledger.snapshot("alice", "AAPL").cashReserved());

        // 加量到 15 -> 追加冻结 500
        AmendOrderResult up = engine.amend(amend("up", "alice", "buy", null, 15L));
        assertTrue(up.success());
        assertEquals(1500, ledger.snapshot("alice", "AAPL").cashReserved());
        assertEquals(1500, ledger.snapshot("alice", "AAPL").exposure());
        log.info("[case] 买单加量 10->15: 冻结=1500 敞口=1500 (判定: 差额立即追加)");

        // 减量到 8 -> 释放 700
        AmendOrderResult down = engine.amend(amend("down", "alice", "buy", null, 8L));
        assertTrue(down.success());
        assertEquals(800, ledger.snapshot("alice", "AAPL").cashReserved());
        assertEquals(800, ledger.snapshot("alice", "AAPL").exposure());
        log.info("[case] 买单减量 15->8: 冻结=800 敞口=800 (判定: 多占用立即释放)");
    }

    @Test
    void amendPriceUpOnBuyRejectsWhenInsufficientAndLeavesEverythingUntouched() {
        // 独立账户：现金与敞口上限都只有 5000
        ledger.seedCash("carol", 5_000);
        ledger.setExposureLimit("carol", "AAPL", 5_000);
        engine.submit(limit("buy", "carol", Side.BUY, 100, 10)); // 占用 1000
        var before = ledger.snapshot("carol", "AAPL");

        // 改价 100->1000，剩余 10 股需 10000，净增 9000，现金/敞口均不足
        AmendOrderResult r = engine.amend(amend("up", "carol", "buy", 1000L, null));
        assertFalse(r.success());
        assertEquals(RejectReason.EXPOSURE_LIMIT_EXCEEDED, r.rejectReason());

        Order o = engine.findByClientOrderId("buy");
        assertEquals(100, o.getPrice(), "拒绝后价格不得改变");
        assertEquals(10, o.getOriginalQty(), "拒绝后数量不得改变");
        var after = ledger.snapshot("carol", "AAPL");
        assertEquals(before.cashReserved(), after.cashReserved());
        assertEquals(before.exposure(), after.exposure());
        log.info("[case] 改价占用不足被拒: reason={} 价格仍=100 冻结仍={} 敞口仍={} (判定: 整体零改动)",
                r.rejectReason(), after.cashReserved(), after.exposure());
    }

    @Test
    void amendRejectsWithDistinctReasons() {
        engine.submit(limit("o1", "alice", Side.BUY, 100, 10));

        AmendOrderResult notFound = engine.amend(amend("a1", "alice", "missing", 100L, null));
        AmendOrderResult owner = engine.amend(amend("a2", "bob", "o1", 100L, null));
        AmendOrderResult noChange = engine.amend(amend("a3", "alice", "o1", null, null));
        AmendOrderResult badPrice = engine.amend(amend("a4", "alice", "o1", 0L, null));
        AmendOrderResult badQty = engine.amend(amend("a5", "alice", "o1", null, 0L));

        log.info("[case] 改单拒因: notFound={} owner={} noChange={} badPrice={} badQty={}",
                notFound.rejectReason(), owner.rejectReason(), noChange.rejectReason(),
                badPrice.rejectReason(), badQty.rejectReason());

        assertEquals(RejectReason.ORDER_NOT_FOUND, notFound.rejectReason());
        assertEquals(RejectReason.ORDER_OWNER_MISMATCH, owner.rejectReason());
        assertEquals(RejectReason.AMEND_NO_CHANGE, noChange.rejectReason());
        assertEquals(RejectReason.AMEND_INVALID_PRICE, badPrice.rejectReason());
        assertEquals(RejectReason.AMEND_INVALID_QUANTITY, badQty.rejectReason());

        // 全部拒绝后订单价格/数量/占用不变
        Order o = engine.findByClientOrderId("o1");
        assertEquals(100, o.getPrice());
        assertEquals(10, o.getOriginalQty());
        assertEquals(1000, ledger.snapshot("alice", "AAPL").cashReserved());

        // 先撤掉 o1，避免它参与后续行情撮合
        engine.cancel("alice", "o1");

        // 新总量不大于已成交量：用一笔先成交 5 股的订单验证（已成交 5，改到 5 应拒）
        engine.submit(limit("pf2", "alice", Side.BUY, 100, 10));
        engine.submit(limit("pf2-s", "bob", Side.SELL, 100, 5));
        engine.onTick(new MarketTick(1, "AAPL", 100));
        assertEquals(5, engine.findByClientOrderId("pf2").getFilledQty());
        AmendOrderResult qtyNotAboveFilled = engine.amend(amend("a6", "alice", "pf2", null, 5L));
        assertEquals(RejectReason.AMEND_INVALID_QUANTITY, qtyNotAboveFilled.rejectReason());
        log.info("[case] 部分成交5后改总量到5(不大于已成交) 拒因={}", qtyNotAboveFilled.rejectReason());

        // 已撤销订单不可改
        AmendOrderResult terminal = engine.amend(amend("a7", "alice", "o1", 101L, null));
        assertEquals(RejectReason.ORDER_NOT_AMENDABLE, terminal.rejectReason());
        log.info("[case] 撤单后改单拒因={} (判定: 终结订单不可改)", terminal.rejectReason());
    }

    @Test
    void duplicateAmendAppliesExactlyOnce() {
        engine.submit(limit("d1", "alice", Side.BUY, 100, 10)); // 冻结 1000
        AmendOrderRequest req = amend("dup-amend", "alice", "d1", null, 20L); // 改到 20，冻结应为 2000
        AmendOrderResult first = engine.amend(req);
        AmendOrderResult second = engine.amend(req);
        AmendOrderResult third = engine.amend(req);

        assertTrue(first.success());
        assertFalse(first.duplicate());
        assertTrue(second.duplicate());
        assertTrue(third.duplicate());
        assertEquals(2000, ledger.snapshot("alice", "AAPL").cashReserved(),
                "同一改单请求重复提交只能生效一次");
        assertEquals(20, engine.findByClientOrderId("d1").getOriginalQty());
        log.info("[case] 同一改单提交3次: success={} duplicates={},{} 冻结={}(期望2000) (判定: 只生效一次)",
                first.success(), second.duplicate(), third.duplicate(),
                ledger.snapshot("alice", "AAPL").cashReserved());

        // 另一个 amendId 对同一订单改到 5 应正常生效（证明幂等是按 amendId 而非订单）
        AmendOrderResult other = engine.amend(amend("other-amend", "alice", "d1", null, 5L));
        assertTrue(other.success());
        assertEquals(500, ledger.snapshot("alice", "AAPL").cashReserved());
        log.info("[case] 不同 amendId 再次改单成功，冻结=500 (判定: 幂等键含 amendId)");
    }

    @Test
    void amendAfterPartialFillAdjustsRemainingReservation() {
        // alice 买 10@100，bob 卖 4@100，行情成交 4 股，买单剩 6 股、冻结 600
        engine.submit(limit("pf", "alice", Side.BUY, 100, 10));
        engine.submit(limit("pf-s", "bob", Side.SELL, 100, 4));
        engine.onTick(new MarketTick(1, "AAPL", 100));
        Order pf = engine.findByClientOrderId("pf");
        assertEquals(OrderStatus.PARTIALLY_FILLED, pf.getStatus());
        assertEquals(600, ledger.snapshot("alice", "AAPL").cashReserved());

        // 剩余 6 股的总数量改到 8（即剩余 4 股），应按剩余数量释放 200
        AmendOrderResult down = engine.amend(amend("pf-down", "alice", "pf", null, 8L));
        assertTrue(down.success());
        assertEquals(8, pf.getOriginalQty());
        assertEquals(4, pf.remainingQty());
        assertEquals(400, ledger.snapshot("alice", "AAPL").cashReserved());
        assertEquals(400, ledger.snapshot("alice", "AAPL").exposure());
        log.info("[case] 部分成交4后改总数量到8(剩4): 冻结=400 敞口=400 (判定: 按剩余数量调整)");

        // 总数量改到 12（剩余 8 股），追加冻结 400
        AmendOrderResult up = engine.amend(amend("pf-up", "alice", "pf", null, 12L));
        assertTrue(up.success());
        assertEquals(800, ledger.snapshot("alice", "AAPL").cashReserved());
        assertEquals(800, ledger.snapshot("alice", "AAPL").exposure());
        log.info("[case] 再改总数量到12(剩8): 冻结=800 敞口=800 (判定: 追加只针对剩余)");

        // 新总量不大于已成交量(4)必须拒绝
        AmendOrderResult tooSmall = engine.amend(amend("pf-bad", "alice", "pf", null, 4L));
        assertEquals(RejectReason.AMEND_INVALID_QUANTITY, tooSmall.rejectReason());
    }

    @Test
    void amendMarketOrderProtectionPriceRequeuesAndValidates() {
        // 用 tickSize=5 / lotSize=5 的引擎覆盖保护价刻度校验
        MatchingEngine strict = new MatchingEngine(ledger, 5, 5);
        strict.registerSymbol("AAPL");
        // alice 市价买，保护价 100（5 的倍数），数量 10，冻结 1000
        strict.submit(market("mk", "alice", Side.BUY, 100, 10));
        assertEquals(1000, ledger.snapshot("alice", "AAPL").cashReserved());

        // 保护价非刻度（7）=> INVALID_PROTECTION_PRICE
        AmendOrderResult bad = strict.amend(amend("mk-bad", "alice", "mk", 7L, null));
        assertEquals(RejectReason.INVALID_PROTECTION_PRICE, bad.rejectReason());

        // 保护价提到 110（5 的倍数，合法），追加冻结 100，并重新排队
        long seqBefore = strict.findByClientOrderId("mk").getEnqueueSeq();
        AmendOrderResult ok = strict.amend(amend("mk-up", "alice", "mk", 110L, null));
        assertTrue(ok.success());
        assertEquals(110, strict.findByClientOrderId("mk").getPrice());
        assertEquals(1100, ledger.snapshot("alice", "AAPL").cashReserved());
        assertTrue(strict.findByClientOrderId("mk").getEnqueueSeq() > seqBefore);
        log.info("[case] 市价买保护价100->110: 冻结=1100 重新排队 (判定: 保护价同改价规则)");

        // 保护价降到 90（5 的倍数），立即释放 200
        AmendOrderResult down = strict.amend(amend("mk-down", "alice", "mk", 90L, null));
        assertTrue(down.success());
        assertEquals(900, ledger.snapshot("alice", "AAPL").cashReserved());
        log.info("[case] 市价买保护价110->90: 冻结=900 (判定: 多占用立即释放)");
    }

    @Test
    void amendSellAdjustsPositionAndNotionalAndRejectsShortfall() {
        // dave 只有 20 股 AAPL，敞口上限按名义金额
        ledger.seedCash("dave", 1_000_000);
        ledger.seedPosition("dave", "AAPL", 20);
        ledger.setExposureLimit("dave", "AAPL", 1_000_000);

        // 卖 10@100：冻结持仓 10，敞口 1000
        engine.submit(limit("sell", "dave", Side.SELL, 100, 10));
        var snap0 = ledger.snapshot("dave", "AAPL");
        assertEquals(10, snap0.positionReserved());
        assertEquals(1000, snap0.exposure());

        // 加量到 15：追加冻结持仓 5，敞口 +500
        AmendOrderResult up = engine.amend(amend("s-up", "dave", "sell", null, 15L));
        assertTrue(up.success());
        var snap1 = ledger.snapshot("dave", "AAPL");
        assertEquals(15, snap1.positionReserved());
        assertEquals(1500, snap1.exposure());
        log.info("[case] 卖单加量10->15: 冻结持仓=15 敞口=1500 (判定: 持仓与名义同步追加)");

        // 加量到 25 超过可用持仓(20-15=5) => 拒绝，零改动
        AmendOrderResult noPos = engine.amend(amend("s-nopos", "dave", "sell", null, 25L));
        assertFalse(noPos.success());
        assertEquals(RejectReason.EXPOSURE_LIMIT_EXCEEDED, noPos.rejectReason());
        var snap2 = ledger.snapshot("dave", "AAPL");
        assertEquals(15, snap2.positionReserved());
        assertEquals(1500, snap2.exposure());
        assertEquals(15, engine.findByClientOrderId("sell").getOriginalQty());
        log.info("[case] 卖单加量到25超持仓被拒: 冻结持仓仍=15 敞口仍=1500 (判定: 整体零改动)");

        // 只提价 100->120（量仍 15）：持仓冻结不变，名义敞口 +300
        AmendOrderResult priceUp = engine.amend(amend("s-price", "dave", "sell", 120L, null));
        assertTrue(priceUp.success());
        var snap3 = ledger.snapshot("dave", "AAPL");
        assertEquals(15, snap3.positionReserved());
        assertEquals(1800, snap3.exposure());
        log.info("[case] 卖单提价100->120: 冻结持仓仍=15 敞口=1800 (判定: 只追加名义)");

        // 减量到 8：释放持仓 7、名义 120*8=960 => 冻结持仓 8、敞口 960
        AmendOrderResult down = engine.amend(amend("s-down", "dave", "sell", null, 8L));
        assertTrue(down.success());
        var snap4 = ledger.snapshot("dave", "AAPL");
        assertEquals(8, snap4.positionReserved());
        assertEquals(960, snap4.exposure());
        log.info("[case] 卖单减量15->8: 冻结持仓=8 敞口=960 (判定: 持仓与名义同步释放)");
    }
}
