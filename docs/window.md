# Window 模块

Module: `window/`

## 模块职责

将无限流按时间切成有限窗口。模块只提供两类东西：**窗口分配器**（`assigners`，决定元素属于哪些窗口）和**触发器**（`triggers`，决定窗口何时发射/丢弃），外加一个时间窗口值对象 `TimeWindow`。执行接入由 `runtime` 的两个引擎（InMemory / Redis）完成（见下文「执行引擎中的行为」）。

模块共 7 个主源码文件：3 个分配器（`TumblingWindow` / `SlidingWindow` / `SessionWindow`）、3 个触发器（`EventTimeTrigger` / `ProcessingTimeTrigger` / `CountTrigger`）、1 个窗口对象（`TimeWindow`）。

## 窗口分配器

包 `io.github.cuihairu.redis.streaming.window.assigners`，均实现 `WindowAssigner<T>`。

### TumblingWindow（滚动窗口）

固定大小、不重叠。

```java
TumblingWindow<String> w = TumblingWindow.of(Duration.ofMinutes(1)); // 或 ofMillis(60_000)
w.getSize();                       // 窗口大小（毫秒）
w.assignWindows("e", 65_000L);     // -> [60000, 120000)，按 floorMod 对齐，1 个窗口
```

- 构造约束：`size <= 0` 抛 `IllegalArgumentException`
- 默认触发器：`EventTimeTrigger`
- `supportsWindowMerging()` 为 `false`（默认）

### SlidingWindow（滑动窗口）

固定大小、重叠；一个元素可落入多个窗口。

```java
SlidingWindow<String> w = SlidingWindow.of(Duration.ofSeconds(10), Duration.ofSeconds(5));
// 或 ofMillis(sizeMillis, slideMillis)
w.getSize();   // 10000
w.getSlide();  // 5000
w.assignWindows("e", 12_000L);     // -> 多个窗口：起点 slide 对齐且落在 (ts-size, ts] 内（size=10s、slide=5s 时为 [5000,15000) 与 [10000,20000)）
```

- 构造约束：`size <= 0` 或 `slide <= 0` 抛 `IllegalArgumentException`
- 默认触发器：`EventTimeTrigger`
- `supportsWindowMerging()` 为 `false`：滑动窗口的重叠是有意的，不能合并

### SessionWindow（会话窗口）

按不活动间隔（gap）划分，每个元素先落进自己的 `[ts, ts+gap)` 窗口，重叠窗口随后合并。

```java
SessionWindow<String> w = SessionWindow.withGap(Duration.ofSeconds(30)); // 或 withGapMillis(30_000)
w.getSessionGap();                 // 30000
w.assignWindows("e", ts);          // -> 单个 [ts, ts+gap)
w.supportsWindowMerging();         // true：唯一覆写为 true 的内置分配器
SessionWindow.shouldMerge(w1, w2); // 等价于 TimeWindow.intersects(w1, w2)
```

- 构造约束：`gap == null`（`withGap(Duration)`）或 `gapMillis <= 0` 抛异常
- 默认触发器：`EventTimeTrigger`

## 时间窗口 TimeWindow

`io.github.cuihairu.redis.streaming.window.TimeWindow` 实现 core 的 `WindowAssigner.Window`（`getStart()` / `getEnd()`）：

```java
TimeWindow w = new TimeWindow(0, 60_000);
w.getStart();          // 0
w.getEnd();            // 60000
w.maxTimestamp();      // 59999（end - 1）
w.getSize();           // 60000
w.contains(59_999L);   // true，[start, end) 半开区间
TimeWindow.intersects(w1, w2);        // 两窗口是否相交
TimeWindow.merge(w1, w2);             // 合并为覆盖两者的窗口
```

## 触发器

包 `io.github.cuihairu.redis.streaming.window.triggers`，均实现 `WindowAssigner.Trigger<T>`。

| 触发器 | `onElement` | `onEventTime` | `onProcessingTime` |
|---|---|---|---|
| `EventTimeTrigger<T>` | `CONTINUE` | `time >= window.getEnd()` 时 `FIRE_AND_PURGE` | `CONTINUE` |
| `ProcessingTimeTrigger<T>` | `CONTINUE`（不注册定时器） | `CONTINUE` | `time >= window.getEnd()` 时 `FIRE_AND_PURGE` |
| `CountTrigger<T>` | 计满 `maxCount` 个元素时 `FIRE` 并清零计数 | `CONTINUE` | `CONTINUE` |

- `EventTimeTrigger` / `ProcessingTimeTrigger`：无参构造，无工厂方法。
- `CountTrigger`：`new CountTrigger<>(10)` 或 `CountTrigger.of(5)`；`maxCount <= 0` 抛 `IllegalArgumentException`。触发器实例是有状态的（保留计数），因此必须通过 `getDefaultTrigger()` 按 (key, window) 桶取新实例（见下）。

## 核心 API（定义在 core 模块）

window 模块实现的是 `io.github.cuihairu.redis.streaming.api.stream.WindowAssigner`：

```java
public interface WindowAssigner<T> extends Serializable {
    Iterable<Window> assignWindows(T element, long timestamp);

    // 每个 (key, window) 桶调用一次，实现必须返回新实例（有状态触发器不得共享实例）
    Trigger<T> getDefaultTrigger();

    // 会话类可合并窗口时覆写为 true；为 true 时引擎把相交的桶合并成并集窗口
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

`CONTINUE` 继续累积；`FIRE` 发射并保留状态继续累积；`FIRE_AND_PURGE` 发射并清空；`PURGE` 不发射直接丢弃。

窗口算子入口在 `KeyedStream`：

```java
WindowedStream<K, T> window(WindowAssigner<T> windowAssigner);
```

`WindowedStream<K, T>` 提供的窗口算子（没有 `trigger(...)`、`allowedLateness(...)`、`sideOutputLateData(...)`、`process(...)` 这些方法）：

```java
DataStream<T> reduce(ReduceFunction<T> reducer);                 // 窗口内归约
<R> DataStream<R> aggregate(AggregateFunction<T, R> fn);         // 窗口内聚合
<R> DataStream<R> apply(WindowFunction<K, T, R> windowFunction); // 拿到窗口内全部元素
DataStream<T> sum(Function<T, ? extends Number> fieldSelector);  // 按数值字段求和
DataStream<Long> count();                                        // 窗口计数
```

`AggregateFunction<IN, OUT>`（`io.github.cuihairu.redis.streaming.api.stream`）需要实现 `createAccumulator()` / `add(IN, Accumulator<IN>)` / `getResult(Accumulator<IN>)` / `merge(a, b)`。`WindowFunction<K, IN, OUT>` 是函数式接口，方法签名为 `apply(K key, WindowAssigner.Window window, Iterable<IN> elements, Collector<OUT> out)`。

## 执行引擎中的行为

- **InMemory 引擎**（`runtime` 的 `InMemoryWindowedStream`）：批式处理，每个元素先按分配器落桶并调用 `trigger.onElement`，流结束时对每个非空桶做最后一次 `trigger.onEventTime(window.getEnd(), window)` 冲刷。`supportsWindowMerging()` 为 `true` 的分配器（会话窗口）会把相交桶合并成并集窗口再累积。`onProcessingTime` 不会被调用。
- **Redis 引擎**（`runtime` 的 `RedisStreamBuilder`）：元素累加后咨询 `trigger.onElement`，水位线推进时对到期窗口调用 `trigger.onEventTime`；`FIRE` 表示部分发射（保留状态继续累积），`FIRE_AND_PURGE` / `PURGE` 结束该窗口。默认 `EventTimeTrigger`（`CONTINUE` / `FIRE_AND_PURGE`）与纯水位线关闭行为等价。`onProcessingTime` 同样没有接入（无 processing-time 窗口定时器，见 docs/watermark.md、todo.md B3）。

## 配置项

window 模块本身没有配置文件键，全部参数走构造器/工厂方法（见上文各构造约束）。

窗口相关的运行时配置在 Redis 引擎的 `RedisRuntimeConfig.Builder`（runtime 模块）：

| Builder 方法 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `windowAllowedLateness(Duration)` | `Duration` | `Duration.ZERO` | 允许迟到：最终发射推迟到 `windowEnd + windowAllowedLateness`；负值抛 `IllegalArgumentException` |
| `windowMaxFiresPerRecord(int)` | `int` | `256` | 单条记录最多触发的窗口发射数（滑动窗口下限制单条记录的工作量）；`< 1` 抛异常 |
| `watermarkOutOfOrderness(Duration)` | `Duration` | `Duration.ZERO` | 无自定义 WatermarkGenerator 时的乱序容忍启发式 |

## 用法示例

### 事件时间滚动窗口（InMemory 引擎，来自 runtime 测试）

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

### reduce / sum / aggregate / apply（Redis 引擎，来自 runtime 集成测试）

```java
// 归约
keyed -> keyed.window(TumblingWindow.<Integer>ofMillis(1000)).reduce(Integer::sum)

// 按数值字段求和
keyed -> keyed.window(TumblingWindow.<Integer>ofMillis(1000)).sum(v -> v)

// 计数
keyed -> keyed.window(TumblingWindow.<Integer>ofMillis(1000)).count()

// 自定义聚合（原型见 runtime 测试 RedisRuntimeWindowedStreamIntegrationTest 的 MaxAccumulator）
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

// 窗口函数：拿到 key、窗口与全部元素
keyed.window(TumblingWindow.<Integer>ofMillis(1000))
     .apply((WindowFunction<String, Integer, String>) (key, window, elements, collector) -> {
         List<Integer> seen = new ArrayList<>();
         elements.forEach(seen::add);
         collector.collect(key + ":" + seen);
     });
```

### 滑动窗口（一个元素进多个窗口）

```java
env.fromMqTopic(topic, "g")
        .map(m -> Integer.parseInt((String) m.getPayload()))
        .keyBy(v -> "k")
        .window(SlidingWindow.<Integer>of(Duration.ofMillis(1500), Duration.ofMillis(1500)))
        .sum(v -> v)
        .addSink(out::add);
```

### 不经过引擎，直接使用分配器

```java
SessionWindow<String> session = SessionWindow.withGap(Duration.ofSeconds(30));
for (WindowAssigner.Window w : session.assignWindows("click", ts)) {
    // w.getStart() / w.getEnd()
}
```

### 允许迟到数据

没有 `WindowedStream.allowedLateness(...)` 或侧输出 API；迟到容忍是 Redis 引擎的运行时配置：

```java
RedisRuntimeConfig cfg = RedisRuntimeConfig.builder()
        .jobName("job")
        .stateKeyPrefix("streaming:job:")
        .watermarkOutOfOrderness(Duration.ofSeconds(5))
        .windowAllowedLateness(Duration.ofSeconds(30)) // 窗口在 windowEnd+30s 才最终发射
        .build();
```

## 相关文档

- [watermark](watermark.md) - 水位线机制（窗口关闭的时间依据）
- [Aggregation](Aggregation.md) - Redis 聚合工具（独立于 runtime 的 WindowedStream）
