# Aggregation

Module: `aggregation/`

Window aggregations (PV/UV, TopK).

## Scope
- Redis-backed window aggregation utilities（计数/最值/TopK/PV/UV 等）
- 说明：该模块是独立聚合引擎（非 `runtime` 的 `WindowedStream` 执行器）

## Key Classes
- `WindowAggregator` / `TimeWindow` / `TumblingWindow` / `SlidingWindow`
- `aggregation.functions.*`（`CountFunction`, `SumFunction`, `MinFunction`, ...）
- `aggregation.analytics.*`（`PVCounter`, `TopKAnalyzer`）

## Minimal Sample (WindowAggregator)
```java
import io.github.cuihairu.redis.streaming.aggregation.TumblingWindow;
import io.github.cuihairu.redis.streaming.aggregation.WindowAggregator;
import io.github.cuihairu.redis.streaming.aggregation.functions.CountFunction;

// RedissonClient redisson = ... (see State.md)
WindowAggregator agg = new WindowAggregator(redisson, "demo:agg");
agg.registerFunction("COUNT", CountFunction.getInstance());

var win = TumblingWindow.ofSeconds(10);
var now = java.time.Instant.now();
agg.addValue(win, "events", "id-1", now);
Long cnt = agg.getAggregatedResult(win, "events", "COUNT", now);
```

## References
- Design.md
