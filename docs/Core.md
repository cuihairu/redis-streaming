# Core Module

模块目录:`core/`

流处理框架的纯抽象层:不依赖 Redis/Redisson,定义用户编程 API 与运行时之间的契约。所有功能模块与两种运行时引擎(内存、Redis)都实现或消费这些接口。

## 1. Scope
- 基础 API:DataStream / KeyedStream / WindowedStream
- 源与汇:`StreamSource`、`StreamSink`(均含可选 `open()/close()` 生命周期)
- 状态接口:`ValueState`、`ListState`、`MapState`、`SetState`(独立实现见 state 模块)
- 窗口与时间:`WindowAssigner`(含内嵌 `Window`/`Trigger`)、`WindowFunction`、`TimestampAssigner`、`WatermarkGenerator`、`Watermark`
- 检查点契约:`Checkpoint`、`CheckpointCoordinator`、`CheckpointAwareSink`、`IdempotentRecord`
- 工具:`SystemUtils`、`InstanceIdGenerator`

## 2. Key Packages & Classes
- `api.stream`:`DataStream`, `KeyedStream`, `WindowedStream`, `StreamSource`, `StreamSink`, `CheckpointAwareSink`, `IdempotentRecord`, `KeyedProcessFunction`, `WindowAssigner`, `WindowFunction`, `AggregateFunction`, `ReduceFunction`
- `api.state`:`State`, `ValueState`, `ListState`, `MapState`, `SetState`, `StateDescriptor`
- `api.checkpoint`:`Checkpoint`, `CheckpointCoordinator`
- `api.watermark`:`Watermark`, `WatermarkGenerator`, `TimestampAssigner`
- `core.utils`:`SystemUtils`, `InstanceIdGenerator`

## 3. 算子生命周期契约

`StreamSink` / `StreamSource` 的 `open()` / `close()` 均为 default 空实现,向后兼容:

```java
public interface StreamSink<T> extends Serializable {
    default void open() throws Exception {}          // 首次投递前调用一次
    void invoke(T value) throws Exception;           // 逐条处理(唯一抽象方法)
    default void close() throws Exception {}         // 作业结束/取消时调用,必须幂等
}
```

- 内存引擎:`addSink` 在遍历前调用 `open()`、finally 中调用 `close()`;`addSource` 对称调用 `source.open()/run()/close()`。
- Redis 引擎:runner 在首条消息到达时幂等 `open()`,任务取消/关闭时 `close()`;`close()` 抛出的异常只记录日志不上抛。

## 4. Minimal Sample (with runtime)
```java
import io.github.cuihairu.redis.streaming.runtime.StreamExecutionEnvironment;

StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
env.fromElements("a", "bb", "ccc")
        .map(String::length)
        .print("core> ");
```

> 注意:内存引擎是惰性拉取模型,没有 `execute()`;终止操作(`addSink`/`print`)触发全量执行。Redis 引擎入口见 [runtime 模块文档](runtime.md)。

## References
- [Design.md](Design.md) · [Architecture.md](Architecture.md) · [watermark.md](watermark.md) · [source-sink.md](source-sink.md)
