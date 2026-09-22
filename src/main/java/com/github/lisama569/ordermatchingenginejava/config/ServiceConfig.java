package com.github.lisama569.ordermatchingenginejava.config;

import com.github.lisama569.ordermatchingenginejava.account.AccountLedger;
import com.github.lisama569.ordermatchingenginejava.engine.MatchingEngine;
import com.github.lisama569.ordermatchingenginejava.journal.EventJournal;
import com.github.lisama569.ordermatchingenginejava.journal.FileEventJournal;
import com.github.lisama569.ordermatchingenginejava.market.MarketDataService;
import com.github.lisama569.ordermatchingenginejava.market.SimulatedMarketFeeder;
import com.github.lisama569.ordermatchingenginejava.risk.RiskValidator;
import com.github.lisama569.ordermatchingenginejava.service.OrderService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/** Bean 装配。 */
@Configuration
@EnableConfigurationProperties(MatchingProperties.class)
public class ServiceConfig {

    @Bean
    public AccountLedger accountLedger() {
        return new AccountLedger();
    }

    @Bean
    public RiskValidator riskValidator(MatchingProperties props) {
        return new RiskValidator(props.getTickSize(), props.getLotSize());
    }

    @Bean
    public MatchingEngine matchingEngine(AccountLedger ledger, MatchingProperties props) {
        return new MatchingEngine(ledger, props.getTickSize(), props.getLotSize());
    }

    @Bean
    public EventJournal eventJournal(MatchingProperties props) {
        return new FileEventJournal(Path.of(props.getJournalPath()));
    }

    @Bean
    public MarketDataService marketDataService() {
        return new MarketDataService();
    }

    @Bean
    public SimulatedMarketFeeder simulatedMarketFeeder(MarketDataService marketDataService) {
        return new SimulatedMarketFeeder(marketDataService);
    }

    @Bean
    public OrderService orderService(MatchingEngine engine, EventJournal journal,
                                     MarketDataService marketDataService,
                                     SimulatedMarketFeeder feeder) {
        return new OrderService(engine, journal, marketDataService, feeder);
    }
}
