package com.github.lisama569.ordermatchingenginejava.account;

import com.github.lisama569.ordermatchingenginejava.model.AccountSnapshot;

import java.util.HashMap;
import java.util.Map;

/**
 * 账户：现金余额、冻结资金、各标的持仓与冻结持仓。
 *
 * <p>本类只做记账原语，不做业务判断；所有方法必须成对守恒：
 * <ul>
 *   <li>{@code reserveCash} / {@code releaseCash}：预占与释放资金；</li>
 *   <li>{@code reservePosition} / {@code releasePosition}：冻结与释放标的；</li>
 *   <li>{@code settleBuy}：成交时扣现金、加持仓，并按实际成交额释放多余预占资金；</li>
 *   <li>{@code settleSell}：成交时加现金、扣持仓，并释放对应冻结标的。</li>
 * </ul>
 * 调用方（TradingService）持有全局锁，本类自身不再加锁。
 */
public class Account {

    private final String accountId;
    private long cash;
    private long reservedCash;
    /** symbol -> 总持仓（含冻结）。 */
    private final Map<String, Long> positions = new HashMap<>();
    /** symbol -> 冻结持仓。 */
    private final Map<String, Long> reservedPositions = new HashMap<>();

    public Account(String accountId, long initialCash) {
        this.accountId = accountId;
        this.cash = initialCash;
    }

    public String getAccountId() {
        return accountId;
    }

    public long getCash() {
        return cash;
    }

    public long getReservedCash() {
        return reservedCash;
    }

    public long getAvailableCash() {
        return cash - reservedCash;
    }

    public long getPosition(String symbol) {
        return positions.getOrDefault(symbol, 0L);
    }

    public long getReservedPosition(String symbol) {
        return reservedPositions.getOrDefault(symbol, 0L);
    }

    public long getAvailablePosition(String symbol) {
        return getPosition(symbol) - getReservedPosition(symbol);
    }

    /** 入金（测试/初始化用）。 */
    public void deposit(long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("deposit amount must be positive: " + amount);
        }
        cash += amount;
    }

    /** 划入持仓（测试/初始化/交割用），不影响现金。 */
    public void depositPosition(String symbol, long qty) {
        if (qty <= 0) {
            throw new IllegalArgumentException("deposit qty must be positive: " + qty);
        }
        positions.merge(symbol, qty, Long::sum);
    }

    /** 预占买单资金，返回是否成功（可用资金不足则拒绝，不允许出现负可用）。 */
    public boolean reserveCash(long amount) {
        if (amount <= 0 || amount > getAvailableCash()) {
            return false;
        }
        reservedCash += amount;
        return true;
    }

    /** 释放预占资金（撤单/拒单）。amount 不得超过当前预占总额。 */
    public void releaseCash(long amount) {
        if (amount < 0 || amount > reservedCash) {
            throw new IllegalStateException("releaseCash not conserved: amount=" + amount
                    + ", reservedCash=" + reservedCash);
        }
        reservedCash -= amount;
    }

    /** 冻结卖单标的，返回是否成功（可用持仓不足则拒绝）。 */
    public boolean reservePosition(String symbol, long qty) {
        if (qty <= 0 || qty > getAvailablePosition(symbol)) {
            return false;
        }
        reservedPositions.merge(symbol, qty, Long::sum);
        return true;
    }

    /** 释放冻结标的（撤单/拒单）。 */
    public void releasePosition(String symbol, long qty) {
        long current = getReservedPosition(symbol);
        if (qty < 0 || qty > current) {
            throw new IllegalStateException("releasePosition not conserved: symbol=" + symbol
                    + ", qty=" + qty + ", reserved=" + current);
        }
        if (qty == current) {
            reservedPositions.remove(symbol);
        } else {
            reservedPositions.put(symbol, current - qty);
        }
    }

    /**
     * 买单成交结算：按成交额扣现金、增加持仓；
     * 若该笔成交对应释放 notional 的预占资金（含预占用多的差额），同步释放。
     * 不变量：reservedCash 只减不增，现金扣减额 == notional。
     */
    public void settleBuy(String symbol, long qty, long price, long reservedToRelease) {
        long notional = qty * price;
        if (reservedToRelease < notional || reservedToRelease > reservedCash) {
            throw new IllegalStateException("settleBuy reservation mismatch: notional=" + notional
                    + ", release=" + reservedToRelease + ", reservedCash=" + reservedCash);
        }
        reservedCash -= reservedToRelease;
        cash -= notional;
        positions.merge(symbol, qty, Long::sum);
    }

    /**
     * 卖单成交结算：增加现金、扣减持仓；
     * reservedToRelease 为该笔成交释放的冻结标的（必须 == qty，卖单逐股冻结）。
     */
    public void settleSell(String symbol, long qty, long price, long reservedToRelease) {
        long notional = qty * price;
        if (reservedToRelease != qty) {
            throw new IllegalStateException("sell fill must release exactly filled qty");
        }
        releasePosition(symbol, reservedToRelease);
        long pos = getPosition(symbol);
        if (qty > pos) {
            throw new IllegalStateException("settleSell oversold: qty=" + qty + ", pos=" + pos);
        }
        if (qty == pos) {
            positions.remove(symbol);
        } else {
            positions.put(symbol, pos - qty);
        }
        cash += notional;
    }

    /** 内部 MARKET 对手盘镜像记账：买入直接扣现金加持仓，不涉及预占。 */
    public void mirrorBuy(String symbol, long qty, long price) {
        cash -= qty * price;
        positions.merge(symbol, qty, Long::sum);
    }

    /** 内部 MARKET 对手盘镜像记账：卖出直接加现金扣持仓，不涉及冻结。 */
    public void mirrorSell(String symbol, long qty, long price) {
        long pos = getPosition(symbol) - qty;
        if (pos == 0) {
            positions.remove(symbol);
        } else {
            positions.put(symbol, pos);
        }
        cash += qty * price;
    }

    public AccountSnapshot snapshot(Map<String, Long> openExposure) {
        return new AccountSnapshot(accountId, cash, reservedCash, getAvailableCash(),
                Map.copyOf(positions), Map.copyOf(reservedPositions), Map.copyOf(openExposure));
    }
}
