# Checkpoint Module

Module directory: `checkpoint/`

## Overview

The checkpoint module implements core's checkpoint contracts on top of Redis:

- `checkpoint.storage.CheckpointStorage` — the checkpoint persistence interface
- `checkpoint.redis.RedisCheckpointStorage` — the Redis implementation (one Redis String/Bucket key per checkpoint)
- `checkpoint.redis.RedisCheckpointCoordinator` — a coordinator for trigger → task acknowledge (ack) → complete; completion conditions and timeouts are managed in the coordinator process
- `checkpoint.DefaultCheckpoint` — the default `Checkpoint` implementation, carrying a snapshot format version

The module itself contains no periodic scheduling: when checkpoints trigger is the caller's decision; the runtime's Redis engine uses `RedisRuntimeConfig.checkpointInterval` (default `Duration.ZERO`, i.e. no scheduling) for periodic triggering and reuses this module's `DefaultCheckpoint` / `RedisCheckpointStorage` (see [runtime.md](runtime.md)).

## Core Interfaces

### CheckpointCoordinator (core module)

```java
public interface CheckpointCoordinator {
    long triggerCheckpoint();                              // returns the checkpoint ID
    void acknowledgeCheckpoint(long checkpointId, String taskId);
    void completeCheckpoint(long checkpointId);
    void restoreFromCheckpoint(long checkpointId);
    Checkpoint getLatestCheckpoint();                      // null when none
    Checkpoint getCheckpoint(long checkpointId);           // null when none
}
```

None of the interface methods throw checked exceptions.

### Checkpoint (core module) and DefaultCheckpoint

```java
public interface Checkpoint extends Serializable {
    long getCheckpointId();
    long getTimestamp();
    StateSnapshot getStateSnapshot();
    boolean isCompleted();
    void markCompleted();
    default int getSnapshotVersion();      // default 0: legacy snapshots without a version marker
}

public interface Checkpoint.StateSnapshot extends Serializable {
    <T> T getState(String key);
    default <T> T getState(String key, Class<T> type);  // throws IllegalStateException on a type mismatch
    <T> void putState(String key, T value);
    Iterable<String> getKeys();
}
```

`DefaultCheckpoint` (`checkpoint.DefaultCheckpoint`) implementation:

- Constants `CURRENT_SNAPSHOT_VERSION = 1` and `LEGACY_SNAPSHOT_VERSION = 0`; newly created instances write version 1, while instances deserialized via the no-arg constructor (prepared for the JSON codec) keep 0.
- `new DefaultCheckpoint(long checkpointId, long timestamp)` is the application-facing constructor entry point.
- `StateSnapshotImpl` additionally provides `size()` and `clear()`; `getState(key, type)` attempts a conversion via Jackson's `ObjectMapper.convertValue` on a type mismatch and throws `IllegalStateException` on failure.

### CheckpointStorage

```java
public interface CheckpointStorage {
    void storeCheckpoint(Checkpoint checkpoint) throws Exception;
    Checkpoint loadCheckpoint(long checkpointId) throws Exception;   // null when none
    Checkpoint getLatestCheckpoint() throws Exception;               // only the latest completed checkpoint
    List<Checkpoint> listCheckpoints(int limit) throws Exception;    // descending by timestamp
    boolean deleteCheckpoint(long checkpointId) throws Exception;
    int cleanupOldCheckpoints(int keepCount) throws Exception;       // returns the number deleted
    void close();
}
```

### Coordination Semantics of RedisCheckpointCoordinator

- `triggerCheckpoint()`: generates a monotonically increasing ID (counter seed = latest stored checkpoint ID + 1), writes it to storage and registers it as pending; on storage failure it returns `-1` and registers nothing.
- `acknowledgeCheckpoint(checkpointId, taskId)`: records the task acknowledgement; once `requiredTaskAcks` distinct taskIds are gathered it automatically calls `completeCheckpoint`.
- `completeCheckpoint(checkpointId)`: reads the checkpoint back from storage, calls `markCompleted()`, and writes it again.
- Timeouts: checkpoints pending longer than `checkpointTimeout` milliseconds can never complete and are removed by `cleanupExpiredPendingCheckpoints()`; `getPendingCheckpointCount()` returns the current pending count.
- Restore: `restoreFromCheckpoint(long)` only validates and hands back snapshot entries (the coordinator holds no state backend); the sink-carrying overload `restoreFromCheckpoint(long, BiConsumer<String, Object>)` passes every `(key, value)` to the callback and returns the number of entries handed over; it returns `-1` and restores nothing when the checkpoint is missing or incomplete.
- `cleanupOldCheckpoints(int keepCount)`: evicts incomplete checkpoints first, then older completed ones, until exactly `keepCount` survive.
- `close()`: clears pending state and calls `storage.close()`.

### The runtime in-memory engine: snapshot/pipeline mutual exclusion

The runtime's built-in in-memory engine (`InMemoryCheckpointCoordinator`) does not use the Redis storage above; its snapshot barrier is a **two-lock contract**:

- **Snapshot lock**: the coordinator holds an independent `checkpointLock()` monitor; all mutations of registered stores (put/remove/setCurrentKey/snapshot/restore) and the coordinator's snapshot/restore synchronize on it, and `triggerCheckpoint` holds the lock across all registered stores — one checkpoint is a single consistent cut, and a snapshot racing a pipeline thread's writes (CME/dirty copies) is impossible. The lock is held only over bounded, non-blocking in-memory operations.
- **Emission lock**: `SourceContext.getCheckpointLock()` returns the source-level emission lock; producers may block across a full queue while holding it, and consumers never take it. The two locks never intersect, so backpressure can never deadlock; the Flink-style idiom of wrapping `collect()` in `getCheckpointLock()` is safe.
- **Snapshot-source generalization (2026-10-12)**: `registerSnapshotSource(name, snapshot, restore)` lets any `(name, snapshot, restore)` triple enter the checkpoint in the same consistent cut as the keyed stores; the callbacks enjoy the same snapshot-lock protection as store snapshots. `assignTimestampsAndWatermarks`'s pipeline stage, while holding the coordinator, automatically registers the stage's `WatermarkState` as a `watermark-N` snapshot source, so checkpoints include the current watermark and idle flag, replayed on restore.
- **Window accumulator registration (2026-10-14, contract in docs/InMemory-Operator-State-Lifecycle-Design.md)**: each terminal-operator consumer holds one piece of operator state — the bucket table is a field of that instance, lazily registered under a collision-free generated `window-N` name when consumption starts (unconsumed terminal operators register nothing); the drive() loop body and the end-of-input flush run wholly inside `fenceLock()` (= the coordinator's checkpoint lock, the same contract as the keyed path's per-record barrier; source iteration happens outside the fence so a blocking source cannot freeze checkpointing). Snapshot payloads record per bucket: key/window boundaries/lastTimestamp/accumulated contents (the incremental path's current fire result or a copy of the raw elements); Triggers have no snapshot API and instances do not enter the snapshot. Restore defaults to FRESH: payloads only write a read-only checkpoint mirror and do not inject live buckets — with in-memory sources replaying in full, adopting restored buckets would double-count.
- **Per-record barrier (2026-10-13)**: the record body executes wholly inside the store's `fenceLock()` — that lock is the registered `checkpointLock`, so a checkpoint triggered mid-record blocks until the record has written all its state, no longer splitting a single record's multiple writes; `synchronized` is reentrant, so the store's own per-write locking idles inside the fence. The end-of-input `drainAllTimers()` also enters the fence (timer callbacks write state too).

## Configuration

| Item | Type | Default | Notes |
|---|---|---|---|
| `RedisCheckpointStorage` constructor `keyPrefix` | `String` | `"checkpoint:"` | Checkpoint key prefix; actual key = `keyPrefix + checkpointId` |
| `RedisCheckpointCoordinator` constructor `requiredTaskAcks` | `int` | required | Number of distinct task acknowledgements needed to complete a checkpoint |
| `RedisCheckpointCoordinator` constructor `checkpointTimeout` | `long` (ms) | `60000` | Pending checkpoint timeout; `<= 0` disables timeout cleanup |
| `DefaultCheckpoint.CURRENT_SNAPSHOT_VERSION` | `int` | `1` | Snapshot version written by the current implementation |
| `DefaultCheckpoint.LEGACY_SNAPSHOT_VERSION` | `int` | `0` | Version read from legacy snapshots without a version marker |

Runtime-side related settings (`RedisRuntimeConfig`, details in [runtime.md](runtime.md)): `checkpointInterval` (default `Duration.ZERO`), `checkpointThreads` (default 1), `checkpointKeyPrefix` (default `streaming:runtime:checkpoint:`), `checkpointsToKeep` (default 5), `checkpointDrainTimeout` (default 30s).

## Usage Example

The following is taken from the `examples` module's `CheckpointExample` (requires a local Redis; the address can be overridden with `REDIS_URL`):

```java
import java.util.HashMap;
import java.util.Map;
import io.github.cuihairu.redis.streaming.api.checkpoint.Checkpoint;
import io.github.cuihairu.redis.streaming.checkpoint.redis.RedisCheckpointCoordinator;
import io.github.cuihairu.redis.streaming.checkpoint.redis.RedisCheckpointStorage;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

Config config = new Config();
config.useSingleServer().setAddress("redis://127.0.0.1:6379");
RedissonClient redisson = Redisson.create(config);

// 1. Create storage and coordinator (3 task acks required)
RedisCheckpointStorage storage = new RedisCheckpointStorage(redisson, "example:checkpoint:");
RedisCheckpointCoordinator coordinator = new RedisCheckpointCoordinator(storage, 3);

// 2. Trigger a checkpoint (registered as pending)
long checkpointId = coordinator.triggerCheckpoint();

// 3. Write operator state into the pending checkpoint's snapshot, then store it back
//    (the same flow as the integration test CheckpointRedisLiveIntegrationTest)
Checkpoint pending = storage.loadCheckpoint(checkpointId);
pending.getStateSnapshot().putState("user-count", 1000);
pending.getStateSnapshot().putState("offsets", Map.of("p0", 11L, "p1", 22L));
storage.storeCheckpoint(pending);

// 4. Task acks; completing automatically once 3 acks are gathered
coordinator.acknowledgeCheckpoint(checkpointId, "task-1");
coordinator.acknowledgeCheckpoint(checkpointId, "task-2");
coordinator.acknowledgeCheckpoint(checkpointId, "task-3");

Checkpoint checkpoint = coordinator.getCheckpoint(checkpointId);
System.out.println("completed: " + checkpoint.isCompleted());   // true

// 5. Restore: hand every entry of the completed checkpoint to a callback
Checkpoint latest = coordinator.getLatestCheckpoint();
if (latest != null) {
    Map<String, Object> restored = new HashMap<>();
    int count = coordinator.restoreFromCheckpoint(latest.getCheckpointId(), restored::put);
    System.out.println("restored " + count + " entries");
}

// 6. Clean up old checkpoints, keeping only the latest 2
int deleted = coordinator.cleanupOldCheckpoints(2);

coordinator.close();
redisson.shutdown();
```

## Redis Data Layout

- One key per checkpoint: `{keyPrefix}{checkpointId}` (with the default prefix that is `checkpoint:0`, `checkpoint:1`, ...), the value being the whole `Checkpoint` object, encoded/decoded by the Redisson codec via `RBucket.set/get`.
- `listCheckpoints` scans with `getKeys(KeysScanOptions.defaults().pattern(keyPrefix + "*"))`, accepts only keys with a purely numeric suffix, and returns them descending by timestamp; no Hash or Sorted Set index is used.
- `getLatestCheckpoint()` treats only checkpoints with `isCompleted()` as valid restore points.

## Caveats

1. The coordinator's pending/ack tables live in the coordinator process's memory (`ConcurrentHashMap`), not in Redis: ack requests must go to the same `RedisCheckpointCoordinator` instance that created the checkpoint.
2. Incomplete checkpoints (missing acks or timed out) are never selected by `getLatestCheckpoint()`, and `restoreFromCheckpoint` refuses them too (returns `-1`), avoiding restores from torn partial snapshots.
3. The single-argument `restoreFromCheckpoint(long)` only validates and logs; it backfills no state — use the `BiConsumer` overload to actually retrieve data.
4. The checkpoint ID counter seeds from the storage's latest ID + 1 at construction; if storage is unreachable at construction time the coordinator marks `idSeedUnverified` and lazily retries re-seeding before the next `triggerCheckpoint`, preventing old checkpoints from being overwritten with ID 0.

## Related Documentation

- [State module](state.md) - state management
- [Runtime module](runtime.md) - periodic triggering and runtime checkpoint management
- [Checkpoint barrier design](Checkpoint-Barrier-Design.md) - snapshot barriers and the lock contract
- [Core API](Core.md) - checkpoint interface definitions
