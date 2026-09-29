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

# 5. 直接改单（不用先撤再下）：把 o-1 的限价从 100 改到 99、总量从 10 改到 8
curl -s -X POST localhost:8080/api/orders/modify -H 'Content-Type: application/json' -d \
  '{"amendId":"am-1","accountId":"alice","clientOrderId":"o-1","newPrice":99,"newQuantity":8}'
# 同一 amendId 重复提交只生效一次（返回 duplicate=true）；市价单的 newPrice 改的是保护价
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
    下单/撤单/改单/行情）   敞口上限，守恒校验）        （进程内模拟行情源）
        │
   OrderBook（单标的：价格优先、时间优先、市价单优先）
```

- **线程模型**：`MatchingEngine` 用一把可重入锁串行化所有状态变更；
  下单 / 撤单 / 改单 / 行情的 **WAL 追加也在这把锁内、于首个状态判定点原子完成**，
  因此"日志顺序"严格等于"实际执行顺序"。并发命令下即便线程先竞争到追加、
  后竞争到执行，也不会出现日志与执行顺序错位（这是改价重排队能确定性回放的前提）。
  回放阶段不写日志（钩子为空）。
- **确定性**：订单号、入队序号、改单重排序号、成交序号、行情期望序号都是引擎实例内的单调计数；
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

## 改单（modify）

挂单未终结（`NEW` / `PARTIALLY_FILLED`）时可直接改价 / 改量，无需先撤再下，
避免两个动作之间丢掉时间优先或被瞬时占用拒单。

- **接口**：`POST /api/orders/modify`，字段
  `amendId`（本次改单的幂等键）、`accountId`、`clientOrderId`、
  `newPrice`（限价单=新限价；**市价单=新保护价**；不传=不改价）、
  `newQuantity`（新的订单**总**数量，必须大于已成交量；不传=不改量）。
  两者至少传一个。
- **排队规则**：
  - **改了价**（含市价单改保护价）=> 按改单时刻重新发号、重新入队，
    **失去原来的时间优先**，与此刻之后的新单一样按新价格/新序号参与价格优先、时间优先；
  - **只改量**（无论增加还是减少，价格不变）=> **保留原排队先后**，
    原 `enqueueSeq` 不变，盘口位置不动；
  - 市价单优先、价格优先、时间优先规则在改单后继续生效；
    市价单保护价收紧后，超出保护价的行情不再会让它成交（不穿仓）。
- **占用守恒（要么全成，要么不动）**：占用差额按**剩余数量**的名义金额调整。
  - 需要更多现金 / 持仓 / 敞口时，先做额度检查：可用不足则改单**整体拒绝**，
    订单价格、数量与已占用额**一点都不变**（返回 `EXPOSURE_LIMIT_EXCEEDED`，
    不会出现部分追加占用）；
  - 需求量减少时，多出来的冻结现金 / 持仓 / 敞口**立即释放**；
  - 量增价降（或量降价升）时，持仓与敞口、现金分别按各自增量校验/释放，一次原子生效；
  - 改单前后、无论成功失败，账户现金、持仓、敞口账面均无多占 / 漏放 / 双花。
- **部分成交后改单**：改量以"总数量"给出，必须大于已成交量；
  占用只按剩余部分（`新总量 - 已成交`）×新参考价补差或释放。
- **幂等**：改单幂等键 = `(accountId, clientOrderId, amendId)`，
  同一改单请求重复提交只生效一次，重复请求返回首次结果并带 `duplicate=true`；
  不同 `amendId` 是两次独立改单，按到达顺序依次生效。
- **可区分拒因**（与下单失败分离，走改单结果体 `ModifyOrderResult`）：
  `ORDER_NOT_FOUND`（订单不存在）、`ORDER_OWNER_MISMATCH`（不属于该账户）、
  `ORDER_NOT_MODIFIABLE`（已全部成交 / 已撤销 / 已拒单）、
  `MODIFY_NOTHING`（价与量都没给）、`MODIFY_QTY_BELOW_FILLED`（新总量不大于已成交量）、
  `INVALID_PRICE` / `INVALID_PROTECTION_PRICE`（价格非正/非刻度，按订单类型区分）、
  `INVALID_QUANTITY`（数量非正/非整手）、`EXPOSURE_LIMIT_EXCEEDED`（额度不足）。
  参数层面（字段缺失、新价/新量非正）返回 HTTP 400；业务拒绝返回 422。

## 占用与释放规则（守恒）

| 动作 | 买单 | 卖单 |
| --- | --- | --- |
| 下单（占用） | 冻结现金 `qty × 参考价`，敞口同额增加 | 冻结持仓 `qty`，敞口增加 `qty × 参考价` |
| 成交 | 按**实际成交价**结算现金、收到持仓；按**参考价**核销冻结与敞口（价格改善差额自动回到可用） | 按**实际成交价**收入现金、付出持仓；按参考价核销冻结与敞口 |
| 撤单 / 拒单 | 按剩余数量精确释放冻结现金与敞口 | 按剩余数量精确释放冻结持仓与敞口 |
| 改单（价/量） | 按剩余数量的名义金额差额原子补差或释放；增量不足整体拒绝、零改动 | 持仓与名义敞口分别按差额原子补差或释放；减量立即释放 |

- 敞口 = 单账户单标的的「买单冻结现金 + 卖单名义金额」，上限由
  `matching.default-exposure-limit` 控制，占用申请超过上限即整体拒绝（不产生部分占用）。
- 占用申请是原子的：可用资金/持仓不足或敞口不足时**零改动**返回
  `EXPOSURE_LIMIT_EXCEEDED`。
- 台账在释放/结算时做守恒断言（释放不得超过冻结），任何不一致会立即抛错而非静默错账。

## 幂等与拒因

- **下单幂等键** = `clientOrderId`；**撤单幂等键** = `(accountId, clientOrderId)`；
  **改单幂等键** = `(accountId, clientOrderId, amendId)`。
  重复请求返回首次处理结果并带 `duplicate=true`，不产生第二笔占用/成交/撤单/改单。
- 下单拒因可区分（`RejectReason`）：
  `INVALID_PRICE`（限价非法/非刻度）、`INVALID_PROTECTION_PRICE`（市价单保护价缺失或非法）、
  `INVALID_QUANTITY`（非正/非整手）、`INVALID_ARGUMENT`、`UNKNOWN_ACCOUNT_OR_SYMBOL`、
  `EXPOSURE_LIMIT_EXCEEDED`（资金/持仓/敞口不足）、
  `ORDER_NOT_FOUND`、`ORDER_OWNER_MISMATCH`、`ORDER_NOT_CANCELLABLE`（已成交/已撤销/已拒单）。
- 改单专属拒因：`ORDER_NOT_MODIFIABLE`、`MODIFY_NOTHING`、`MODIFY_QTY_BELOW_FILLED`，
  其余（不存在 / 属主不符 / 静态非法 / 额度不足）与下单共用同名枚举但出现在
  `ModifyOrderResult` 中，不会与下单失败混淆。
- 行情拒因：`TICK_DUPLICATE`（重复）、`TICK_OUT_OF_ORDER`（乱序）、
  `TICK_SEQUENCE_GAP`（断层）、`TICK_INVALID_PRICE`。
  被拒行情**不静默丢弃**：返回明确拒因与期望序号，撮合不发生，状态零改动。
- HTTP 语义：参数错误 400；业务拒绝 422（body 含 `rejectReason`）；
  行情序号冲突 409；成功 200。

## 行情序号与重放语义

- 每个标的的行情序号从 1 开始必须严格连续（`expectedSeq` 由引擎维护）。
- 所有命令（下单/撤单/改单/行情，含将被拒绝的）**先追加 WAL、后执行**，且二者在同一把引擎锁内；
  被拒命令不改变状态，因此重放时得到相同的拒绝结论，状态演化一致。
- 改单命令（`MODIFY` 事件，含 `amendId/newPrice/newQuantity`）与其他命令走同一份 WAL；
  重放时依次重放改价（重新发号入队）、改量（保留序号）、占用补差/释放，
  重启后订单簿挂单与价格档位、排队优先级、账户占用、成交流水、行情进度与停机前一致。
- **兼容范围**：WAL 为 JSONL，`MODIFY` 是在事件记录上**新增可空字段**；
  新版本可以直接读取旧版本写出的、不含改单字段与 `MODIFY` 类型的历史日志
  （缺失字段按 null 解析，旧命令的回放结果不变）。反过来，旧版本无法识别 `MODIFY` 类型，
  回滚旧版本前需确认日志中没有改单事件。种子数据（账户/持仓/敞口）仍属配置基线、不进 WAL。
- 启动时 `BootstrapSeeder` 先加载种子配置，再按 WAL 原序回放命令，
  重建订单簿、台账、占用、成交流水与行情期望序号。
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
mvn test            # 全部 35 个用例（可连续重复执行，结果一致）
mvn -Dtest=ConcurrencyTest test        # 并发专项
mvn -Dtest=ModifyOrderEngineTest test  # 改单专项
mvn -Dtest=ModifyReplayTest test       # 改单回放专项
```

- `OrderBookTest`：价格/时间/市价单优先级、保护价边界、部分成交连续撮合。
- `AccountLedgerTest`：占用-结算-释放守恒、价格改善退款、资金不足与敞口上限两种拒绝且零改动。
- `MatchingEngineTest`：可区分拒因、下单/撤单幂等、行情重复/乱序/断层、端到端结算、账户隔离。
- `ModifyOrderEngineTest`（9 个）：改价重排失去时间优先、只改量保留排队、
  全部改单拒因（不存在/属主不符/已终结/无改动/低于已成交/价格数量非法）、
  买单占用增加的原子拒绝与零改动、减量/降价立即释放、卖单持仓与敞口双向调整、
  同 `amendId` 只生效一次、市价单保护价修改后的成交边界、部分成交后按剩余量补差。
- `ConcurrencyTest`：32 线程并发下单敞口不击穿、16 线程重复提交恰好一笔占用、
  双边并发撮合下现金/持仓总量守恒。
- `ModifyConcurrencyTest`（3 个）：同 `amendId` 16 线程并发只生效一次；
  32 个互相冲突的不同改单并发后冻结恒等于"剩余量×最终价"，无多占/漏放；
  下单/撤单/改单/行情全部并发打进同一份 WAL 后，全新进程回放的成交流水、
  订单价/量/排队序号、台账逐项一致。
- `ReplayTest`：含成交/撤单/重复提交/乱序行情的命令历史，重启回放后
  成交流水、订单状态、台账、行情期望序号逐项一致。
- `ModifyReplayTest`：含改价、改量（增/减）、超额拒绝改单、重复改单、
  撤单后改单、属主/不存在拒因与断层行情的混合历史，重启回放后订单价格档位、
  排队序号、台账、成交流水逐项一致；回放后重复改单仍幂等、行情序号正确恢复。
- `WebIntegrationTest`：REST 端到端（HTTP 状态码 + 拒因 + 幂等标记 + 改单全流程），
  使用每次运行独立的临时 WAL，连续多跑不互相污染。

所有用例在日志中打印关键输入与判定依据（`[case]` / `[stage]` 前缀），
例如：`[case] 32线程并发买单(单笔100, 上限1000): accepted=10 rejected=22 最终敞口=1000`。
