# Window

Module: `window/`

Windowed stream operations.

## 1. Scope
- 翻滚/滑动/会话窗口，窗口分配与触发（占位）

## 2. Key Concepts (placeholder)
- `WindowAssigner`, `WindowFunction`, `AggregateFunction`, `ReduceFunction`

## 3. Minimal Sample (placeholder)
```java
ks.window(WindowAssigner.tumbling(Duration.ofSeconds(10)))
  .aggregate(new MyAgg());
```

## References
- Design.md
