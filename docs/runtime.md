# Runtime 模块

模块目录:`runtime/`。提供两套彼此独立的执行引擎,实现 core 的 `DataStream`/`KeyedStream`/`WindowedStream` 构建器接口。**尚未统一**(引擎融合是已知架构债,见 `todo.md` B 节),本文如实区分两者能力。

## 1. 内存引擎(StreamExecutionEnvironment)

用途:开发/测试。惰性拉取模型(Iterator),**只能跑有界流**;终止操作(`addSink`/`print`)同步触发全量执行,没有 `execute()`。

```java
import io.github.cuihairu.redis.streaming.runtime.StreamExecutionEnvironment;

StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

env.fromElements("hello", "world", "redis")          // 或 fromCollection(...) / addSource(StreamSource)
   .map(String::toUpperCase)
   .filter(s -> s.startsWith("R"))
   .print();                                          // addSink 即执行
```

能力矩阵(内存引擎):
- `keyBy(...).sum(...) / reduce(...) / process(KeyedProcessFunction)`:按键有状态算子(内存 Map,不可快照)
- `window(TumblingWindow/...).reduce/aggregate/apply/sum/count`:批式全窗口聚合(读完全部输入后输出)
- `assignTimestampsAndWatermarks(gen)` 与 `(TimestampAssigner, gen)` 两个重载均支持
- `addSource` 生命周期:`open → run → finally close`;`addSink` 对称
- 限制:无并行、无取消语义、无限流源会 OOM、窗口丢弃水位线状态

## 2. Redis 引擎(RedisStreamExecutionEnvironment)

用途:生产。推模型:基于 mq 模块的消费者线程回调驱动算子链(`RedisOperatorNode` 内联执行,无跨算子 shuffle;`keyBy` 通过 ThreadLocal 绑定当前 key,状态存 Redis)。

### 2.1 构建与启动

```java
import io.github.cuihairu.redis.streaming.runtime.redis.*;

RedisRuntimeConfig config = RedisRuntimeConfig.builder()
        .jobName("order-agg")                       // 状态/检查点键空间隔离的关键
        .stateKeyPrefix("streaming:state")
        .checkpointInterval(Duration.ofSeconds(30))
        .watermarkOutOfOrderness(Duration.ofSeconds(5))
        .pipelineParallelism(2)                     // 每管道的 consumer 子任务数(按分区取模)
        .deferAckUntilCheckpoint(true)
        .restoreFromLatestCheckpoint(true)
        .sinkDeduplicationEnabled(true)
        .build();

RedisStreamExecutionEnvironment env = RedisStreamExecutionEnvironment.create(redissonClient, config);

env.fromMqTopic("orders", "cg-orders")              // 源 = Redis Stream 消费组(可传 SubscriptionOptions)
   .assignTimestampsAndWatermarks(                   // 可选:接入 core WatermarkGenerator
       new BoundedOutOfOrdernessWatermarkGenerator<>(Duration.ofSeconds(5)))
   .map(m -> parse(m.getPayload()))
   .keyBy(Order::getUserId)
   .window(TumblingWindow.of(Duration.ofMinutes(1)))
   .reduce((a, b) -> merge(a, b))
   .addSink(new MySink());

RedisJobClient job = env.executeAsync();            // 每环境仅可调用一次
```

### 2.2 RedisJobClient(作业句柄)

`AutoCloseable`;`close()` 即 `cancel()`。方法:
- `cancel()`:停消费、关 runner、释放 sink 资源(幂等)
- `triggerCheckpointNow()`:手动触发一次检查点(与周期调度互斥,单飞)
- `pause()` / `resume()` / `inFlight()`:背压式暂停恢复(PausableMessageConsumer)
- `getLatestCheckpoint()` / `diagnostics()`:可观测(返回配置+水位+在途量的 Map)
- `awaitTermination(Duration)`

### 2.3 关键语义

- **事件时间** = 消息投递时间戳;水位线 = max(投递) − `watermarkOutOfOrderness`,用户 `WatermarkGenerator` 只能**单调提升**水位线(`Context.raiseWatermark`)。
- **窗口触发**:在每条消息处理过程中检查到期窗口(每记录最多 `windowMaxFiresPerRecord` 个),迟到元素计数并丢弃。
- **状态**:`RedisKeyedStateStore`(按 key 分片到多 Redis Hash,支持 TTL、schema 演进策略、热键告警);`keyBy().process` 中 `ctx.getState(StateDescriptor)` 取 `ValueState`。
- **检查点**:`RedisRuntimeCheckpointManager` 快照 key 状态集合 + 消费组 pending/commit frontier;恢复时回放 sink `onCheckpointRestore` 并可从 commit frontier 重建消费组。
- **exactly-once 演示 sink**:`runtime/redis/sink` 下 `RedisIdempotentListSink` / `RedisCheckpointedIdempotentListSink` / `RedisAtomicCheckpointListSink`(去重表/Lua 原子提交)。
- **定时器**:`KeyedProcessFunction` 的 processing-time(共享 ScheduledExecutor)与 event-time(优先队列,随水位线触发)。
- **sink 生命周期**:首条消息时 `open()`,`job.cancel()/close()` 时 `close()`。
- 未支持:`fromCollection/fromElements/addSource`(仅 MQ 源);`(TimestampAssigner, generator)` 重载;窗口 Trigger 接口。

## 3. 指标

`RedisRuntimeMetrics` 单例收集(job/topic/group 维度):作业启停、管道、处理时延与成败、检查点触发/耗时/失败、窗口触发/迟到、keyed state 读写与热键、事件时间定时器队列。桥接到 Micrometer/Prometheus 由 spring-boot-starter 完成(见 starter 文档)。

## 相关文档
[Core.md](Core.md) · [state.md](state.md) · [checkpoint.md](checkpoint.md) · [watermark.md](watermark.md) · [MQ.md](MQ.md) · [Spring-Boot-Starter.md](Spring-Boot-Starter.md)
