# Watermark 模块

模块目录:`watermark/`。提供 core `WatermarkGenerator` / `TimestampAssigner` 接口的开箱实现与组合策略。

## 核心概念

水位线(watermark)= "事件时间不会再早于 T" 的断言,驱动事件时间窗口触发与迟到处理。

## 内置生成器(generators 包)

| 类 | 语义 |
|---|---|
| `AscendingTimestampWatermarkGenerator` | 假设数据按事件时间升序,wm = 最大观察时间戳 |
| `BoundedOutOfOrdernessWatermarkGenerator` | 允许乱序 `maxOutOfOrderness`,wm = maxTs − δ;构造参数为 `Duration` |

```java
import io.github.cuihairu.redis.streaming.watermark.generators.BoundedOutOfOrdernessWatermarkGenerator;

BoundedOutOfOrdernessWatermarkGenerator<Event> gen =
        new BoundedOutOfOrdernessWatermarkGenerator<>(Duration.ofSeconds(5));
```

## WatermarkStrategy(组合模式)

`WatermarkStrategy<T>` 把"时间戳提取器 + 生成器工厂"打包,便于在引擎间传递:

```java
import io.github.cuihairu.redis.streaming.watermark.WatermarkStrategy;

WatermarkStrategy<Event> strategy = WatermarkStrategy
        .<Event>forBoundedOutOfOrderness(Duration.ofSeconds(5))
        // 或 forMonotonousTimestamps() / forGenerator(...) / noWatermarks()
        ;

long ts = strategy.extractTimestamp(event, recordTs);
WatermarkGenerator<Event> generator = strategy.createWatermarkGenerator();
```

## 在两种引擎中的使用

```java
// 方式一:直接传 core 接口(内存与 Redis 引擎均支持)
stream.assignTimestampsAndWatermarks(new BoundedOutOfOrdernessWatermarkGenerator<>(Duration.ofSeconds(5)));

// 方式二:同时重指派事件时间(仅内存引擎支持;
// Redis 引擎需要 runner 级事件时间传播改造,见 todo.md B2/B3)
stream.assignTimestampsAndWatermarks(
        (TimestampAssigner<Event>) (e, recordTs) -> e.getEventTime(),
        WatermarkStrategy.<Event>forBoundedOutOfOrderness(Duration.ofSeconds(5)).createWatermarkGenerator());
```

### Redis 引擎的水位线模型

- 默认(未调用 `assignTimestampsAndWatermarks`):引擎内部启发式 `wm = max(投递时间戳) − RedisRuntimeConfig.watermarkOutOfOrderness`(默认 0)。
- 调用后:`WatermarkGenerator.onEvent/onPeriodicEmit` 生成的水位线通过 `Context.raiseWatermark` **单调提升**全局水位线(不会下调启发式已达到的值)。集成测试证明:当配置 `watermarkOutOfOrderness=10s` 时,用户生成器可提前触发窗口,而启发式不能。
- 窗口触发时机:水位线推进发生在消息处理链入口,窗口算子在逐条消息处理中检查到期(无独立定时器驱动窗口触发)。

## 已知限制

- `WindowAssigner.getDefaultTrigger()` 与 window 模块的 `EventTimeTrigger` 等尚未被任一引擎调用(死接口,待统一,见 todo.md B3)。
- `SourceContext.getCheckpointLock` 目前返回无锁对象,未参与检查点对齐。

## References
- [Core.md](Core.md) · [window.md](window.md) · [runtime.md](runtime.md)
