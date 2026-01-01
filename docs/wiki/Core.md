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

## 3. Minimal Sample (with runtime)
```java
import io.github.cuihairu.redis.streaming.runtime.StreamExecutionEnvironment;

StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
env.fromElements("a", "bb", "ccc")
        .map(String::length)
        .print("core> ");
```

## References
- Design.md
- Architecture.md
