# KTable Changelog Design (toStream as a Continuous Change Stream)

> Decision date: 2026-10-10. Corresponding todo: `RedisKTable.toStream(): from a static snapshot export to a continuous changelog`.
> Related: [Control-Plane-Design.md](Control-Plane-Design.md) (the same desired-state/event-stream thinking), [Table.md](Table.md).

## Background

`KTable.toStream()`'s original implementation (both engines) was a **static snapshot**: the `getState()` at call time materialized into a one-shot DataStream. In Flink semantics, `KTable.toStream()` emits a **stream of change events** (a changelog: INSERT/UPDATE/DELETE in application order), and consumers fold the events to obtain the table state. RedisKTable was therefore cut off from the KStream ecosystem: table updates could not flow into downstream stream pipelines.

## Decision

1. **Event transport: the MQ layer** (`MessageProducer` → `fromMqTopic`), with no bare RStream custom consumption loop. Reasons:
   - An MQ consumer group created for the first time replays the full history from `0-0` (the ensure-group Lua of `RedisMessageConsumer.subscribe` pins `ARGV[2]="0-0"`) — **full-history replay = complete state rebuild**, so the changelog semantics come for free;
   - Partitioning, leases, rebalancing, pending takeover, and checkpoint offsets all reuse existing infrastructure;
   - The `toStream()` consumer is just an ordinary MQ pipeline, composable with every runtime operator.
2. **Event format** (payload JSON): `{"op":"PUT","k":<keyJson>,"v":<valueJson>}` / `{"op":"DEL","k":<keyJson>}`. `k`/`v` are the raw JSON encodings of the table's primary store (the same encoding as the RMap key/value — double encoding: the outer JSON string value = the inner JSON text). Event `KeyValue<K,V>`: PUT → `(k, v)`; DEL → `(k, null)`.
3. **The switch: off by default, `withChangelog()` to enable explicitly**. Reason: every put paying an extra MQ append is write amplification — the wrong trade for query-style tables (config/dimension tables); changelog is for "tables that stream". With the switch off, `toStream()` keeps its static-snapshot behavior (unchanged; zero impact on existing callers).
4. **Emit is best-effort**: the primary store updates first; a failed event send (async callback exception / sync serialization exception) only WARNs, never throws — the same trade as the control plane's audit stream: a missing event leaves the consumer holding a stale value (converged by later events), and the main path is never blocked.
5. **Consumer group semantics and execution model**: the default group `table-changelog-group:<tableName>` (unicast within the group = multiple consumers share the load); the `toStream(env, group)` overload takes a custom group, and each group receives the full stream (cross-group broadcast). **Execution rides the caller's environment**: Redis-engine pipelines can only be started via `RedisStreamExecutionEnvironment.executeAsync()`, so changelog mode provides `toStream(env)` / `toStream(env, group)` overloads that attach the pipeline to the caller's environment, started by the caller's `executeAsync()` and lifecycle-managed through the returned `RedisJobClient`; therefore the no-arg `toStream()` throws `IllegalStateException` in changelog mode (a DataStream inside a hidden environment can never be started — the CI integration test empirically hit exactly this trap with a CCE), while the static-snapshot path keeps working with no arguments.
6. **clear()/delete() write no changelog**: compensating `clear` with per-key DEL events would cost a full-table read, and "emptied" should be explicitly recognizable in the event stream (introducing a CLEAR event would fork downstream fold logic); `delete` reclaims the table together with its lineage, and consumers should stop on their own. The javadoc notes: in streaming scenarios use per-key `put(k, null)` instead of `clear()`.
7. **InMemoryKTable is unchanged**: with no distributed state, a changelog is meaningless; the static snapshot is the correct semantics.

## Redis/MQ Layout

- Changelog topic: `table-changelog:<tableName>` (landed through the MQ's regular layout as `stream:topic:table-changelog:<tableName>:<partition>` etc.)
- Event order: the message key = `tableName` (the key of `send(topic, key, payload)` routes through `HashPartitioner`'s `key.hashCode()`) — all events of one table always land in one partition, a total order as long as the partition count is unchanged; partition expansion can reorder events across partitions (existing MQ-layer behavior, identical to ordinary topics, not a risk introduced by this design).

## Known Trade-offs / Non-Goals

- **DEL's value semantics**: a consumer receiving `(k, null)` means deletion; `KeyValue` allows a null value, and downstream `filter`s must handle it themselves (compared with Flink changelog's -D/+I markers there is no explicit op type — the event type is distinguishable from the null value, which suffices for this repo; if an explicit op is ever needed, the payload can evolve to carry an op field and the DataStream element type become an event wrapper — a breaking evolution, not done in this batch).
- **Snapshot+increment hybrid streams** (emit the current state, then follow the increments) are not done: it conflicts with "event stream" semantics; a consumer folding the full history obtains the state, no hybrid needed.
- **Changelog retention** follows the MQ layer's retention (`retentionMaxLenPerPartition`); no separate governance.
- **Derived tables** (mapValues/filter/join results) do not inherit the changelog: a derived table is rebuilt from its parent's events; dual-writing the event stream only adds cost; if ever needed, propagate the switch in `newDerivedChild`.

## Implementation Points

- The `table` module adds `implementation project(':mq')` (changelog events go through the MQ layer).
- `RedisKTable`: `withChangelog()`/`isChangelogEnabled()`/`changelogTopic()`/`defaultChangelogGroup()`/`toStream(env)`/`toStream(env, group)`; `put` dual-writes PUT/DEL events; `toStream(env)` attaches the pipeline `env.fromMqTopic(topic, group).map(parse)`, started by the caller's `env.executeAsync()`.

## Verification

- Unit tests: event JSON codec round-trips (PUT/DEL/unknown-op rejection/bad-payload wrapping), the full emit chain (a producer injection seam: PUT/DEL event content, failed futures swallowed, send throws swallowed), switch and environment semantics (with the switch off `toStream` takes the snapshot path; with changelog on, the no-arg `toStream` throws ISE; `toStream(env[, group])` argument validation; enabled lazily builds without touching Redis).
- Integration tests (real Redis): after put a→put b→put(a,null), `toStream(env)`+`executeAsync()` full-history replay asserts all events arrive and a's DEL lands after its PUT (cross-partition arrival order is asserted loosely — partition expansion may reorder); an appended put c then arrives as a new event; `toStream(envX, "grp-x")`/`toStream(envY, "grp-y")` each get their own full stream in their own environment (order-insensitive comparison).
