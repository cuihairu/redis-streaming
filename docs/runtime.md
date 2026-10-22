# Runtime Module

Module directory: `runtime/`. Responsibility: implement the `DataStream`/`KeyedStream`/`WindowedStream` builder interfaces defined by core, turning operator chains into executable jobs. The module contains **two independent engines**:

| Engine | Entry class | Purpose |
|---|---|---|
| In-memory engine | `io.github.cuihairu.redis.streaming.runtime.StreamExecutionEnvironment` | dev/test: a pull model (Iterators), single-threaded, bounded data |
| Redis engine | `io.github.cuihairu.redis.streaming.runtime.redis.RedisStreamExecutionEnvironment` | production: a push model driven by MQ consumer-thread callbacks, with state/checkpoints in Redis |

The two engines are not yet unified (a known architectural debt; see `todo.md`'s "B. dual-execution-engine unification"); this page describes their capabilities separately, faithfully.

## 1. The In-Memory Engine (StreamExecutionEnvironment)

Purpose: dev/test. This is a lazy pull model. Terminal operations like `addSink`/`print` synchronously trigger full execution; there is no `execute()` method.

### 1.1 Public Interface

`StreamExecutionEnvironment`:
- `static StreamExecutionEnvironment getExecutionEnvironment()`
- `StreamExecutionEnvironment enableCheckpointing()`: turns on in-memory checkpointing (snapshotting keyed state)
- `CheckpointCoordinator getCheckpointCoordinator()`: returns `null` before enabling
- `<T> DataStream<T> fromCollection(Collection<T>)` / `fromElements(T...)`: record timestamps are the synthetic values `0..N-1`
- `<T> DataStream<T> addSource(StreamSource<T>)`: `collect(...)` assigns an incrementing fallback timestamp; `collectWithTimestamp(...)` keeps the given event time

Operators (the core interfaces; the in-memory implementations live in `runtime.internal`):
- `DataStream`: `map` / `filter` / `flatMap` / `keyBy` / `addSink` / `print()` / `print(String)` / `assignTimestampsAndWatermarks(gen)` / `assignTimestampsAndWatermarks(assigner, gen)` (both overloads supported); it also implements `Iterable<T>` and can be iterated directly
- `KeyedStream`: `map` / `process(KeyedProcessFunction)` / `window(WindowAssigner)` / `reduce` / `sum` / `getState(StateDescriptor)`
- `WindowedStream`: `reduce` / `aggregate` / `apply` / `sum` / `count`

### 1.2 Usage Example

```java
import io.github.cuihairu.redis.streaming.runtime.StreamExecutionEnvironment;
import io.github.cuihairu.redis.streaming.api.state.StateDescriptor;
import io.github.cuihairu.redis.streaming.api.state.ValueState;
import io.github.cuihairu.redis.streaming.api.stream.KeyedStream;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

// a basic operator chain (from runtime/src/test .../StreamExecutionEnvironmentTest)
List<String> out = new ArrayList<>();
env.fromElements("a b", "c", "d e")
        .flatMap(line -> Arrays.asList(line.split(" ")))
        .filter(word -> !"c".equals(word))
        .map(String::toUpperCase)
        .addSink(out::add);                       // addSink triggers execution

// stateful per-key process (keyed state)
StateDescriptor<Integer> descriptor = new StateDescriptor<>("count", Integer.class, 0);
KeyedStream<String, String> keyed = env.fromElements("a", "b", "a").keyBy(v -> v);
ValueState<Integer> count = keyed.getState(descriptor);
keyed.<String>process((key, value, ctx, collector) -> {
            int next = count.value() + 1;
            count.update(next);
            collector.collect(key + ":" + next);
        })
        .addSink(out::add);
```

### 1.3 Capabilities and Semantics

- `keyBy(...).sum/reduce/process`: per-key stateful operators whose state lives in in-memory Maps; after `enableCheckpointing()`, that keyed state is snapshotted by `InMemoryCheckpointCoordinator` (`triggerCheckpoint()` / `restoreFromCheckpoint(id)`), and stores registered later are also caught up to the latest snapshot.
- Windows: each (key, window) bucket holds an independent trigger from `WindowAssigner.getDefaultTrigger()`; `onElement` fires when an element arrives — `FIRE` emits the current partial result and keeps accumulating, `FIRE_AND_PURGE` emits and clears, `PURGE` clears silently, `CONTINUE` continues; when input is exhausted the effective watermark is +∞, remaining buckets receive `onEventTime(windowEnd)` before flushing, and no data is silently dropped; `onProcessingTime` is never called; session windows (`supportsWindowMerging()`) merge intersecting buckets of the same key.
- `process` timers: `registerProcessingTimeTimer` / `registerEventTimeTimer` (deduplicated per key+timestamp+type); processing time advances with "the current record's timestamp" (batch simulation), and after input is exhausted `drainAllTimers` fires everything.
- Lifecycle: `addSource` runs `open → run → finally close`; `addSink` runs `open → invoke×N → finally close` (`print`/`print(prefix)` also go through `addSink`, and output goes through slf4j's `log.info`, not `System.out`).
- Limits: single-threaded, no parallelism, no cancellation semantics; `addSource` buffers all records in memory (an infinite source OOMs); `sum` on a non-`Number` throws `UnsupportedOperationException`.

## 2. The Redis Engine (RedisStreamExecutionEnvironment)

Purpose: production. A push model: the mq module's consumer threads callback into the operator chain (`RedisOperatorNode`s execute inline, with no cross-operator shuffle; `keyBy` binds the current key via `RedisKeyedStateStore`'s ThreadLocal, and state lives in Redis Hashes). The delivery baseline is at-least-once (acked only after end-to-end processing succeeds).

### 2.1 Public Interface

`RedisStreamExecutionEnvironment`:
- `static create(RedissonClient)` / `static create(RedissonClient, RedisRuntimeConfig)`
- `DataStream<Message> fromMqTopic(String topic, String consumerGroup)`: the source is a Redis Stream consumer group, yielding raw `mq.Message`s
- `DataStream<Message> fromMqTopic(String topic, String consumerGroup, SubscriptionOptions)`: overrides per subscription (batchCount/pollTimeout)
- `DataStream<Message> fromMqTopicWithId(String sourceId, String topic, String consumerGroup[, SubscriptionOptions])`: an explicit stable sourceId (feeding operator/state id derivation; must not change across restarts; only `[A-Za-z0-9_.-]` allowed)
- `void registerPipelineDefinition(RedisPipelineDefinition)`: registering after `executeAsync()` throws `IllegalStateException`; **within one environment a (topic, consumerGroup) pair may register only one pipeline** — duplicate registration throws `IllegalStateException` (two pipelines sharing one consumer group would split the messages between them — Redis Streams group semantics are competing consumption within the group, not broadcast — and the same-source forks would also share keyed state names, cross-wiring results; each independent chain should use its own group, e.g. `env.fromMqTopic(topic, group + "-fork2")`)
- `RedisJobClient executeAsync()`: starts all registered pipelines; **callable at most once per environment**, and at least one pipeline must exist (a pipeline without a sink throws `IllegalStateException`)

`RedisJobClient` (`AutoCloseable`; `close()` is `cancel()`):

| Method | Semantics |
|---|---|
| `void cancel()` | idempotent; stops periodic checkpoints/leader renewal scheduling, releases the leader lease, stops consumers, closes runners (including `sink.close()`), and shuts the shared timer pool |
| `Checkpoint triggerCheckpointNow()` | manually triggers one stop-the-world checkpoint; shares the CAS with the periodic schedule (single-flight) and returns `null` when already running, cancelled, or refused by the job-level control gate (see 2.6) |
| `Checkpoint getLatestCheckpoint()` | reads the latest checkpoint (possibly `null`) |
| `void pause()` / `void resume()` | backpressure-style pause/resume; effective only when the consumer implements `PausableMessageConsumer` |
| `long inFlight()` | aggregates in-flight messages across consumers; `-1` when no pausable implementation |
| `Map<String,Object> diagnostics()` | runtime diagnostics: jobName/jobInstanceId, parallelism and thread counts, the switches, inFlight, checkpointing, the leader state (isLeader/leaderInstanceId/fencingToken), the latest checkpoint id and the latest sinkCommitted checkpoint id, the pipelines list; **no current watermark value** |
| `boolean awaitTermination(Duration)` | waits for the job to stop; `false` on timeout |

### 2.2 Configuration (RedisRuntimeConfig.builder())

Types and defaults taken one by one from `runtime/src/main/java/.../runtime/redis/RedisRuntimeConfig.java`:

| Builder method | Type | Default | Notes |
|---|---|---|---|
| `jobName(String)` | String | `redis-streaming-job` | the key to state/checkpoint/dedup keyspace isolation |
| `jobInstanceId(String)` | String | the local hostname (falling back to `local` on failure) | feeds the stable consumer name `jobName-jobInstanceId-{n}` |
| `stateKeyPrefix(String)` | String | `streaming:runtime` | the keyed-state key prefix |
| `stateTtl(Duration)` | Duration | `ZERO` (no TTL) | the expiry granularity is the individual Hash of (job,topic,group,partition,operator,stateName) |
| `stateSizeReportEveryNStateWrites(int)` | int | `0` (off) | samples HLEN every N state writes and reports the metric |
| `keyedStateShardCount(int)` | int | `1` (no sharding) | ≥1; >1 splits the state Hash by key hash |
| `keyedStateHotKeyFieldsWarnThreshold(long)` | long | `0` (off) | the per-state-Hash field-count warning threshold |
| `keyedStateHotKeyWarnInterval(Duration)` | Duration | `1min` | the minimum interval between warnings/handling windows for the same Hash |
| `keyedStateHotKeyPolicy(HotKeyPolicy)` | enum `LOG_ONLY/THROTTLE/FAIL_FAST` | `LOG_ONLY` | the write-handling policy once a hot key is hit |
| `keyedStateHotKeyThrottleMaxMs(long)` | long | `200` | the per-write pre-sleep cap under `THROTTLE` |
| `stateSchemaEvolutionEnabled(boolean)` | boolean | `true` | the state schema version check switch |
| `stateSchemaMismatchPolicy(StateSchemaMismatchPolicy)` | enum `FAIL/CLEAR/IGNORE` | `FAIL` | handling of incompatible schemas |
| `restoreConsumerGroupFromCommitFrontier(boolean)` | boolean | `true` | when the consumer group is missing, rebuild the starting point from the MQ commit frontier instead of replaying everything |
| `sinkDeduplicationEnabled(boolean)` | boolean | `false` | sink-side dedup by message id (`x-original-message-id` preferred) |
| `sinkDeduplicationTtl(Duration)` | Duration | `7` days | the dedup-marker TTL |
| `sinkDedupKeyPrefix(String)` | String | `streaming:runtime:sinkDedup:` | the dedup key prefix (jobName appended to the actual key) |
| `deferAckUntilCheckpoint(boolean)` | boolean | `false` | messages stay pending; ACKed only after the checkpoint completes |
| `ackDeferredMessagesOnCheckpoint(boolean)` | boolean | `true` | set `false` to let the Redis-only sinks do the atomic XACK themselves |
| `pipelineParallelism(int)` | int | `1` | consumer subtasks per pipeline; partitions pinned by `partitionId % n == subtask` |
| `timerThreads(int)` | int | `1` | the shared processing-time timer pool size |
| `checkpointThreads(int)` | int | `1` | checkpoint scheduling threads (execution stays serialized per job) |
| `eventTimeTimerMaxSize(int)` | int | `100000` | the per-runner event-time timer queue cap; `0` unbounded; when full the record's processing fails (an `IllegalStateException` flows through `runner.handle` into MQ retry/dead-letter — visible and recoverable) rather than being dropped silently |
| `watermarkOutOfOrderness(Duration)` | Duration | `ZERO` | watermark = max(event time) − this value; non-negative |
| `watermarkIdleTimeout(Duration)` | Duration | `null` (off) | the idle-watermark flush: after the source stays silent for this long (or a generator calls `markIdle()`), the watermark flushes to `MAX_VALUE`, firing all due event-time timers and draining windows' due sets via the flush hooks; the next record opens a new watermark epoch; null/ZERO disables, negatives throw `IllegalArgumentException` |
| `windowAllowedLateness(Duration)` | Duration | `ZERO` | window close time = windowEnd + this value |
| `windowMaxFiresPerRecord(int)` | int | `256` | the maximum due windows a single record may trigger (≥1) |
| `mdcEnabled(boolean)` | boolean | `false` | installs MDC per message (job/topic/group/consumer/id/key/partition) |
| `mdcSampleRate(double)` | double | `1.0` | the MDC sampling rate, in [0,1] |
| `checkpointInterval(Duration)` | Duration | `ZERO` (off) | periodic checkpoints; the scheduled interval floors at 50ms |
| `restoreFromLatestCheckpoint(boolean)` | boolean | `false` | restore from the latest checkpoint at startup |
| `checkpointKeyPrefix(String)` | String | `streaming:runtime:checkpoint:` | the checkpoint key prefix (jobName appended to the actual key) |
| `checkpointsToKeep(int)` | int | `5` | checkpoints retained; `0` disables cleanup |
| `checkpointDrainTimeout(Duration)` | Duration | `30s` | the cap stop-the-world checkpoints wait for in-flight messages to drain; non-positive values fall back to the default |
| `mqOptions(MqOptions)` | MqOptions | `MqOptions.builder().build()` | the underlying MQ consumption settings (threads/leases/retries etc.; see the mq docs) |
| `processingErrorResult(MessageHandleResult)` | enum `RETRY/DEAD_LETTER/FAIL` | `RETRY` | handling of pipeline exceptions; `DEAD_LETTER` and `FAIL` currently both go to the DLQ and then ack |
| `leaderElectionEnabled(boolean)` | boolean | `false` | multi-instance leader election (see 2.6) |
| `leaderLeaseTtl(Duration)` | Duration | `30s` | the leader lease TTL; must be >0 |
| `leaderRenewInterval(Duration)` | Duration | `10s` | the renewal interval; must be >0 and < leaseTtl (validated at construction) |
| `jobControlGateEnabled(Boolean)` | Boolean | `null` (follows `leaderElectionEnabled`) | the job-level control gate (see 2.6): stop-the-world checkpoints and `scaleParallelism` serialize across same-`jobName` instances via Redis leases; `null` follows `leaderElectionEnabled` (off by default), an explicit `true/false` overrides in either direction without touching election itself |
| `controlGateWait(Duration)` | Duration | `ZERO` (fail fast) | the wait cap for contending the cross-instance control lease; when refused, `triggerCheckpointNow()` returns null and `scaleParallelism` returns false; positive values poll until acquired or timeout; negatives throw `IllegalArgumentException` |

### 2.3 Build and Startup Example

All of the following calls have prototypes in `runtime/src/test` (the integration cases):

```java
import io.github.cuihairu.redis.streaming.api.stream.DataStream;
import io.github.cuihairu.redis.streaming.api.stream.KeyedStream;
import io.github.cuihairu.redis.streaming.api.state.StateDescriptor;
import io.github.cuihairu.redis.streaming.api.state.ValueState;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import io.github.cuihairu.redis.streaming.runtime.redis.RedisJobClient;
import io.github.cuihairu.redis.streaming.runtime.redis.RedisRuntimeConfig;
import io.github.cuihairu.redis.streaming.runtime.redis.RedisStreamExecutionEnvironment;

RedisRuntimeConfig config = RedisRuntimeConfig.builder()
        .jobName("rt-job-" + UUID.randomUUID().toString().substring(0, 6))
        .stateKeyPrefix("streaming:runtime:" + UUID.randomUUID().toString().substring(0, 6))
        .watermarkOutOfOrderness(Duration.ofSeconds(5))
        .pipelineParallelism(2)
        .checkpointInterval(Duration.ofSeconds(30))
        .restoreFromLatestCheckpoint(true)
        .sinkDeduplicationEnabled(true)
        .mqOptions(MqOptions.builder().workerThreads(1).schedulerThreads(1).build())
        .build();

RedisStreamExecutionEnvironment env = RedisStreamExecutionEnvironment.create(redissonClient, config);

// map + keyBy + keyed state + process + sink (one chain; state and operators bind to the same pipeline)
DataStream<String> base = env.fromMqTopic(topic, group).map(m -> (String) m.getPayload());
KeyedStream<String, String> keyed = base.keyBy(v -> v);
StateDescriptor<Integer> desc = new StateDescriptor<>("cnt", Integer.class, 0);
ValueState<Integer> cnt = keyed.getState(desc);
keyed.<String>process((key, value, ctx, out) -> {
            Integer c = (cnt.value() == null ? 0 : cnt.value()) + 1;
            cnt.update(c);
            out.collect(key + ":" + c);
        })
        .addSink(results::add);

try (RedisJobClient job = env.executeAsync()) {   // close() is cancel()
    producer.send(topic, "k1", "a").get(5, TimeUnit.SECONDS);
    // ...
}
```

With windows (`TumblingWindow` comes from the window module, a runtime main dependency; the watermark module is `testImplementation` for the runtime, so using `BoundedOutOfOrdernessWatermarkGenerator` requires adding the `io.github.cuihairu.redis.streaming:watermark` dependency yourself):

```java
import io.github.cuihairu.redis.streaming.window.assigners.TumblingWindow;
import io.github.cuihairu.redis.streaming.watermark.generators.BoundedOutOfOrdernessWatermarkGenerator;

env.fromMqTopic(topic, group)
        .map(m -> Integer.parseInt((String) m.getPayload()))
        .keyBy(v -> "k")
        .window(TumblingWindow.<Integer>ofMillis(1500))
        .reduce(Integer::sum)
        .addSink(out::add);

env.fromMqTopic(topic, group)
        .assignTimestampsAndWatermarks(new BoundedOutOfOrdernessWatermarkGenerator<>(Duration.ofSeconds(5)))
        // ... continue with map/keyBy/window/...
```

### 2.4 Key Semantics

- Event time comes from `Message.getTimestamp()` (the sender's `Instant.now()` at message construction), falling back to the current system time when empty; the watermark = max(event time) − `watermarkOutOfOrderness`, monotonically non-decreasing.
- A user WatermarkGenerator: `assignTimestampsAndWatermarks(gen)` calls `onEvent` + `onPeriodicEmit` per element; generators may only **raise** the watermark through `Context.raiseWatermark` (monotonically); `markIdle()` declares the source idle (with `watermarkIdleTimeout` configured, the idle sweep flushes immediately), `markActive()` restores activity, and `onPeriodicEmit` is element-driven rather than on a wall-clock timer.
- Event-time assignment (Flink semantics): in the `assignTimestampsAndWatermarks(assigner, gen)` overload the `TimestampAssigner` decides each record's event time (downstream operators read it via `ctx.currentEventTime()`, and window membership is computed from the assigned value), while `gen` owns watermark advancement — the message-timestamp heuristic of the pipeline's remaining chain is then silenced (delivery time may far exceed the assigned event time; without silencing, windows would close early); if the generator never emits, the watermark stays at `MIN_VALUE` — pair the silent period with `watermarkIdleTimeout` (RT-M3); `markIdle/markActive` through the generator's output also feed the idle sweep.
- Idle-watermark flush (RT-M3, configurable `watermarkIdleTimeout`, off by default): after the pipeline stays silent for that long (or a generator calls `markIdle()`), the periodic sweep flushes the watermark to `MAX_VALUE` and fires all due event-time timers — an idle pipeline's windows/timers no longer wait forever; the next record opens a new watermark epoch (based on that record's event time, not pinned to the flush value).
- Window triggering: due windows are checked during each message's processing, at most `windowMaxFiresPerRecord` per record, with the remaining due members waiting for the next record; when the idle-watermark flush (RT-M3, `watermarkIdleTimeout`) pushes the watermark to `MAX_VALUE`, window operators drain their due sets without limit through registered flush hooks — a silent pipeline's windows no longer wait forever. On the record path the due sets' sole owner remains the per-record drain; trigger semantics (e.g. a defer-once close trigger) and the capping behavior are unaffected by the flush; window close time = `windowEnd + windowAllowedLateness`, past which the watermark marks elements late — they are dropped and counted in the `incWindowLateDropped` metric. Window operators also drive `WindowAssigner.getDefaultTrigger()`: one instance per (partition,key,window) bucket, `onElement` on arrival (`FIRE` emits early and keeps accumulating / `FIRE_AND_PURGE` emits and clears / `PURGE` clears silently / `CONTINUE`), `onEventTime` before due firing (`CONTINUE` defers, `PURGE` drops silently, `FIRE` is treated as `FIRE_AND_PURGE`). The default `EventTimeTrigger` is equivalent to the pure watermark-close behavior (see docs/watermark.md).
- State is written by `RedisKeyedStateStore` into Redis Hashes keyed by (job,topic,group,partition,operator,stateName) (fields are serialized keys), with TTL, sharding per `keyedStateShardCount`, schema version checks, and hot-key handling; state access: `keyed.getState(StateDescriptor)` returns a `ValueState` (`value/update/clear`). Collection states also exist (`getMapState(name,keyType,valueType)` / `getListState(StateDescriptor)` / `getSetState(StateDescriptor)`, 2026-10-11): one Redis Hash/List/Set per current key, key `{...}:state:{operator}:{state}:{serialized key}`, elements JSON-serialized, registered into the `stateKeys` index (checkpoint snapshots cover MAP/LIST/SET values); Map states reuse TTL/hot keys; List/Set states slide their TTL (hot-key detection works per Hash field count and does not apply to List/Set).
- Checkpoints: `RedisRuntimeCheckpointManager` stops the world and snapshots `runtime:meta` (jobName/jobInstanceId/stateKeyPrefix/sinkCommitted/fencingToken), `runtime:offsets` (each partition's commit frontier — the max acked stream id), `runtime:state` (the full MAP/ZSET values covered by the state-key index), `runtime:stateSchema`, and optionally `runtime:txns` (the two-phase-commit handles); restore rebuilds the consumer group (`XGROUP DESTROY`+`CREATE` at the snapshot offset), replays state, calls `onCheckpointRestore` per runner, and handles the 2PC compensation (see 2.8).
- Timers come in two kinds: processing-time on a shared `ScheduledExecutor` (`timerThreads`), event-time on an in-memory priority queue (capacity `eventTimeTimerMaxSize`), fired with the watermark.
- Pipeline exceptions are handled per `processingErrorResult` (default `RETRY`: MQ retry + backoff, DLQ past the cap); retried/dead-lettered messages carry `RedisRuntimeHeaders`' `x-runtime-job` / `x-runtime-group` / `x-runtime-error-type` / `x-runtime-error-message` (truncated to 512 chars) for troubleshooting.
- Sink lifecycle: `open()` on the first message (idempotent), `close()` on `job.cancel()/close()` (exceptions only logged).
- MDC is sampled per `mdcSampleRate` when `mdcEnabled(true)`, installing the MDC keys for the sampled messages.

### 2.5 Checkpoint Retention and Cleanup

- `checkpointsToKeep` (default 5): beyond that, old checkpoints are cleaned, incomplete ones deleted first; `0` disables.
- Within the stop-the-world window, cleanup is deferred until after consumption resumes (`cleanupOld()`), avoiding a longer pause.

### 2.6 Leader Election, HA Takeover, and the Job-Level Control Gate (`RedisLeaderElector` / `JobControlGate`)

- With `leaderElectionEnabled(true)`, multiple instances of the same `jobName` contend for a Redis lease (key `stateKeyPrefix:jobName:leader`): acquire = `SET NX PX ttl`; renew = a compare-and-expire Lua (only while still held); release = a compare-and-delete Lua. The lease expires automatically with its holder's death; no manual cleanup.
- Periodic checkpoint scheduling runs only on the leader and stops immediately when the lease is lost; followers attempt takeover each renewal interval, and on success allocate a new fencing token and `refreshCheckpointIdFromStorage()` to align the checkpoint counter before starting their own schedule.
- The fencing token is allocated once per term via Redis `INCR` and written into that term's checkpoints' meta; restore adopts only checkpoints whose token equals the historical maximum, so checkpoints an old leader writes after losing its lease are never adopted (going further since 2026-10-15: an instance that held the lease but failed to renew refuses to persist snapshots at write time, no longer producing orphan snapshots that rely on token-based rejection; the manual `triggerCheckpointNow` path of instances that never held the lease is preserved).
- `diagnostics()` exposes `isLeader` / `leaderInstanceId` / `fencingToken`.
- **The job-level control gate (cross-instance serialization, `jobControlGateEnabled`, defaulting to follow `leaderElectionEnabled`)**: when on, stop-the-world checkpoints and `scaleParallelism` first contend for the job-level two-level Redis lease (keys `<stateKeyPrefix>:<jobName>:ctl:excl` / `:ctl:silence`, holder value `jobInstanceId:opSeq`) — both levels contended together, released in reverse (`silence ⇒ exclusive`); TTL = `max(30s, 2 × checkpointDrainTimeout)` for self-healing, renewed at TTL/3 while held. Another instance holding the lock or Redis being unreachable is always refused rather than blindly run: `triggerCheckpointNow()` returns null and `scaleParallelism` returns false (polling first when `controlGateWait > 0`); `cancel()` releases before closing, so a restarted job's first control operation is not blocked by the stale TTL; with the gate off it is fully inert and never touches the lease keys. Cross-process crash recovery is backstopped by the TTL — the same self-healing boundary as the election lease.

### 2.7 Keyed-State Hot-Key Handling

Detection is sampled (one HLEN per `stateSizeReportEveryNStateWrites` writes); when the field count reaches `keyedStateHotKeyFieldsWarnThreshold`, warnings are rate-limited (interval `keyedStateHotKeyWarnInterval`) and an arming window opens for that Hash, within which every write is handled per `keyedStateHotKeyPolicy`:

| Policy | Behavior |
|---|---|
| `LOG_ONLY` | a warning log + the `incKeyedStateHotKey` metric |
| `THROTTLE` | additionally sleeps at most `keyedStateHotKeyThrottleMaxMs` before the write, backpressuring the hot key with latency |
| `FAIL_FAST` | additionally throws `KeyedStateHotKeyException` — reusing the MQ consumer's retry/backoff as backpressure, DLQ past the cap |

### 2.8 Sinks, Dedup, and the Exactly-Once Components

The components under `runtime/redis/sink` (class names and constructor shapes from the source):

| Class | Semantics |
|---|---|
| `RedisIdempotentListSink<T>` | one Lua script atomically performs `SISMEMBER → SADD(+EXPIRE) → RPUSH`, writing at most once per idempotency key; the input is `IdempotentRecord<T>`; constructor `(redissonClient, dedupSetKey, listKey[, objectMapper, dedupTtl])`; in a cluster both keys must share a hash slot |
| `RedisCheckpointedIdempotentListSink<T>` | implements `CheckpointAwareSink`: invoke only buffers; `onCheckpointComplete` hands the batch to `RedisIdempotentListSink`; abort/restore clears the buffer |
| `RedisAtomicCheckpointListSink<T>` | paired with `RedisExactlyOnceRecord<T>` (topic/consumerGroup/partitionId/messageId/idempotencyKey/value): at checkpoint completion it groups by (topic,group,partition) and one Lua script atomically performs dedup + RPUSH + `XACK` + advancing the commit frontier; requires `deferAckUntilCheckpoint(true)` and `ackDeferredMessagesOnCheckpoint(false)`, and the MQ stream key and the sink keys must share a hash slot |
| `RedisOutboxSink<T>` | implements `TwoPhaseCommitSink`: invoke buffers in memory → `preCommit` XADDs records one by one into the outbox stream (epoch/seq/id/payload fields) → `commit`/`recoverAndCommit` flips `<outboxKey>:epochs` to `COMMITTED` with a single HSET (the whole epoch becomes visible atomically); `abort`/`recoverAndAbort` writes `ABORTED` |
| `RedisOutboxDispatcher<T>` | the outbox's async dispatcher (consumer-group driven): `COMMITTED` entries are delivered in order + acked + XDELed; `ABORTED` entries are dropped; entries without a marker block at the head awaiting the runtime's compensation; delivery failures stay pending and retry per `retryIdleMs`, moving to `<outboxKey>:dlq` past `maxAttempts` (with `dlqReason`/`failedAttempts`/`dlqTime` fields). Delivery itself is at-least-once; end-to-end exactly-once needs the target idempotent on a stable record id (see docs/exactly-once.md) |

The runtime wires `TwoPhaseCommitSink` automatically (`instanceof` detection): the checkpoint flow is `preCommit → store the handles (runtime:txns) → commit → mark sinkCommitted → ack`; a crash "after storing handles, before commit" replays `recoverAndCommit` from the stored handles at restore (contractually idempotent).

### 2.9 Not Supported (relative to the core interfaces)

- `fromCollection` / `fromElements` / `addSource`: the Redis engine has only the MQ source
- The window `Trigger`'s `onProcessingTime`: no processing-time window timers

### 2.10 Dynamic Scaling (`RedisJobClient.scaleParallelism`)

- `scaleParallelism(n)` (n≥1; the `RedisJobClient` interface defaults to false, `LaunchedJobClient` implements it): grows/shrinks **each of the job's pipelines** to n subtasks and re-pins all live consumers to `partitionId % n == subtaskIndex` (via the MQ layer's `updatePartitionAssignment`; partitions moved out of an assignment stop their worker and release the lease immediately, the new same-group owner picking them up via rebalance without waiting for the lease TTL).
- **State and checkpoints are naturally forward-compatible**: keyed state and checkpoints are both keyed by `(job,topic,group,partition,…)` with no parallelism/subtask dimension — after scaling, a new owner subtask reads and writes the existing per-partition state directly; a checkpoint written at parallelism 2 restores at parallelism 3 (`restoreFromLatestCheckpoint`).
- **Scaling and checkpoints are mutually exclusive**: resize waits (bounded to 30s) for an in-flight stop-the-world checkpoint to complete before touching the subtask list; the consumers/runners lists are copy-on-write, so `cancel()` may traverse without locks. With the job-level control gate on, that exclusion escalates cross-instance: while another instance of the same `jobName` runs a control operation (checkpoint/rescale), this instance's resize waits or is refused (see 2.6).
- **Scale-down semantics**: a removed subtask's consumer stops first (workers release their leases as owners on exit, signaling handover) and then closes; its runner does **not** close — sinks are shared by all of a pipeline's runners, so surviving runners still hold them, closed together at job cancel. A removed runner has no thread of its own; at most a bounded tail of already-scheduled timers still writes into the shared per-partition-keyed state.
- **The handover window is at-least-once**: between lease release and the rebalance pickup (seconds), messages stay in the stream and are not lost — the same semantics as leader-crash takeover.
- Consumer names increase monotonically across scaling (`jobName-jobInstanceId-{seq}`, seq never reused); `diagnostics()`'s `liveParallelism` reflects the post-scaling parallelism (the configured value is the startup value).

## 3. Metrics

`RedisRuntimeMetrics` is a static singleton (Noop by default; `setCollector` overrides, `null` ignored), dimensioned by jobName (+topic/consumerGroup/operatorId/stateName/partitionId):

- Job/pipeline: `incJobStarted` / `incJobCanceled` / `incPipelineStarted` / `incPipelineStartFailed`
- Handling: `incHandleSuccess` / `incHandleError` / `recordHandleLatency`
- Checkpoints: `incCheckpointTriggered` / `incCheckpointCompleted` / `incCheckpointFailed`, total duration `recordCheckpointDuration` and the drain/store/sinkCommit segments
- Keyed state: `incKeyedStateRead/Write/Delete`, read/write latencies, `recordKeyedStateSize` (HLEN sampling), `incKeyedStateHotKey`
- Windows: `incWindowFired` / `incWindowLateDropped`; watermark: `setWatermarkMs`; timer queue: `setEventTimeTimerQueueSize`

The Micrometer bridge is done by the spring-boot-starter (`RedisRuntimeMicrometerCollector`, metric prefix `redis_streaming_runtime_*`, see Spring-Boot-Starter.md).

## Related Documentation

[Core.md](Core.md) · [state.md](state.md) · [checkpoint.md](checkpoint.md) · [watermark.md](watermark.md) · [MQ.md](MQ.md) · [Spring-Boot-Starter.md](Spring-Boot-Starter.md) · [exactly-once.md](exactly-once.md)
