# Join

Module: `join/`

## Module Responsibility

Time-window-based stream-stream join. `StreamJoiner` pairs elements from a left and a right stream (fed element by element) by join key and time window, emitting the result of a `JoinFunction`.

Positioning: a pure in-memory, single-instance implementation (the class javadoc describes itself as "for testing and simple use cases"). State lives in an in-process `ConcurrentHashMap`, is not persisted to Redis, does not support multi-instance distributed joins, and does not consume watermarks.

The module has 6 main source files: `JoinConfig`, `JoinWindow`, `JoinType`, `JoinFunction`, `JoinedElement`, `StreamJoiner`.

## Public Interface

### StreamJoiner&lt;L, R, K, O&gt;

```java
public StreamJoiner(JoinConfig<L, R, K> config, JoinFunction<L, R, O> joinFunction) // validates config at construction

synchronized List<O> processLeft(L element) throws Exception;   // feed a left element, return the outputs produced now
synchronized List<O> processRight(R element) throws Exception;
synchronized int getLeftBufferSize();     // current number of buffered left elements
synchronized int getRightBufferSize();
synchronized void clear();                // clear both buffers
```

### JoinConfig&lt;L, R, K&gt; (Lombok `@Data` + `@Builder`, serializable)

| Field | Type | Default | Notes |
|---|---|---|---|
| `joinType` | `JoinType` | required | `INNER` / `LEFT` / `RIGHT` / `FULL_OUTER` |
| `joinWindow` | `JoinWindow` | required | the time window |
| `leftKeySelector` | `Function<L, K>` | required | extracts the left key |
| `rightKeySelector` | `Function<R, K>` | required | extracts the right key |
| `leftTimestampExtractor` | `Function<L, Long>` | `null` (falls back to `System.currentTimeMillis()`) | extracts the left timestamp |
| `rightTimestampExtractor` | `Function<R, Long>` | same as above | extracts the right timestamp |
| `maxStateSize` | `int` | `10000` | total buffer cap across both sides; beyond it, elements are evicted oldest-timestamp-first |
| `stateRetentionTime` | `long` (ms) | `3600000` (1 hour) | how long buffers are retained |

`validate()` checks: the four required fields are non-null; `maxStateSize > 0`; `stateRetentionTime > 0`; and `stateRetentionTime >= before + after` (the window span) — a retention shorter than the window would evict buffered elements still inside the window.

There are only two convenience factories: `JoinConfig.innerJoin(leftKey, rightKey, window)` and `JoinConfig.leftJoin(leftKey, rightKey, window)`; build `RIGHT` / `FULL_OUTER` with the builder.

### JoinWindow

```java
static JoinWindow of(Duration before, Duration after);  // asymmetric
static JoinWindow ofSize(Duration size);                // symmetric: before = after = size
static JoinWindow afterOnly(Duration after);            // look forward only
static JoinWindow beforeOnly(Duration before);          // look backward only
boolean contains(long referenceTimestamp, long candidateTimestamp);
long getBeforeMillis();
long getAfterMillis();
```

Match condition: `candidateTs - referenceTs ∈ [-before, +after]`. In the join, reference is always the **left** element's timestamp and candidate the right element's timestamp; both sides are judged by the same predicate, independent of arrival order (see the `StreamJoiner` implementation comments).

### JoinFunction&lt;L, R, O&gt; / JoinedElement&lt;L, R&gt;

```java
@FunctionalInterface
public interface JoinFunction<L, R, O> extends Serializable {
    O join(L left, R right) throws Exception;          // left or right may be null (outer joins)
    static <L, R, O> JoinFunction<L, R, O> of(BiFunction<L, R, O> function); // wrap a BiFunction
}
```

`JoinedElement<L, R>` is an optional result value object (`getLeft()` / `getRight()` / `getTimestamp()`, `isLeftOnly()` / `isRightOnly()` / `isBothPresent()`, static factories `of` / `leftOnly` / `rightOnly`). Note that `StreamJoiner` does **not** produce `JoinedElement` automatically: it emits the result of `joinFunction.join(left, right)` directly; whether to wrap it in a `JoinedElement` is up to your JoinFunction.

## Output Behavior of the Four JoinTypes

| Type | Match found | Element unmatched |
|---|---|---|
| `INNER` | emits `join(L, R)` | nothing |
| `LEFT` | emits `join(L, R)` | `processLeft` emits `join(L, null)` immediately |
| `RIGHT` | emits `join(L, R)` | `processRight` emits `join(null, R)` immediately |
| `FULL_OUTER` | emits `join(L, R)` | each side immediately emits `join(L, null)` / `join(null, R)` |

Important outer-join semantics (source comment B-36): unmatched elements are emitted **immediately** — no watermark barrier, no waiting period, no retractions. If the counterpart arrives later (still within the window), the pair is emitted again as a second record — downstream sees the same element first as "unmatched" then as "matched", and must tolerate or deduplicate. Wait-then-decide / retract-style implementations are out of scope for this joiner.

## Memory and Cleanup Behavior

- `maxStateSize`: the total element count is checked after every buffer insertion; when exceeded, elements are evicted one by one starting from the oldest timestamp across both buffers.
- `stateRetentionTime`: cleaned at the end of every `processLeft`/`processRight`; the baseline is the **maximum element timestamp** seen in either buffer (not wall-clock time, which makes testing with arbitrary historical timestamps easy); elements older than `maxTs - stateRetentionTime` are removed.
- A null join key throws `IllegalArgumentException` (the message says whether the left or the right selector produced it).
- When timestamps are absent, `System.currentTimeMillis()` is used — pairing then happens purely by arrival time.

## Usage Examples

### Factory + simple strings (verifiable with `JoinConfigConvenienceTest` / `StreamJoinerTest`)

```java
var cfg = JoinConfig.innerJoin(
        (String s) -> s,                       // left key
        (String s) -> s,                       // right key
        JoinWindow.ofSize(java.time.Duration.ofSeconds(5)));

StreamJoiner<String, String, String, String> joiner =
        new StreamJoiner<>(cfg, (l, r) -> l + "+" + r);

joiner.processRight("k1");
var out = joiner.processLeft("k1");            // ["k1+k1"]
```

### Full builder (the Order/User prototype from StreamJoinerTest)

```java
JoinConfig<Order, User, String> config = JoinConfig.<Order, User, String>builder()
        .joinType(JoinType.INNER)
        .joinWindow(JoinWindow.ofSize(Duration.ofSeconds(10)))
        .leftKeySelector(Order::getUserId)
        .rightKeySelector(User::getUserId)
        .leftTimestampExtractor(Order::getTimestamp)
        .rightTimestampExtractor(User::getTimestamp)
        .maxStateSize(1000)                    // optional, default 10000
        .stateRetentionTime(Duration.ofMinutes(30).toMillis()) // optional, default 1 hour
        .build();

StreamJoiner<Order, User, String, EnrichedOrder> joiner =
        new StreamJoiner<>(config, (order, user) ->
                new EnrichedOrder(order.getOrderId(), user.getName(), order.getAmount()));

List<EnrichedOrder> r1 = joiner.processRight(user);   // no matching left element -> [] (INNER)
List<EnrichedOrder> r2 = joiner.processLeft(order);   // paired within the window -> 1 record
```

### Outer joins and a dedup hint

```java
JoinConfig<String, Integer, String> cfg = JoinConfig.<String, Integer, String>builder()
        .joinType(JoinType.FULL_OUTER)
        .joinWindow(JoinWindow.ofSize(Duration.ofMinutes(1)))
        .leftKeySelector(s -> s)
        .rightKeySelector(i -> String.valueOf(i))
        .build();

StreamJoiner<String, Integer, String, String> joiner =
        new StreamJoiner<>(cfg, (l, r) -> l + "|" + r);
// The first unmatched element is emitted immediately as "x|null"; when its counterpart 1 arrives
// later within the window, the key emits another record "x|1" — downstream must tolerate or
// deduplicate (see the outer-join semantics above).
```

## Operator Form: Running the Join in a DataStream Pipeline

`StreamJoinOperator` wraps the join semantics above into a `KeyedProcessFunction`; the two streams enter a single keyed pipeline via **envelope multiplexing** (design details and the phased roadmap in [Join/CEP Operator Design](Join-CEP-Operators-Design.md)):

```java
// Map each of the two sources into an Envelope (joinKey/timestamp travel with the envelope and are
// the authoritative values on the operator path), then merge into one join input stream:
// the Redis engine lands envelopes on a join input topic (each envelope written once),
// the in-memory engine interleaves them directly with DataStream.union, then:
env.fromMqTopic(joinInputTopic, "join-group")
    .map(m -> parseEnvelope(String.valueOf(m.getPayload())))
    .keyBy(Envelope::getJoinKey)
    .process(StreamJoinOperator.asKeyedProcessFunction(config, (l, r) -> l + "+" + r))
    .addSink(out);
```

- Semantics are point-for-point identical with `StreamJoiner` (pinned by differential tests): the left-anchored window predicate, outer joins emitting immediately + backfilling pairs on late arrival, retention/maxStateSize eviction;
- The in-memory engine merges streams with `DataStream.union` (round-robin interleaving; intra-source order preserved, cross-source order undefined, reusable per consumption) with no relay topic; the Redis engine still goes through the join input topic (cross-engine merging: [dual-source union design](Union-DualSource-Design.md));

### Syntactic Sugar: Joining Two Streams Directly

Two mergeable `DataStream`s do not require a hand-written envelope map / keyBy — `StreamJoinOperator.join` builds envelopes from the `JoinConfig`'s own key selectors and timestamp extractors, merges with `union`, and keys on the envelope's own join key (the envelope key is the partitioning key, so the manual path's pitfall of "key selector disagreeing with the envelope key causing silent misses" is structurally impossible on the sugar path):

```java
JoinConfig<Order, User, String> config = JoinConfig.<Order, User, String>builder()
        .joinType(JoinType.INNER)
        .joinWindow(JoinWindow.ofSize(Duration.ofSeconds(30)))
        .leftKeySelector(o -> o.getUserId())
        .rightKeySelector(u -> u.getUserId())
        .leftTimestampExtractor(Order::getEventTime)   // falls back to system time, same as StreamJoiner
        .rightTimestampExtractor(User::getEventTime)
        .build();

StreamJoinOperator.join(orders, users, config, (order, user) -> new EnrichedOrder(order, user))
        .addSink(out);
```

The sugar and the manual envelope path produce identical output (pinned by differential tests); the sugar only covers the case where both sides are mergeable `DataStream`s; cross-engine (two independent Redis pipelines) uses the `env.join` orchestration below.

- The envelope key must agree with the `JoinConfig` selector — disagreement does not error; it just routes pairs into mutually invisible partitions and **silently misses**;
- **From Phase 2 (2026-10-12) buffers live in keyed state**: one JSON document per key (self-describing elements, decodable across restarts), read/written via `ctx.getState` and restored with the engine's checkpoint snapshots — after failover the unpaired half is no longer lost, and semantics upgrade to **at-least-once recomputation** (envelopes replayed by MQ may pair again; dedup is downstream's job); `maxStateSize` and retention narrow from instance-level to **per-key** (the window predicate still guards correctness; see the design doc §3.3/§3.4).

### Three Steps to Wire It Up

Joining two mergeable `DataStream`s into one join output takes three steps (a complete runnable example at `examples/.../join/StreamJoinSugarExample.java`, in-memory engine, no Redis needed):

1. **Build a `JoinConfig`**: `joinType` + `joinWindow` + left/right key selectors + left/right timestamp extractors (extractors optional, falling back to system time);
2. **Call the sugar**: `StreamJoinOperator.join(left, right, config, (l, r) -> ...)` returns a `DataStream<O>` that accepts `map`/`filter`/`addSink` like any stream;
3. **Run**: the in-memory engine consumes with `addSink` directly; the Redis engine hands the returned stream to the `RedisStreamBuilder` for execution.

```java
// 1) configure
JoinConfig<Order, User, String> config = JoinConfig.<Order, User, String>builder()
        .joinType(JoinType.LEFT)
        .joinWindow(JoinWindow.ofSize(Duration.ofSeconds(30)))
        .leftKeySelector(Order::getUserId)
        .rightKeySelector(User::getUserId)
        .leftTimestampExtractor(Order::getEventTime)
        .rightTimestampExtractor(User::getEventTime)
        .build();

// 2) sugar: one call does envelope + merge + keyBy + operator
DataStream<String> joined = StreamJoinOperator.join(orders, users, config,
        (order, user) -> user == null
                ? order.getOrderId() + "|unmatched"
                : order.getOrderId() + "|" + user.getName() + "|" + order.getAmount());

// 3) consume
joined.addSink(System.out::println);
```

Outer-join semantics: when a left element arrives first it emits immediately with a `null` right half; when the right element arrives within the window the paired result is emitted once more (pinned by `StreamJoinerTest.testLeftJoin`); `JoinType.INNER` keeps only successfully paired elements.

### Redis Engine: the `env.join` Three-Stage Orchestration

In the Redis engine, two independent pipelines cannot merge directly; `RedisStreamExecutionEnvironment.join` expands the sugar into the U3 three-stage orchestration (design in [dual-source union design](Union-DualSource-Design.md) §3 option U3): each side starts a "land envelopes" pipeline that packs records into `Envelope` JSON and writes them to the join topic (the join key doubles as the MQ key, ordering preserved within a partition of the same key), and a merged-consumption pipeline (parse → `keyBy` → join operator) reading from that topic carries the join semantics:

```java
RedisStreamExecutionEnvironment env = new RedisStreamExecutionEnvironment(redisson, config, mqFactory);
DataStream<EnrichedOrder> joined = env.join(
        "order-user-join",                       // join topic: user-supplied, must be stable across restarts
        env.fromMqTopic("orders", "orders-group"),
        env.fromMqTopic("users", "users-group"),
        config,
        (order, user) -> new EnrichedOrder(order, user));

joined.addSink(...);
env.executeAsync("order-user-join-job");
```

Conventions and guards:

- **The joinTopic charset is `[A-Za-z0-9_.-]+`**: it feeds the stable source id `join-<topic>` and consumer group `<topic>-join`, invariant across restarts (keyed state keys are therefore stable);
- **The sugar does not govern topic lifecycle**: trimming/TTL goes through MQ's stream retention governance; a second call with the same joinTopic is rejected by the stable-source-id duplicate-registration guard (fail-fast);
- **Envelopes travel cross-topic as self-describing JSON** (side + key + timestamp + payload each carrying a type name); the codec contract matches the join operator's keyed-state serialization, so payload types survive restarts;
- Missing timestamps fall back to system time (consistent with the in-memory sugar and `StreamJoiner`); event-time watermark alignment remains Phase 3.

## References

- [Architecture](Architecture.md)
- [Table](Table.md) - KTable joins (`KTable.join`/`leftJoin`; a separate API from this module)
