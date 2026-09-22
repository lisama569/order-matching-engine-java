package com.github.lisama569.ordermatchingenginejava.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 服务启动后立即按既有流水回放，重建订单簿与账户状态。
 */
@Component
public class ReplayStartupRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ReplayStartupRunner.class);

    private final TradingService tradingService;

    public ReplayStartupRunner(TradingService tradingService) {
        this.tradingService = tradingService;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("STARTUP 开始重放既有流水");
        tradingService.replay();
        log.info("STARTUP 重放结束，订单簿与成交结论已恢复");
    }
}
