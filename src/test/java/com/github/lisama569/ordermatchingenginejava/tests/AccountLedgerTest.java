package com.github.lisama569.ordermatchingenginejava.tests;

import com.github.lisama569.ordermatchingenginejava.account.AccountLedger;
import com.github.lisama569.ordermatchingenginejava.account.AccountSnapshot;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 台账占用守恒测试：买单冻结/结算/释放、卖单冻结、价格改善退款、
 * 可用资金不足与敞口上限两种超量拒绝路径，且失败不得产生任何改动。
 */
class AccountLedgerTest {

    private static final Logger log = LoggerFactory.getLogger(AccountLedgerTest.class);

    @Test
    void buyReserveSettleAndPriceImprovement() {
        AccountLedger ledger = new AccountLedger();
        ledger.seedCash("alice", 1000);
        ledger.setExposureLimit("alice", "AAPL", 1000);

        // 按参考价10冻结 100
        assertTrue(ledger.tryReserveBuy("alice", "AAPL", 100));
        AccountSnapshot reserved = ledger.snapshot("alice", "AAPL");
        assertEquals(900, reserved.cashAvailable());
        assertEquals(100, reserved.cashReserved());
        assertEquals(100, reserved.exposure());
        log.info("[case] 买单冻结 qty=10 ref=10 => 可用={}, 冻结={}, 敞口={} (判定输入: 现金1000)",
                reserved.cashAvailable(), reserved.cashReserved(), reserved.exposure());

        // 其中 6 股在行情价 9 成交（价格改善）：成本54，核销冻结60，差额6退回
        ledger.settleBuy("alice", "AAPL", 6, 54, 10);
        AccountSnapshot afterFill = ledger.snapshot("alice", "AAPL");
        assertEquals(6, afterFill.position());
        assertEquals(946, afterFill.cashBalance());
        assertEquals(40, afterFill.cashReserved());
        assertEquals(40, afterFill.exposure());
        log.info("[case] 6股按9成交 => 余额={}, 冻结={}, 持仓={} (判定: 价格改善差额6退回可用)",
                afterFill.cashBalance(), afterFill.cashReserved(), afterFill.position());

        // 剩余 4 股撤单释放
        ledger.releaseBuy("alice", "AAPL", 40);
        AccountSnapshot afterCancel = ledger.snapshot("alice", "AAPL");
        assertEquals(946, afterCancel.cashBalance());
        assertEquals(0, afterCancel.cashReserved());
        assertEquals(0, afterCancel.exposure());
        log.info("[case] 剩余4股撤单 => 冻结={}, 敞口={} (判定: 占用守恒回到0)",
                afterCancel.cashReserved(), afterCancel.exposure());
    }

    @Test
    void sellReserveSettleAndRelease() {
        AccountLedger ledger = new AccountLedger();
        ledger.seedCash("bob", 0);
        ledger.seedPosition("bob", "AAPL", 20);
        ledger.setExposureLimit("bob", "AAPL", 10_000);

        assertTrue(ledger.tryReserveSell("bob", "AAPL", 10, 1000));
        AccountSnapshot reserved = ledger.snapshot("bob", "AAPL");
        assertEquals(10, reserved.positionAvailable());
        assertEquals(10, reserved.positionReserved());
        assertEquals(1000, reserved.exposure());
        log.info("[case] 卖单冻结 10股@ref100 => 可用持仓={}, 敞口={}",
                reserved.positionAvailable(), reserved.exposure());

        // 5 股以 101 成交：收入 505
        ledger.settleSell("bob", "AAPL", 5, 505, 100);
        AccountSnapshot filled = ledger.snapshot("bob", "AAPL");
        assertEquals(505, filled.cashBalance());
        assertEquals(15, filled.position());
        assertEquals(5, filled.positionReserved());
        assertEquals(500, filled.exposure());
        log.info("[case] 5股@101成交 => 现金={}, 持仓={}, 冻结持仓={}, 敞口={}",
                filled.cashBalance(), filled.position(), filled.positionReserved(), filled.exposure());

        ledger.releaseSell("bob", "AAPL", 5, 500);
        AccountSnapshot released = ledger.snapshot("bob", "AAPL");
        assertEquals(15, released.positionAvailable());
        assertEquals(0, released.exposure());
        log.info("[case] 剩余5股撤单 => 可用持仓={}, 敞口={} (判定: 持仓与敞口守恒)",
                released.positionAvailable(), released.exposure());
    }

    @Test
    void insufficientCashLeavesNoMutation() {
        AccountLedger ledger = new AccountLedger();
        ledger.seedCash("c", 50);
        ledger.setExposureLimit("c", "AAPL", 10_000);

        boolean ok = ledger.tryReserveBuy("c", "AAPL", 60);
        AccountSnapshot snap = ledger.snapshot("c", "AAPL");
        log.info("[case] 现金50 尝试冻结60 => accepted={} 冻结={} 可用={} (判定: 拒绝且零改动)",
                ok, snap.cashReserved(), snap.cashAvailable());
        assertFalse(ok);
        assertEquals(0, snap.cashReserved());
        assertEquals(50, snap.cashAvailable());
    }

    @Test
    void exposureLimitIsEnforcedIndependentlyOfCash() {
        AccountLedger ledger = new AccountLedger();
        ledger.seedCash("d", 10_000);
        ledger.setExposureLimit("d", "AAPL", 100);

        boolean ok = ledger.tryReserveBuy("d", "AAPL", 200);
        AccountSnapshot snap = ledger.snapshot("d", "AAPL");
        log.info("[case] 现金10000 但敞口上限100, 尝试占用200 => accepted={} 冻结={} (判定: 敞口拒绝且零改动)",
                ok, snap.cashReserved());
        assertFalse(ok);
        assertEquals(0, snap.cashReserved());

        assertTrue(ledger.tryReserveBuy("d", "AAPL", 100));
        assertFalse(ledger.tryReserveBuy("d", "AAPL", 1));
        log.info("[case] 已用满敞口100后再申请1 => accepted=false (判定: 严格不超上限)");
    }
}
