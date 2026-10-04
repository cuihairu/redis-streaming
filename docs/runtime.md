# Runtime 模块

模块目录:`runtime/`。职责:实现 core 定义的 `DataStream`/`KeyedStream`/`WindowedStream` 构建器接口,把算子链变成可执行作业。模块内有**两套彼此独立的引擎**:

| 引擎 | 入口类 | 用途 |
|---|---|---|
| 内存引擎 | `io.github.cuihairu.redis.streaming.runtime.StreamExecutionEnvironment` | 开发/测试:拉模型(Iterator)、单线程、有界数据 |
| Redis 引擎 | `io.github.cuihairu.redis.streaming.runtime.redis.RedisStreamExecutionEnvironment` | 生产:MQ 消费者线程回调驱动的推模型,状态/检查点存 Redis |

两套引擎尚未统一(已知架构债,见 `todo.md`「B. 双执行引擎统一」),本文如实区分两者能力。

## 1. 内存引擎(StreamExecutionEnvironment)

用途:开发/测试。这是惰性拉取模型。`addSink`/`print` 这类终止操作同步触发全量执行,没有 `execute()` 方法。

### 1.1 对外接口

`StreamExecutionEnvironment`:
- `static StreamExecutionEnvironment getExecutionEnvironment()`
- `StreamExecutionEnvironment enableCheckpointing()`:开启内存检查点(快照 keyed state)
- `CheckpointCoordinator getCheckpointCoordinator()`:未开启时返回 `null`
- `<T> DataStream<T> fromCollection(Collection<T>)` / `fromElements(T...)`:记录时间戳为合成值 `0..N-1`
- `<T> DataStream<T> addSource(StreamSource<T>)`:`collect(...)` 用递增回退时间戳,`collectWithTimestamp(...)` 保留指定事件时间

算子(来自 core 接口,内存实现为 `runtime.internal` 包):
- `DataStream`:`map` / `filter` / `flatMap` / `keyBy` / `addSink` / `print()` / `print(String)` / `assignTimestampsAndWatermarks(gen)` / `assignTimestampsAndWatermarks(assigner, gen)`(两个重载均支持);同时实现 `Iterable<T>`,可直接遍历
- `KeyedStream`:`map` / `process(KeyedProcessFunction)` / `window(WindowAssigner)` / `reduce` / `sum` / `getState(StateDescriptor)`
- `WindowedStream`:`reduce` / `aggregate` / `apply` / `sum` / `count`

### 1.2 用法示例

```java
import io.github.cuihairu.redis.streaming.runtime.StreamExecutionEnvironment;
import io.github.cuihairu.redis.streaming.api.state.StateDescriptor;
import io.github.cuihairu.redis.streaming.api.state.ValueState;
import io.github.cuihairu.redis.streaming.api.stream.KeyedStream;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

// 基础算子链(取自 runtime/src/test .../StreamExecutionEnvironmentTest)
List<String> out = new ArrayList<>();
env.fromElements("a b", "c", "d e")
        .flatMap(line -> Arrays.asList(line.split(" ")))
        .filter(word -> !"c".equals(word))
        .map(String::toUpperCase)
        .addSink(out::add);                       // addSink 即触发执行

// 按键有状态 process(keyed state)
StateDescriptor<Integer> descriptor = new StateDescriptor<>("count", Integer.class, 0);
KeyedStream<String, String> keyed = env.fromElements("a", "b", "a").keyBy(v -> v);
ValueState<Integer> count = keyed.getState(descriptor);
keyed.<String>process((key, value, ctx, collector) -> {
            int next = count.value() + 1;
            count.update(next);
            collector.collect(key + ":" + next);
        })
        .addSink(out::add);
```

### 1.3 能力与语义

- `keyBy(...).sum/reduce/process`:按键有状态算子,状态存内存 Map;开启 `enableCheckpointing()` 后这些 keyed state 会被 `InMemoryCheckpointCoordinator` 快照(`triggerCheckpoint()` / `restoreFromCheckpoint(id)`),后注册的 store 也会补上最近一次快照
- 窗口:每个 (key, window) 桶持有 `WindowAssigner.getDefaultTrigger()` 返回的独立 trigger;元素进入时调 `onElement`——`FIRE` 发射当前部分结果并继续累积、`FIRE_AND_PURGE` 发射并清空、`PURGE` 静默清空、`CONTINUE` 继续;输入耗尽时有效水位线为 +∞,剩余桶先收到 `onEventTime(windowEnd)` 再冲刷,数据不会静默丢弃;`onProcessingTime` 永不调用;session 窗口(`supportsWindowMerging()`)会合并同 key 相交桶
- `process` 的定时器:`registerProcessingTimeTimer` / `registerEventTimeTimer`(同 key+时间戳+类型去重);processing-time 以"当前记录时间戳"推进(批式仿真),输入耗尽后 `drainAllTimers` 全部触发
- 生命周期:`addSource` 为 `open → run → finally close`;`addSink` 为 `open → invoke×N → finally close`(`print`/`print(prefix)` 也走 `addSink`,输出经 slf4j `log.info`,不是 `System.out`)
- 限制:单线程、无并行、无取消语义;`addSource` 会把全部记录缓存在内存(无限流源会 OOM);`sum` 对非 `Number` 抛 `UnsupportedOperationException`

## 2. Redis 引擎(RedisStreamExecutionEnvironment)

用途:生产。推模型:由 mq 模块的消费者线程回调驱动算子链(`RedisOperatorNode` 内联执行,无跨算子 shuffle;`keyBy` 通过 `RedisKeyedStateStore` 的 ThreadLocal 绑定当前 key,状态存 Redis Hash)。投递语义基线为 at-least-once(端到端处理成功才 ack)。

### 2.1 对外接口

`RedisStreamExecutionEnvironment`:
- `static create(RedissonClient)` / `static create(RedissonClient, RedisRuntimeConfig)`
- `DataStream<Message> fromMqTopic(String topic, String consumerGroup)`:源 = Redis Stream 消费组,产出原始 `mq.Message`
- `DataStream<Message> fromMqTopic(String topic, String consumerGroup, SubscriptionOptions)`:按订阅覆写(batchCount/pollTimeout)
- `DataStream<Message> fromMqTopicWithId(String sourceId, String topic, String consumerGroup[, SubscriptionOptions])`:显式稳定 sourceId(参与算子/状态 id 派生,重启需保持不变;仅允许 `[A-Za-z0-9_.-]`)
- `void registerPipelineDefinition(RedisPipelineDefinition)`:`executeAsync()` 之后注册抛 `IllegalStateException`
- `RedisJobClient executeAsync()`:启动全部已注册管道;**每个环境仅可调用一次**,且至少要有一条管道(没有 sink 会抛 `IllegalStateException`)

`RedisJobClient`(`AutoCloseable`,`close()` 即 `cancel()`):

| 方法 | 语义 |
|---|---|
| `void cancel()` | 幂等;停周期检查点/leader 续约调度、释放 leader 租约、停消费者、关 runner(连带 `sink.close()`)、关共享定时器线程池 |
| `Checkpoint triggerCheckpointNow()` | 手动触发一次 stop-the-world 检查点;与周期调度共用同一 CAS(单飞),已在执行或已取消时返回 `null` |
| `Checkpoint getLatestCheckpoint()` | 读取最新检查点(可能为 `null`) |
| `void pause()` / `void resume()` | 背压式暂停/恢复;仅当消费者实现 `PausableMessageConsumer` 时生效 |
| `long inFlight()` | 汇总各消费者在途消息数;无 pausable 实现时返回 `-1` |
| `Map<String,Object> diagnostics()` | 运行诊断:jobName/jobInstanceId、并行度与线程数、各开关、inFlight、checkpointing、leader 状态(isLeader/leaderInstanceId/fencingToken)、最近检查点 id 与最近 sinkCommitted 检查点 id、pipelines 列表;**不含当前水位值** |
| `boolean awaitTermination(Duration)` | 等待作业停止,超时返回 `false` |

### 2.2 配置项(RedisRuntimeConfig.builder())

类型与默认值逐一取自 `runtime/src/main/java/.../runtime/redis/RedisRuntimeConfig.java`:

| Builder 方法 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `jobName(String)` | String | `redis-streaming-job` | 状态/检查点/去重键空间隔离的关键 |
| `jobInstanceId(String)` | String | 本机 hostname(失败回退 `local`) | 参与构造稳定消费者名 `jobName-jobInstanceId-{n}` |
| `stateKeyPrefix(String)` | String | `streaming:runtime` | keyed 状态键前缀 |
| `stateTtl(Duration)` | Duration | `ZERO`(不设 TTL) | 过期粒度为 (job,topic,group,partition,operator,stateName) 的单个 Hash |
| `stateSizeReportEveryNStateWrites(int)` | int | `0`(关闭) | 每 N 次状态写采样一次 HLEN 并上报指标 |
| `keyedStateShardCount(int)` | int | `1`(不分片) | ≥1;>1 时按 key hash 拆分状态 Hash |
| `keyedStateHotKeyFieldsWarnThreshold(long)` | long | `0`(关闭) | 单状态 Hash 字段数告警阈值 |
| `keyedStateHotKeyWarnInterval(Duration)` | Duration | `1min` | 同一 Hash 的告警/处理窗口最小间隔 |
| `keyedStateHotKeyPolicy(HotKeyPolicy)` | enum `LOG_ONLY/THROTTLE/FAIL_FAST` | `LOG_ONLY` | 命中热键后的写处理策略 |
| `keyedStateHotKeyThrottleMaxMs(long)` | long | `200` | `THROTTLE` 单次写前休眠上限 |
| `stateSchemaEvolutionEnabled(boolean)` | boolean | `true` | 状态 schema 版本校验开关 |
| `stateSchemaMismatchPolicy(StateSchemaMismatchPolicy)` | enum `FAIL/CLEAR/IGNORE` | `FAIL` | schema 不兼容时的处理 |
| `restoreConsumerGroupFromCommitFrontier(boolean)` | boolean | `true` | 消费组缺失时按 MQ commit frontier 重建起点,避免整段重放 |
| `sinkDeduplicationEnabled(boolean)` | boolean | `false` | sink 侧按消息 id 去重(`x-original-message-id` 优先) |
| `sinkDeduplicationTtl(Duration)` | Duration | `7` 天 | 去重标记 TTL |
| `sinkDedupKeyPrefix(String)` | String | `streaming:runtime:sinkDedup:` | 去重键前缀(实际键追加 jobName) |
| `deferAckUntilCheckpoint(boolean)` | boolean | `false` | 消息保持 pending,检查点完成后再 ACK |
| `ackDeferredMessagesOnCheckpoint(boolean)` | boolean | `true` | 置 `false` 交由 Redis-only sink 自行原子 XACK |
| `pipelineParallelism(int)` | int | `1` | 每管道 consumer 子任务数;分区按 `partitionId % n == subtask` 固定 |
| `timerThreads(int)` | int | `1` | 共享 processing-time 定时器线程池大小 |
| `checkpointThreads(int)` | int | `1` | 检查点调度线程数(执行仍按作业串行) |
| `eventTimeTimerMaxSize(int)` | int | `100000` | 每 runner 事件时间定时器队列上限;`0` 不限;满时限频告警并丢弃注册 |
| `watermarkOutOfOrderness(Duration)` | Duration | `ZERO` | 水位线 = max(事件时间) − 该值;非负 |
| `windowAllowedLateness(Duration)` | Duration | `ZERO` | 窗口关闭时间 = windowEnd + 该值 |
| `windowMaxFiresPerRecord(int)` | int | `256` | 每条记录最多触发的到期窗口数(≥1) |
| `mdcEnabled(boolean)` | boolean | `false` | 为每条消息安装 MDC(job/topic/group/consumer/id/key/partition) |
| `mdcSampleRate(double)` | double | `1.0` | MDC 采样率,取值 [0,1] |
| `checkpointInterval(Duration)` | Duration | `ZERO`(关闭) | 周期检查点;实际调度间隔下限 50ms |
| `restoreFromLatestCheckpoint(boolean)` | boolean | `false` | 启动时从最近检查点恢复 |
| `checkpointKeyPrefix(String)` | String | `streaming:runtime:checkpoint:` | 检查点键前缀(实际键追加 jobName) |
| `checkpointsToKeep(int)` | int | `5` | 保留的检查点数;`0` 关闭清扫 |
| `checkpointDrainTimeout(Duration)` | Duration | `30s` | stop-the-world 检查点等待在途消息排空的上限;非正值回退默认 |
| `mqOptions(MqOptions)` | MqOptions | `MqOptions.builder().build()` | 底层 MQ 消费参数(线程数/租约/重试等,见 mq 文档) |
| `processingErrorResult(MessageHandleResult)` | enum `RETRY/DEAD_LETTER/FAIL` | `RETRY` | 管道异常的处理;`DEAD_LETTER` 与 `FAIL` 当前同为进 DLQ 后 ack |
| `leaderElectionEnabled(boolean)` | boolean | `false` | 多实例 leader 选举(见 2.6) |
| `leaderLeaseTtl(Duration)` | Duration | `30s` | leader 租约 TTL;必须 >0 |
| `leaderRenewInterval(Duration)` | Duration | `10s` | 续约间隔;必须 >0 且 < leaseTtl(构造期校验) |

### 2.3 构建与启动示例

以下调用全部可在 `runtime/src/test`(integration 用例)中找到原型:

```java
import io.github.cuihairu.redis.streaming.api.stream.DataStream;
import io.github.cuihairu.redis.streaming.api.stream.KeyedStream;
import io.github.cuihairu.redis.streaming.api.state.StateDescriptor;
import io.github.cuihairu.redis.streaming.api.state.ValueState;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import io.github.cuihairu.redis.streaming.runtime.redis.RedisJobClient;
import io.github.cuihairu.redis.streaming.runtime.redis.RedisRuntimeConfig;
import io.github.cuihairu.redis.streaming.runtime.redis.RedisStreamExecutionEnvironment;

RedisRuntimeConfig config = RedisRuntimeConfig.builder()
        .jobName("rt-job-" + UUID.randomUUID().toString().substring(0, 6))
        .stateKeyPrefix("streaming:runtime:" + UUID.randomUUID().toString().substring(0, 6))
        .watermarkOutOfOrderness(Duration.ofSeconds(5))
        .pipelineParallelism(2)
        .checkpointInterval(Duration.ofSeconds(30))
        .restoreFromLatestCheckpoint(true)
        .sinkDeduplicationEnabled(true)
        .mqOptions(MqOptions.builder().workerThreads(1).schedulerThreads(1).build())
        .build();

RedisStreamExecutionEnvironment env = RedisStreamExecutionEnvironment.create(redissonClient, config);

// map + keyBy + keyed state + process + sink(单条链;状态与算子绑定同一管道)
DataStream<String> base = env.fromMqTopic(topic, group).map(m -> (String) m.getPayload());
KeyedStream<String, String> keyed = base.keyBy(v -> v);
StateDescriptor<Integer> desc = new StateDescriptor<>("cnt", Integer.class, 0);
ValueState<Integer> cnt = keyed.getState(desc);
keyed.<String>process((key, value, ctx, out) -> {
            Integer c = (cnt.value() == null ? 0 : cnt.value()) + 1;
            cnt.update(c);
            out.collect(key + ":" + c);
        })
        .addSink(results::add);

try (RedisJobClient job = env.executeAsync()) {   // close() 即 cancel()
    producer.send(topic, "k1", "a").get(5, TimeUnit.SECONDS);
    // ...
}
```

带窗口的写法(`TumblingWindow` 来自 window 模块,runtime 的 main 依赖;`watermark` 模块对 runtime 是 `testImplementation`,使用 `BoundedOutOfOrdernessWatermarkGenerator` 需自行添加 `io.github.cuihairu.redis.streaming:watermark` 依赖):

```java
import io.github.cuihairu.redis.streaming.window.assigners.TumblingWindow;
import io.github.cuihairu.redis.streaming.watermark.generators.BoundedOutOfOrdernessWatermarkGenerator;

env.fromMqTopic(topic, group)
        .map(m -> Integer.parseInt((String) m.getPayload()))
        .keyBy(v -> "k")
        .window(TumblingWindow.<Integer>ofMillis(1500))
        .reduce(Integer::sum)
        .addSink(out::add);

env.fromMqTopic(topic, group)
        .assignTimestampsAndWatermarks(new BoundedOutOfOrdernessWatermarkGenerator<>(Duration.ofSeconds(5)))
        // ... 继续 map/keyBy/window/...
```

### 2.4 关键语义

- 事件时间取 `Message.getTimestamp()`(发送方构造消息时的 `Instant.now()`),为空回退当前系统时间;水位线 = max(事件时间) − `watermarkOutOfOrderness`,单调不减。
- 用户 WatermarkGenerator:`assignTimestampsAndWatermarks(gen)` 对每条元素调用 `onEvent` + `onPeriodicEmit`;生成器只能通过 `Context.raiseWatermark` **单调提升**水位线;`markIdle/markActive` 为空实现(idle 语义未接入),`onPeriodicEmit` 按元素驱动而非墙钟定时。
- 窗口触发:每条消息处理过程中检查到期窗口,每条记录最多触发 `windowMaxFiresPerRecord` 个;窗口关闭时间 = `windowEnd + windowAllowedLateness`,水位线越过即晚到,元素被丢弃并计入 `incWindowLateDropped` 指标。窗口算子同时驱动 `WindowAssigner.getDefaultTrigger()`:每个 (partition,key,window) 桶一个实例,元素到达调 `onElement`(`FIRE` 提前发射并继续累积 / `FIRE_AND_PURGE` 发射并清桶 / `PURGE` 静默清桶 / `CONTINUE`),到期发射前调 `onEventTime`(`CONTINUE` 推迟、`PURGE` 静默丢弃、`FIRE` 按 `FIRE_AND_PURGE` 处理)。默认 `EventTimeTrigger` 与纯水位线关闭行为等价(见 docs/watermark.md)。
- 状态由 `RedisKeyedStateStore` 写入 (job,topic,group,partition,operator,stateName) 维度的 Redis Hash(字段为序列化后的 key),支持 TTL、按 `keyedStateShardCount` 分片、schema 版本校验与热键处理;状态取用:`keyed.getState(StateDescriptor)` 返回 `ValueState`(`value/update/clear`)。
- 检查点由 `RedisRuntimeCheckpointManager` 停止世界后快照,内容为 `runtime:meta`(jobName/jobInstanceId/stateKeyPrefix/sinkCommitted/fencingToken)、`runtime:offsets`(各分区 commit frontier,即已 ack 的最大 stream id)、`runtime:state`(状态键索引覆盖到的 MAP/ZSET 全量)、`runtime:stateSchema`、可选 `runtime:txns`(两阶段提交句柄);恢复时重建消费组(`XGROUP DESTROY`+`CREATE` 到快照 offset)、回放状态、逐 runner 调 `onCheckpointRestore`,并处理 2PC 补偿(见 2.8)。
- 定时器分两类:processing-time 走共享 `ScheduledExecutor`(`timerThreads`),event-time 走内存优先队列(容量 `eventTimeTimerMaxSize`),随水位线触发。
- 管道异常按 `processingErrorResult` 处理(默认 `RETRY`:MQ 重试+退避,超限进 DLQ);重试/死信消息头会带 `RedisRuntimeHeaders` 的 `x-runtime-job` / `x-runtime-group` / `x-runtime-error-type` / `x-runtime-error-message`(截断 512 字符),便于排障。
- sink 生命周期:首条消息到达时 `open()`(幂等),`job.cancel()/close()` 时 `close()`(异常只记日志)。
- MDC 按 `mdcEnabled(true)` 时的 `mdcSampleRate` 采样,为命中的消息安装 MDC 键。

### 2.5 检查点保留与清扫

- `checkpointsToKeep`(默认 5):超过后清扫旧检查点,不完整的优先删除;`0` 关闭。
- 在 stop-the-world 窗口内,清扫被延后到恢复消费之后执行(`cleanupOld()`),避免拉长暂停。

### 2.6 Leader 选举与 HA 接管(`RedisLeaderElector`)

- `leaderElectionEnabled(true)` 后,同 `jobName` 的多实例竞争 Redis 租约(键 `stateKeyPrefix:jobName:leader`):获取 = `SET NX PX ttl`;续约 = compare-and-expire Lua(仅当自己仍持有);释放 = compare-and-delete Lua。租约随持有者死亡自动过期,无需人工干预。
- 周期检查点调度只由 leader 运行;丢失租约立即停止;follower 按续约间隔尝试接管,接管成功时分配新 fencing token 并 `refreshCheckpointIdFromStorage()` 对齐检查点计数,再启动自己的调度。
- fencing token 每个任期通过 Redis `INCR` 分配一次,写入该任期所有检查点的 meta;恢复时只采纳 token 等于历史最大 token 的检查点,旧 leader 在丢租约后仍写入的检查点不会被采纳。
- `diagnostics()` 中可观测 `isLeader` / `leaderInstanceId` / `fencingToken`。

### 2.7 Keyed 状态热键处理

检测是采样的(每 `stateSizeReportEveryNStateWrites` 次写一次 HLEN);字段数达到 `keyedStateHotKeyFieldsWarnThreshold` 时限频告警(间隔 `keyedStateHotKeyWarnInterval`)并为该 Hash 武装处理窗口,窗口内每次写按 `keyedStateHotKeyPolicy` 处理:

| 策略 | 行为 |
|---|---|
| `LOG_ONLY` | 告警日志 + `incKeyedStateHotKey` 指标 |
| `THROTTLE` | 额外在写前休眠至多 `keyedStateHotKeyThrottleMaxMs`,用延迟反压热 key |
| `FAIL_FAST` | 额外抛 `KeyedStateHotKeyException`——复用 MQ 消费者的重试/退避形成反压,超限后进 DLQ |

### 2.8 Sink、去重与 exactly-once 组件

`runtime/redis/sink` 下的组件(类名与构造参数均来自源码):

| 类 | 语义 |
|---|---|
| `RedisIdempotentListSink<T>` | Lua 原子执行 `SISMEMBER → SADD(+EXPIRE) → RPUSH`,按幂等键至多写一次;入参 `IdempotentRecord<T>`;构造:`(redissonClient, dedupSetKey, listKey[, objectMapper, dedupTtl])`;集群下两键需同 hash slot |
| `RedisCheckpointedIdempotentListSink<T>` | 实现 `CheckpointAwareSink`:invoke 只缓冲,`onCheckpointComplete` 批量交给 `RedisIdempotentListSink`;abort/restore 清空缓冲 |
| `RedisAtomicCheckpointListSink<T>` | 配 `RedisExactlyOnceRecord<T>`(topic/consumerGroup/partitionId/messageId/idempotencyKey/value):checkpoint 完成时按 (topic,group,partition) 分组,单条 Lua 原子完成去重 + RPUSH + `XACK` + 推进 commit frontier;需 `deferAckUntilCheckpoint(true)` 且 `ackDeferredMessagesOnCheckpoint(false)`,且 MQ 流键与 sink 键需同 hash slot |
| `RedisOutboxSink<T>` | 实现 `TwoPhaseCommitSink`:invoke 内存缓冲 → `preCommit` 逐条 XADD 进 outbox 流(epoch/seq/id/payload 字段)→ `commit`/`recoverAndCommit` 单个 HSET 把 `<outboxKey>:epochs` 翻成 `COMMITTED`(整个 epoch 原子可见);`abort`/`recoverAndAbort` 写 `ABORTED` |
| `RedisOutboxDispatcher<T>` | outbox 的异步投递器(消费组驱动):`COMMITTED` 按序投递 + ack + XDEL;`ABORTED` 丢弃;无 marker 的条目头部阻塞等待运行时补偿;投递失败留 pending 按 `retryIdleMs` 重试,超 `maxAttempts` 转 `<outboxKey>:dlq`(附 `dlqReason`/`failedAttempts`/`dlqTime` 字段)。投递本身 at-least-once,端到端 exactly-once 需目标端按稳定 record id 幂等(见 docs/exactly-once.md) |

运行时对 `TwoPhaseCommitSink` 是自动接入的(`instanceof` 探测):checkpoint 流程为 `preCommit → 存句柄(runtime:txns) → commit → 标记 sinkCommitted → ack`;若在"存句柄之后、commit 之前"崩溃,恢复时按存储句柄回放 `recoverAndCommit`(契约幂等)。

### 2.9 未支持(相对 core 接口)

- `fromCollection` / `fromElements` / `addSource`:Redis 引擎只有 MQ 源
- `assignTimestampsAndWatermarks(TimestampAssigner, WatermarkGenerator)` 重载:未覆写,调用走 core 默认实现抛 `UnsupportedOperationException`
- 窗口 `Trigger` 的 `onProcessingTime`:无 processing-time 窗口定时器
- 用户 `WatermarkGenerator` 的 `markIdle/markActive`:空实现,无 idle 语义

## 3. 指标

`RedisRuntimeMetrics` 静态单例(默认 Noop,`setCollector` 覆盖,`null` 被忽略),维度为 jobName(+topic/consumerGroup/operatorId/stateName/partitionId):

- 作业/管道:`incJobStarted` / `incJobCanceled` / `incPipelineStarted` / `incPipelineStartFailed`
- 处理:`incHandleSuccess` / `incHandleError` / `recordHandleLatency`
- 检查点:`incCheckpointTriggered` / `incCheckpointCompleted` / `incCheckpointFailed`、总耗时 `recordCheckpointDuration` 与 drain/store/sinkCommit 分段耗时
- keyed state:`incKeyedStateRead/Write/Delete`、读写延迟、`recordKeyedStateSize`(HLEN 采样)、`incKeyedStateHotKey`
- 窗口:`incWindowFired` / `incWindowLateDropped`;水位线:`setWatermarkMs`;定时器队列:`setEventTimeTimerQueueSize`

桥接到 Micrometer 由 spring-boot-starter 完成(`RedisRuntimeMicrometerCollector`,指标前缀 `redis_streaming_runtime_*`,见 Spring-Boot-Starter.md)。

## 相关文档

[Core.md](Core.md) · [state.md](state.md) · [checkpoint.md](checkpoint.md) · [watermark.md](watermark.md) · [MQ.md](MQ.md) · [Spring-Boot-Starter.md](Spring-Boot-Starter.md) · [exactly-once.md](exactly-once.md)
