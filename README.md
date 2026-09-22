# 本地限价单撮合与执行服务（order-matching-engine-java）

进程内、可并发访问的限价/市价单撮合服务。用模拟行情流驱动撮合，
以命令流水（WAL）保证**重复请求、乱序行情、服务重启**后的成交结论一致且可解释。

## 快速开始

```bash
# 构建 + 全部测试
mvn test

# 启动（默认 8080，WAL 写入 data/journal.jsonl）
mvn spring-boot:run
# 或
java -jar target/order-matching-engine-java-1.0-SNAPSHOT.jar
```

示例：

```bash
# 1. 下限价买单（alice 有现金种子）
curl -s -X POST localhost:8080/api/orders -H 'Content-Type: application/json' -d \
  '{"clientOrderId":"o-1","accountId":"alice","symbol":"AAPL","side":"BUY","type":"LIMIT","limitPrice":100,"quantity":10}'

# 2. 下限价卖单（alice 持有 AAPL 种子持仓；演示中 bob 持有 MSFT）
curl -s -X POST localhost:8080/api/orders -H 'Content-Type: application/json' -d \
  '{"clientOrderId":"o-2","accountId":"alice","symbol":"AAPL","side":"SELL","type":"LIMIT","limitPrice":100,"quantity":10}'

# 3. 推送行情 seq=1 触发撮合
curl -s -X POST localhost:8080/api/market/ticks -H 'Content-Type: application/json' -d \
  '{"seq":1,"symbol":"AAPL","price":100}'

# 4. 查询成交 / 订单 / 占用
curl -s localhost:8080/api/fills
curl -s localhost:8080/api/orders/o-1
curl -s localhost:8080/api/accounts/alice/symbols/AAPL/exposure
```

重启验证：直接重启进程即可，`data/journal.jsonl` 会被自动回放，
订单簿、占用、成交流水与停机前一致（`ReplayTest` 用同一份 WAL 逐项断言）。

## 架构

```
HTTP ─ OrderController ── OrderService（WAL write-ahead + 回放编排）
                              │
        ┌─────────────────────┼──────────────────────────┐
   MatchingEngine        AccountLedger              MarketDataService
   （全局锁串行化：        （资金/持仓/占用台账，      SimulatedMarketFeeder
    下单/撤单/行情）        敞口上限，守恒校验）        （进程内模拟行情源）
        │
   OrderBook（单标的：价格优先、时间优先、市价单优先）
```

- **线程模型**：`MatchingEngine` 用一把可重入锁串行化所有状态变更；
  锁内不做 IO，WAL 追加在锁外（OrderService 先写日志再进引擎）。
- **确定性**：订单号、入队序号、成交序号、行情期望序号都是引擎实例内的单调计数；
  同一命令序列必然产生同一状态序列，因此重放可逐字节复现。

## 撮合优先级

1. **价格优先**：买单价高者优先、卖单价低者优先。
2. **时间优先**：同价按入队序号（先到先成交）。
3. **市价单优先**：市价单在同方向队列中排在限价单之前（仍按入队时间排序）。
4. **成交条件**：行情价 `P` 到达时，买单要求 `P ≤ 参考价`、卖单要求 `P ≥ 参考价`
   （参考价 = 限价单的限价 / 市价单的保护价）。不满足则继续挂单，等待后续行情。
   市价单保护价同时用于买单冻结与防穿仓，因此市价单不会以超出保护价的价格成交。
5. **成交价**：按行情价 `P` 成交，允许价格改善（买单实际支出可低于冻结额，差额自动退回）。
6. 同一行情内可连续撮合多笔（部分成交的头部订单留在队首，已吃尽的弹出）。

## 占用与释放规则（守恒）

| 动作 | 买单 | 卖单 |
| --- | --- | --- |
| 下单（占用） | 冻结现金 `qty × 参考价`，敞口同额增加 | 冻结持仓 `qty`，敞口增加 `qty × 参考价` |
| 成交 | 按**实际成交价**结算现金、收到持仓；按**参考价**核销冻结与敞口（价格改善差额自动回到可用） | 按**实际成交价**收入现金、付出持仓；按参考价核销冻结与敞口 |
| 撤单 / 拒单 | 按剩余数量精确释放冻结现金与敞口 | 按剩余数量精确释放冻结持仓与敞口 |

- 敞口 = 单账户单标的的「买单冻结现金 + 卖单名义金额」，上限由
  `matching.default-exposure-limit` 控制，占用申请超过上限即整体拒绝（不产生部分占用）。
- 占用申请是原子的：可用资金/持仓不足或敞口不足时**零改动**返回
  `EXPOSURE_LIMIT_EXCEEDED`。
- 台账在释放/结算时做守恒断言（释放不得超过冻结），任何不一致会立即抛错而非静默错账。

## 幂等与拒因

- **下单幂等键** = `clientOrderId`；**撤单幂等键** = `(accountId, clientOrderId)`。
  重复请求返回首次处理结果并带 `duplicate=true`，不产生第二笔占用/成交/撤单。
- 拒因可区分（`RejectReason`）：
  `INVALID_PRICE`（限价非法/非刻度）、`INVALID_PROTECTION_PRICE`（市价单保护价缺失或非法）、
  `INVALID_QUANTITY`（非正/非整手）、`INVALID_ARGUMENT`、`UNKNOWN_ACCOUNT_OR_SYMBOL`、
  `EXPOSURE_LIMIT_EXCEEDED`（资金/持仓/敞口不足）、
  `ORDER_NOT_FOUND`、`ORDER_OWNER_MISMATCH`、`ORDER_NOT_CANCELLABLE`（已成交/已撤销/已拒单）。
- 行情拒因：`TICK_DUPLICATE`（重复）、`TICK_OUT_OF_ORDER`（乱序）、
  `TICK_SEQUENCE_GAP`（断层）、`TICK_INVALID_PRICE`。
  被拒行情**不静默丢弃**：返回明确拒因与期望序号，撮合不发生，状态零改动。
- HTTP 语义：参数错误 400；业务拒绝 422（body 含 `rejectReason`）；
  行情序号冲突 409；成功 200。

## 行情序号与重放语义

- 每个标的的行情序号从 1 开始必须严格连续（`expectedSeq` 由引擎维护）。
- 所有命令（下单/撤单/行情，含将被拒绝的）**先追加 WAL、后执行**；
  被拒命令不改变状态，因此重放时得到相同的拒绝结论，状态演化一致。
- 启动时 `BootstrapSeeder` 先加载种子配置（账户/持仓/敞口，属配置基线不进 WAL），
  再按 WAL 原序回放命令，重建订单簿、台账、占用、成交流水与行情期望序号。
- 删除 `data/journal.jsonl` 即清空全部命令历史（种子配置仍在）。

## 配置（application.properties）

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `matching.tick-size` | 1 | 最小价格单位 |
| `matching.lot-size` | 1 | 最小数量单位 |
| `matching.default-exposure-limit` | 1000000 | 单账户单标的敞口上限 |
| `matching.journal-path` | data/journal.jsonl | WAL 文件路径 |
| `matching.replay-on-startup` | true | 启动时回放 |
| `matching.auto-market-feed` | false | 是否开启定时模拟行情 |
| `matching.symbols.<SYM>` | AAPL=100, MSFT=200 | 标的与初始行情价 |
| `matching.accounts.<ID>` | alice/bob=500000 | 种子现金 |
| `matching.positions[i]` | alice:AAPL=1000, bob:MSFT=1000 | 种子持仓 |

## 测试

```bash
mvn test            # 全部 22 个用例
mvn -Dtest=ConcurrencyTest test   # 并发专项
```

- `OrderBookTest`：价格/时间/市价单优先级、保护价边界、部分成交连续撮合。
- `AccountLedgerTest`：占用-结算-释放守恒、价格改善退款、资金不足与敞口上限两种拒绝且零改动。
- `MatchingEngineTest`：可区分拒因、下单/撤单幂等、行情重复/乱序/断层、端到端结算、账户隔离。
- `ConcurrencyTest`：32 线程并发下单敞口不击穿、16 线程重复提交恰好一笔占用、
  双边并发撮合下现金/持仓总量守恒。
- `ReplayTest`：含成交/撤单/重复提交/乱序行情的命令历史，重启回放后
  成交流水、订单状态、台账、行情期望序号逐项一致。
- `WebIntegrationTest`：REST 端到端（HTTP 状态码 + 拒因 + 幂等标记）。

所有用例在日志中打印关键输入与判定依据（`[case]` / `[stage]` 前缀），
例如：`[case] 32线程并发买单(单笔100, 上限1000): accepted=10 rejected=22 最终敞口=1000`。
