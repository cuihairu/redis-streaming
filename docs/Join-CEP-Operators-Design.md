# Join / CEP 算子化设计

> 状态：设计定稿（Phase 1 随附实现）。来源：架构评审遗留待办 C——「`join`/`cep`：包装为 DataStream 算子（现为纯内存工具类，无法参与 pipeline）；join 与 table 的 join 语义二选一」。
> 本文先行落稿，Phase 1 实现以其为准；Phase 2/3 条目仅设计不实现。

## 1. 背景与问题

`join` 与 `cep` 两个模块目前是**调用方驱动的纯内存工具库**：

- `join.StreamJoiner`：`processLeft/processRight` 逐条喂入、同步返回配对结果，缓冲在实例内 `ConcurrentHashMap`；
- `cep.PatternSequenceMatcher` / `PatternMatcher`：调用方在自己的消息循环里调 `process(event, timestamp)`。

两者都**没有接入 `DataStream` 算子链**：用户没法写出 `env.fromMqTopic(...).keyBy(...).<join|match>` 这样的 pipeline，得自己搭消费循环、自己处理分区与生命周期。而两套执行引擎（`runtime` 的 in-memory 引擎与 `runtime` 的 Redis 引擎）都已提供统一的有状态算子底座：

```java
KeyedStream.process(KeyedProcessFunction<K, I, O>)   // 双引擎均已实现
```

其中 Redis 引擎的 `process` 完整支持 processing-time / event-time 定时器（`RedisStreamBuilder` 接线 `ctx.registerProcessingTimeTimer/registerEventTimeTimer` → 算子 `onProcessingTime/onEventTime`）。**算子化因此不需要动任何引擎代码**——把工具逻辑装进 `KeyedProcessFunction` 即可在两引擎跑。

## 2. 语义二选一：DataStream 侧 join 的定位

仓库里有两套「join」：

| | `join` 模块 | `table` 模块（KTable join） |
|---|---|---|
| 输入模型 | 两条**事件流** + 时间窗口 | **changelog 表**（有界、可回放） |
| 输出 | 每次配对即发射（append） | 表视图 / changelog |
| 状态 | 窗口期双缓冲 | 全量键值 |

**决策：DataStream 算子层的 join = stream-stream windowed join（`join` 模块语义）。** KTable join 保持 table 模块内的表语义，不重复提供流算子形态。理由：

1. 输入模型不同——双流无界事件 vs 有界 changelog，强行统一会迫使一方削足适履；
2. KTable join 已有独立文档与测试背书（`docs/Table.md`），语义成立；
3. 流处理运行时的空白面恰是 stream-stream join 算子（Flink 同样两者并存且语义分立）。

边界写进两份文档（`docs/Join.md`、`docs/Table.md`）：涉及「流与流按时间窗口配对」用 join 算子；涉及「表与表按键对齐」用 KTable。

## 3. Join 算子化

### 3.1 输入模型：三方案

stream-stream join 是双输入算子，而当前两引擎的 pipeline 都是单源（`RedisPipelineDefinition` 一 topic 一 group）。三个方案：

| 方案 | 形态 | 引擎改动 | 评估 |
|---|---|---|---|
| A. 引擎级双源 | `fromMqTopics(A, B)`，runner 单循环消费两 topic、两 group、双 offset checkpoint | 大（runner/checkpoint/恢复全要双源化） | 正确姿势但工程量大，与动态伸缩（P4 待办）耦合，远期单列 |
| B. 信封多路复用 | 左右流各自 `map` 成 `Envelope`（tag=left/right、joinKey、时间戳、payload）写入**同一** join 输入 topic；`keyBy(joinKey).process(JoinOperator)` | **零**（纯算子 + 用户级 map） | 立即可用、双引擎通吃；同 key 经 MQ 分区哈希天然路由到同一 runner；checkpoint 复用单 topic offset 语义 |
| C. `DataStream.join(other)` 流式糖 | `a.join(b).where(...).equalTo(...).window(...).apply(...)` | 编译期展开为 B（信封 + 桥接 map） | 语法糖，Phase 2 |

**决策：Phase 1 落方案 B；Phase 2 落 C 作为 B 的语法糖；A 归入 P4 动态伸缩一并设计。**

方案 B 的代价与说明：

- 多一跳 MQ（左右流先写入 join 输入 topic）。对已基于 Redis Streams MQ 的作业是同构操作；吞吐敏感场景可在 Phase 2 后评估 A；
- 信封是公开 API（`join` 模块新增类型），tag 左右、带原始事件时间戳——时间戳由**信封构造端**从 `JoinConfig` 的 extractor 提取后随信封携带，JoinOperator 不再对 payload 做反射式取时。

### 3.2 `StreamJoinOperator<K, L, R, O>`（Phase 1 核心类，落 `join` 模块）

```java
// io.github.cuihairu.redis.streaming.join.operator
Envelope<K, L, R>          // 单流信封：tag(LEFT/RIGHT)、joinKey、timestamp、payload(L|R 二选一)
StreamJoinOperator.asKeyedProcessFunction(JoinConfig<L,R,K> cfg, JoinFunction<L,R,O> fn)
                           // 产 KeyedProcessFunction<K, Envelope<K,L,R>, O>
```

语义（尽量逐点对齐既有 `StreamJoiner`，可复用其窗口谓词逻辑）：

- 实例内按 key 持 `leftBuffer / rightBuffer`（与 `StreamJoiner` 同型的 `Map<K, List<TimestampedElement>>`）；
- 配对谓词锚定**左元素时间戳**（沿用 `StreamJoiner` 修正后的语义：无论哪侧先到，asymmetric 窗口 `afterOnly/beforeOnly` 判定一致）；
- 外连接（LEFT/RIGHT/FULL_OUTER）沿用 B-36 语义：无匹配**立即**发射 `join(elem, null)`；后到的对侧在窗口内命中时**再发射一次** `join(L, R)`——下游须容忍同 key 双记录（文档明示，非 waiting/retract 实现）；
- 状态治理沿用 `JoinConfig.maxStateSize`（超限按最旧时间戳逐条淘汰）与 `stateRetentionTime`（每 key 注册 processing-time 清理定时器，到期清该 key 过期缓冲；窗口跨度 > 保留时长在 `JoinConfig.validate()` 已拦）；
- 与 `StreamJoiner` 的关系：`StreamJoiner` 保留原样（独立场景与既有测试），`StreamJoinOperator` 在窗口谓词/淘汰策略上**委托同一份逻辑**（抽公共 helper 或直接内聚一份实现 + 对照测试钉住行为一致），避免第三套窗口判定。

### 3.3 一致性边界（Phase 1 明示）

- 算子缓冲在**算子实例内存**中，按 MQ 分区隔离（同 key 同分区），**不参与 checkpoint 快照**；
- failover 后：envelope 经 MQ 重放（at-least-once），但窗口内历史缓冲丢失——已发射的配对不会重算、未配对的半边状态丢失。即 join 输出对 failover 是 at-most-once（半边状态丢失）+ envelope 层 at-least-once 的组合，文档必须写明；
- Phase 2：缓冲迁入 keyed `ValueState`（可序列化），随既有 state/checkpoint 机制快照与恢复，failover 语义升级为 at-least-once 重算（配对去重交给下游幂等或 sink 去重）。

## 4. CEP 算子化

### 4.1 `PatternSequenceProcessFunction<K, T>`（落 `cep` 模块）

```java
// io.github.cuihairu.redis.streaming.cep.operator
PatternSequenceProcessFunction.of(PatternSequence<T> pattern, Function<T, Long> timestampExtractor)
                            // 产 KeyedProcessFunction<K, T, EventSequence<T>>
```

语义：

- 实例内按 key 持 `PatternSequenceMatcher` 实例（每 key 独立匹配状态，互不串扰）；
- `within(duration)` 截止：构造时注册 processing-time 定时器，到期把该 key 的过期 partial 序列整体丢弃（超时未完成 = 不匹配，`PatternSequence` 的 within 语义）；
- 输出复用 `cep.EventSequence<T>`；
- 单条件匹配（`Pattern` / `PatternMatcher`）不做算子：无序列状态的需求 `filter` 即可，有界扩展序列的场景归入后续需求，不预造 API。

### 4.2 状态与恢复

与 Join 算子同一边界：partial match 状态在算子实例内存、按分区隔离、不进 checkpoint（Phase 1）；Phase 2 迁 keyed `ValueState`。CEP 的 partial 丢失后果比 join 轻（丢的是未完成序列，不产生错误输出，只是漏报），文档同样明示。

## 5. 分阶段落地

| 阶段 | 内容 | 状态 |
|---|---|---|
| Phase 1 | `Envelope` + `StreamJoinOperator` + `PatternSequenceProcessFunction`；InMemory / Redis 双引擎集成测试；`docs/Join.md`、`docs/CEP.md` 增「算子化」章节；本文档落稿 | 本批实现 |
| Phase 2 | `DataStream.join(other)` 语法糖（编译期展开为信封 + 桥接）；两算子缓冲迁 keyed `ValueState` 进 checkpoint；`TimestampAssigner` 重载接入后评估 event-time join 窗口 | 设计定稿 |
| Phase 3 | 引擎级双源 pipeline（方案 A）、event-time 水位对齐窗口、retract/waiting 语义（外连接二次发射消除） | 远期，与 P4 动态伸缩合并设计 |

## 6. 影响面与兼容性

- `join` / `cep` 模块既有公开 API **零改动**（`StreamJoiner` / `PatternSequenceMatcher` 等原样保留）；新增类型均在各自模块新 `operator` 子包；
- 两模块已 `api project(':core')`，算子实现无新增模块依赖；构建脚本零改动；
- 引擎（runtime 模块）零改动——纯用户级算子接入既有 `keyBy().process()` 通道；
- 新增面全部带单测（窗口谓词对照 `StreamJoiner` 钉一致性）+ 双引擎集成测试（Redis 腿 reachability 门控）。
