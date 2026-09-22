package com.github.lisama569.ordermatchingenginejava.web;

import com.github.lisama569.ordermatchingenginejava.account.Account;
import com.github.lisama569.ordermatchingenginejava.account.AccountManager;
import com.github.lisama569.ordermatchingenginejava.market.MarketDataService;
import com.github.lisama569.ordermatchingenginejava.matching.MatchingEngine;
import com.github.lisama569.ordermatchingenginejava.model.AccountSnapshot;
import com.github.lisama569.ordermatchingenginejava.model.CancelRequest;
import com.github.lisama569.ordermatchingenginejava.model.CancelResponse;
import com.github.lisama569.ordermatchingenginejava.model.MarketTick;
import com.github.lisama569.ordermatchingenginejava.model.Order;
import com.github.lisama569.ordermatchingenginejava.model.OrderRequest;
import com.github.lisama569.ordermatchingenginejava.model.OrderResponse;
import com.github.lisama569.ordermatchingenginejava.model.TickResponse;
import com.github.lisama569.ordermatchingenginejava.service.TradingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HTTP 接口（本地验证与演示用）。
 *
 * <ul>
 *   <li>POST /api/accounts                开户/入金</li>
 *   <li>GET  /api/accounts/{id}           账户余额/占用/敞口快照</li>
 *   <li>POST /api/orders                  下限价/市价单（body 为 OrderRequest）</li>
 *   <li>POST /api/orders/cancel           撤单（body 为 CancelRequest）</li>
 *   <li>GET  /api/orders/{id}             查订单</li>
 *   <li>GET  /api/book/{symbol}           查订单簿档位</li>
 *   <li>POST /api/ticks                   注入行情（body 为 MarketTick）</li>
 *   <li>GET  /api/ticks/expected/{symbol} 查期望行情序号</li>
 * </ul>
 */
@RestController
@RequestMapping("/api")
public class TradingController {

    private final TradingService tradingService;
    private final MarketDataService marketDataService;
    private final MatchingEngine engine;
    private final AccountManager accountManager;

    public TradingController(TradingService tradingService,
                             MarketDataService marketDataService,
                             MatchingEngine engine,
                             AccountManager accountManager) {
        this.tradingService = tradingService;
        this.marketDataService = marketDataService;
        this.engine = engine;
        this.accountManager = accountManager;
    }

    @PostMapping("/accounts")
    public Map<String, Object> createAccount(@RequestParam String accountId,
                                             @RequestParam(defaultValue = "0") long initialCash) {
        Account a = tradingService.createAccount(accountId, initialCash);
        return Map.of("accountId", a.getAccountId(), "cash", a.getCash());
    }

    @GetMapping("/accounts/{id}")
    public AccountSnapshot account(@PathVariable String id) {
        return tradingService.snapshot(id);
    }

    @PostMapping("/orders")
    public OrderResponse placeOrder(@RequestBody OrderRequest request) {
        return tradingService.placeOrder(request);
    }

    @PostMapping("/orders/cancel")
    public CancelResponse cancel(@RequestBody CancelRequest request) {
        return tradingService.cancel(request);
    }

    @GetMapping("/orders/{id}")
    public Order order(@PathVariable String id) {
        return engine.getOrder(id);
    }

    @GetMapping("/book/{symbol}")
    public Map<String, Object> book(@PathVariable String symbol) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("symbol", symbol);
        out.put("bids", engine.getBidDepth(symbol));
        out.put("asks", engine.getAskDepth(symbol));
        return out;
    }

    @PostMapping("/ticks")
    public TickResponse tick(@RequestBody MarketTick tick) {
        return marketDataService.publish(tick);
    }

    @GetMapping("/ticks/expected/{symbol}")
    public Map<String, Object> expectedTick(@PathVariable String symbol) {
        return Map.of("symbol", symbol,
                "expectedSeq", marketDataService.expectedSeq(symbol),
                "lastPrice", marketDataService.lastPrice(symbol));
    }
}
