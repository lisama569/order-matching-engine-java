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

# 5. 改单（无需先撤再下）：把 o-1 改价到 99、改总数量到 20，二者也可只给一个
curl -s -X POST localhost:8080/api/orders/amend -H 'Content-Type: application/json' -d \
  '{"amendId":"a-1","accountId":"alice","clientOrderId":"o-1","newPrice":99,"newQuantity":20}'
# 同一 amendId 重复提交只生效一次（返回 duplicate=true）
```

重启验证：直接重启进程即可，`data/journal.jsonl` 会被自动回放，
订单簿、占用、成交流水与停机前一致（`ReplayTest` 用同一份 WAL 逐项断言）。

## 架构

```
HTTP ─ OrderController ── OrderService（命令锁："追加 WAL + 应用引擎"原子化 + 回放编排）
                              │
        ┌─────────────────────┼──────────────────────────┐
   MatchingEngine        AccountLedger              MarketDataService
   （引擎锁串行化：        （资金/持仓/占用台账，      SimulatedMarketFeeder
    下单/撤单/改单/行情）  敞口上限，守恒校验）        （进程内模拟行情源）
        │
   OrderBook（单标的：价格优先、时间优先、市价单优先；改价重排队）
```

- **线程模型（两层锁）**：
  - `MatchingEngine` 的引擎锁串行化所有状态变更（下单/撤单/改单/行情），锁内不做 IO；
  - `OrderService` 另有一把**命令锁**，把"**追加 WAL + 应用到引擎**"包成一个原子单元。
    仅靠引擎锁并不够：引擎锁只能串行化"执行"那一小段，两个线程仍可能在
    "先写日志、后进引擎"两步之间互相穿插，出现 **A 先落盘却后生效、B 后落盘却先生效**。
    那样在线状态按 B→A 演化，而重启严格按 WAL 的 A→B 回放，两边必然分叉
    （并发改价重排队时，最终价格与盘口先后尤其容易变）。命令锁保证
    **WAL 中命令的先后顺序，恒等于命令真正作用于引擎的先后顺序**。
- **确定性**：订单号、入队序号（含改价重排队产生的新序号）、成交序号、行情期望序号
  都是引擎实例内的单调计数；命令的全序由命令锁唯一确定，同一历史必然产生同一状态序列，
  因此并发混打后重启回放可逐项复现，且同一份历史反复回放结果稳定一致。

## 撮合优先级

1. **价格优先**：买单价高者优先、卖单价低者优先。
2. **时间优先**：同价按入队序号（先到先成交）。
3. **市价单优先**：市价单在同方向队列中排在限价单之前（仍按入队时间排序）。
4. **成交条件**：行情价 `P` 到达时，买单要求 `P ≤ 参考价`、卖单要求 `P ≥ 参考价`
   （参考价 = 限价单的限价 / 市价单的保护价）。不满足则继续挂单，等待后续行情。
   市价单保护价同时用于买单冻结与防穿仓，因此市价单不会以超出保护价的价格成交。
5. **成交价**：按行情价 `P` 成交，允许价格改善（买单实际支出可低于冻结额，差额自动退回）。
6. 同一行情内可连续撮合多笔（部分成交的头部订单留在队首，已吃尽的弹出）。

## 改单（amend）规则

对尚未终结（`NEW` / `PARTIALLY_FILLED`）的挂单可直接改价或改量，无需先撤再下。

- **改什么**：`newPrice` 改参考价（限价单=限价，市价单=保护价），`newQuantity` 改**订单新的总数量
  （含已成交部分，必须严格大于已成交量）；两者至少给一个，给 `null` 表示该项不改。
- **排队优先级**：
  - **只要显式带了 `newPrice`（即改价，哪怕数值恰好与原价相同）**，订单按改单时刻
    **重新排队、刷新入队序号，失去原来的时间优先**；
  - **只改量不改价**（`newPrice=null`）**保留原排队先后**，仅原地更新数量；
  - 改价后市价单的市价优先、价格优先、时间优先规则照常生效，保护价约束不变。
- **成交连续性**：部分成交后的订单改量按**剩余数量**调整占用，已成交部分不受影响。

## 占用与释放规则（守恒）

| 动作 | 买单 | 卖单 |
| --- | --- | --- |
| 下单（占用） | 冻结现金 `qty × 参考价`，敞口同额增加 | 冻结持仓 `qty`，敞口增加 `qty × 参考价` |
| 成交 | 按**实际成交价**结算现金、收到持仓；按**参考价**核销冻结与敞口（价格改善差额自动回到可用） | 按**实际成交价**收入现金、付出持仓；按参考价核销冻结与敞口 |
| 撤单 / 拒单 | 按剩余数量精确释放冻结现金与敞口 | 按剩余数量精确释放冻结持仓与敞口 |
| 改单（按剩余数量） | 需求量=`剩余量×新参考价`，相对旧需求**净增**则原子追加冻结现金/敞口，**净减**立即释放 | 持仓冻结按剩余量增量追加/释放，敞口按 `剩余量×新参考价` 调整，二者可分别增减 |

- **改单占用守恒（要么全成，要么不动）**：引擎先在台账上做一次**原子的增量调整**——
  需要追加现金/持仓/敞口时，若任一可用额度不足，整体返回 `EXPOSURE_LIMIT_EXCEEDED`，
  **订单价格、数量与已占用额度一点都不变**；需求量减少时，多占用部分在同一临界区内立即释放。
  无论改单成功还是失败，现金、持仓、敞口账面都不会多占、漏放或双花。
- 部分成交后改单只对**剩余数量**对应的占用做差额调整，已结算部分不再变动。
- 敞口 = 单账户单标的的「买单冻结现金 + 卖单名义金额」，上限由
  `matching.default-exposure-limit` 控制，占用申请超过上限即整体拒绝（不产生部分占用）。
- 占用申请是原子的：可用资金/持仓不足或敞口不足时**零改动**返回
  `EXPOSURE_LIMIT_EXCEEDED`。
- 台账在释放/结算时做守恒断言（释放不得超过冻结），任何不一致会立即抛错而非静默错账。

## 幂等与拒因

- **下单幂等键** = `clientOrderId`；**撤单幂等键** = `(accountId, clientOrderId)`；
  **改单幂等键** = `(accountId, clientOrderId, amendId)`。
  重复请求返回首次处理结果并带 `duplicate=true`，不产生第二笔占用/成交/撤单/占用调整。
  即同一改单请求（相同 `amendId`）重复提交**只生效一次**；对同一订单用**不同 `amendId`**
  发起的后续改单会正常再次生效。
- 拒因可区分（`RejectReason`）：
  `INVALID_PRICE`（限价非法/非刻度）、`INVALID_PROTECTION_PRICE`（市价单保护价缺失/非法，含改单）、
  `INVALID_QUANTITY`（非正/非整手）、`INVALID_ARGUMENT`、`UNKNOWN_ACCOUNT_OR_SYMBOL`、
  `EXPOSURE_LIMIT_EXCEEDED`（资金/持仓/敞口不足，**含改单追加占用不足**）、
  `ORDER_NOT_FOUND`、`ORDER_OWNER_MISMATCH`（撤单/改单对象不属于该账户）、
  `ORDER_NOT_CANCELLABLE`（已成交/已撤销/已拒单不可撤）。
  改单另有独立拒因，与下单失败区分：`AMEND_INVALID_PRICE`（改后限价非法/非刻度）、
  `AMEND_INVALID_QUANTITY`（改后数量非正/非整手，或不大于已成交量）、
  `AMEND_NO_CHANGE`（既没给新价也没给新量）、`ORDER_NOT_AMENDABLE`（订单已终结，不可改）。
- 行情拒因：`TICK_DUPLICATE`（重复）、`TICK_OUT_OF_ORDER`（乱序）、
  `TICK_SEQUENCE_GAP`（断层）、`TICK_INVALID_PRICE`。
  被拒行情**不静默丢弃**：返回明确拒因与期望序号，撮合不发生，状态零改动。
- HTTP 语义：参数错误 400；业务拒绝 422（body 含 `rejectReason`，含改单拒绝）；
  行情序号冲突 409；成功 200。改单端点为 `POST /api/orders/amend`。

## 行情序号与重放语义

- 每个标的的行情序号从 1 开始必须严格连续（`expectedSeq` 由引擎维护，为唯一权威值；
  `MarketDataService` 门面的期望序号在每条行情处理后——**无论接受还是被拒**——都向引擎同步）。
- 所有命令（下单/撤单/**改单**/行情，含将被拒绝的）**先追加 WAL、后执行**；
  被拒命令不改变状态，因此重放时得到相同的拒绝结论，状态演化一致。
- **并发混打后的重启一致性（本次修复重点）**：下单、撤单、改单、行情被多线程同时打入时，
  `OrderService` 的命令锁把每条命令的"落盘 + 执行"原子化，从而给所有命令确定唯一全序。
  无论线程怎样交错：
  - 重启后按 WAL 回放得到的**订单簿挂单、盘口排队先后（含并发改价重排队后的入队序号）、
    每张订单最终价格/数量、账户资金/持仓/占用、成交流水（逐笔 maker/taker/价/量/序号）、
    每个标的的行情期望序号**，都与停机前逐项一致；
  - **"谁最后生效"唯一确定**：几笔不同 `amendId` 的并发改单打在同一张单或相邻单上，
    最终生效的价格/数量/排队位置由命令全序中最后一条决定，回放按同一全序复现；
  - 被叫停的命令（对象不存在、非属主、已全部成交/已撤销/已拒绝、改后价量非法、
    追加占用不足等）在并发下同样**零副作用**，不会改动订单与占用，也不会让回放分叉；
  - 占用守恒在并发下依旧成立：不多占、不漏放、不双花；同一幂等键的重复提交
    （同 `clientOrderId` / 同撤单键 / 同 `amendId`）即使多线程并发也恰好生效一次。
  - 同一份历史连续回放、同一用例反复多跑，结果稳定相同（无时间/调度相关的随机分支）。
- **保证范围与边界**：一致性针对的是"**已成功落盘并被引擎接受执行的命令序列**"。
  进程在某条命令"已追加 WAL、尚未执行"的窗口内崩溃时，重启会把这条已落盘命令照常重放
  （WAL 的 write-ahead 语义，属正常恢复，不产生分叉）；未落盘的命令本来就不构成历史。
  行情仍要求每个标的序号严格连续：并发下若一条更大序号先于较小序号被处理，
  后者会得到与单线程相同的 `TICK_OUT_OF_ORDER`/`TICK_DUPLICATE` 结论并被回放复现。
- 改单命令完整记录 `amendId / accountId / clientOrderId / newPrice / newQuantity`，
  回放时按原序重新执行：改价重排队产生的**新入队序号**、只改量保留的**原序号**、
  价格/数量、占用增减、成交结果与幂等结论都会逐字节复现，因此重启后订单簿挂单与优先级、
  账户占用、成交流水、行情进度与停机前一致。WAL 的 `AMEND` 记录是**向后兼容追加**：
  JSON 中新增 `amend` 字段，旧的 SUBMIT/CANCEL/TICK 行仍可正常反序列化；
  本次未改动任何落盘格式，**之前已经落盘的历史可照常加载回放**。
- 启动时 `BootstrapSeeder` 先加载种子配置（账户/持仓/敞口，属配置基线不进 WAL），
  再在命令锁内按 WAL 原序回放命令（回放期间在线命令一律等待，不会插入历史之间），
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
mvn test            # 全部 37 个用例
mvn -Dtest=AmendOrderTest test        # 改单引擎专项
mvn -Dtest=AmendReplayTest test       # 改单重启回放专项
mvn -Dtest=AmendConcurrencyTest test  # 改单并发专项（引擎层，不经 WAL）
mvn -Dtest=ConcurrencyTest test       # 下单/撮合并发专项（引擎层，不经 WAL）
mvn -Dtest=ConcurrentReplayConsistencyTest test  # 并发混打+重启回放一致性（端到端，经同一份 WAL）
```

- `OrderBookTest`：价格/时间/市价单优先级、保护价边界、部分成交连续撮合。
- `AccountLedgerTest`：占用-结算-释放守恒、价格改善退款、资金不足与敞口上限两种拒绝且零改动。
- `MatchingEngineTest`：可区分拒因、下单/撤单幂等、行情重复/乱序/断层、端到端结算、账户隔离。
- `AmendOrderTest`：改价重新排队失去时间优先、只改量保留优先级、买卖单占用增减守恒、
  追加占用不足整体拒绝且价格/数量/占用零改动、部分成交后按剩余量调整、市价单改保护价、
  全部改单拒因（不存在/非属主/不可改/无变化/价量非法/不大于已成交）、同一改单幂等只生效一次。
- `AmendReplayTest`：含改价重排队、改量、占用增减、被拒改单、重复改单、成交与撤单的历史，
  重启回放后订单价/量/状态/**入队序号**、双方台账、成交流水逐项一致，并验证回放后可继续撮合。
- `AmendConcurrencyTest`：16 线程并发改单占用精确等于在簿需求（不多占/漏放/双花）、
  同一 amendId 并发重复提交恰好生效一次、32 线程把订单往超额改时全部被拒且不击穿敞口上限。
  （该用例直接打引擎锁，验证引擎层守恒，不经过 WAL。）
- `ConcurrentReplayConsistencyTest`（**本次新增，覆盖旧并发用例的盲区**）：12 线程在同一
  栅栏后真正并发地把**下单 / 撤单 / 改单（改价重排队、只改量、价量同改、同 amendId 并发
  重复、非法价量/无变化/对象不存在等必拒改单）/ 行情（两个标的各自连续 40 条，与命令线程
  同时跑，真实成交）**混着打进同一份 WAL；随后：
  1. 用一个全新实例按该 WAL 回放，逐项比对**成交流水（逐笔 maker/taker/价/量/序号）、
     每张订单价格/总量/已成交/状态/入队序号/内部订单号、每个标的买卖盘口的排队先后、
     所有账户-标的的现金/持仓/冻结/可用/敞口、每个标的行情期望序号**；
  2. 用第二个全新实例对同一份 WAL **再回放一遍**，要求三方一致、且连放稳定相同；
  3. 再用两个全新引擎按 WAL 原序逐条重放，比对每条命令的
     接受/拒绝/幂等/拒因结论完全一致。
  整个场景在一个用例内**连跑 5 轮**（每轮独立 WAL、固定随机种子保证可复现）；
  建议本地再用下面的命令反复多轮确认稳定。修复前该用例可稳定复现回放分叉
  （同样的成交在停机前与回放后对应到不同订单），修复后连跑全绿。
- `ConcurrencyTest`：32 线程并发下单敞口不击穿、16 线程重复提交恰好一笔占用、
  双边并发挂单后连续行情撮合下现金/持仓总量守恒。
  （引擎层并发守恒用例：并发阶段只下单、行情在收口后单线程推、不经 WAL/不做重启回放；
  "并发推行情 + 重启回放"由 `ConcurrentReplayConsistencyTest` 覆盖。）
- `ReplayTest`：含成交/撤单/重复提交/乱序行情的命令历史，重启回放后
  成交流水、订单状态、台账、行情期望序号逐项一致。
- `WebIntegrationTest`：REST 端到端（HTTP 状态码 + 拒因 + 幂等标记），含改价/改量/
  改单拒因/改单幂等；该测试使用独立 WAL 且关闭启动回放，可连续多次重复执行。

所有用例在日志中打印关键输入与判定依据（`[case]` / `[stage]` 前缀），
例如：`[case] 32线程并发买单(单笔100, 上限1000): accepted=10 rejected=22 最终敞口=1000`。

## 本地验证改单能力

```bash
mvn spring-boot:run            # 另开终端执行下面的 curl

# 下限价买单并占用
curl -s -X POST localhost:8080/api/orders -H 'Content-Type: application/json' -d \
  '{"clientOrderId":"o-1","accountId":"alice","symbol":"AAPL","side":"BUY","type":"LIMIT","limitPrice":100,"quantity":10}'
# 查占用（cashReserved=1000）
curl -s localhost:8080/api/accounts/alice/symbols/AAPL/exposure

# 只改量 10->20（保留排队），占用变 2000
curl -s -X POST localhost:8080/api/orders/amend -H 'Content-Type: application/json' -d \
  '{"amendId":"a-1","accountId":"alice","clientOrderId":"o-1","newQuantity":20}'
# 改价 100->99（重新排队，失去时间优先），占用按新参考价变为 20*99=1980
curl -s -X POST localhost:8080/api/orders/amend -H 'Content-Type: application/json' -d \
  '{"amendId":"a-2","accountId":"alice","clientOrderId":"o-1","newPrice":99}'
# 重复提交 a-2 只返回首次结果（duplicate=true），占用不再变化
curl -s -X POST localhost:8080/api/orders/amend -H 'Content-Type: application/json' -d \
  '{"amendId":"a-2","accountId":"alice","clientOrderId":"o-1","newPrice":99}'

# 直接重启进程（Ctrl-C 后再次 mvn spring-boot:run），订单簿/优先级/占用按 data/journal.jsonl 回放复原
```

## 本地验证并发回放一致性（含反复多跑）

并发问题偶发，建议用重复执行加压。该专项用例自身连跑 5 轮（固定随机种子），
再配合外层多次启动 Maven / Surefire 重复，可稳定确认"并发混打→重启回放"无分叉：

```bash
# 专项：单独反复跑（每次 JVM 启动内含 5 个并发场景 ×2 次回放 + 逐命令决策比对）
for i in $(seq 1 10); do
  mvn -q -Dtest=ConcurrentReplayConsistencyTest test || break
done

# 全量回归反复跑（下单/撤单/行情/回放/改单既有用例都不应回归）
for i in $(seq 1 5); do
  mvn -q test || break
done
```

也可在 IDE 中对 `ConcurrentReplayConsistencyTest` 选择"重复运行 N 次"反复加压；
命令内容由固定随机种子生成，失败信息会直接打印是哪一笔成交 / 哪张订单 / 哪个账户维度
或哪条命令决策在停机前与回放后对不上。

手工端到端验证（可选）：开 `mvn spring-boot:run`，用多个后台作业/`ab`/`wrk`
同时打 `/api/orders`、`/api/orders/cancel`、`/api/orders/amend`、`/api/market/ticks`
（注意每个标的的行情序号要由单一计数器连续分配），跑一段时间后 Ctrl-C 重启，
用 `/api/fills`、`/api/orders/{id}`、`/api/accounts/{a}/symbols/{s}/exposure`
比对停机前后；重启后期望序号恢复，重复行情返回 `TICK_DUPLICATE`。
