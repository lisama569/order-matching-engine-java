package com.github.lisama569.ordermatchingenginejava.config;

import com.github.lisama569.ordermatchingenginejava.account.AccountLedger;
import com.github.lisama569.ordermatchingenginejava.service.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 启动引导（确定性顺序）：
 * <ol>
 *   <li>登记标的、种子资金/持仓、设置敞口上限；</li>
 *   <li>按 WAL 回放既有命令，重建订单簿/占用/成交/行情序号；</li>
 *   <li>按需开启进程内模拟行情。</li>
 * </ol>
 * 种子数据属于配置基线（不进 WAL），与命令流水分离，回放只重演命令。
 */
@Component
@Order(0)
public class BootstrapSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapSeeder.class);

    private final OrderService orderService;
    private final AccountLedger ledger;
    private final MatchingProperties properties;

    public BootstrapSeeder(OrderService orderService, AccountLedger ledger,
                           MatchingProperties properties) {
        this.orderService = orderService;
        this.ledger = ledger;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        properties.getSymbols().forEach((symbol, seedPrice) ->
                orderService.engine().registerSymbol(symbol));
        properties.getAccounts().forEach(ledger::seedCash);
        properties.getAccounts().forEach((account, cash) ->
                properties.getSymbols().keySet().forEach(symbol ->
                        ledger.setExposureLimit(account, symbol, properties.getDefaultExposureLimit())));
        for (MatchingProperties.PositionSeed p : properties.getPositions()) {
            ledger.seedPosition(p.getAccount(), p.getSymbol(), p.getQty());
        }
        log.info("[bootstrap] seed done accounts={} symbols={} positions={}",
                properties.getAccounts().size(), properties.getSymbols().size(),
                properties.getPositions().size());

        if (properties.isReplayOnStartup()) {
            int events = orderService.replay();
            log.info("[bootstrap] replay complete events={}", events);
        }

        if (properties.isAutoMarketFeed()) {
            properties.getSymbols().forEach((symbol, seedPrice) ->
                    orderService.feeder().startAutoFeed(
                            properties.getAutoFeedIntervalMillis(), symbol, seedPrice));
        }
    }
}
