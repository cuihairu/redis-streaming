# API Overview

The public programming model lives in the `api` package of the `core` module; every other module (`state` / `checkpoint` / `watermark` / `window` / `runtime`, etc.) implements or consumes these contracts.

- `core/src/main/java/io/github/cuihairu/redis/streaming/api/*` —— all public interfaces
- Implementation locations: `state/src/main/java` (Redis state implementations), `checkpoint/src/main/java` (checkpoint storage and coordination), `watermark/src/main/java` (watermark generators and strategies), `window/src/main/java` (window assigners / triggers), `runtime/src/main/java` (both the in-memory and the Redis execution engines)

## Package List

| Package | Contents | Details |
|---|---|---|
| `api.stream` | `DataStream`, `KeyedStream`, `WindowedStream`, `StreamSource`, `StreamSink`, `CheckpointAwareSink`, `TwoPhaseCommitSink`, `IdempotentRecord`, `KeyedProcessFunction`, `WindowAssigner`, `WindowFunction`, `AggregateFunction`, `ReduceFunction` | [Core.md](../Core.md) |
| `api.state` | `State`, `ValueState`, `ListState`, `MapState`, `SetState`, `StateDescriptor` | [Core.md](../Core.md) · [state.md](../state.md) |
| `api.checkpoint` | `Checkpoint` (including `StateSnapshot`), `CheckpointCoordinator` | [Core.md](../Core.md) · [checkpoint.md](../checkpoint.md) |
| `api.watermark` | `Watermark`, `WatermarkGenerator`, `TimestampAssigner` | [Core.md](../Core.md) · [watermark.md](../watermark.md) |
| `api` (root) | `StreamingApiExample` (documentation-only class, no logic) | — |

For the full list of interface defaults (such as `StateDescriptor.schemaVersion = 1`, the `DataStream.assignTimestampsAndWatermarks` default that throws `UnsupportedOperationException`, and `WindowAssigner.supportsWindowMerging() = false`), see [section 3 of Core.md](../Core.md).

## Minimal Example

```java
import java.util.Arrays;
import io.github.cuihairu.redis.streaming.runtime.StreamExecutionEnvironment;

StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
env.fromElements("a b", "c", "d e")
        .flatMap(line -> Arrays.asList(line.split(" ")))
        .filter(w -> !w.isEmpty())
        .print("api> ");
```

The in-memory engine has no `execute()`: a terminal operation (`addSink` / `print`) triggers a full run. The Redis engine is built via `RedisStreamExecutionEnvironment.create(redissonClient, config)` and returns a `RedisJobClient` from `executeAsync()` (see [runtime.md](../runtime.md)).

Module-level getting-started samples: the `state`, `checkpoint`, `mq`, `registry`, `window`, `streaming` and other packages under `examples/src/main/java/io/github/cuihairu/redis/streaming/examples/`.

## Generating Javadoc Locally

```bash
./gradlew javadoc
```

(The `java` plugin creates a `javadoc` task per module; at publish time it is paired with `withSourcesJar()` and the maven-publish plugin — see the root `build.gradle`.)

---

**Version**: 0.2.4
**Last Updated**: 2026-10-16
