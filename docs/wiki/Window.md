# Window

Module: `window/`

Windowed stream operations.

## Scope
- 翻滚/滑动/会话窗口 assigner
- Trigger（处理时间/事件时间/计数触发）

## Key Concepts
- `WindowAssigner`, `WindowFunction`, `AggregateFunction`, `ReduceFunction`

## Minimal Sample (assign windows)
```java
import io.github.cuihairu.redis.streaming.window.assigners.TumblingWindow;

var assigner = TumblingWindow.<String>of(java.time.Duration.ofSeconds(10));
var windows = assigner.assignWindows("evt", System.currentTimeMillis());
```

## References
- Design.md
