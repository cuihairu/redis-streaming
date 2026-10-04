# API Overview

对外编程模型集中在 `core` 模块的 `api` 包,其余模块(`state`/`checkpoint`/`watermark`/`window`/`runtime` 等)实现或消费这些契约。

- `core/src/main/java/io/github/cuihairu/redis/streaming/api/*` —— 全部对外接口
- 实现位置:`state/src/main/java`(状态 Redis 实现)、`checkpoint/src/main/java`(检查点存储与协调)、`watermark/src/main/java`(水位线生成器与策略)、`window/src/main/java`(窗口分配器/触发器)、`runtime/src/main/java`(内存与 Redis 两套执行引擎)

## 包清单

| 包 | 内容 | 详情 |
|---|---|---|
| `api.stream` | `DataStream`、`KeyedStream`、`WindowedStream`、`StreamSource`、`StreamSink`、`CheckpointAwareSink`、`TwoPhaseCommitSink`、`IdempotentRecord`、`KeyedProcessFunction`、`WindowAssigner`、`WindowFunction`、`AggregateFunction`、`ReduceFunction` | [Core.md](Core.md) |
| `api.state` | `State`、`ValueState`、`ListState`、`MapState`、`SetState`、`StateDescriptor` | [Core.md](Core.md) · [state.md](state.md) |
| `api.checkpoint` | `Checkpoint`(含 `StateSnapshot`)、`CheckpointCoordinator` | [Core.md](Core.md) · [checkpoint.md](checkpoint.md) |
| `api.watermark` | `Watermark`、`WatermarkGenerator`、`TimestampAssigner` | [Core.md](Core.md) · [watermark.md](watermark.md) |
| `api`(根) | `StreamingApiExample`(纯文档类,无逻辑) | — |

接口默认值(如 `StateDescriptor.schemaVersion = 1`、`DataStream.assignTimestampsAndWatermarks` default 抛 `UnsupportedOperationException`、`WindowAssigner.supportsWindowMerging() = false`)的完整清单见 [Core.md 第 3 节](Core.md)。

## 最小示例

```java
import java.util.Arrays;
import io.github.cuihairu.redis.streaming.runtime.StreamExecutionEnvironment;

StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
env.fromElements("a b", "c", "d e")
        .flatMap(line -> Arrays.asList(line.split(" ")))
        .filter(w -> !w.isEmpty())
        .print("api> ");
```

内存引擎无 `execute()`:终止操作(`addSink`/`print`)触发全量执行;Redis 引擎通过 `RedisStreamExecutionEnvironment.create(redissonClient, config)` 构建并以 `executeAsync()` 返回 `RedisJobClient`(见 [runtime.md](runtime.md))。

模块级上手示例:`examples/src/main/java/io/github/cuihairu/redis/streaming/examples/` 下的 `state`、`checkpoint`、`mq`、`registry`、`window`、`streaming` 等包。

## 本地生成 Javadoc

```bash
./gradlew javadoc
```

(`java` 插件为每个模块生成 `javadoc` 任务;发布时由 `withSourcesJar()` 与 maven-publish 插件配套,见根 `build.gradle`。)
