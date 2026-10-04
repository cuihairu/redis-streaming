# Runtime 模块

## 职责

实现 `core` 模块定义的流处理 API(`DataStream` / `KeyedStream` / `WindowedStream`),提供两套彼此独立的执行引擎:

| 引擎 | 入口类 | 定位 |
|---|---|---|
| 内存引擎 | `io.github.cuihairu.redis.streaming.runtime.StreamExecutionEnvironment` | 测试/示例:单线程、拉模型(Iterator)、仅支持有界数据 |
| Redis 引擎 | `io.github.cuihairu.redis.streaming.runtime.redis.RedisStreamExecutionEnvironment` | 可运行作业:Redis Streams 消费组驱动,状态/检查点存 Redis |

两套引擎尚未统一(架构债见仓库根 `todo.md`「B. 双执行引擎统一」)。完整能力矩阵与配置参考见 `docs/runtime.md`。

源码结构:

- `runtime/.../runtime`:`StreamExecutionEnvironment`(内存引擎入口)
- `runtime/.../runtime/internal`:内存引擎实现(`InMemoryDataStream`/`InMemoryKeyedStream`/`InMemoryWindowedStream`/`InMemoryCheckpointCoordinator` 等)
- `runtime/.../runtime/redis`:`RedisStreamExecutionEnvironment`、`RedisRuntimeConfig`、`RedisJobClient`、`RedisRuntimeHeaders`、`KeyedStateHotKeyException`
- `runtime/.../runtime/redis/internal`:管道执行(`RedisStreamBuilder`/`RedisPipeline(Definition)`/`RedisPipelineRunner`/`RedisOperatorNode`)、状态(`RedisKeyedStateStore`)、检查点(`RedisRuntimeCheckpointManager`)、选主(`RedisLeaderElector`)
- `runtime/.../runtime/redis/sink`:exactly-once 相关 sink(`RedisIdempotentListSink`/`RedisCheckpointedIdempotentListSink`/`RedisAtomicCheckpointListSink`/`RedisOutboxSink`/`RedisOutboxDispatcher`)
- `runtime/.../runtime/redis/metrics`:`RedisRuntimeMetrics` + `RedisRuntimeMetricsCollector`

## 内存引擎

对外接口(`StreamExecutionEnvironment`):

- `static getExecutionEnvironment()`
- `enableCheckpointing()` / `getCheckpointCoordinator()`:开启后 `InMemoryCheckpointCoordinator` 会同步快照 keyed state(`triggerCheckpoint()` / `restoreFromCheckpoint(id)` / `getLatestCheckpoint()`)
- `fromCollection(Collection)` / `fromElements(...)` / `addSource(StreamSource)`

算子与语义:

- `DataStream`:`map`/`filter`/`flatMap`/`keyBy`/`addSink`/`print()`/`print(String)`;`assignTimestampsAndWatermarks` 的单参数与 `(TimestampAssigner, generator)` 两个重载都支持;`fromElements/fromCollection` 赋合成时间戳 `0..N-1`,`addSource` 保留 `collectWithTimestamp` 的时间戳
- `KeyedStream`:`map`/`process(KeyedProcessFunction)`/`window`/`reduce`/`sum`/`getState(StateDescriptor)`;`process` 支持 processing-time 与 event-time 定时器(processing-time 以记录时间戳推进,输入耗尽统一冲刷)
- `WindowedStream`:`reduce`/`aggregate`/`apply`/`sum`/`count`;每个 (key, window) 桶独享 `WindowAssigner.getDefaultTrigger()` 返回的 trigger,`onElement` 处理 `FIRE/FIRE_AND_PURGE/PURGE/CONTINUE`,输入耗尽时水位线视为 +∞ 并对剩余桶补一次 `onEventTime` 后冲刷;`onProcessingTime` 不会被调用;session 窗口支持合并相交桶
- 终止操作触发执行:`addSink` 生命周期 `open → invoke×N → finally close`;`print(prefix)` 经 slf4j 输出;没有 `execute()`
- 限制:单线程、无并行、无取消;`addSource` 全量缓存在内存(无限流源会 OOM);`sum` 对非 `Number` 抛 `UnsupportedOperationException`

```java
var env = StreamExecutionEnvironment.getExecutionEnvironment();
List<String> out = new ArrayList<>();
env.fromElements("a b", "c", "d e")
        .flatMap(line -> Arrays.asList(line.split(" ")))
        .filter(word -> !"c".equals(word))
        .map(String::toUpperCase)
        .addSink(out::add);
```

## Redis 引擎

入口:`io.github.cuihairu.redis.streaming.runtime.redis.RedisStreamExecutionEnvironment`。

- 源:`fromMqTopic(topic, consumerGroup)` / `fromMqTopic(topic, consumerGroup, SubscriptionOptions)` / `fromMqTopicWithId(sourceId, topic, consumerGroup[, SubscriptionOptions])`,源 = Redis Stream 消费组,产出原始 `mq.Message`
- 算子:`map` / `filter` / `flatMap` / `keyBy` / `process` / `window(...).reduce/aggregate/apply/sum/count` / `assignTimestampsAndWatermarks(WatermarkGenerator)` / `addSink` / `print`
- 状态:`KeyedStream.getState(StateDescriptor)` → Redis `ValueState`(`value/update/clear`),支持 TTL、分片、schema 版本、热键策略
- 启动:`RedisJobClient executeAsync()`;每个环境仅可调用一次,且至少要注册一条管道(即至少一个 `addSink`)
- 作业句柄 `RedisJobClient`:`cancel()`(与 `close()` 等价)、`awaitTermination(Duration)`、`triggerCheckpointNow()`、`getLatestCheckpoint()`、`pause()`/`resume()`/`inFlight()`、`diagnostics()`
- 投递语义基线:at-least-once;消费者名 `jobName-jobInstanceId-{n}`,用 `RedisRuntimeConfig.jobInstanceId(...)` 固定实例 id 可保持重启后名称稳定
- 错误诊断:处理异常时向消息头写 `x-runtime-job`/`x-runtime-group`/`x-runtime-error-type`/`x-runtime-error-message`(`RedisRuntimeHeaders`),重试与死信携带根因上下文

### 配置项(RedisRuntimeConfig.builder(),默认值取自源码)

| Builder 方法 | 类型 | 默认值 |
|---|---|---|
| `jobName` | String | `redis-streaming-job` |
| `jobInstanceId` | String | 本机 hostname(回退 `local`) |
| `stateKeyPrefix` | String | `streaming:runtime` |
| `stateTtl` | Duration | `ZERO`(不设 TTL) |
| `stateSizeReportEveryNStateWrites` | int | `0`(关闭) |
| `keyedStateShardCount` | int | `1`(不分片) |
| `keyedStateHotKeyFieldsWarnThreshold` | long | `0`(关闭) |
| `keyedStateHotKeyWarnInterval` | Duration | `1min` |
| `keyedStateHotKeyPolicy` | `LOG_ONLY/THROTTLE/FAIL_FAST` | `LOG_ONLY` |
| `keyedStateHotKeyThrottleMaxMs` | long | `200` |
| `stateSchemaEvolutionEnabled` | boolean | `true` |
| `stateSchemaMismatchPolicy` | `FAIL/CLEAR/IGNORE` | `FAIL` |
| `restoreConsumerGroupFromCommitFrontier` | boolean | `true` |
| `sinkDeduplicationEnabled` | boolean | `false` |
| `sinkDeduplicationTtl` | Duration | `7` 天 |
| `sinkDedupKeyPrefix` | String | `streaming:runtime:sinkDedup:` |
| `deferAckUntilCheckpoint` | boolean | `false` |
| `ackDeferredMessagesOnCheckpoint` | boolean | `true` |
| `pipelineParallelism` | int | `1`(分区按 `partitionId % n == subtask` 分配) |
| `timerThreads` | int | `1` |
| `checkpointThreads` | int | `1` |
| `eventTimeTimerMaxSize` | int | `100000`(`0` 不限) |
| `watermarkOutOfOrderness` | Duration | `ZERO` |
| `windowAllowedLateness` | Duration | `ZERO` |
| `windowMaxFiresPerRecord` | int | `256` |
| `mdcEnabled` | boolean | `false` |
| `mdcSampleRate` | double | `1.0` |
| `checkpointInterval` | Duration | `ZERO`(关闭;调度下限 50ms) |
| `restoreFromLatestCheckpoint` | boolean | `false` |
| `checkpointKeyPrefix` | String | `streaming:runtime:checkpoint:` |
| `checkpointsToKeep` | int | `5`(`0` 关闭清扫) |
| `checkpointDrainTimeout` | Duration | `30s` |
| `mqOptions` | `MqOptions` | `MqOptions.builder().build()` |
| `processingErrorResult` | `RETRY/DEAD_LETTER/FAIL` | `RETRY` |
| `leaderElectionEnabled` | boolean | `false` |
| `leaderLeaseTtl` | Duration | `30s` |
| `leaderRenewInterval` | Duration | `10s`(须 < leaseTtl) |

其余可调项在底层 `mq` 模块:`MqOptions.maxInFlight(...)` 限制单消费者在途消息数,`MqOptions.maxLeasedPartitionsPerConsumer(...)` 限制单消费者可持有的分区租约数(默认 `0` = 跟随 `workerThreads`)。

### 高可用与检查点

- `leaderElectionEnabled(true)`:`RedisLeaderElector` 以 Redis 租约(`SET NX PX` + compare-and-expire 续约)选出 leader,只有 leader 运行周期检查点调度;丢失租约立即停调度,follower 按 `leaderRenewInterval` 尝试接管,接管时换新 fencing token 并对齐检查点计数。恢复只采纳 token 为历史最大值的检查点,旧 leader 的残留写入不会被采纳。
- 检查点快照:`runtime:meta`(含 fencingToken/sinkCommitted)+ `runtime:offsets`(各分区 commit frontier)+ `runtime:state` + `runtime:stateSchema` + 可选 `runtime:txns`(两阶段提交句柄);恢复重建消费组到快照 offset、回放状态与 `onCheckpointRestore`,并在"存句柄未提交"时按句柄执行 `recoverAndCommit` 补偿。
- `deferAckUntilCheckpoint(true)`:消息保持 pending 至检查点完成;`RedisAtomicCheckpointListSink` + `RedisExactlyOnceRecord` 配合 `ackDeferredMessagesOnCheckpoint(false)` 可由单条 Lua 原子完成去重写入 + `XACK` + 推进 commit frontier。
- Outbox:`RedisOutboxSink`(实现 `TwoPhaseCommitSink`,epoch 原子提交)+ `RedisOutboxDispatcher`(异步投递,失败重试,超限进 `<outboxKey>:dlq`);投递 at-least-once,端到端 exactly-once 依赖目标端按 record id 幂等,见 `docs/exactly-once.md`。

### 示例

```java
RedisRuntimeConfig config = RedisRuntimeConfig.builder()
        .jobName("rt-job")
        .stateKeyPrefix("streaming:runtime")
        .watermarkOutOfOrderness(Duration.ofSeconds(5))
        .build();

RedisStreamExecutionEnvironment env = RedisStreamExecutionEnvironment.create(redissonClient, config);
DataStream<String> base = env.fromMqTopic("orders", "cg-orders").map(m -> (String) m.getPayload());
KeyedStream<String, String> keyed = base.keyBy(v -> v);
ValueState<Integer> cnt = keyed.getState(new StateDescriptor<>("cnt", Integer.class, 0));
keyed.<String>process((key, value, ctx, out) -> {
            int c = (cnt.value() == null ? 0 : cnt.value()) + 1;
            cnt.update(c);
            out.collect(key + ":" + c);
        })
        .addSink(v -> { /* ... */ });

try (RedisJobClient job = env.executeAsync()) {
    // job.triggerCheckpointNow() / job.pause() / job.resume() / job.diagnostics()
}
```

窗口示例与完整语义(事件时间、水位线、触发器、迟到、检查点保留清扫)见 `docs/runtime.md`。

## 指标

`RedisRuntimeMetrics`(静态单例,默认 Noop)覆盖作业/管道生命周期、处理成败与时延、检查点触发/完成/失败与分段耗时、keyed state 读写/延迟/大小采样/热键、窗口触发与迟到、水位线、事件时间定时器队列大小;Spring Boot Starter 负责桥接到 Micrometer(`redis_streaming_runtime_*`)。

## 限制

- Redis 引擎不支持 `fromCollection/fromElements/addSource`(仅 MQ 源),也不支持 `assignTimestampsAndWatermarks(TimestampAssigner, generator)` 重载(默认实现抛 `UnsupportedOperationException`)
- 窗口不响应 `Trigger.onProcessingTime`(没有 processing-time 窗口定时器)
- `stateSchemaMismatchPolicy` 只做校验/清除/忽略,不迁移数据
- 两套引擎未统一,算子语义差异按本文与 `docs/runtime.md` 为准

## 相关文档

`docs/runtime.md`(完整能力矩阵与配置参考)· `docs/checkpoint.md` · `docs/watermark.md` · `docs/exactly-once.md` · `docs/Spring-Boot-Starter.md`
