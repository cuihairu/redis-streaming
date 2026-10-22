# Window Module

Module: `window/`

## Module Responsibility

Cut an unbounded stream into finite windows by time. The module provides exactly two kinds of things: **window assigners** (`assigners`, deciding which windows an element belongs to) and **triggers** (`triggers`, deciding when a window fires/purges), plus one time-window value object `TimeWindow`. Execution wiring is done by the two `runtime` engines (InMemory / Redis) — see "Behavior in the Execution Engines" below.

The module has 7 main source files: 3 assigners (`TumblingWindow` / `SlidingWindow` / `SessionWindow`), 3 triggers (`EventTimeTrigger` / `ProcessingTimeTrigger` / `CountTrigger`), 1 window object (`TimeWindow`).

## Window Assigners

Package `io.github.cuihairu.redis.streaming.window.assigners`, all implementing `WindowAssigner<T>`.

### TumblingWindow

Fixed size, non-overlapping.

```java
TumblingWindow<String> w = TumblingWindow.of(Duration.ofMinutes(1)); // or ofMillis(60_000)
w.getSize();                       // window size (ms)
w.assignWindows("e", 65_000L);     // -> [60000, 120000), floored onto the size grid, 1 window
```

- Constructor constraint: `size <= 0` throws `IllegalArgumentException`
- Default trigger: `EventTimeTrigger`
- `supportsWindowMerging()` is `false` (default)

### SlidingWindow

Fixed size, overlapping; one element may fall into multiple windows.

```java
SlidingWindow<String> w = SlidingWindow.of(Duration.ofSeconds(10), Duration.ofSeconds(5));
// or ofMillis(sizeMillis, slideMillis)
w.getSize();   // 10000
w.getSlide();  // 5000
w.assignWindows("e", 12_000L);     // -> multiple windows: starts slide-aligned and inside (ts-size, ts]
                                   //    (size=10s/slide=5s gives [5000,15000) and [10000,20000))
```

- Constructor constraint: `size <= 0` or `slide <= 0` throws `IllegalArgumentException`
- Default trigger: `EventTimeTrigger`
- `supportsWindowMerging()` is `false`: a sliding window's overlap is intentional and must not be merged

### SessionWindow

Divided by an inactivity gap; every element first lands in its own `[ts, ts+gap)` window, and overlapping windows are merged afterwards.

```java
SessionWindow<String> w = SessionWindow.withGap(Duration.ofSeconds(30)); // or withGapMillis(30_000)
w.getSessionGap();                 // 30000
w.assignWindows("e", ts);          // -> a single [ts, ts+gap)
w.supportsWindowMerging();         // true: the only built-in assigner overriding it to true
SessionWindow.shouldMerge(w1, w2);  // equivalent to TimeWindow.intersects(w1, w2)
```

- Constructor constraint: `gap == null` (`withGap(Duration)`) or `gapMillis <= 0` throws
- Default trigger: `EventTimeTrigger`

## The TimeWindow Value Object

`io.github.cuihairu.redis.streaming.window.TimeWindow` implements core's `WindowAssigner.Window` (`getStart()` / `getEnd()`):

```java
TimeWindow w = new TimeWindow(0, 60_000);
w.getStart();          // 0
w.getEnd();            // 60000
w.maxTimestamp();      // 59999 (end - 1)
w.getSize();           // 60000
w.contains(59_999L);   // true, [start, end) half-open interval
TimeWindow.intersects(w1, w2);        // whether the two windows intersect
TimeWindow.merge(w1, w2);             // merge into a window covering both
```

## Triggers

Package `io.github.cuihairu.redis.streaming.window.triggers`, all implementing `WindowAssigner.Trigger<T>`.

| Trigger | `onElement` | `onEventTime` | `onProcessingTime` |
|---|---|---|---|
| `EventTimeTrigger<T>` | `CONTINUE` | `FIRE_AND_PURGE` when `time >= window.getEnd()` | `CONTINUE` |
| `ProcessingTimeTrigger<T>` | `CONTINUE` (registers no timer) | `CONTINUE` | `FIRE_AND_PURGE` when `time >= window.getEnd()` |
| `CountTrigger<T>` | `FIRE` and reset the counter once `maxCount` elements have accumulated | `CONTINUE` | `CONTINUE` |

- `EventTimeTrigger` / `ProcessingTimeTrigger`: no-arg constructors, no factory methods.
- `CountTrigger`: `new CountTrigger<>(10)` or `CountTrigger.of(5)`; `maxCount <= 0` throws `IllegalArgumentException`. Trigger instances are stateful (they keep the count), so they must be obtained per (key, window) bucket as fresh instances through `getDefaultTrigger()` (see below).

## Core API (defined in the core module)

The window module implements `io.github.cuihairu.redis.streaming.api.stream.WindowAssigner`:

```java
public interface WindowAssigner<T> extends Serializable {
    Iterable<Window> assignWindows(T element, long timestamp);

    // called once per (key, window) bucket; implementations must return a new instance
    // (stateful triggers must not share instances)
    Trigger<T> getDefaultTrigger();

    // mergeable window types (sessions) override to true; when true the engine merges
    // intersecting buckets into a union window
    default boolean supportsWindowMerging() { return false; }

    interface Window { long getStart(); long getEnd(); }

    interface Trigger<T> {
        TriggerResult onElement(T element, long timestamp, Window window);
        TriggerResult onProcessingTime(long time, Window window);
        TriggerResult onEventTime(long time, Window window);
    }

    enum TriggerResult { CONTINUE, FIRE, FIRE_AND_PURGE, PURGE }
}
```

`CONTINUE` keeps accumulating; `FIRE` emits and keeps the state accumulating; `FIRE_AND_PURGE` emits and empties; `PURGE` drops the window without emitting.

The window entry point is on `KeyedStream`:

```java
WindowedStream<K, T> window(WindowAssigner<T> windowAssigner);
```

The window operators offered by `WindowedStream<K, T>` (there are no `trigger(...)`, `allowedLateness(...)`, `sideOutputLateData(...)`, or `process(...)` methods):

```java
DataStream<T> reduce(ReduceFunction<T> reducer);                 // reduce within the window
<R> DataStream<R> aggregate(AggregateFunction<T, R> fn);         // aggregate within the window
<R> DataStream<R> apply(WindowFunction<K, T, R> windowFunction); // access all elements in the window
DataStream<T> sum(Function<T, ? extends Number> fieldSelector);  // sum a numeric field
DataStream<Long> count();                                        // window count
```

`AggregateFunction<IN, OUT>` (`io.github.cuihairu.redis.streaming.api.stream`) must implement `createAccumulator()` / `add(IN, Accumulator<IN>)` / `getResult(Accumulator<IN>)` / `merge(a, b)`. `WindowFunction<K, IN, OUT>` is a functional interface with the signature `apply(K key, WindowAssigner.Window window, Iterable<IN> elements, Collector<OUT> out)`.

## Behavior in the Execution Engines

- **InMemory engine** (`runtime`'s `InMemoryWindowedStream`): batch processing; each element is bucketed by the assigner and passed to `trigger.onElement`, and at end-of-stream every non-empty bucket gets a final `trigger.onEventTime(window.getEnd(), window)` flush. Assigners with `supportsWindowMerging() == true` (session windows) merge intersecting buckets into union windows before accumulating. `onProcessingTime` is never called.
- **Redis engine** (`runtime`'s `RedisStreamBuilder`): elements accumulate and `trigger.onElement` is consulted; as the watermark advances, due windows get `trigger.onEventTime`; `FIRE` means a partial emission (state kept, accumulation continues), `FIRE_AND_PURGE` / `PURGE` end the window. The default `EventTimeTrigger` (`CONTINUE` / `FIRE_AND_PURGE`) is point-for-point equivalent to the pure watermark-close behavior. `onProcessingTime` is likewise not wired (no processing-time window timers; see docs/watermark.md, todo.md B3).

## Configuration

The window module itself has no configuration-file keys; all parameters go through constructors/factory methods (see the constructor constraints above).

Window-related runtime configuration lives in the Redis engine's `RedisRuntimeConfig.Builder` (runtime module):

| Builder method | Type | Default | Notes |
|---|---|---|---|
| `windowAllowedLateness(Duration)` | `Duration` | `Duration.ZERO` | Allowed lateness: the final emission is deferred to `windowEnd + windowAllowedLateness`; negatives throw `IllegalArgumentException` |
| `windowMaxFiresPerRecord(int)` | `int` | `256` | Maximum window emissions a single record may trigger (bounds per-record work under sliding windows); `< 1` throws |
| `watermarkOutOfOrderness(Duration)` | `Duration` | `Duration.ZERO` | The out-of-orderness heuristic when no custom WatermarkGenerator is set |

## Usage Examples

### Event-time tumbling window (InMemory engine, from the runtime tests)

```java
StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

record Event(long ts, String value) {}

env.fromElements(new Event(1000, "a"), new Event(1500, "b"), new Event(2500, "c"))
        .assignTimestampsAndWatermarks((event, recordTs) -> event.ts(),
                new AscendingTimestampWatermarkGenerator<>())
        .keyBy(v -> "k")
        .window(TumblingWindow.of(Duration.ofMillis(1000)))
        .count()                       // -> [2, 1]
        .addSink(counts::add);
```

### reduce / sum / aggregate / apply (Redis engine, from the runtime integration tests)

```java
// reduce
keyed -> keyed.window(TumblingWindow.<Integer>ofMillis(1000)).reduce(Integer::sum)

// sum a numeric field
keyed -> keyed.window(TumblingWindow.<Integer>ofMillis(1000)).sum(v -> v)

// count
keyed -> keyed.window(TumblingWindow.<Integer>ofMillis(1000)).count()

// custom aggregation (prototype: MaxAccumulator in the runtime test RedisRuntimeWindowedStreamIntegrationTest)
class MaxAccumulator implements AggregateFunction<Integer, Integer> {
    static final class Max implements AggregateFunction.Accumulator<Integer> {
        int max = Integer.MIN_VALUE;
    }
    public Accumulator<Integer> createAccumulator() { return new Max(); }
    public Accumulator<Integer> add(Integer v, Accumulator<Integer> acc) {
        ((Max) acc).max = Math.max(((Max) acc).max, v);
        return acc;
    }
    public Integer getResult(Accumulator<Integer> acc) { return ((Max) acc).max; }
    public Accumulator<Integer> merge(Accumulator<Integer> a, Accumulator<Integer> b) {
        ((Max) a).max = Math.max(((Max) a).max, ((Max) b).max);
        return a;
    }
}
// keyed.window(TumblingWindow.<Integer>ofMillis(1000)).aggregate(new MaxAccumulator())

// window function: access the key, the window, and all elements
keyed.window(TumblingWindow.<Integer>ofMillis(1000))
     .apply((WindowFunction<String, Integer, String>) (key, window, elements, collector) -> {
         List<Integer> seen = new ArrayList<>();
         elements.forEach(seen::add);
         collector.collect(key + ":" + seen);
     });
```

### Sliding window (one element into multiple windows)

```java
env.fromMqTopic(topic, "g")
        .map(m -> Integer.parseInt((String) m.getPayload()))
        .keyBy(v -> "k")
        .window(SlidingWindow.<Integer>of(Duration.ofMillis(1500), Duration.ofMillis(1500)))
        .sum(v -> v)
        .addSink(out::add);
```

### Using assigners directly, without an engine

```java
SessionWindow<String> session = SessionWindow.withGap(Duration.ofSeconds(30));
for (WindowAssigner.Window w : session.assignWindows("click", ts)) {
    // w.getStart() / w.getEnd()
}
```

### Allowed lateness

There is no `WindowedStream.allowedLateness(...)` or side-output API; late tolerance is a Redis-engine runtime setting:

```java
RedisRuntimeConfig cfg = RedisRuntimeConfig.builder()
        .jobName("job")
        .stateKeyPrefix("streaming:job:")
        .watermarkOutOfOrderness(Duration.ofSeconds(5))
        .windowAllowedLateness(Duration.ofSeconds(30)) // windows finally fire at windowEnd+30s
        .build();
```

## Related Documentation

- [watermark](watermark.md) - the watermark mechanism (the time basis on which windows close)
- [Aggregation](Aggregation.md) - Redis aggregation utilities (independent of the runtime's WindowedStream)
