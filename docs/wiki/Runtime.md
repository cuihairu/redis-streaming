# Runtime Engine

Module: `runtime/`

Execution runtime for stream graphs, scheduling, and operator lifecycle.

## Status
- ✅ Redis-backed runtime (Redis Streams): in-process parallelism + stop-the-world checkpoint + Redis keyed state
- ✅ Watermark/timers/window (event-time) wired end-to-end (best-effort; see limits below)
- ✅ In-memory runtime (single-thread; for tests/examples)

## Scope
- 提供 `core` 层 API 的最小可执行实现：`StreamExecutionEnvironment` + 基础算子链
- 目标：后续演进为可调度/可扩展的执行引擎（Task/Operator 生命周期、资源管理等）

## Redis Runtime (RedisStreamExecutionEnvironment)

模块内提供 Redis-backed 的执行环境：`io.github.cuihairu.redis.streaming.runtime.redis.RedisStreamExecutionEnvironment`。

### 核心能力（best-effort）
- 并行度（单进程）：`RedisRuntimeConfig.pipelineParallelism(n)` + 分区固定分配（`partitionId % n`）
- 背压：MQ 层 `MqOptions.maxInFlight(n)`（信号量限流）
- Checkpoint（实验）：pause + drain + snapshot(offsets+state) + sink hook 协调；支持 `triggerCheckpointNow()`
- State：keyed state Redis Hash（按 partition 隔离），支持 TTL、sharding、schema/version 校验
- Watermark：`watermarkOutOfOrderness`（watermark=maxEventTime-outOfOrderness）
- Window：reduce/aggregate/apply/sum/count（final fire 延后到 `windowEnd + windowAllowedLateness`）
- 可观测：runtime metrics（Micrometer）+ MDC（`mdcEnabled` + `mdcSampleRate`）
- 诊断：`RedisJobClient.diagnostics()`（best-effort snapshot）

### 限制（当前版本）
- checkpoint 是 **单进程 stop-the-world**（不跨多实例 barrier 对齐）；多实例部署时建议只依赖幂等 sink / Redis-only 原子 sink 做端到端效果一致性。
- Window 触发器语义以 watermark 驱动为主，复杂 trigger/side output 需按业务扩展。

### Minimal Sample (Redis)
```java
import io.github.cuihairu.redis.streaming.runtime.redis.RedisRuntimeConfig;
import io.github.cuihairu.redis.streaming.runtime.redis.RedisStreamExecutionEnvironment;
import org.redisson.api.RedissonClient;

RedisRuntimeConfig cfg = RedisRuntimeConfig.builder()
        .jobName("demo")
        .pipelineParallelism(2)
        .checkpointInterval(java.time.Duration.ofSeconds(10))
        .deferAckUntilCheckpoint(true)
        .mdcEnabled(true)
        .build();

RedisStreamExecutionEnvironment env = RedisStreamExecutionEnvironment.create(redissonClient, cfg);
env.fromMqTopic("topicA", "groupA")
        .map(msg -> msg.getValue())
        .print("runtime> ");
env.executeAsync();
```

## Supported API (in-memory)
- `StreamExecutionEnvironment#fromElements/fromCollection/addSource`
- `DataStream#map/filter/flatMap/keyBy/addSink/print`
- `KeyedStream#getState/process/reduce`（keyed state 为内存态）

## Minimal Sample
```java
import io.github.cuihairu.redis.streaming.runtime.StreamExecutionEnvironment;

StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

env.fromElements(1, 2, 3, 4)
        .filter(v -> v % 2 == 0)
        .map(v -> "even=" + v)
        .print("runtime> ");
```

## References
- Design.md
- Architecture.md
