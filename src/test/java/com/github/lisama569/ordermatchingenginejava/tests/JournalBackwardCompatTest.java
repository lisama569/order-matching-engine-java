package com.github.lisama569.ordermatchingenginejava.tests;

import com.github.lisama569.ordermatchingenginejava.account.AccountLedger;
import com.github.lisama569.ordermatchingenginejava.engine.MatchingEngine;
import com.github.lisama569.ordermatchingenginejava.journal.FileEventJournal;
import com.github.lisama569.ordermatchingenginejava.journal.JournalEvent;
import com.github.lisama569.ordermatchingenginejava.market.MarketDataService;
import com.github.lisama569.ordermatchingenginejava.market.SimulatedMarketFeeder;
import com.github.lisama569.ordermatchingenginejava.service.OrderService;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 旧版本落盘的 WAL 必须能被当前代码照常读取、回放：
 * 用仓库里既有的历史文件（含 SUBMIT/CANCEL/AMEND/TICK 四类记录）做一次只读复制回放，
 * 并对同一历史连续回放两次，要求结论完全一致、无异常。
 */
class JournalBackwardCompatTest {

    @Test
    void oldJournalReplaysCleanlyAndDeterministically() throws Exception {
        Path source = Path.of("target/test-data/web-journal.jsonl");
        assertTrue(Files.exists(source), "历史 WAL 文件应存在: " + source);

        List<JournalEvent> events = new FileEventJournal(source).readAll();
        assertTrue(events.size() > 100, "历史文件应含大量命令，实际=" + events.size());

        Path work = Files.createTempDirectory("journal-compat-");
        Path copy1 = work.resolve("j1.jsonl");
        Path copy2 = work.resolve("j2.jsonl");
        Files.copy(source, copy1, StandardCopyOption.REPLACE_EXISTING);
        Files.copy(source, copy2, StandardCopyOption.REPLACE_EXISTING);

        long fills1;
        long fills2;
        try (var ctx1 = new ReplayCtx(copy1);
             var ctx2 = new ReplayCtx(copy2)) {
            int n1 = ctx1.service.replay();
            int n2 = ctx2.service.replay();
            assertEquals(events.size(), n1);
            assertEquals(events.size(), n2);

            fills1 = ctx1.service.fills().size();
            fills2 = ctx2.service.fills().size();
            assertEquals(fills1, fills2, "同一旧历史连续回放成交数必须一致");
            assertEquals(ctx1.service.fills(), ctx2.service.fills(),
                    "旧历史回放成交流水必须逐项一致");
            assertEquals(ctx1.service.engine().expectedTickSeq("AAPL"),
                    ctx2.service.engine().expectedTickSeq("AAPL"),
                    "旧历史回放行情进度必须一致");
        }
        assertTrue(fills1 >= 0);
    }

    private static final class ReplayCtx implements AutoCloseable {
        final OrderService service;

        ReplayCtx(Path journal) {
            AccountLedger ledger = new AccountLedger();
            ledger.seedCash("alice", 1_000_000);
            ledger.seedCash("bob", 1_000_000);
            ledger.seedPosition("alice", "AAPL", 10_000);
            ledger.seedPosition("bob", "AAPL", 10_000);
            ledger.setExposureLimit("alice", "AAPL", 1_000_000);
            ledger.setExposureLimit("bob", "AAPL", 1_000_000);
            MatchingEngine engine = new MatchingEngine(ledger, 1, 1);
            engine.registerSymbol("AAPL");
            MarketDataService market = new MarketDataService();
            this.service = new OrderService(engine, new FileEventJournal(journal),
                    market, new SimulatedMarketFeeder(market));
        }

        @Override
        public void close() {
        }
    }
}
