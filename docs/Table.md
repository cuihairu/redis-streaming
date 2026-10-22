# Table

Module: `table/`

## Module Responsibility

Stream-table duality: interpret a key-based update stream as the current state of a "table" (a materialized view). Each key has at most one value at any instant, and `value == null` deletes the key.

The module has 8 main source files: 3 interfaces (`KTable`, `KGroupedTable`, `TableAggregator`), 1 utility (`StreamTableConverter`), 4 implementations (`impl/InMemoryKTable`, `impl/RedisKTable`, `impl/InMemoryKGroupedTable`, `impl/RedisKGroupedTable`, the Redis grouped table being obtained via `groupBy`).

## Public Interface

### KTable&lt;K, V&gt; (`io.github.cuihairu.redis.streaming.table`)

```java
<VR> KTable<K, VR> mapValues(Function<V, VR> mapper);        // transform values
<VR> KTable<K, VR> mapValues(BiFunction<K, V, VR> mapper);   // transform values (with key)
KTable<K, V> filter(BiFunction<K, V, Boolean> predicate);    // filter rows
<VO, VR> KTable<K, VR> join(KTable<K, VO> other, BiFunction<V, VO, VR> joiner);      // inner join: rows without a counterpart are dropped
<VO, VR> KTable<K, VR> leftJoin(KTable<K, VO> other, BiFunction<V, VO, VR> joiner);  // left join: the left row is kept, the right value may be null
DataStream<KTable.KeyValue<K, V>> toStream();                       // export the current snapshot as a stream
<KR> KGroupedTable<KR, V> groupBy(Function<KeyValue<K, V>, KR> keySelector); // group by a new key
```

Nested type `KTable.KeyValue<K, V>` (`getKey()` / `getValue()`, static factory `KeyValue.of(key, value)`, default implementation `DefaultKeyValue`).

### KGroupedTable&lt;K, V&gt;

```java
<VR> KTable<K, VR> aggregate(Supplier<VR> initializer,
                             TableAggregator<K, V, VR> adder,
                             TableAggregator<K, V, VR> subtractor);
KTable<K, Long> count();
KTable<K, V> reduce(BiFunction<V, V, V> adder, BiFunction<V, V, V> subtractor);
```

`TableAggregator<K, V, VR>` is a functional interface: `VR apply(K key, V value, VR aggregate)` (folds one record into the current aggregate).

### StreamTableConverter (static utility)

```java
static <K, V> KTable<K, V> toTable(DataStream<KTable.KeyValue<K, V>> stream);
static <T, K, V> KTable<K, V> toTable(DataStream<T> stream, Function<T, K> keyExtractor, Function<T, V> valueExtractor);
static <T, K, V> TableBuilder<T, K, V> tableBuilder(Function<T, K> keyExtractor, Function<T, V> valueExtractor);
// TableBuilder.build(DataStream<T>) is equivalent to the three-arg toTable above
```

## The Two Implementations

| | `InMemoryKTable` | `RedisKTable` |
|---|---|---|
| Positioning | tests and simple use cases | production (state in a Redis Hash, JSON-serialized keys/values) |
| Construction | `new InMemoryKTable<>()` or with an initial `Map<K,V>` | `new RedisKTable<>(redissonClient, tableName, keyClass, valueClass)`; `tableName` is the Redis Hash key |
| Extra methods | `put` / `get` / `getState` / `size` / `clear` | `put` / `get` / `getState` / `size` / `clear` / `delete` (deletes the whole Hash) / `getTableName()` / `withChangelog()` / `isChangelogEnabled()` / `changelogTopic()` / `defaultChangelogGroup()` / `toStream(env[, group])` (changelog mode, next section) |
| `join`/`leftJoin` counterpart | only `InMemoryKTable` or `RedisKTable` (other types throw `UnsupportedOperationException`) | same |

Behavior shared by both implementations:

- `put(key, null)` deletes the key (the Redis version deletes the Hash field).
- `toStream()` is a **snapshot** export: at call time the current state is materialized into a one-shot collection stream (not a continuous changelog). With the Redis table's changelog mode enabled (`withChangelog()`), the no-arg `toStream()` throws `IllegalStateException` — a continuous change stream needs the caller's environment to start, so it must go through `toStream(env)` (next section).

## KTable Changelog (`RedisKTable` only, off by default)

With `withChangelog()` enabled, every `put` (including the delete `put(key, null)`) appends an `op=PUT/DEL` event to the MQ topic `table-changelog:<tableName>` in addition to updating the primary store; `toStream(env)` switches from a static snapshot to a **continuous change stream** (Flink `KTable.toStream` semantics): the pipeline `env.fromMqTopic(topic, group)` is attached to the caller's environment, started and lifecycle-managed by the caller's `env.executeAsync()`. Design details in [Table-Changelog-Design](Table-Changelog-Design.md).

- **Group semantics**: the default group `table-changelog-group:<tableName>` delivers unicast within the group (multiple consumers share the load); `toStream(env, group)` passes an explicit group, and each group receives the full stream (cross-group broadcast). Groups are created from 0-0; the first consumer replays all events and thereby rebuilds the table state.
- **Event order**: emits are chained on the call path, so events in the topic appear in `put` call order — an out-of-order full-history replay would rebuild wrong values.
- **Best-effort**: the primary store updates first; a failed event append only degrades the stream consumers (they keep the last known value) and never affects the `put` itself.
- **Off by default**: every put paying an extra MQ append is write amplification — the wrong trade for query-style tables (config/dimension tables); changelog is for "tables that stream". `isChangelogEnabled()` / `changelogTopic()` / `defaultChangelogGroup()` are for observability.
- The results of `mapValues` / `filter` / `join` / `groupBy` are **new tables**: the in-memory version returns a new in-memory table; the Redis version writes a derived table `<tableName>:<op>:<millis>-<uuid>` (e.g. `...:mapValues:...`, `...:join:...`; grouped results `<tableName>:groupBy:count:...` etc.); the original table is untouched.
- Rows whose `groupBy` keySelector returns `null` are skipped (both implementations).

## Semantic Boundaries (current implementation)

- `aggregate` / `count` / `reduce` are **one-shot recomputations** over the current snapshot: rows are folded group by group through the adder into a result table; the `subtractor` argument is never invoked in the current implementation (no retraction step).
- `reduce` adopts a group's first value directly, without calling the adder.

## Configuration

The module has no configuration-file keys. `RedisKTable`'s entire configuration is its constructor arguments: `redissonClient`, `tableName` (the Redis Hash key), `keyClass` / `valueClass` (Jackson deserialization target types).

## Usage Examples (prototypes can all be found in the module tests)

```java
// 1) in-memory table: put / null delete / get (InMemoryKTableTest)
InMemoryKTable<String, Integer> table = new InMemoryKTable<>();
table.put("a", 1);
table.put("b", 2);
table.put("a", null);          // deletes a
table.get("b");                // 2
table.size();                  // 1

// 2) stream -> table -> stream (StreamTableConverterTest)
StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
KTable<String, Integer> built = StreamTableConverter.toTable(
        env.fromElements(KTable.KeyValue.of("a", 1), KTable.KeyValue.of("a", 2),
                         KTable.KeyValue.of("b", 3), KTable.KeyValue.of("a", null)));
// (after casting to InMemoryKTable<String, Integer>) size() == 1; get("a") == null (null overwrote as delete), get("b") == 3

List<String> out = new ArrayList<>();
built.toStream()
        .map(kv -> kv.getKey() + "=" + kv.getValue())
        .addSink(out::add);

// 3) grouped aggregation (InMemoryKGroupedTableTest)
KGroupedTable<String, Integer> grouped = built.groupBy(kv -> kv.getKey().substring(0, 1));
KTable<String, Long> counts = grouped.count();
KTable<String, Integer> summed = grouped.aggregate(
        () -> 0,
        (key, value, agg) -> agg + value,
        (key, value, agg) -> agg);   // the subtractor is not invoked today

// 4) Redis table (RedisKTableTest / RedisKTableGroupingUpdateIntegrationTest)
RedisKTable<String, Integer> users = new RedisKTable<>(redissonClient, "users", String.class, Integer.class);
users.put("eu-1", 100);
var byRegion = users.groupBy(kv -> kv.getKey().startsWith("eu-") ? "EU" : "OTHER");
RedisKTable<String, Long> regionCounts = (RedisKTable<String, Long>) byRegion.count();
regionCounts.getTableName();     // like "users:groupBy:count:<millis>-<uuid>"

// 5) changelog: a continuous change stream (RedisKTableChangelog*Test / Table-Changelog-Design.md)
users.withChangelog();           // enable (idempotent); every put afterwards appends PUT/DEL events
users.put("eu-1", 200);          // primary store updated + PUT event into table-changelog:users
users.put("eu-1", null);         // DEL event

RedisStreamExecutionEnvironment env = new RedisStreamExecutionEnvironment(config);
users.toStream(env)              // continuous change stream (default group, load-shared within the group;
                                 // the first consumer replays the full history)
        .map(kv -> kv.getKey() + "=" + kv.getValue())
        .addSink(...);
env.executeAsync();              // started by the caller's environment

users.toStream(env, "audit-group"); // explicit group: each group gets the full history (cross-group broadcast)
```

## References

- [Architecture](Architecture.md)
- [Join](Join.md) - stream-stream joins (a separate API from this module's KTable table joins)
