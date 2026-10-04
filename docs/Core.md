# Core Module

模块目录:`core/`

流处理框架的纯接口层:定义用户编程 API 与运行时之间的契约,本身不含执行引擎与 Redis 访问代码——`core/src/main/java` 全包没有 `org.redisson` / `com.fasterxml` / `org.slf4j` 的 import。runtime 模块的两套引擎(内存 `StreamExecutionEnvironment`、Redis `RedisStreamExecutionEnvironment`)实现这些接口,state / checkpoint / watermark / window 等模块在这些接口之上提供实现与策略。

> 构建依赖提示:`core/build.gradle` 仍以 `api` 形式声明 `libs.redisson`、`libs.jackson.databind`、`libs.slf4j.api`,会被下游模块传递继承;"不依赖"只针对本模块源码。

## 1. Scope

- 基础 API:`DataStream` / `KeyedStream` / `WindowedStream`
- 源与汇:`StreamSource`、`StreamSink`、`CheckpointAwareSink`、`TwoPhaseCommitSink`、`IdempotentRecord`(源/汇均含可选 `open()/close()` 生命周期)
- 有状态处理:`KeyedProcessFunction`(含 `Context` 定时器注册与 `Collector`)
- 状态接口:`State`、`ValueState`、`ListState`、`MapState`、`SetState`、`StateDescriptor`(Redis 实现见 [state 模块文档](state.md))
- 窗口与时间:`WindowAssigner`(内嵌 `Window`/`Trigger`/`TriggerResult`)、`WindowFunction`、`AggregateFunction`、`ReduceFunction`、`TimestampAssigner`、`WatermarkGenerator`、`Watermark`
- 检查点契约:`Checkpoint`(含 `StateSnapshot`)、`CheckpointCoordinator`
- 工具:`SystemUtils`、`InstanceIdGenerator`

## 2. 对外接口(关键类与方法签名)

### 包 `api.stream`

| 接口/类 | 关键方法 |
|---|---|
| `DataStream<T>` | `<R> DataStream<R> map(Function<T,R>)`、`filter(Predicate<T>)`、`flatMap(Function<T,Iterable<R>>)`、`<K> KeyedStream<K,T> keyBy(Function<T,K>)`、`addSink(StreamSink<T>)`、`print()`、`print(String)`、`assignTimestampsAndWatermarks(WatermarkGenerator<T>)`、`assignTimestampsAndWatermarks(TimestampAssigner<T>, WatermarkGenerator<T>)`(后两个为 default,抛 `UnsupportedOperationException`) |
| `KeyedStream<K,T>` | `map`、`<R> DataStream<R> process(KeyedProcessFunction<K,T,R>)`、`window(WindowAssigner<T>)`、`reduce(ReduceFunction<T>)`、`sum(Function<T,? extends Number>)`、`<S> ValueState<S> getState(StateDescriptor<S>)` |
| `WindowedStream<K,T>` | `reduce(ReduceFunction<T>)`、`<R> DataStream<R> aggregate(AggregateFunction<T,R>)`、`<R> DataStream<R> apply(WindowFunction<K,T,R>)`、`sum(Function<T,? extends Number>)`、`count()`(返回 `DataStream<Long>`) |
| `StreamSource<T>` | `default open()`、`run(SourceContext<T>)`、`default cancel()`、`default close()`;内嵌 `SourceContext`:`collect(T)`、`collectWithTimestamp(T,long)`、`getCheckpointLock()`、`isStopped()` |
| `StreamSink<T>` | `@FunctionalInterface`;`default open()`、唯一抽象方法 `invoke(T)`、`default close()` |
| `CheckpointAwareSink<T>` | 继承 `StreamSink<T>`;default 钩子 `onCheckpointStart(long)`、`onCheckpointComplete(long)`、`onCheckpointAbort(long,Throwable)`、`onCheckpointRestore(long)` |
| `TwoPhaseCommitSink<T,Txn extends Serializable>` | 继承 `CheckpointAwareSink<T>`;`beginTxn()`、`invoke(T,Txn)`、`preCommit(Txn)`、`commit(Txn)`、`default abort(Txn)`(委托 `recoverAndAbort`)、`recoverAndCommit(Txn)`、`recoverAndAbort(Txn)`;单参 `invoke(T)` 的 default 实现直接抛 `IllegalStateException` |
| `IdempotentRecord<T>` | `record IdempotentRecord<T>(String id, T value)`;紧凑构造器校验 `id`/`value` 非空且 `id` 非空白 |
| `KeyedProcessFunction<K,I,O>` | `processElement(K, I, Context, Collector<O>)` + default `onProcessingTime(long,K,Context,Collector<O>)`、`onEventTime(long,K,Context,Collector<O>)`(均空实现);`Context`:`currentProcessingTime()`、`currentWatermark()`、`registerProcessingTimeTimer(long)`、`registerEventTimeTimer(long)`;`Collector<T>`:`collect(T)` |
| `WindowAssigner<T>` | `assignWindows(T,long)`、`getDefaultTrigger()`、`default supportsWindowMerging()`(默认 `false`);内嵌 `Window`(`getStart()/getEnd()`)、`Trigger<T>`(`onElement`/`onProcessingTime`/`onEventTime`)、`TriggerResult` 枚举(`CONTINUE`/`FIRE`/`FIRE_AND_PURGE`/`PURGE`) |
| `WindowFunction<K,IN,OUT>` | `apply(K, WindowAssigner.Window, Iterable<IN>, Collector<OUT>)`(内嵌 `Collector<T>`) |
| `AggregateFunction<IN,OUT>` | `createAccumulator()`、`add(IN, Accumulator<IN>)`、`getResult(Accumulator<IN>)`、`merge(a,b)`;内嵌标记接口 `Accumulator<T>` |
| `ReduceFunction<T>` | `@FunctionalInterface`;`T reduce(T,T) throws Exception` |

### 包 `api.state`

- `State extends Serializable`:`void clear()`
- `ValueState<T>`:`T value()`(无值返回 null)、`void update(T)`
- `ListState<T>`:`void add(T)`、`Iterable<T> get()`、`void update(Iterable<T>)`(整体替换)、`void addAll(Iterable<T>)`
- `MapState<K,V>`:`V get(K)`、`put(K,V)`、`remove(K)`、`contains(K)`、`Iterable<Map.Entry<K,V>> entries()`、`Iterable<K> keys()`、`Iterable<V> values()`、`boolean isEmpty()`
- `SetState<T>`:`boolean add(T)`、`boolean remove(T)`、`contains(T)`、`Iterable<T> get()`、`boolean isEmpty()`、`int size()`
- `StateDescriptor<T>`:构造 `StateDescriptor(name, type)` / `(name, type, defaultValue)` / `(name, type, defaultValue, schemaVersion)`;getter `getName()`、`getType()`、`getDefaultValue()`、`getSchemaVersion()`

### 包 `api.checkpoint`

- `Checkpoint`:`getCheckpointId()`、`getTimestamp()`、`getStateSnapshot()`、`isCompleted()`、`markCompleted()`、`default int getSnapshotVersion()`(默认 0,当前实现写 1);内嵌 `StateSnapshot`:`getState(String)`、`default getState(String, Class<T>)`、`putState(String,T)`、`getKeys()`
- `CheckpointCoordinator`:`long triggerCheckpoint()`、`acknowledgeCheckpoint(long,String)`、`completeCheckpoint(long)`、`restoreFromCheckpoint(long)`、`Checkpoint getLatestCheckpoint()`、`Checkpoint getCheckpoint(long)`(均不抛受检异常)

### 包 `api.watermark`

- `Watermark`:`Watermark(long)`、`getTimestamp()`、`Comparable<Watermark>`、静态 `maxWatermark()`(= `Long.MAX_VALUE`)
- `WatermarkGenerator<T>`:`onEvent(T, long, WatermarkOutput)`、`onPeriodicEmit(WatermarkOutput)`;内嵌 `WatermarkOutput`:`emitWatermark(Watermark)`、`markIdle()`、`markActive()`
- `TimestampAssigner<T>`:`@FunctionalInterface`;`long extractTimestamp(T element, long recordTimestamp)`

### 包 `core.utils` 与 `api`

- `SystemUtils`:`static String getLocalHostname()`(带缓存,失败抛 `RuntimeException`)、`static void clearHostnameCache()`
- `InstanceIdGenerator`:`generateInstanceId(String serviceName, String host, int port)`(格式 `serviceName-host:port`)、`generateInstanceId(String serviceName, int port)`、`generateLocalInstanceId(String serviceName, int port)`(格式 `hostname:port`)
- `api.StreamingApiExample`:纯文档类(私有构造器,无逻辑)

## 3. 配置项与默认值

core 没有运行时配置键,只有接口层默认值:

| 位置 | 默认值 |
|---|---|
| `StateDescriptor.schemaVersion` | `1`(`Math.max(1, schemaVersion)` 截断) |
| `Checkpoint.getSnapshotVersion()` | `0`(无版本标记的旧快照);checkpoint 模块 `DefaultCheckpoint.CURRENT_SNAPSHOT_VERSION = 1` |
| `DataStream.assignTimestampsAndWatermarks(...)` 两个 default 重载 | 抛 `UnsupportedOperationException("This runtime does not support watermark assignment" / "...event-time timestamp assignment")` |
| `KeyedProcessFunction.onProcessingTime/onEventTime` | default 空实现 |
| `WindowAssigner.supportsWindowMerging()` | `false` |
| `StreamSource.open/cancel/close`、`StreamSink.open/close` | default 空实现;`open()` 抛出的异常在两套运行时都会向外传播(内存引擎包装为 `RuntimeException`),`close()` 抛出的异常只记日志不上抛 |
| `TwoPhaseCommitSink.abort(Txn)` | default 调用 `recoverAndAbort(Txn)` |

## 4. 算子生命周期契约

`StreamSink` / `StreamSource` 的 `open()` / `close()` 均为 default 空实现,向后兼容:

```java
public interface StreamSink<T> extends Serializable {
    default void open() throws Exception {}          // 首次投递前调用一次
    void invoke(T value) throws Exception;           // 逐条处理(唯一抽象方法)
    default void close() throws Exception {}         // 作业结束/取消时调用,必须幂等
}
```

- 内存引擎:`addSink` 在遍历前调用 `open()`、finally 中调用 `close()`(`close()` 异常只记日志);`addSource` 对称调用 `source.open()` → `run(ctx)` → finally `source.close()`。
- Redis 引擎:runner 在消息处理入口幂等 `open()`(`ensureSinksOpen` 由 `sinksOpened` 标志保护),任务关闭时 `close()`;`close()` 抛出的异常只记录日志不上抛。
- `StreamSource.cancel()` 定义在接口上,但当前两套运行时均未调用(未见于实现)。
- `SourceContext.getCheckpointLock()`:内存引擎返回 `addSource` 内新建的 `Object`,运行时没有对它 `synchronized` 的代码;Redis 引擎没有 `StreamSource` 路径(入口是 `fromMqTopic`)。

## 5. Minimal Sample(内存引擎)

```java
import io.github.cuihairu.redis.streaming.runtime.StreamExecutionEnvironment;

StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
env.fromElements("a", "bb", "ccc")
        .map(String::length)
        .print("core> ");
```

> 注意:内存引擎是惰性拉取模型,没有 `execute()`;终止操作(`addSink`/`print`)触发全量执行。`print(prefix)` 在内存引擎里通过 SLF4J logger(`InMemoryDataStream`)输出,不是直接写 stdout。Redis 引擎入口见 [runtime 模块文档](runtime.md)。

## References

- [Architecture.md](Architecture.md) · [API.md](API.md) · [state.md](state.md) · [checkpoint.md](checkpoint.md) · [watermark.md](watermark.md) · [runtime.md](runtime.md) · [source-sink.md](source-sink.md)
