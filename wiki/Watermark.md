# Watermark

Module: `watermark/`

Event-time watermark mechanism.

## 1. Scope
- 乱序事件处理、Watermark 策略、与窗口触发（占位）

## 2. Key Classes (placeholder)
- `WatermarkStrategy`, `generators.BoundedOutOfOrdernessWatermarkGenerator`, `AscendingTimestampWatermarkGenerator`

## 3. Minimal Sample (placeholder)
```java
WatermarkStrategy s = WatermarkStrategy.boundedOutOfOrderness(Duration.ofSeconds(5));
```

## References
- Design.md
