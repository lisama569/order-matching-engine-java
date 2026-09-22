package com.github.lisama569.ordermatchingenginejava.market;

import com.github.lisama569.ordermatchingenginejava.model.MarketTick;
import com.github.lisama569.ordermatchingenginejava.model.TickResponse;
import com.github.lisama569.ordermatchingenginejava.service.TradingService;
import org.springframework.stereotype.Service;

/**
 * 行情门面：外部（HTTP 测试注入或模拟流）的行情统一经此提交给交易服务，
 * 由 TradingService 完成序号完整性校验与按序撮合。
 */
@Service
public class MarketDataService {

    private final TradingService tradingService;

    public MarketDataService(TradingService tradingService) {
        this.tradingService = tradingService;
    }

    public TickResponse publish(MarketTick tick) {
        return tradingService.acceptTick(tick);
    }

    public long expectedSeq(String symbol) {
        return tradingService.getExpectedTickSeq(symbol);
    }

    public long lastPrice(String symbol) {
        return tradingService.getLastPrice(symbol);
    }
}
