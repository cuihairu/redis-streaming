# Core Module

Module: `core/`

Core abstractions and API for streams, state, windowing, and checkpoints.

## 1. Scope
- 基础 API：DataStream/KeyedStream/WindowedStream、状态接口、窗口与分配器、检查点协调等

## 2. Key Packages & Classes
- `api.stream`：`DataStream`, `KeyedStream`, `WindowedStream`, `StreamSource`, `StreamSink`, `WindowAssigner`, `WindowFunction`, `AggregateFunction`, `ReduceFunction`, `KeyedProcessFunction`
- `api.state`：`State`, `ValueState`, `ListState`, `MapState`, `SetState`, `StateDescriptor`
- `api.checkpoint`：`Checkpoint`, `CheckpointCoordinator`
- `api.watermark`：`Watermark`, `WatermarkGenerator`
- `core.utils`：`SystemUtils`, `InstanceIdGenerator`

## 3. Minimal Sample (placeholder)
```java
DataStream<String> s = ...; // from Source
KeyedStream<String, String> ks = s.keyBy(x -> x);
WindowedStream<String, ?> ws = ks.window(WindowAssigner.tumbling(Duration.ofSeconds(5)));
ws.aggregate(new AggregateFunction<>() { /* ... */ });
```

## References
- Design.md
- Architecture.md
