package com.github.lisama569.ordermatchingenginejava.service;

import com.github.lisama569.ordermatchingenginejava.account.AccountManager;
import com.github.lisama569.ordermatchingenginejava.journal.EventJournal;
import com.github.lisama569.ordermatchingenginejava.matching.MatchingEngine;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.util.HashSet;

/**
 * 引擎核心 Bean 装配。
 */
@Configuration
@EnableConfigurationProperties(EngineProperties.class)
public class EngineConfig {

    @Bean
    public EventJournal eventJournal(EngineProperties props) {
        return new EventJournal(Path.of(props.journalDir()));
    }

    @Bean
    public MatchingEngine matchingEngine() {
        return new MatchingEngine();
    }

    @Bean
    public TradingService tradingService(EventJournal journal, AccountManager accountManager,
                                         MatchingEngine engine, EngineProperties props) {
        return new TradingService(journal, accountManager, engine,
                props.exposureLimit(), props.marketProtectNum(), props.marketProtectDen(),
                new HashSet<>(props.symbols()));
    }
}
