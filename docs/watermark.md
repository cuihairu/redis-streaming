# Watermark 模块

模块目录:`watermark/`。提供 core `WatermarkGenerator` / `TimestampAssigner` 接口的内置实现与组合策略。模块只依赖 `core`,不含任何运行时引擎代码(runtime 对本模块的依赖仅存在于测试配置)。

## 核心概念

水位线(watermark)= "事件时间不会再早于 T" 的断言,驱动事件时间窗口触发与迟到处理。core 的 `Watermark` 是一个带 `long timestamp` 的可比较值,`Watermark.maxWatermark()` 等于 `Long.MAX_VALUE`。

## 内置生成器(`watermark.generators` 包)

| 类 | 语义 | 辅助 getter |
|---|---|---|
| `AscendingTimestampWatermarkGenerator<T>` | 假设时间戳升序:`onEvent` 记录 `max(当前值, 事件时间戳 − 1)`,`onPeriodicEmit` 发出该值;初始 `Long.MIN_VALUE` | `getCurrentWatermark()` |
| `BoundedOutOfOrdernessWatermarkGenerator<T>` | 允许乱序 `δ`:`onEvent` 记录最大观察时间戳 `maxTs`,`onPeriodicEmit` 发出 `maxTs − δ − 1`;构造参数为 `Duration`;初始 `maxTimestamp = Long.MIN_VALUE + δ + 1`(首次 emit 为 `Long.MIN_VALUE`) | `getMaxTimestamp()`、`getMaxOutOfOrdernessMillis()` |

两者都是 `Serializable`(有状态,每次使用需新实例)。

```java
import java.time.Duration;
import io.github.cuihairu.redis.streaming.watermark.generators.BoundedOutOfOrdernessWatermarkGenerator;

// Event 为用户的事件类型
BoundedOutOfOrdernessWatermarkGenerator<Event> gen =
        new BoundedOutOfOrdernessWatermarkGenerator<>(Duration.ofSeconds(5));
// 事件 1000/2000/3000 + δ=5s → emit Watermark(3000 - 5000 - 1 = -2001)
// (watermark 模块单测 BoundedOutOfOrdernessWatermarkGeneratorTest 断言同一数值)
```

## WatermarkStrategy(组合模式)

`WatermarkStrategy<T>`(`io.github.cuihairu.redis.streaming.watermark.WatermarkStrategy`)把"生成器工厂 + 时间戳提取器"打包,便于在引擎间传递:

| 静态工厂 | 行为 |
|---|---|
| `forMonotonousTimestamps()` | 生成 `AscendingTimestampWatermarkGenerator` |
| `forBoundedOutOfOrderness(Duration)` | 生成 `BoundedOutOfOrdernessWatermarkGenerator` |
| `forGenerator(Supplier<WatermarkGenerator<T>>)` | 自定义生成器工厂 |
| `noWatermarks()` | 私有 `NoWatermarkGenerator`,`onEvent`/`onPeriodicEmit` 均不发水位线 |

实例方法:

- `WatermarkGenerator<T> createWatermarkGenerator()` —— 每次调用 `supplier.get()` 产出新实例
- `TimestampAssigner<T> getTimestampAssigner()`
- `long extractTimestamp(T event, long recordTimestamp)` —— 有 assigner 用 assigner,否则原样返回 `recordTimestamp`
- `WatermarkStrategy<T> withTimestampAssigner(TimestampAssigner<T>)` —— 返回带提取器的新策略(策略本身不可变)

内嵌类型:`TimestampAssigner<T>`(继承 core 同名接口,便于 lambda 书写)与 `SerializableBiFunction<T,U,R>`(可序列化函数标记)。

```java
import java.time.Duration;
import io.github.cuihairu.redis.streaming.watermark.WatermarkStrategy;

// Event 为用户的事件类型,需提供 getEventTime()
WatermarkStrategy<Event> strategy = WatermarkStrategy
        .<Event>forBoundedOutOfOrderness(Duration.ofSeconds(5))
        .withTimestampAssigner((e, recordTs) -> e.getEventTime());

long ts = strategy.extractTimestamp(event, recordTs);      // e.getEventTime()
WatermarkGenerator<Event> generator = strategy.createWatermarkGenerator();
```

## 在两种引擎中的使用

```java
// 方式一:直接传 core 接口(内存与 Redis 引擎均实现此重载)
stream.assignTimestampsAndWatermarks(new BoundedOutOfOrdernessWatermarkGenerator<>(Duration.ofSeconds(5)));

// 方式二:同时重指派事件时间(仅内存引擎实现;
// Redis 引擎未覆盖该重载,会走 core 的 default 实现抛
// UnsupportedOperationException("This runtime does not support event-time timestamp assignment"),
// 需要 runner 级事件时间传播改造,见 todo.md B2)
stream.assignTimestampsAndWatermarks(
        (TimestampAssigner<Event>) (e, recordTs) -> e.getEventTime(),
        WatermarkStrategy.<Event>forBoundedOutOfOrderness(Duration.ofSeconds(5)).createWatermarkGenerator());
```

两个引擎实现里的水位线都是单调提升:内存引擎 `WatermarkState.emit` 只接受大于当前值的水位线;Redis 引擎 `Context.raiseWatermark` 用 `Math.max` 更新。

### Redis 引擎的水位线模型

- 默认(未调用 `assignTimestampsAndWatermarks`):`RedisPipelineRunner` 在每条消息处理链入口计算 `事件时间 = message.getTimestamp()`(无时间戳时回退为投递时刻),维护全局最大值,水位线 = `max(事件时间) − RedisRuntimeConfig.watermarkOutOfOrderness`(Builder 默认 `Duration.ZERO`,负值在配置校验时抛 `IllegalArgumentException`)。
- 调用后:用户生成器的 `onEvent`/`onPeriodicEmit` 在每条消息上被调用(每条都调,非周期定时),发出的水位线经 `WatermarkOutput.emitWatermark` → `Context.raiseWatermark` **单调提升**全局水位线(不会下调启发式已达到的值)。集成测试 `RedisRuntimeWindowedStreamIntegrationTest` 证明:配置 `watermarkOutOfOrderness=10s` 时,启发式推不动的窗口可被用户生成器提前触发。
- 窗口触发时机:水位线推进发生在消息处理链入口,窗口算子在逐条消息处理中检查到期(无独立定时器驱动窗口触发)。

## 已知限制

- `WindowAssigner.getDefaultTrigger()` 已接入两套引擎的窗口算子(原死接口,接入记录见 todo.md B3):按 (partition, key, window) 桶各持一个触发器实例。元素到达时调用 `onElement`(`FIRE` 提前发射并保留状态、`FIRE_AND_PURGE` 提前发射并清空桶、`PURGE` 丢弃桶、`CONTINUE` 继续累积);到期检查时调用 `onEventTime`(Redis 引擎:`CONTINUE` 推迟本次关闭、`PURGE` 不发射直接丢弃、`FIRE`/`FIRE_AND_PURGE` 发射并关闭;内存引擎:输入耗尽时以 `+inf` 水位线对每个剩余桶调一次 `onEventTime(window.getEnd(), window)` 后冲刷)。内置 `TumblingWindow`/`SlidingWindow`/`SessionWindow` 的 `getDefaultTrigger()` 均返回新的 `EventTimeTrigger`(`onElement`→`CONTINUE`,`onEventTime` 到点→`FIRE_AND_PURGE`),接入前的纯关闭行为与它逐点等价(等价用例钉住,见 todo.md B3)。`onProcessingTime` 仍未被任何引擎调用(两引擎均无 processing-time 窗口定时器,窗口只随水位线触发)。
- `StreamSource.SourceContext.getCheckpointLock()`:内存引擎返回 `addSource` 内新建的 `Object`,运行时没有任何代码对它 `synchronized`,未参与检查点对齐;Redis 引擎没有 `StreamSource` 路径(源入口是 `fromMqTopic`)。

## References

- [Core.md](Core.md) · [window.md](window.md) · [runtime.md](runtime.md)
