# 本地限价单撮合与执行服务（order-matching-engine-java）

一个可并发访问的**进程内**限价单/市价单撮合与执行服务：用模拟行情流与本地账户余额驱动撮合，
通过追加式事件流水（WAL）保证「价格优先、时间优先」的成交结论在**重复请求、乱序行情、
服务重启**后保持一致且可解释。

- Java 21 + Spring Boot 3.4，零外部中间件（无数据库/无消息队列），单进程可运行
- 价格、数量一律用 `long` 整数（价格单位：**分**），杜绝浮点误差导致重放不一致
- 单把状态锁 + 追加日志，26 个测试覆盖并发下单、重复提交、乱序/断层行情与重启重放

---

## 1. 目录结构

```
src/main/java/com/github/lisama569/ordermatchingenginejava
├── model/           # 订单、成交、行情、请求/响应、失败原因枚举
├── account/         # 账户记账原语（现金/持仓/冻结，守恒）
├── matching/        # 撮合引擎：订单簿 + 价格优先时间优先
├── market/          # 行情门面 + 进程内模拟行情流（可开关）
├── journal/         # 追加式事件流水：落盘与回放
├── service/         # TradingService：校验/占用/幂等/结算/重放编排
└── web/             # REST 接口与全局异常处理
```

## 2. 撮合优先级

1. **价格优先**：买盘取价格最高者、卖盘取价格最低者。
2. **时间优先**：同价时 `arrivalSeq` 小者先成交。`arrivalSeq` 是引擎内全局单调递增的
   到达序号（不使用墙钟时间），因此同一流水重放时成交顺序逐笔确定。
3. **成交价 = 挂单价**（resting maker price）：谁先挂单谁定价。即使新到的限价单价格更优，
   也按对手挂单价成交，差额作为买方预占资金的释放部分返还。
4. **成交顺序**：
   - 新订单进入：先与对手盘逐笔成交；限价单剩余进入订单簿，**市价单剩余直接撤销、不挂簿**。
   - 行情进入：按行情价扫簿，先卖侧（卖一价 ≤ 行情价）后买侧（买一价 ≥ 行情价），
     对手方为内部 `MARKET` 账户（模拟外部流动性）。

## 3. 下单校验与失败原因（可区分）

| 失败原因 (`RejectReason`) | 触发条件 |
| --- | --- |
| `INVALID_PARAMETER` | 账户/标的/方向/类型/幂等键缺失或非法 |
| `UNKNOWN_SYMBOL` / `UNKNOWN_ACCOUNT` | 标的或账户不存在 |
| `INVALID_PRICE` | 限价 ≤ 0；市价单携带价格 |
| `INVALID_QUANTITY` | 数量 ≤ 0 |
| `INSUFFICIENT_CASH` | 买单可用资金不足以完成预占 |
| `INSUFFICIENT_POSITION` | 卖单可用持仓不足以冻结 |
| `EXPOSURE_LIMIT_EXCEEDED` | 下单后单账户单标的在簿敞口超过 `matching.exposure-limit` |
| `NO_LIQUIDITY` | 市价单无最近价参考，或簿中无任何可成交对手盘（零成交） |
| `ORDER_NOT_FOUND` | 撤单目标不存在或不属于该账户 |
| `ORDER_NOT_CANCELLABLE` | 撤销已全部成交/已撤销/已拒单的订单 |
| `TICK_DUPLICATE` / `TICK_OUT_OF_ORDER` / `TICK_GAP` / `TICK_INVALID_PRICE` | 行情重复 / 乱序 / 断层 / 价格非法 |

被拒订单**不入簿、不占用任何资源**；被拒操作不会改变其他账户的任何状态。
市价单深度不足时：能成交多少成交多少，剩余撤销（`CANCELLED`，不产生额外失败原因；
若零成交则为 `REJECTED + NO_LIQUIDITY`）。

## 4. 资金与持仓占用、释放规则（守恒）

账户维护四条量：`cash`（现金总额）、`reservedCash`（买单预占）、
`positions`（总持仓）、`reservedPositions`（卖单冻结持仓）。

不变量（任何时刻成立）：

```
cash            = availableCash     + reservedCash
positions[sym]  = availablePosition + reservedPosition[sym]
```

- **限价买单**：下单时按 `limitPrice × orderQty` 预占资金。
- **市价买单**：需要最近价作为保护价（默认 `最近价 × 110%`，可配置），
  按 `保护价 × orderQty` 预占，保证在簿期间即使价格波动也不会出现负可用资金。
- **卖单**：下单时按 `orderQty` 冻结标的持仓。
- **成交结算**：
  - 买方按**实际成交价 × 成交量**扣现金、加持仓，并释放对应预占
    （限价单成交时，预占价与成交价的差额当场返还；市价单按保护价口径返还差额）；
  - 卖方按成交价加现金、扣减持仓，并逐股解冻。
- **撤单/市价余单撤销/拒单**：剩余预占资金或冻结持仓**全额释放**。
- **单账户单标的敞口**：定义为该账户在该标的全部活跃订单的剩余占用名义金额之和
  （买单按限价/保护价，卖单按数量金额）。每次成交、撤单后，系统以订单簿为基准
  **重算敞口**（而非增量递减），从机制上杜绝双花或漏放。

## 5. 并发模型

- 所有改变状态的操作（下单的校验+占用+簿记、撤单释放、行情扫簿结算）在**单把
  `ReentrantLock`** 下原子完成。
- 并发下单时「先检查后占用」是同一个临界区，因此敞口上限被严格遵守
  （见 `ConcurrentExposureTest`：640 笔并发买单，最终在簿敞口恰好顶满上限，其余被明确拒绝）。
- 撤单与吃单并发时，每笔挂单**要么被撤、要么被吃**，不可能既撤又吃
  （见 `ConcurrentCancelMatchTest`）。

## 6. 幂等语义

- 幂等键为 `(accountId, clientRequestId)`。
- 同一幂等键的**重复下单**返回首次结论（`duplicate=true`、同一 `orderId`、同样的成交与状态），
  不会产生第二次成交或第二次占用——即使首次结论是拒单，也返回首次的拒单原因。
- **重复撤单**返回首次撤单结论（`duplicate=true`），释放金额不重复计算。
- 幂等键的作用域是账户：不同账户使用相同 `clientRequestId` 互不影响。

## 7. 行情顺序、完整性与重放

- 每个标的维护独立的、从 1 开始严格递增的期望序号。只接受 `seq == 期望序号` 的行情：
  - `seq == 期望-1` → `TICK_DUPLICATE`；更小 → `TICK_OUT_OF_ORDER`；
    更大 → `TICK_GAP`（响应中带回 `expectedSeq` 供调用方补齐）。
  - 非法行情**不会**静默丢弃：序号水位不推进、不触发任何撮合，并返回可区分原因。
- **事件流水（`data/journal/events.journal`）**：所有确定性输入「先落盘再执行」：
  `ACCOUNT`（开户）、`POSITION`（划入持仓）、`PRICE`（基准价）、
  `ORDER`（下单，含首次确定的 orderId/arrivalSeq/eventTime；拒单也落盘并带原因）、
  `CANCEL`、`TICK`（仅被接受的行情）、`SNAP`（编号水位）。
  成交结果不单独落盘——它是上述输入的确定性产物。
- **重启重放**：服务启动时 `ReplayStartupRunner` 按流水顺序回放，重建账户、订单簿、
  行情序号与幂等表；重放后订单簿档位、每笔成交价量、账户余额/冻结、期望行情序号
  与重启前逐笔一致（见 `ReplayRestartTest`），随后可继续按 `expectedSeq` 接行情、
  旧的 `clientRequestId` 仍命中幂等。

## 8. REST 接口

| 方法 & 路径 | 说明 |
| --- | --- |
| `POST /api/accounts?accountId=&initialCash=` | 开户/入金（幂等） |
| `GET  /api/accounts/{id}` | 余额/冻结/敞口快照 |
| `POST /api/orders` | 下单，body 为 `OrderRequest` JSON |
| `POST /api/orders/cancel` | 撤单，body 为 `CancelRequest` JSON |
| `GET  /api/orders/{id}` | 查订单 |
| `GET  /api/book/{symbol}` | 订单簿买卖档位 |
| `POST /api/ticks` | 注入行情，body 为 `MarketTick` JSON |
| `GET  /api/ticks/expected/{symbol}` | 期望行情序号与最近价 |

> 持仓划入（测试/交割用）在 Java API 上为 `TradingService#depositPosition`。

## 9. 配置（`src/main/resources/application.properties`）

```properties
matching.journal-dir=data/journal     # 流水目录
matching.exposure-limit=10000000      # 单账户单标的敞口上限（分）=10 万元
matching.market-protect-num=110       # 市价单保护价 = 最近价 * num/den
matching.market-protect-den=100
matching.symbols[0]=600000            # 注册标的
matching.symbols[1]=BTCUSDT
matching.feed-enabled=false           # 进程内模拟行情定时推送（默认关，测试显式注入）
matching.feed-interval-ms=1000
```

## 10. 本地验证方法

### 10.1 运行全部测试

```bash
mvn test
```

测试清单（26 个用例，均打印关键输入与判定依据日志）：

| 测试类 | 覆盖场景 |
| --- | --- |
| `MatchingPriorityTest` | 价格优先、同价时间优先、成交价取挂单价 |
| `IdempotencyTest` | 重复下单/撤单幂等、拒单重复返回同原因、零重复占用、幂等键账户隔离 |
| `RejectionTest` | 10 种可区分拒单原因、市价无参考价、被拒不影响他人、不可撤状态 |
| `ReservationConservationTest` | 买卖双向占用/成交结算/撤单释放守恒、敞口归零 |
| `ConcurrentExposureTest` | 32 线程 × 20 笔并发买单，敞口上限严格不被突破 |
| `ConcurrentCancelMatchTest` | 100 挂单并发撤单+吃单，每单只可能撤或成交其一 |
| `MarketDataIntegrityTest` | 重复/乱序/断层/非法价行情拒绝、行情扫簿、非法行情不错误撮合 |
| `MarketOrderTest` | 市价单无流动性拒绝、部分成交撤余并退还差额 |
| `ReplayRestartTest` | 重启回放后簿/成交/余额/序号/幂等结论与重启前一致 |
| `WebApiIntegrationTest` | HTTP 全栈：开户→下单→幂等→乱序行情拒绝→成交→快照 |

查看关键日志：

```bash
mvn test -Dtest=MatchingPriorityTest   # 日志关键字 ORDER/TRADE/TICK/CANCEL/REPLAY
cat target/surefire-reports/*.txt
```

### 10.2 启动服务并用 curl 手动验证

```bash
mvn spring-boot:run

# 开户
curl -XPOST "localhost:8080/api/accounts?accountId=A&initialCash=1000000"
curl -XPOST "localhost:8080/api/accounts?accountId=B&initialCash=0"

# 下限价买单（价格 9900 分 = 99.00 元，5 股）
curl -XPOST localhost:8080/api/orders -H 'Content-Type: application/json' -d '{
  "clientRequestId":"req-1","accountId":"A","symbol":"600000",
  "side":"BUY","type":"LIMIT","limitPrice":9900,"orderQty":5}'

# 乱序行情（应被拒：期望 seq=1，发来 2 -> TICK_GAP）
curl -XPOST localhost:8080/api/ticks -H 'Content-Type: application/json' -d \
  '{"symbol":"600000","seq":2,"price":9800,"eventTime":1}'

# 正确序号行情（9800 低于买价 9900，买单与 MARKET 成交 5 股，成交价=挂单价 9900）
curl -XPOST localhost:8080/api/ticks -H 'Content-Type: application/json' -d \
  '{"symbol":"600000","seq":1,"price":9800,"eventTime":2}'

# 重复提交同一下单请求 -> duplicate=true，不重复成交
# 重启进程 -> 启动日志出现 REPLAY，订单簿/账户/序号恢复，再发 seq=2 行情可正常接续
```

## 11. 设计取舍说明

- **单锁而非无锁结构**：撮合路径短（内存簿记 + 一次日志 flush），单锁实现可证明的原子性，
  且使「占用校验与成交结算」天然线性化，优先保证正确性与可解释性。
- **敞口重算而非增量记账**：成交后以活跃订单簿全量重算账户敞口，避免增量误差累积。
- **整数计价、确定性编号**：所有价格为 long 分，成交/到达序号单调，重放结果天然一致。
