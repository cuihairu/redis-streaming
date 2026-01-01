# Watermark

Module: `watermark/`

Event-time watermark mechanism.

## Scope
- 乱序事件处理：通过 watermark 推进 event time
- 与窗口触发/迟到数据处理等能力配合使用（端到端集成仍在演进中）

## Key Classes
- `WatermarkStrategy`, `generators.BoundedOutOfOrdernessWatermarkGenerator`, `AscendingTimestampWatermarkGenerator`

## Minimal Sample
```java
import io.github.cuihairu.redis.streaming.watermark.WatermarkStrategy;

WatermarkStrategy<MyEvent> strategy =
        WatermarkStrategy.<MyEvent>forBoundedOutOfOrderness(java.time.Duration.ofSeconds(5))
                .withTimestampAssigner((evt, recordTs) -> evt.getEventTimeMillis());
```

## References
- Design.md
