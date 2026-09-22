package com.github.lisama569.ordermatchingenginejava.account;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 账户注册与存取。
 *
 * <p>内置 {@value #MARKET_ACCOUNT} 账户作为行情触发成交时的内部对手盘
 * （模拟外部流动性），其余额视为无限，不参与占用与敞口校验。
 */
@Component
public class AccountManager {

    /** 行情驱动成交时的内部对手盘账户。 */
    public static final String MARKET_ACCOUNT = "MARKET";

    private final Map<String, Account> accounts = new ConcurrentHashMap<>();

    public AccountManager() {
        // 内部对手盘：给一个足够大的初始资金，持仓随成交自然累积（可为负，模拟外部流动性）。
        Account market = new Account(MARKET_ACCOUNT, Long.MAX_VALUE / 4);
        accounts.put(MARKET_ACCOUNT, market);
    }

    /** 开户（如已存在直接返回既有账户，保证回放/重复开户幂等）。 */
    public Account createAccount(String accountId, long initialCash) {
        return accounts.computeIfAbsent(accountId, id -> new Account(id, initialCash));
    }

    public Account get(String accountId) {
        return accounts.get(accountId);
    }

    public boolean exists(String accountId) {
        return accountId != null && accounts.containsKey(accountId);
    }

    public Collection<Account> all() {
        return accounts.values();
    }
}
