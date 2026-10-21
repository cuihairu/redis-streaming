# Join / CEP 算子化设计

> 状态：设计定稿（Phase 1 随附实现）。来源：架构评审遗留待办 C——「`join`/`cep`：包装为 DataStream 算子（现为纯内存工具类，无法参与 pipeline）；join 与 table 的 join 语义二选一」。
> 本文先行落稿，Phase 1 实现以其为准；Phase 2/3 条目仅设计不实现。

## 1. 背景与问题

`join` 与 `cep` 两个模块在 Phase 1 之前是**调用方驱动的纯内存工具库**（Phase 1 已包装为 DataStream 算子，用法见 [Join.md](Join.md) / [CEP.md](CEP.md)，本节保留当时的问题陈述）：

- `join.StreamJoiner`：`processLeft/processRight` 逐条喂入、同步返回配对结果，缓冲在实例内 `ConcurrentHashMap`；
- `cep.PatternSequenceMatcher` / `PatternMatcher`：调用方在自己的消息循环里调 `process(event, timestamp)`。

Phase 1 之前两者都**没有接入 `DataStream` 算子链**：用户没法写出 `env.fromMqTopic(...).keyBy(...).<join|match>` 这样的 pipeline，得自己搭消费循环、自己处理分区与生命周期。而两套执行引擎（`runtime` 的 in-memory 引擎与 `runtime` 的 Redis 引擎）都已提供统一的有状态算子底座：

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
- 状态治理沿用 `JoinConfig.maxStateSize`（超限按最旧时间戳逐条淘汰）与 `stateRetentionTime`（清理为**事件驱动**：每次配对入口按两侧缓冲的最大时间戳统一清过期，与 `StreamJoiner.cleanup` 逐点一致，算子不另注册引擎定时器；窗口跨度 > 保留时长在 `JoinConfig.validate()` 已拦）；
- 与 `StreamJoiner` 的关系：`StreamJoiner` 保留原样（独立场景与既有测试），`StreamJoinOperator` 在窗口谓词/淘汰策略上**委托同一份逻辑**（抽公共 helper 或直接内聚一份实现 + 对照测试钉住行为一致），避免第三套窗口判定。

### 3.3 一致性边界（Phase 2 起升级为 at-least-once 重算）

- **Phase 1（历史）**：算子缓冲在算子实例内存中，不参与 checkpoint——failover 后未配对的半边状态丢失，join 输出对 failover 是 at-most-once（半边丢失）+ envelope 层 at-least-once 的组合。
- **Phase 2（2026-10-12 落地）**：两侧缓冲迁入 keyed `ValueState`（每 key 一份 JSON 文档，经 `ctx.getState` 读写，见 §3.4），随既有 state/checkpoint 机制快照与恢复。failover 语义升级为 **at-least-once 重算**：MQ 重放 + 状态恢复后配对继续，重放的 envelope 可能再次配对——去重交给下游幂等或 sink 去重。
- 两处相对 raw joiner 实例级记账的**范围收窄**（均为卫生性调整，窗口谓词仍守护正确性，retention/eviction 只是内存管理）：
  - `maxStateSize` 按 **per-key** 计（两侧合计），不再是实例内跨 key 总量；
  - retention 清理的参考时间是**该 key 自己的最新时间戳**，空闲 key 的过期元素活到该 key 下一条事件（或被 cap 逐出），不再被其他 key 的活动清扫。

### 3.4 状态布局（join / cep 算子，Phase 2）

- **通道**：`ctx.getState(new StateDescriptor<>(stateName, String.class))`——`Context.getState` 为 Phase 2 新增的默认方法（默认抛 `UnsupportedOperationException`，两引擎各自覆写接线：内存引擎接 `InMemoryKeyedStateStore`、Redis 引擎接 keyed store）。泛型擦除下 Redis JSON 反序列化会退化为 `LinkedHashMap`，故算子自己持 `ObjectMapper`、把缓冲编码为 **JSON 字符串**存 `ValueState<String>`。
- **确定性命名**：Redis 引擎的 state 键形如 `prefix:jobName:cg:group:topic:p:pid:state:operatorId:stateName`，operatorId 确定性生成（`streamId-keyBy-N`），因此 stateName 必须固定——`__operator:join:buffers` / `__operator:cep:partials`——重启的 job 才能找回上一实例的文档（随机名等于自废持久化）。同一 keyed stream 上不可能挂两个同类算子（`process()` 返回 `DataStream` 非 `KeyedStream`），单一固定名无冲突。
- **join 文档**：`JoinKeyBuffers{left[], right[]}`，元素为自描述 JSON（`BufferedElement{type, json, timestamp}`，type 记运行时类名）——两侧各自独立可解码，不依赖先到的是哪一侧的信封。
- **cep 文档**：`PersistedMatcherState<T>{partials[]}`（`PartialMatchState{startTimestamp, currentStep, eventsByStep}`）。
- mapper 统一 `findAndRegisterModules()` + 关 `WRITE_DATES_AS_TIMESTAMPS`（事件可能带 `Instant` 等 java.time 字段）+ 关 `FAIL_ON_UNKNOWN_PROPERTIES`；`jackson-datatype-jsr310` 以 `implementation` 依赖加入 join/cep 两模块。

## 4. CEP 算子化

### 4.1 `PatternSequenceProcessFunction<K, T>`（落 `cep` 模块）

```java
// io.github.cuihairu.redis.streaming.cep.operator
new PatternSequenceProcessFunction<>(pattern, timestampExtractor)
                            // 产 KeyedProcessFunction<K, T, EventSequence<T>>
// 另有 3 参重载：追加 maxTrackedKeys（0 = 不设上限）
```

语义：

- 实例内按 key 持 `PatternSequenceMatcher` 实例（每 key 独立匹配状态，互不串扰）；
- `within(duration)` 截止由 matcher 自身的**事件驱动清理**承担——`process(event, timestamp)` 入口先按事件时间戳丢弃过期 partial（超时未完成 = 不匹配），算子不另注册引擎定时器；闲置 key 的残留 partial 在该 key 下一条事件到达时清理，或被 key 数上限逐出；
- key 数上限（`maxTrackedKeys`，默认 10000，0 = 不限）：按最近活跃时间逐出最久未触达的 key 的 matcher，防止高基数 key 场景下 matcher 映射无界增长；
- 输出复用 `cep.EventSequence<T>`；
- 单条件匹配（`Pattern` / `PatternMatcher`）不做算子：无序列状态的需求 `filter` 即可，有界扩展序列的场景归入后续需求，不预造 API。

### 4.2 状态与恢复（Phase 2 起进 keyed state）

**Phase 1（历史）**：partial match 状态在算子实例内存、不进 checkpoint，failover 后未完成序列丢失（后果比 join 轻——丢的是未完成序列，不产生错误输出，只是漏报）。

**Phase 2（2026-10-12 落地）**：采用**直写缓存**结构——

- 实例内的 matcher 映射仍是热缓存（每 key 一个 `PatternSequenceMatcher`），每条事件处理后**直写**该 key 的 keyed `ValueState<String>`（无 partial 则 `clear()`）；
- 缓存 miss 时**先水合**：从 keyed state 反序列化该 key 的 partial 列表回 matcher；
- 失效墓碑（`evictedKeys`）：被 `maxTrackedKeys` 逐出的 key 在本实例生命周期内**不得**从 state 复活陈旧 partial（防止逐出后的旧状态与新事件错配）。

**快照可精确恢复的证明**：matcher 的 partial 的 step 序号只增不减，因此 flat 事件序 == 按 step 升序逐级拼接的序——`PatternSequenceMatcher.exportPartialMatches()` 导出（step 升序深拷贝）、`restorePartialMatches()` 按此序重放 `addEvent`，事件顺序与时间戳逐一还原（钉在 `PatternSequenceMatcherStateExportTest`）。

## 5. 分阶段落地

| 阶段 | 内容 | 状态 |
|---|---|---|
| Phase 1 | `Envelope` + `StreamJoinOperator` + `PatternSequenceProcessFunction`；InMemory / Redis 双引擎集成测试；`docs/Join.md`、`docs/CEP.md` 增「算子化」章节；本文档落稿 | 本批实现 |
| Phase 2 | `DataStream.join(other)` 语法糖（编译期展开为信封 + 桥接）；两算子缓冲迁 keyed `ValueState` 进 checkpoint；`TimestampAssigner` 重载接入后评估 event-time join 窗口 | **缓冲迁移本批落地**（2026-10-12，卡点 A 解除，见 §5.1）；语法糖内存侧已落地（2026-10-14，见 §5.2）；Redis 侧 U3 三段编排亦已落地（2026-10-14，落点 `RedisStreamExecutionEnvironment.join`，见 docs/Union-DualSource-Design.md §5），卡点 B 解除 |
| Phase 3 | 引擎级双源 pipeline（方案 A）、event-time 水位对齐窗口、retract/waiting 语义（外连接二次发射消除） | **已落地**：`fromMqTopics` 双源读取与逐源对齐水位（781485d、8538f14）；`emitUnmatchedImmediately=false` 等待/收口语义（5dc27c2）；join 172 单测 + runtime 918 单测绿 |

### 5.1 卡点 A：Context 状态访问面——已解除（2026-10-12）

原卡点：`KeyedProcessFunction.Context` 只有时钟+定时器方法，没有 `getState(...)` 通道，两引擎都无法把 keyed state 句柄交给算子。

解除方式（本批）：

- `core`：`Context.getState(StateDescriptor)` 新增**默认方法**，默认抛 `UnsupportedOperationException`（不破坏既有自实现 Context 的二进制兼容），并钉契约测试；
- `runtime`：内存引擎 `ContextImpl` 接 `InMemoryKeyedStateStore`，Redis 引擎匿名 Context 接 keyed store 的 `getValueState`；
- `join` / `cep`：两算子缓冲迁 keyed `ValueState<String>`（自管 JSON 编解码，见 §3.4），failover 语义升级（§3.3/§4.2）；
- 证明：模块级测试以「同一 store + 全新函数实例」模拟重启——join 配对与 cep partial 均从上一实例状态续跑。

### 5.2 卡点 B：`DataStream.join(other)` 语法糖——已解除（2026-10-14，内存侧与 Redis 侧 U3 均落地）

设计走「编译期展开为信封 + 桥接」，需要把左右流汇入同一 join 输入（MQ topic 或引擎内 union）——`DataStream.union(...)` 双源汇合原语已落地（默认方法立契约 + 内存引擎 round-robin 实现，2026-10-14），内存引擎仍无 topic 基底；设计本身把「引擎级双源 pipeline（方案 A）」放在 Phase 3。

**解除方式（内存侧，2026-10-14，按 `docs/Union-DualSource-Design.md` 推荐路径）**：

- `DataStream.union(DataStream<? extends T>)` 默认方法立契约（Redis 引擎默认抛不支持并指向 join topic 模式），内存引擎 round-robin 交错实现（11 测试）；
- `StreamJoinOperator.join(left, right, config, fn)` 静态工厂（join 模块）：信封 map + union + keyBy 信封键 + 算子，与手工信封路径差分对照钉住（7 测试）——键即分区键，「信封键与 keyBy 选择器不一致静默失配」在糖路径结构性不存在。

**Redis 侧 U3 已落地（2026-10-14）**：静态工厂拿不到执行环境（`RedisStreamBuilder.env` 私有）的缺口以实例方法落点解决——`RedisStreamExecutionEnvironment.join(joinTopic, left, right, config, fn)` 注册「信封落地 join topic」的左右 pipeline 与合流消费 pipeline（三选一代决与被否选项见 `docs/Union-DualSource-Design.md` §5）。join topic 生命周期规则随代决一并定：topic 名由用户显式给定（字符集 `[A-Za-z0-9_.-]+`，喂稳定 source id `join-<topic>`）、消费组派生 `<topic>-join`、trim/TTL 走 MQ 既有 stream 保留治理（糖不擅自删 topic）、同一 joinTopic 二次调用被稳定 source id 的重复注册守卫拒绝（fail-fast）。双引擎差分对照已钉住（Redis 侧 `RedisEnvJoinSugarTest#redisJoinSugarMatchesTheHandWrittenEnvelopeExpansion`）。

**未实施清单**（留档）：
- ~~`DataStream.union(...)`（或等价汇合原语）双引擎实现~~（2026-10-14：内存侧落地，Redis 侧按设计走 join topic 模式即 U3，不另做引擎级实现）；
- ~~`DataStream.join(other)` 语法糖展开为 envelope map + 汇合 + keyBy + `StreamJoinOperator`~~（2026-10-14：内存侧以 `StreamJoinOperator.join` 落地；Redis 侧以 `RedisStreamExecutionEnvironment.join` 三段编排落地，双引擎差分对照钉住）；
- event-time join 窗口评估（依赖 `TimestampAssigner` 重载接入）。

## 6. 影响面与兼容性

- `join` / `cep` 模块既有公开 API **零改动**（`StreamJoiner` / `PatternSequenceMatcher` 等原样保留；`PatternSequenceMatcher` 新增 export/restore 一对方法，纯增量）；新增类型均在各自模块新 `operator` 子包；
- Phase 1 算子实现无新增模块依赖；**Phase 2** 两模块新增 `implementation libs.jackson.jsr310`（状态 JSON 需序列化 java.time 字段），`core` 增 `Context.getState` 默认方法，`runtime` 两处接线；
- 既有自实现 `KeyedProcessFunction.Context` 不受影响（默认方法抛不支持，契约不变）；
- 窗口谓词对照 `StreamJoiner` 的差分测试保持钉住一致；新增状态面单测（缓冲/partial 跨实例续跑、retention/cap 行为、JSON round-trip）+ 双引擎集成测试（Redis 腿 reachability 门控）。
