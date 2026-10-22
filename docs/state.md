# State Module

Module directory: `state/`

## Overview

The state module provides the state backend interface `StateBackend` and its Redis implementation `RedisStateBackend`. Since step 3 of the dual-execution-engine state unification (the plan and phasing in [State-Unification-Design.md](State-Unification-Design.md)), `RedisStateBackend` is a **thin adapter** over the runtime's `RedisKeyedStateStore`: the module no longer contains its own state implementations, and all four state kinds land in the runtime's keyed layout — every state key is registered in the job's `stateKeys` index and the `stateSchema` registry, so the checkpoint snapshot mechanism covers them directly.

Adapter semantics: the backend internally holds a keyed store bound to a **fixed solo identity** (topic/consumerGroup/operatorId all `standalone`, partition 0), and pins the current key to the constant `solo` (thread-local, cleared when the operation ends) around every operation; the keyless `StateBackend` contract therefore works unchanged while the data lands in the standard keyed layout.

Module layout:

- `state.backend`: `StateBackend` (interface)
- `state.redis`: `RedisStateBackend` (the adapter)

## Core Interfaces

### StateBackend

The state backend interface, responsible for creating the various state instances (`io.github.cuihairu.redis.streaming.state.backend.StateBackend`):

```java
public interface StateBackend {
    <T> ValueState<T> createValueState(StateDescriptor<T> descriptor);
    <K, V> MapState<K, V> createMapState(String name, Class<K> keyType, Class<V> valueType);
    <T> ListState<T> createListState(StateDescriptor<T> descriptor);
    <T> SetState<T> createSetState(StateDescriptor<T> descriptor);
    void close();
}
```

Note: none of the interface methods throw checked exceptions; `RedisStateBackend.close()` is a no-op (the `RedissonClient` lifecycle is managed externally).

### State interfaces (core module `api.state`)

```java
public interface State extends Serializable {
    void clear();
}

public interface ValueState<T> extends State {
    T value();               // null when unset (the descriptor's defaultValue when it carries one)
    void update(T value);
}

public interface ListState<T> extends State {
    void add(T value);                       // appends to the list tail (RPUSH semantics)
    Iterable<T> get();
    void update(Iterable<T> values);         // wholesale replacement (atomic batch)
    void addAll(Iterable<T> values);
}

public interface MapState<K, V> extends State {
    V get(K key);                            // null when absent
    void put(K key, V value);
    void remove(K key);
    boolean contains(K key);
    Iterable<Map.Entry<K, V>> entries();
    Iterable<K> keys();
    Iterable<V> values();
    boolean isEmpty();
}

public interface SetState<T> extends State {
    boolean add(T value);                    // returns whether it was newly added
    boolean remove(T value);                 // returns whether a deletion happened
    boolean contains(T value);
    Iterable<T> get();
    boolean isEmpty();
    int size();
}
```

### StateDescriptor

The state descriptor, defining a state's name and type (`io.github.cuihairu.redis.streaming.api.state.StateDescriptor`):

```java
public class StateDescriptor<T> implements Serializable {
    public StateDescriptor(String name, Class<T> type);
    public StateDescriptor(String name, Class<T> type, T defaultValue);
    public StateDescriptor(String name, Class<T> type, T defaultValue, int schemaVersion);

    public String getName();
    public Class<T> getType();
    public T getDefaultValue();
    public int getSchemaVersion();           // default 1, clamped to Math.max(1, n) at construction
}
```

`MapState` does not go through `StateDescriptor`: `createMapState(name, keyType, valueType)` creates it directly from a name and key/value types.

## Configuration

| Item | Type | Default | Notes |
|---|---|---|---|
| `RedisStateBackend` constructor `jobName` | `String` | — | Job name; the state-key namespace root is `{stateKeyPrefix}:{jobName}` |
| `RedisStateBackend` constructor `stateKeyPrefix` | `String` | `"streaming:runtime"` | State key prefix (matches `RedisRuntimeConfig.stateKeyPrefix`'s default) |
| `StateDescriptor.schemaVersion` | `int` | `1` | Written into stateSchema (`type.getName() + "\|" + schemaVersion`); mismatches fail fast (fixed in the adapter, not configurable) |

`RedisStateBackend` also exposes `getRedisson()` / `getKeyPrefix()`.

## Usage Examples

### 1. Creating a StateBackend

```java
import io.github.cuihairu.redis.streaming.state.redis.RedisStateBackend;
import io.github.cuihairu.redis.streaming.state.backend.StateBackend;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

Config config = new Config();
config.useSingleServer().setAddress("redis://127.0.0.1:6379"); // override with REDIS_URL
RedissonClient redisson = Redisson.create(config);

StateBackend backend = new RedisStateBackend(redisson);                        // jobName "default"
StateBackend myBackend = new RedisStateBackend(redisson, "my-job");            // default prefix streaming:runtime
StateBackend pfxBackend = new RedisStateBackend(redisson, "my-job", "my:pfx"); // custom prefix
```

### 2. ValueState

```java
StateDescriptor<Integer> descriptor = new StateDescriptor<>("session-counter", Integer.class);
ValueState<Integer> counter = stateBackend.createValueState(descriptor);

Integer count = counter.value();      // null on first use (the defaultValue when the descriptor carries one)
if (count == null) count = 0;
counter.update(count + 1);
counter.clear();                      // deletes this key's field
```

### 3. ListState

```java
import java.util.Arrays;
import java.util.List;

StateDescriptor<String> descriptor = new StateDescriptor<>("activity-log", String.class);
ListState<String> activityLog = stateBackend.createListState(descriptor);

activityLog.add("login");             // RPUSH append
activityLog.addAll(Arrays.asList("view_product", "checkout"));
for (String activity : activityLog.get()) { /* iterate the Iterable */ }
activityLog.update(List.of("reset")); // wholesale replacement (one REDIS_WRITE_ATOMIC batch, see below)
activityLog.clear();
```

### 4. MapState

```java
MapState<String, String> preferences =
        stateBackend.createMapState("user-preferences", String.class, String.class);

preferences.put("theme", "dark");
preferences.get("theme");             // "dark"
preferences.contains("theme");        // true
preferences.entries().forEach(e -> System.out.println(e.getKey() + "=" + e.getValue()));
preferences.keys();
preferences.values();
preferences.isEmpty();
preferences.remove("theme");
preferences.clear();
```

### 5. SetState

```java
StateDescriptor<String> descriptor = new StateDescriptor<>("unique-visitors", String.class);
SetState<String> visitors = stateBackend.createSetState(descriptor);

boolean isNew = visitors.add("user1");   // true the first time; a repeated add returns false
visitors.contains("user1");              // true
visitors.size();
for (String user : visitors.get()) { /* iterate the Iterable */ }
visitors.remove("user1");
visitors.clear();
```

### 6. Using state inside a KeyedStream (runtime in-memory engine)

There are two state-access entry points:

- `KeyedStream.getState(StateDescriptor)` — hold the state handle outside the operator at build time;
- `KeyedProcessFunction.Context.getState(StateDescriptor)` — read/write under the current key inside `processElement` (a default method that throws `UnsupportedOperationException` by default, overridden and wired by both engines; the join/cep operators go through this channel — see [Join/CEP Operator Design](Join-CEP-Operators-Design.md) §3.4).

```java
import java.util.ArrayList;
import java.util.List;
import io.github.cuihairu.redis.streaming.runtime.StreamExecutionEnvironment;
import io.github.cuihairu.redis.streaming.api.stream.KeyedStream;
import io.github.cuihairu.redis.streaming.api.state.StateDescriptor;
import io.github.cuihairu.redis.streaming.api.state.ValueState;

StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

StateDescriptor<Integer> descriptor = new StateDescriptor<>("count", Integer.class, 0);
KeyedStream<String, String> keyed = env.fromElements("a", "b", "a").keyBy(v -> v);
ValueState<Integer> count = keyed.getState(descriptor);

List<String> out = new ArrayList<>();
keyed.<String>process((key, value, ctx, collector) -> {
            int next = count.value() + 1;
            count.update(next);
            collector.collect(key + ":" + next);
        })
        .addSink(out::add);
// result: a:1, b:1, a:2 (same usage as the runtime test StreamExecutionEnvironmentTest)
```

The Redis engine's keyed state is managed by the runtime's own `RedisKeyedStateStore` (key prefix in `RedisRuntimeConfig.stateKeyPrefix`, default `streaming:runtime`); this module's `RedisStateBackend` adapts to the same store and the same key layout — the only difference is the solo identity and the fixed partition 0.

## Redis Data Layout (the new layout)

Job namespace root `{stateKeyPrefix}:{jobName}`; the solo identity is fixed at `cg:standalone / topic:standalone / p:0 / state:standalone`, with `{base}` = `{stateKeyPrefix}:{jobName}:cg:standalone:topic:standalone:p:0:state:standalone`:

| State | Redis type | Key | Fields / elements |
|---|---|---|---|
| ValueState | Hash | `{base}:{stateName}` | field = `s:solo`, value = state value JSON |
| MapState | Hash | `{base}:{name}:s:solo` | field = map key JSON, value = value JSON |
| ListState | List | `{base}:{name}:s:solo` | elements = JSON; `update`/`addAll` = one `REDIS_WRITE_ATOMIC` batch (DEL then addAll, atomic replacement) |
| SetState | Set | `{base}:{name}:s:solo` | members = JSON |

All keys are registered on first access into `{stateKeyPrefix}:{jobName}:stateKeys` (the checkpoint index) and `{stateKeyPrefix}:{jobName}:stateSchema` (the schema registry, value = `type fullName|schemaVersion`).

## Migration Table (unification step 3, breaking change)

The old implementations (`RedisValueState` / `RedisMapState` / `RedisListState` / `RedisSetState`) are deleted. **Both the key layout and the codec are incompatible; the new implementation does not read old data** — this is by design (old data has no production consumers). If you truly need to keep old data, migrate it once by hand per the table below:

| | Old implementation (deleted) | New implementation (adapter) |
|---|---|---|
| Key | `{keyPrefix}{stateName}` (default prefix `state:`, one flat key) | `{prefix}:{job}:cg:standalone:topic:standalone:p:0:state:standalone:{name}[:s:solo]` |
| ValueState layout | String (whole value in an RBucket) | Hash with a single field (field `s:solo`) |
| ListState layout | List (RList) | List (same structure, key renamed) |
| MapState layout | Hash (RMap, field = map key) | Hash (field = map key JSON) |
| SetState layout | Set (RSet) | Set (same structure, key renamed) |
| codec | Redisson default `JsonJacksonCodec` (values carry `@class` type info) | `StringCodec` + pure Jackson JSON (no type info; deserialized per the `StateDescriptor` type) |
| Constructor | `keyPrefix` (default `state:`) | `jobName` + `stateKeyPrefix` (default `streaming:runtime`) |
| No-value semantics | `value()` returns null | same; returns the defaultValue when the descriptor carries one |
| Index / metadata | none | stateKeys index + stateSchema registry (snapshot-able by the runtime checkpoint) |
| TTL | none | none (fixed 0 in the adapter) |

One-shot migration example (ValueState only; the old value must be read back into an object using the old codec's typed JSON):

```java
// old: RBucket on the old key (default JsonJacksonCodec, with @class)
MyType old = redisson.getBucket("state:my-state").get();
// new: the standard layout on the adapter's key
new RedisStateBackend(redisson, "my-job")
        .createValueState(new StateDescriptor<>("my-state", MyType.class))
        .update(old);
```

## Design Notes

### Serialization

- Value encoding is fixed at `StringCodec` + pure Jackson JSON (a module-internal `ObjectMapper`, no type info): writes go through `writeValueAsString`, reads deserialize per the `StateDescriptor` type. Serialization failures throw `RuntimeException("Failed to serialize/deserialize state value for ...")`.

### Concurrency and Atomicity

- Single-key reads/writes use Redisson's atomic commands for the corresponding structure (HGET/HSET, RPUSH, SADD/SREM).
- `ListState.update/addAll` guarantees atomicity of multi-step writes via `redisson.createBatch(BatchOptions.defaults().executionMode(REDIS_WRITE_ATOMIC))` (a mid-batch disconnect leaves neither a half-written nor an emptied state, and retries do not append twice).
- The adapter pins the thread-local current key/partition before each operation and clears it afterwards, so one state instance can safely be shared by concurrent threads (operations never cross keys).

### Relationship with the runtime checkpoint

- Adapter state lands in the job namespace's keyed layout and registers stateKeys; if that `jobName` matches a runtime job, its keys enter the same namespace (the adapter's `topic:standalone` identity differs from a real pipeline, so they normally do not collide). To share state across engines, explicitly reuse the same `jobName` + prefix combination and confirm the partition semantics yourself.

## Caveats

1. `value()` / `get()` return null when unset; there is no "throw to signal absence" path, and interface methods throw no checked exceptions.
2. `RedisStateBackend.close()` is a no-op; closing the Redisson client is the caller's responsibility.
3. `ListState.get()` returns a snapshot copy, `SetState.get()` returns a deserialized copy, and `MapState.entries()/keys()/values()` return snapshot copies (Redisson views are no longer passed through).
4. Keys are determined by `jobName` + prefix: multiple clients sharing one Redis, the same `jobName` and prefix read and write the same state — use `jobName` to separate them.

## Related Documentation

- [State-Unification-Design](State-Unification-Design.md) - the dual-engine state unification plan
- [Checkpoint module](checkpoint.md) - checkpointing and state recovery
- [Runtime module](runtime.md) - the runtime environment
- [Core API](Core.md) - state interface definitions
