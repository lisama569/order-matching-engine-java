package com.github.lisama569.ordermatchingenginejava.tests;

import com.github.lisama569.ordermatchingenginejava.account.AccountManager;
import com.github.lisama569.ordermatchingenginejava.journal.EventJournal;
import com.github.lisama569.ordermatchingenginejava.matching.MatchingEngine;
import com.github.lisama569.ordermatchingenginejava.model.MarketTick;
import com.github.lisama569.ordermatchingenginejava.model.OrderRequest;
import com.github.lisama569.ordermatchingenginejava.model.OrderType;
import com.github.lisama569.ordermatchingenginejava.model.Side;
import com.github.lisama569.ordermatchingenginejava.service.TradingService;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Set;

/**
 * 测试公共构造：每个测试类用独立临时流水目录，避免相互污染。
 * 默认敞口上限 100000000 分（很大），需要测试敞口的用例自行传小值。
 */
public abstract class AbstractEngineTest {

    @TempDir
    Path tempDir;

    protected EventJournal journal;
    protected AccountManager accountManager;
    protected MatchingEngine engine;
    protected TradingService service;
    protected long clock = 1_000_000L;

    protected void setUp() {
        setUp(100_000_000L);
    }

    protected void setUp(long exposureLimit) {
        journal = new EventJournal(tempDir);
        accountManager = new AccountManager();
        engine = new MatchingEngine();
        service = new TradingService(journal, accountManager, engine, exposureLimit,
                110L, 100L, Set.of("600000", "BTCUSDT"));
    }

    protected long now() {
        return clock += 10L;
    }

    protected String account(String id, long cash) {
        service.createAccount(id, cash);
        return id;
    }

    protected void shares(String id, String symbol, long qty) {
        service.depositPosition(id, symbol, qty);
    }

    protected OrderRequest limit(String clientId, String acc, String symbol, Side side,
                                 long price, long qty) {
        return new OrderRequest(clientId, acc, symbol, side, OrderType.LIMIT, price, qty);
    }

    protected OrderRequest market(String clientId, String acc, String symbol, Side side,
                                  long qty) {
        return new OrderRequest(clientId, acc, symbol, side, OrderType.MARKET, null, qty);
    }

    protected void seedPrice(String symbol, long price) {
        service.seedLastPrice(symbol, price, 1L);
    }

    protected void tick(String symbol, long price) {
        long seq = service.getExpectedTickSeq(symbol);
        service.acceptTick(new MarketTick(symbol, seq, price, now()));
    }
}
