# Core Module

Module directory: `core/`

The pure interface layer of the streaming framework: it defines the contract between the user-facing programming API and the runtime, and contains no execution engine or Redis access code itself — the entire `core/src/main/java` tree has no imports of `org.redisson` / `com.fasterxml` / `org.slf4j`. The two engines in the runtime module (the in-memory `StreamExecutionEnvironment` and the Redis `RedisStreamExecutionEnvironment`) implement these interfaces, while modules such as state / checkpoint / watermark / window provide implementations and strategies on top of them.

> Build dependency note: `core/build.gradle` still declares `libs.redisson`, `libs.jackson.databind`, and `libs.slf4j.api` as `api` dependencies, which downstream modules inherit transitively; "no dependencies" refers only to this module's own source code.

## 1. Scope

- Base APIs: `DataStream` / `KeyedStream` / `WindowedStream`
- Sources and sinks: `StreamSource`, `StreamSink`, `CheckpointAwareSink`, `TwoPhaseCommitSink`, `IdempotentRecord` (both sources and sinks carry optional `open()/close()` lifecycle hooks)
- Stateful processing: `KeyedProcessFunction` (with `Context` timer registration and `Collector`)
- State interfaces: `State`, `ValueState`, `ListState`, `MapState`, `SetState`, `StateDescriptor` (for the Redis implementations see the [state module doc](state.md))
- Windows and time: `WindowAssigner` (with embedded `Window`/`Trigger`/`TriggerResult`), `WindowFunction`, `AggregateFunction`, `ReduceFunction`, `TimestampAssigner`, `WatermarkGenerator`, `Watermark`
- Checkpoint contracts: `Checkpoint` (with `StateSnapshot`), `CheckpointCoordinator`
- Utilities: `SystemUtils`, `InstanceIdGenerator`

## 2. Public Interfaces (key classes and method signatures)

### Package `api.stream`

| Interface/Class | Key methods |
|---|---|
| `DataStream<T>` | `<R> DataStream<R> map(Function<T,R>)`, `filter(Predicate<T>)`, `flatMap(Function<T,Iterable<R>>)`, `<K> KeyedStream<K,T> keyBy(Function<T,K>)`, `addSink(StreamSink<T>)`, `print()`, `print(String)`, `assignTimestampsAndWatermarks(WatermarkGenerator<T>)`, `assignTimestampsAndWatermarks(TimestampAssigner<T>, WatermarkGenerator<T>)` (the last two are `default` and throw `UnsupportedOperationException`) |
| `KeyedStream<K,T>` | `map`, `<R> DataStream<R> process(KeyedProcessFunction<K,T,R>)`, `window(WindowAssigner<T>)`, `reduce(ReduceFunction<T>)`, `sum(Function<T,? extends Number>)`, `<S> ValueState<S> getState(StateDescriptor<S>)` |
| `WindowedStream<K,T>` | `reduce(ReduceFunction<T>)`, `<R> DataStream<R> aggregate(AggregateFunction<T,R>)`, `<R> DataStream<R> apply(WindowFunction<K,T,R>)`, `sum(Function<T,? extends Number>)`, `count()` (returns `DataStream<Long>`) |
| `StreamSource<T>` | `default open()`, `run(SourceContext<T>)`, `default cancel()`, `default close()`; embedded `SourceContext`: `collect(T)`, `collectWithTimestamp(T,long)`, `getCheckpointLock()`, `isStopped()` |
| `StreamSink<T>` | `@FunctionalInterface`; `default open()`, the single abstract method `invoke(T)`, `default close()` |
| `CheckpointAwareSink<T>` | Extends `StreamSink<T>`; default hooks `onCheckpointStart(long)`, `onCheckpointComplete(long)`, `onCheckpointAbort(long,Throwable)`, `onCheckpointRestore(long)` |
| `TwoPhaseCommitSink<T,Txn extends Serializable>` | Extends `CheckpointAwareSink<T>`; `beginTxn()`, `invoke(T,Txn)`, `preCommit(Txn)`, `commit(Txn)`, `default abort(Txn)` (delegates to `recoverAndAbort`), `recoverAndCommit(Txn)`, `recoverAndAbort(Txn)`; the default implementation of the single-argument `invoke(T)` throws `IllegalStateException` directly |
| `IdempotentRecord<T>` | `record IdempotentRecord<T>(String id, T value)`; the compact constructor validates that `id`/`value` are non-null and that `id` is not blank |
| `KeyedProcessFunction<K,I,O>` | `processElement(K, I, Context, Collector<O>)` plus default `onProcessingTime(long,K,Context,Collector<O>)` and `onEventTime(long,K,Context,Collector<O>)` (both empty); `Context`: `currentProcessingTime()`, `currentWatermark()`, `registerProcessingTimeTimer(long)`, `registerEventTimeTimer(long)`, default `<S> getState(StateDescriptor<S>)` (throws `UnsupportedOperationException` by default; both engines override it to wire keyed state); `Collector<T>`: `collect(T)` |
| `WindowAssigner<T>` | `assignWindows(T,long)`, `getDefaultTrigger()`, `default supportsWindowMerging()` (defaults to `false`); embedded `Window` (`getStart()/getEnd()`), `Trigger<T>` (`onElement`/`onProcessingTime`/`onEventTime`), `TriggerResult` enum (`CONTINUE`/`FIRE`/`FIRE_AND_PURGE`/`PURGE`) |
| `WindowFunction<K,IN,OUT>` | `apply(K, WindowAssigner.Window, Iterable<IN>, Collector<OUT>)` (with embedded `Collector<T>`) |
| `AggregateFunction<IN,OUT>` | `createAccumulator()`, `add(IN, Accumulator<IN>)`, `getResult(Accumulator<IN>)`, `merge(a,b)`; embedded marker interface `Accumulator<T>` |
| `ReduceFunction<T>` | `@FunctionalInterface`; `T reduce(T,T) throws Exception` |

### Package `api.state`

- `State extends Serializable`: `void clear()`
- `ValueState<T>`: `T value()` (returns null when no value), `void update(T)`
- `ListState<T>`: `void add(T)`, `Iterable<T> get()`, `void update(Iterable<T>)` (wholesale replacement), `void addAll(Iterable<T>)`
- `MapState<K,V>`: `V get(K)`, `put(K,V)`, `remove(K)`, `contains(K)`, `Iterable<Map.Entry<K,V>> entries()`, `Iterable<K> keys()`, `Iterable<V> values()`, `boolean isEmpty()`
- `SetState<T>`: `boolean add(T)`, `boolean remove(T)`, `contains(T)`, `Iterable<T> get()`, `boolean isEmpty()`, `int size()`
- `StateDescriptor<T>`: constructors `StateDescriptor(name, type)` / `(name, type, defaultValue)` / `(name, type, defaultValue, schemaVersion)`; getters `getName()`, `getType()`, `getDefaultValue()`, `getSchemaVersion()`

### Package `api.checkpoint`

- `Checkpoint`: `getCheckpointId()`, `getTimestamp()`, `getStateSnapshot()`, `isCompleted()`, `markCompleted()`, `default int getSnapshotVersion()` (defaults to 0; current implementations write 1); embedded `StateSnapshot`: `getState(String)`, `default getState(String, Class<T>)`, `putState(String,T)`, `getKeys()`
- `CheckpointCoordinator`: `long triggerCheckpoint()`, `acknowledgeCheckpoint(long,String)`, `completeCheckpoint(long)`, `restoreFromCheckpoint(long)`, `Checkpoint getLatestCheckpoint()`, `Checkpoint getCheckpoint(long)` (none throw checked exceptions)

### Package `api.watermark`

- `Watermark`: `Watermark(long)`, `getTimestamp()`, `Comparable<Watermark>`, static `maxWatermark()` (= `Long.MAX_VALUE`)
- `WatermarkGenerator<T>`: `onEvent(T, long, WatermarkOutput)`, `onPeriodicEmit(WatermarkOutput)`; embedded `WatermarkOutput`: `emitWatermark(Watermark)`, `markIdle()`, `markActive()`
- `TimestampAssigner<T>`: `@FunctionalInterface`; `long extractTimestamp(T element, long recordTimestamp)`

### Packages `core.utils` and `api`

- `SystemUtils`: `static String getLocalHostname()` (cached; throws `RuntimeException` on failure), `static void clearHostnameCache()`
- `InstanceIdGenerator`: `generateInstanceId(String serviceName, String host, int port)` (format `serviceName-host:port`), `generateInstanceId(String serviceName, int port)`, `generateLocalInstanceId(String serviceName, int port)` (format `hostname:port`)
- `api.StreamingApiExample`: documentation-only class (private constructor, no logic)

## 3. Configuration Keys and Defaults

core has no runtime configuration keys, only interface-level defaults:

| Location | Default |
|---|---|
| `StateDescriptor.schemaVersion` | `1` (clamped via `Math.max(1, schemaVersion)`) |
| `Checkpoint.getSnapshotVersion()` | `0` (legacy snapshots without a version marker); the checkpoint module has `DefaultCheckpoint.CURRENT_SNAPSHOT_VERSION = 1` |
| The two `default` overloads of `DataStream.assignTimestampsAndWatermarks(...)` | Throw `UnsupportedOperationException("This runtime does not support watermark assignment" / "...event-time timestamp assignment")` |
| `KeyedProcessFunction.onProcessingTime/onEventTime` | Default empty implementations |
| `WindowAssigner.supportsWindowMerging()` | `false` |
| `StreamSource.open/cancel/close`, `StreamSink.open/close` | Default empty implementations; exceptions thrown from `open()` propagate outward in both runtimes (wrapped as `RuntimeException` by the in-memory engine), while exceptions thrown from `close()` are only logged, not rethrown |
| `TwoPhaseCommitSink.abort(Txn)` | Default calls `recoverAndAbort(Txn)` |

## 4. Operator Lifecycle Contract

`open()` / `close()` on `StreamSink` / `StreamSource` are both default empty implementations, for backward compatibility:

```java
public interface StreamSink<T> extends Serializable {
    default void open() throws Exception {}          // called once before the first delivery
    void invoke(T value) throws Exception;           // per-record processing (the only abstract method)
    default void close() throws Exception {}         // called when the job ends/is cancelled; must be idempotent
}
```

- In-memory engine: `addSink` calls `open()` before iterating and `close()` in a finally block (exceptions from `close()` are only logged); `addSource` symmetrically calls `source.open()` → `run(ctx)` → finally `source.close()`.
- Redis engine: the runner idempotently calls `open()` at the message-processing entry point (`ensureSinksOpen` is guarded by a `sinksOpened` flag) and calls `close()` on task shutdown; exceptions thrown from `close()` are only logged, not rethrown.
- `StreamSource.cancel()` is declared on the interface, but neither runtime currently calls it (not seen in any implementation).
- `SourceContext.getCheckpointLock()`: the in-memory engine returns a freshly created `Object` inside `addSource`, and no runtime code ever `synchronized` on it; the Redis engine has no `StreamSource` path (the entry point is `fromMqTopic`).

## 5. Minimal Sample (in-memory engine)

```java
import io.github.cuihairu.redis.streaming.runtime.StreamExecutionEnvironment;

StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
env.fromElements("a", "bb", "ccc")
        .map(String::length)
        .print("core> ");
```

> Note: the in-memory engine is a lazy pull model with no `execute()`; terminal operations (`addSink`/`print`) trigger full execution. In the in-memory engine, `print(prefix)` writes through an SLF4J logger (`InMemoryDataStream`), not directly to stdout. For the Redis engine entry points see the [runtime module doc](runtime.md).

## References

- [Architecture.md](Architecture.md) · [API.md](API.md) · [state.md](state.md) · [checkpoint.md](checkpoint.md) · [watermark.md](watermark.md) · [runtime.md](runtime.md) · [source-sink.md](source-sink.md)
