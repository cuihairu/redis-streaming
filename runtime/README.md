# Streaming Runtime Module

## Overview

This module provides a simple, in-memory runtime implementation of the Streaming API defined in the `core` module.

## Current Status

**Status**: Minimal in-memory runtime available (single-threaded)

The runtime module provides a small, pull-based in-memory implementation intended for tests/examples:

- `StreamExecutionEnvironment`: `fromElements`, `fromCollection`, `addSource`
- `DataStream`: `map`, `filter`, `flatMap`, `keyBy`, `addSink`, `print`
- `DataStream`: watermarks via `assignTimestampsAndWatermarks(...)` (record timestamps or event-time timestamps via `TimestampAssigner`)
- `KeyedStream`: `process` (timers + watermark-aware event-time), `reduce`, `sum`, `getState` (keyed `ValueState`)
- `WindowedStream`: `reduce`, `aggregate`, `apply`, `sum`, `count`
- `Checkpointing`: `enableCheckpointing()` provides an in-memory `CheckpointCoordinator` that snapshots/restores keyed state during a run

Notes about semantics (in-memory runtime):
- Single-threaded, pull-based execution.
- Window results are produced after the upstream iterator is fully consumed (batch-style evaluation).
- Window triggers from `WindowAssigner.Trigger` are not used by the in-memory runtime (windows are evaluated once at the end).
- Timestamps:
  - `fromElements/fromCollection`: assigns deterministic synthetic timestamps `0..N-1`
  - `addSource`: preserves `collectWithTimestamp(...)` timestamps; `collect(...)` uses an increasing fallback timestamp
  - `assignTimestampsAndWatermarks(timestampAssigner, ...)`: rewrites record timestamps to event-time timestamps (used by window assignment)

## Why a Separate Runtime Module?

The `core` module defines the Stream Processing API interfaces, while `runtime` provides an actual execution engine. This separation:

1. **Avoids Circular Dependencies**: The `core` module is depended on by `state`, `watermark`, and `window` modules. If runtime code were in `core`, it would create circular dependencies.

2. **Modular Design**: Users can use just the API definitions from `core` without pulling in the full runtime implementation.

3. **Alternative Runtimes**: In the future, different runtime implementations could be provided (e.g., distributed runtime, optimized runtime).

## Alternative: Use Existing Modules Directly

While the unified streaming runtime is under development, you can use the individual modules directly:

- **MQ Module**: For message queue operations
- **State Module**: For state management
- **Window + Aggregation**: For windowed computations
- **CEP Module**: For complex event processing

See the `examples` module for usage patterns.

## Planned Features

- [x] Core operator abstractions
- [x] Pull-based execution model (in-memory)
- [x] Basic keyed state (in-memory `ValueState`)
- [x] Window assignment (batch-style evaluation)
- [x] Watermark handling (event-time + idle/active)
- [x] Checkpointing integration (in-memory)
- [ ] Parallel execution

## Redis Runtime (Experimental)

This module also contains an experimental Redis-backed runtime that composes existing modules
(`mq/state/...`) into a runnable job driven by Redis Streams consumer groups:

- Entry point: `io.github.cuihairu.redis.streaming.runtime.redis.RedisStreamExecutionEnvironment`
- Source: `fromMqTopic(topic, consumerGroup)` (produces raw `mq.Message`)
- Source (with per-subscription overrides): `fromMqTopic(topic, consumerGroup, SubscriptionOptions)`
- Source (with stable id): `fromMqTopicWithId(sourceId, topic, consumerGroup, SubscriptionOptions)`
- Supported operators (current): `map`, `filter`, `flatMap`, `keyBy`, `process`, `reduce`, `sum`, `addSink`, `print`
- Keyed state: Redis-backed `ValueState` via `KeyedStream.getState(...)`
- Delivery semantics: at-least-once (ack on successful end-to-end processing)
- Consumer name: `jobName-jobInstanceId-{n}`; set `jobInstanceId` in `RedisRuntimeConfig` to keep it stable across restarts.
- Error diagnostics: on handler exceptions, runtime annotates message headers (`x-runtime-*`) so retries/DLQ carry root-cause context.
- State TTL: configure `RedisRuntimeConfig.stateTtl(...)` to apply expiry to Redis state keys (best-effort, per state hash).
- State size sampling: configure `RedisRuntimeConfig.stateSizeReportEveryNStateWrites(n)` to periodically record Redis state hash sizes (fields) via runtime metrics.
- State sharding: configure `RedisRuntimeConfig.keyedStateShardCount(n)` to shard keyed state hashes by key hash (default 1, disabled).
- Hot-key warning: configure `RedisRuntimeConfig.keyedStateHotKeyFieldsWarnThreshold(n)` (fields per hash) to emit rate-limited warnings and metrics when keyed state grows too large.
- State schema/versioning: set `schemaVersion` on `StateDescriptor` (e.g. `new StateDescriptor<>("cnt", Integer.class, 0, 2)`), and configure `RedisRuntimeConfig.stateSchemaMismatchPolicy(FAIL/CLEAR/IGNORE)` to handle incompatible state upgrades.
- Sink deduplication (best-effort): configure `RedisRuntimeConfig.sinkDeduplicationEnabled(true)` to skip re-invoking sinks for the same logical message on retries/replays (uses `x-original-message-id` when present).
- Consumer group restore: configure `RedisRuntimeConfig.restoreConsumerGroupFromCommitFrontier(true)` so a missing group is recreated from MQ commit frontier (acked ids), avoiding full replay after accidental group deletion.
- Checkpointing (experimental): configure `RedisRuntimeConfig.checkpointInterval(...)` to periodically snapshot offsets + keyed state into Redis (stop-the-world via consumer pause/drain), and `RedisRuntimeConfig.restoreFromLatestCheckpoint(true)` to restore on startup.
- Manual checkpointing (experimental): call `RedisJobClient.triggerCheckpointNow()` to force a stop-the-world checkpoint.
- Job control (experimental): call `RedisJobClient.pause()/resume()` to pause/resume consumption, and `RedisJobClient.inFlight()` for a best-effort in-flight count.
- End-to-end checkpoint (experimental): enable `RedisRuntimeConfig.deferAckUntilCheckpoint(true)` to defer MQ ACK until checkpoint completion, and optionally implement `CheckpointAwareSink` to commit buffered side effects on `onCheckpointComplete(...)`.
- Redis-only atomic sink (experimental): use `RedisAtomicCheckpointListSink` + `RedisExactlyOnceRecord` with `deferAckUntilCheckpoint(true)` and `ackDeferredMessagesOnCheckpoint(false)` to atomically (Lua) commit sink + XACK + advance commit frontier.
- Parallelism (experimental): set `RedisRuntimeConfig.pipelineParallelism(n)` to start N consumer subtasks per pipeline; partitions are deterministically pinned by `partitionId % n`.
- Backpressure (MQ): set `MqOptions.maxInFlight(n)` to cap concurrent in-flight message handling per consumer instance.
- Partition leasing cap (MQ): set `MqOptions.maxLeasedPartitionsPerConsumer(n)` to avoid acquiring more partition leases than a consumer can actively run (defaults to `workerThreads`).

Limitations (current):
- Windowed execution is not supported by Redis runtime yet.
- Exactly-once is not implemented yet; see `docs/exactly-once.md` for the roadmap and recommended sink patterns.
- State schema evolution is "best-effort": runtime can enforce/clear/ignore mismatches but does not perform data migrations.

## Quick Example

```java
var env = StreamExecutionEnvironment.getExecutionEnvironment();
env.fromElements("a b", "c")
    .flatMap(line -> Arrays.asList(line.split(" ")))
    .keyBy(w -> w)
    .reduce((x, y) -> x)
    .print("word=");
```

## Implementation Notes

The runtime implementation faces several challenges:

1. **Watermark API Complexity**: The WatermarkGenerator interface requires WatermarkOutput callbacks, which need careful integration with the execution model.

2. **Generic Type Inference**: Java's type system and Lombok's code generation can conflict in complex generic hierarchies.

3. **Window Semantics**: Proper window triggering requires watermark coordination across parallel streams.

Given these complexities, the initial focus is on getting individual modules working correctly. A complete streaming runtime similar to Apache Flink's is a significant undertaking requiring thousands of lines of carefully designed code.

## Recommendation

For production use cases:
- Use individual modules (mq, state, aggregation, cep) directly
- For complex stream processing, consider Apache Flink or similar mature frameworks
- This framework excels at lightweight, Redis-backed streaming operations

## Future Work

The runtime engine will be implemented incrementally:

**Phase 1** (Current): API definitions in `core`
**Phase 2** (Planned): Simple in-memory runtime for testing
**Phase 3** (Future): Production-grade runtime with full feature support
