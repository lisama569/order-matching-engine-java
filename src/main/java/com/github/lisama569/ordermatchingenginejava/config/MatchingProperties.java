package com.github.lisama569.ordermatchingenginejava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 撮合服务配置（application.properties: matching.*）。
 */
@ConfigurationProperties(prefix = "matching")
public class MatchingProperties {

    /** 最小价格变动单位。 */
    private long tickSize = 1;
    /** 最小数量单位。 */
    private long lotSize = 1;
    /** 单账户单标的默认敞口上限。 */
    private long defaultExposureLimit = 1_000_000L;
    /** WAL 文件路径。 */
    private String journalPath = "data/journal.jsonl";
    /** 启动时是否自动回放流水。 */
    private boolean replayOnStartup = true;
    /** 是否启动定时模拟行情。 */
    private boolean autoMarketFeed = false;
    private long autoFeedIntervalMillis = 2000;
    /** 标的 -> 初始行情价。 */
    private Map<String, Long> symbols = new LinkedHashMap<>();
    /** 演示账户初始资金。 */
    private Map<String, Long> accounts = new LinkedHashMap<>();
    /** 初始持仓种子。 */
    private List<PositionSeed> positions = List.of();

    public long getTickSize() { return tickSize; }
    public void setTickSize(long tickSize) { this.tickSize = tickSize; }
    public long getLotSize() { return lotSize; }
    public void setLotSize(long lotSize) { this.lotSize = lotSize; }
    public long getDefaultExposureLimit() { return defaultExposureLimit; }
    public void setDefaultExposureLimit(long defaultExposureLimit) { this.defaultExposureLimit = defaultExposureLimit; }
    public String getJournalPath() { return journalPath; }
    public void setJournalPath(String journalPath) { this.journalPath = journalPath; }
    public boolean isReplayOnStartup() { return replayOnStartup; }
    public void setReplayOnStartup(boolean replayOnStartup) { this.replayOnStartup = replayOnStartup; }
    public boolean isAutoMarketFeed() { return autoMarketFeed; }
    public void setAutoMarketFeed(boolean autoMarketFeed) { this.autoMarketFeed = autoMarketFeed; }
    public long getAutoFeedIntervalMillis() { return autoFeedIntervalMillis; }
    public void setAutoFeedIntervalMillis(long autoFeedIntervalMillis) { this.autoFeedIntervalMillis = autoFeedIntervalMillis; }
    public Map<String, Long> getSymbols() { return symbols; }
    public void setSymbols(Map<String, Long> symbols) { this.symbols = symbols; }
    public Map<String, Long> getAccounts() { return accounts; }
    public void setAccounts(Map<String, Long> accounts) { this.accounts = accounts; }
    public List<PositionSeed> getPositions() { return positions; }
    public void setPositions(List<PositionSeed> positions) { this.positions = positions; }

    /** 初始持仓种子。 */
    public static class PositionSeed {
        private String account;
        private String symbol;
        private long qty;

        public String getAccount() { return account; }
        public void setAccount(String account) { this.account = account; }
        public String getSymbol() { return symbol; }
        public void setSymbol(String symbol) { this.symbol = symbol; }
        public long getQty() { return qty; }
        public void setQty(long qty) { this.qty = qty; }
    }
}
