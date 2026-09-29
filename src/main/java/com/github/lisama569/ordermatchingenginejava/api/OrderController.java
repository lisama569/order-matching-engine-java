package com.github.lisama569.ordermatchingenginejava.api;

import com.github.lisama569.ordermatchingenginejava.account.AccountLedger;
import com.github.lisama569.ordermatchingenginejava.account.AccountSnapshot;
import com.github.lisama569.ordermatchingenginejava.api.dto.CancelOrderDto;
import com.github.lisama569.ordermatchingenginejava.api.dto.ModifyOrderDto;
import com.github.lisama569.ordermatchingenginejava.api.dto.SubmitOrderDto;
import com.github.lisama569.ordermatchingenginejava.api.dto.TickDto;
import com.github.lisama569.ordermatchingenginejava.model.CancelOrderResult;
import com.github.lisama569.ordermatchingenginejava.model.Fill;
import com.github.lisama569.ordermatchingenginejava.model.MarketTick;
import com.github.lisama569.ordermatchingenginejava.model.ModifyOrderRequest;
import com.github.lisama569.ordermatchingenginejava.model.ModifyOrderResult;
import com.github.lisama569.ordermatchingenginejava.model.Order;
import com.github.lisama569.ordermatchingenginejava.model.OrderType;
import com.github.lisama569.ordermatchingenginejava.model.Side;
import com.github.lisama569.ordermatchingenginejava.model.SubmitOrderRequest;
import com.github.lisama569.ordermatchingenginejava.model.SubmitOrderResult;
import com.github.lisama569.ordermatchingenginejava.model.TickResult;
import com.github.lisama569.ordermatchingenginejava.service.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 下单 / 撤单 / 行情 / 查询 REST 接口。 */
@RestController
@RequestMapping("/api")
public class OrderController {

    private static final Logger log = LoggerFactory.getLogger(OrderController.class);

    private final OrderService orderService;
    private final AccountLedger ledger;

    public OrderController(OrderService orderService, AccountLedger ledger) {
        this.orderService = orderService;
        this.ledger = ledger;
    }

    /** 下单（幂等：相同 clientOrderId 重复提交返回首次结果，不重复占用/成交）。 */
    @PostMapping("/orders")
    public ResponseEntity<SubmitOrderResult> submit(@RequestBody SubmitOrderDto dto) {
        log.info("[api] POST /orders body={}", dto);
        SubmitOrderRequest req = toRequest(dto);
        SubmitOrderResult result = orderService.submit(req);
        HttpStatus status = result.accepted() ? HttpStatus.OK
                : HttpStatus.UNPROCESSABLE_ENTITY;
        return ResponseEntity.status(status).body(result);
    }

    /** 撤单（幂等：重复撤单返回首次结果）。 */
    @PostMapping("/orders/cancel")
    public ResponseEntity<CancelOrderResult> cancel(@RequestBody CancelOrderDto dto) {
        log.info("[api] POST /orders/cancel body={}", dto);
        requireText(dto.accountId(), "accountId");
        requireText(dto.clientOrderId(), "clientOrderId");
        CancelOrderResult result = orderService.cancel(dto.accountId(), dto.clientOrderId());
        HttpStatus status = result.success() ? HttpStatus.OK : HttpStatus.UNPROCESSABLE_ENTITY;
        return ResponseEntity.status(status).body(result);
    }

    /**
     * 改单（幂等：相同 amendId 重复提交返回首次结果，只生效一次）。
     * 改价按改单时刻重新排队、失去原时间优先；只改量保留原排队位置。
     */
    @PostMapping("/orders/modify")
    public ResponseEntity<ModifyOrderResult> modify(@RequestBody ModifyOrderDto dto) {
        log.info("[api] POST /orders/modify body={}", dto);
        requireText(dto.amendId(), "amendId");
        requireText(dto.accountId(), "accountId");
        requireText(dto.clientOrderId(), "clientOrderId");
        if (dto.newPrice() == null && dto.newQuantity() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "newPrice or newQuantity is required");
        }
        if (dto.newQuantity() != null && dto.newQuantity() <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "newQuantity must be positive");
        }
        if (dto.newPrice() != null && dto.newPrice() <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "newPrice must be positive");
        }
        ModifyOrderResult result = orderService.modify(new ModifyOrderRequest(
                dto.amendId(), dto.accountId(), dto.clientOrderId(),
                dto.newPrice(), dto.newQuantity()));
        HttpStatus status = result.success() ? HttpStatus.OK : HttpStatus.UNPROCESSABLE_ENTITY;
        return ResponseEntity.status(status).body(result);
    }

    /** 推送行情（序号必须连续，重复/乱序/断层被拒绝并给出可区分原因）。 */
    @PostMapping("/market/ticks")
    public ResponseEntity<TickResult> tick(@RequestBody TickDto dto) {
        log.info("[api] POST /market/ticks body={}", dto);
        if (dto.seq() == null || dto.seq() <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "tick.seq must be positive");
        }
        requireText(dto.symbol(), "symbol");
        if (dto.price() == null || dto.price() <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "tick.price must be positive");
        }
        TickResult result = orderService.feeder().feed(new MarketTick(dto.seq(), dto.symbol(), dto.price()));
        HttpStatus status = result.accepted() ? HttpStatus.OK : HttpStatus.CONFLICT;
        return ResponseEntity.status(status).body(result);
    }

    /** 查询订单。 */
    @GetMapping("/orders/{clientOrderId}")
    public Order order(@PathVariable String clientOrderId) {
        Order order = orderService.getOrder(clientOrderId);
        if (order == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "order not found: " + clientOrderId);
        }
        return order;
    }

    /** 全量成交流水（序号即撮合先后，重放后保持一致）。 */
    @GetMapping("/fills")
    public List<Fill> fills() {
        return orderService.fills();
    }

    /** 查询账户在某标的上的资金/持仓/占用。 */
    @GetMapping("/accounts/{accountId}/symbols/{symbol}/exposure")
    public AccountSnapshot exposure(@PathVariable String accountId, @PathVariable String symbol) {
        return ledger.snapshot(accountId, symbol);
    }

    private SubmitOrderRequest toRequest(SubmitOrderDto dto) {
        requireText(dto.clientOrderId(), "clientOrderId");
        requireText(dto.accountId(), "accountId");
        requireText(dto.symbol(), "symbol");
        Side side = parseEnum(Side.class, dto.side(), "side");
        OrderType type = parseEnum(OrderType.class, dto.type(), "type");
        if (dto.quantity() == null || dto.quantity() <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "quantity must be positive");
        }
        return new SubmitOrderRequest(dto.clientOrderId(), dto.accountId(), dto.symbol(),
                side, type, dto.limitPrice(), dto.protectionPrice(), dto.quantity());
    }

    private static void requireText(String v, String field) {
        if (v == null || v.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, field + " is required");
        }
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> enumType, String raw, String field) {
        if (raw == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, field + " is required");
        }
        try {
            return Enum.valueOf(enumType, raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid " + field + ": " + raw);
        }
    }
}
