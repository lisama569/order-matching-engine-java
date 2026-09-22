package com.github.lisama569.ordermatchingenginejava.account;

import java.util.HashMap;
import java.util.Map;

/**
 * 账户资金 / 持仓 / 占用台账。
 *
 * <p>占用与释放规则（金额单位为最小货币单位，持仓为最小数量单位）：
 * <ul>
 *   <li>买单：下单即按参考价（限价单=限价，市价单=保护价）冻结现金，
 *       同时等量计入该账户-标的敞口；</li>
 *   <li>卖单：下单即冻结持仓，敞口按 数量×参考价 计入；</li>
 *   <li>成交：按<b>实际成交价</b>结算现金/持仓，按<b>订单参考价</b>核销冻结与敞口，
 *       买单因价格改善（成交价低于参考价）多出的冻结差额自动回到可用资金；</li>
 *   <li>撤单 / 拒单：按剩余数量精确释放冻结与敞口。</li>
 * </ul>
 *
 * <p>本类所有方法仅在 {@code MatchingEngine} 的全局锁内被调用，故内部不加锁；
 * 每个"try/reserve"操作要么完整生效，要么不做任何改动（不允许部分占用）。
 */
public final class AccountLedger {

    /** accountId -> 现金[总余额, 冻结]。 */
    private final Map<String, long[]> cash = new HashMap<>();

    /** accountId|symbol -> 维度[持仓, 持仓冻结, 已用敞口, 敞口上限]。 */
    private final Map<String, long[]> dimensions = new HashMap<>();

    public AccountLedger() {
    }

    private long[] cashOf(String accountId) {
        return cash.computeIfAbsent(accountId, k -> new long[2]);
    }

    private long[] dimOf(String accountId, String symbol) {
        return dimensions.computeIfAbsent(key(accountId, symbol), k -> new long[4]);
    }

    private static String key(String accountId, String symbol) {
        return accountId + '|' + symbol;
    }

    /** 种子资金（账户基线现金，不区分标的，允许 0）。 */
    public void seedCash(String accountId, long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("seed cash must be non-negative: " + amount);
        }
        cashOf(accountId)[0] += amount;
    }

    /** 种子持仓。 */
    public void seedPosition(String accountId, String symbol, long qty) {
        if (qty <= 0) {
            throw new IllegalArgumentException("seed position must be positive: " + qty);
        }
        dimOf(accountId, symbol)[0] += qty;
    }

    /** 设置单账户单标的敞口上限（0 表示使用默认，负值非法）。 */
    public void setExposureLimit(String accountId, String symbol, long limit) {
        if (limit < 0) {
            throw new IllegalArgumentException("exposure limit must be >= 0: " + limit);
        }
        dimOf(accountId, symbol)[3] = limit;
    }

    public boolean accountExists(String accountId) {
        return cash.containsKey(accountId);
    }

    /**
     * 尝试为买单占用资金与敞口额度。
     *
     * @param amount 需冻结的现金 = 数量×参考价
     * @return true 表示占用成功；false 表示可用资金或敞口不足，状态无任何改动
     */
    public boolean tryReserveBuy(String accountId, String symbol, long amount) {
        long[] c = cashOf(accountId);
        long[] d = dimOf(accountId, symbol);
        long availableCash = c[0] - c[1];
        long exposureLeft = d[3] - d[2];
        if (availableCash < amount || exposureLeft < amount) {
            return false;
        }
        c[1] += amount;
        d[2] += amount;
        return true;
    }

    /**
     * 尝试为卖单占用持仓与敞口额度。
     *
     * @param qty      需冻结的持仓数量
     * @param notional 占用的敞口 = 数量×参考价
     */
    public boolean tryReserveSell(String accountId, String symbol, long qty, long notional) {
        long[] c = cashOf(accountId);
        long[] d = dimOf(accountId, symbol);
        long availablePosition = d[0] - d[1];
        long exposureLeft = d[3] - d[2];
        if (availablePosition < qty || exposureLeft < notional) {
            return false;
        }
        d[1] += qty;
        d[2] += notional;
        return true;
    }

    /**
     * 买单成交结算：收到持仓，按实际成交价支出现金，
     * 按订单参考价核销冻结/敞口（价格改善部分自动退回可用资金）。
     */
    public void settleBuy(String accountId, String symbol, long qty, long cost, long refPrice) {
        long[] c = cashOf(accountId);
        long[] d = dimOf(accountId, symbol);
        long reservedConsume = qty * refPrice;
        if (c[1] < reservedConsume) {
            throw new IllegalStateException("buy settlement exceeds reserved cash");
        }
        c[1] -= reservedConsume;
        c[0] -= cost;
        d[0] += qty;
        d[2] -= reservedConsume;
    }

    /**
     * 卖单成交结算：付出冻结持仓，按实际成交价收入现金，
     * 按订单参考价核销持仓冻结/敞口。
     */
    public void settleSell(String accountId, String symbol, long qty, long proceeds, long refPrice) {
        long[] d = dimOf(accountId, symbol);
        long[] c = cashOf(accountId);
        long exposureConsume = qty * refPrice;
        if (d[1] < qty) {
            throw new IllegalStateException("sell settlement exceeds reserved position");
        }
        d[1] -= qty;
        d[0] -= qty;
        d[2] -= exposureConsume;
        c[0] += proceeds;
    }

    /** 买单剩余冻结的释放（撤单 / 拒单 / 订单终结时）。 */
    public void releaseBuy(String accountId, String symbol, long amount) {
        if (amount == 0) {
            return;
        }
        long[] c = cashOf(accountId);
        long[] d = dimOf(accountId, symbol);
        if (c[1] < amount || d[2] < amount) {
            throw new IllegalStateException("buy release exceeds reservation: " + amount);
        }
        c[1] -= amount;
        d[2] -= amount;
    }

    /** 卖单剩余冻结的释放（撤单 / 拒单 / 订单终结时）。 */
    public void releaseSell(String accountId, String symbol, long qty, long notional) {
        if (qty == 0) {
            return;
        }
        long[] d = dimOf(accountId, symbol);
        if (d[1] < qty || d[2] < notional) {
            throw new IllegalStateException("sell release exceeds reservation: qty=" + qty);
        }
        d[1] -= qty;
        d[2] -= notional;
    }

    public AccountSnapshot snapshot(String accountId, String symbol) {
        long[] c = cashOf(accountId);
        long[] d = dimOf(accountId, symbol);
        return new AccountSnapshot(accountId, symbol,
                c[0], c[1], c[0] - c[1],
                d[0], d[1], d[0] - d[1],
                d[2], d[3]);
    }

    public Map<String, Long> cashBalances() {
        Map<String, Long> out = new HashMap<>();
        cash.forEach((k, v) -> out.put(k, v[0]));
        return out;
    }
}
