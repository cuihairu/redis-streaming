# State Backend Unification Design (the state module ↔ the runtime keyed store) and Watermark Logic Convergence

This page is for review and contains no code changes. Goal: the repo has two incompatible Redis state implementations and three diverging watermark computations; converge them into one standard.

## Current State (fact inventory)

### State: two implementations

| Dimension | the runtime's `RedisKeyedStateStore` | the state module's `RedisStateBackend` |
| --- | --- | --- |
| Key layout | `{stateKeyPrefix}:{job}:cg:{group}:topic:{topic}:p:{pid}:state:{operatorId}:{stateName}[:shard:N]` (fully keyed by job/group/topic/partition/operator) | the flat key `{keyPrefix}{descriptor.getName()}` (default prefix `state:`, **no job/partition dimension**) |
| codec | StringCodec + JSON | a codec per `Class` type (binary) |
| State kinds | ValueState only | the four: Value/Map/List/Set |
| Extra capabilities | TTL, schema registration (`:{job}:stateSchema`), the stateKeys index (`:{job}:stateKeys`, enumerated for checkpoints), hot-key observation | none |
| Dependency | the checkpoint module depends on its stateKeys index for restore enumeration — **it has a downstream contract** | no production-side consumers (outside examples/docs) |

### Watermarks: three logics

1. the runtime's `internal/WatermarkState`: a monotonic watermark state container (`volatile long` + an `idle` flag, only governing "never decreases").
2. the runtime's `RedisPipelineRunner.candidateFor`: `maxEventTimeMs - outOfOrdernessMs` (guarded by `!= Long.MIN_VALUE`), updated per record.
3. the watermark module's `BoundedOutOfOrdernessWatermarkGenerator`: `maxTimestamp - maxOutOfOrderness - 1` (Flink semantics: a watermark `w` means "no more events with timestamp ≤ w"), initialized at `maxTimestamp = MIN_VALUE + ooo + 1`.

**2 and 3 differ semantically**: the runtime is missing the −1 (its watermark equals exactly `max - ooo`, so whether a boundary event counts as "late" depends on the downstream comparison), and the initial values/guards are handled separately. The same configuration produces different boundary behavior in the two places.

## Problems

- State produced through the user-facing API (the state module's `StateDescriptor`) and the keyed state actually used by the runtime engines **cannot see each other**: both key layout and codec differ — data written by one is unreadable by the other.
- The flat key has no job dimension: two jobs in one process using same-named state cross-contaminate (the same class of problem as `StreamKeys`' static global; see the multi-tenancy design).
- No TTL: state-module state never expires; the runtime side has TTL but only for ValueState.
- The watermark `-1` divergence is a hidden semantic-bug source: fix one side without the other and downstream window-trigger boundary behavior drifts.

## Candidate Approaches

### Approach A: the runtime keyed store becomes the standard implementation; the state module retreats to an API surface + adapter (recommended)

Reason: the runtime store is depended upon by the checkpoint restore contract (the stateKeys index), has TTL/schema/hot-key governance, and its keys carry the full dimensions — it is the "living" standard; the state module's `StateBackend/StateDescriptor` API shape is good (four state kinds, typed descriptors) and fits as the user surface.

- **Keep**: the state module's APIs (`StateBackend`, `StateDescriptor`, the four state interfaces) unchanged.
- **Replace the implementation**: `RedisStateBackend` becomes a thin adapter — key generation delegates to the keyed store's layout function (the job dimension mandatory), and the codec unifies on StringCodec+JSON; Map/List/Set are completed on the keyed store's primitives (Map→Hash per key, List→List per key, Set→Set per key, keyed into the `{...}:state:{operatorId}:{name}` layout with the stateKeys index registered in step).
- **TTL/schema alignment**: all four state kinds go through TTL and schema registration (schema is meaningful for Value/complex types; Map/List/Set register their element type).
- **Compatibility**: the key-layout and codec changes are breaking — major version + a migration guide (old data has no production consumers; low risk).

### Approach B: keep both tracks, draw the boundary in docs only ("state module = standalone use cases; runtime store = engine internals")

Zero change, but two codecs/layouts coexist forever, and checkpoint↔user-state interop remains a permanent pit. **Not recommended** as the end state; acceptable as v0's temporary documentation stance.

### Approach C: reverse unification (the state module's implementation becomes the standard; the runtime migrates over)

Infeasible: the checkpoint stateKeys contract, per-partition keying, and TTL/hot keys would all need redoing — a far bigger blast radius than Approach A.

## Watermark Convergence (an independent small item, done first; landed 2026-10-11)

Converge "the bounded-out-of-orderness computation" to one place in the watermark module:

- `BoundedOutOfOrdernessWatermarkGenerator`'s `max - ooo - 1` is the Flink-standard semantics — **it is authoritative**.
- `RedisPipelineRunner.candidateFor` reuses the same computation (extract a shared static function, or embed a generator instance directly), eliminating the `-1` divergence and the initial-value difference.
- `WatermarkState` stays (it is the "monotonic + idle" container, orthogonal to the computation).
- The boundary semantic change (`-1`) must be flagged in the docs: watermark `w` now means "events ≤ w will not arrive".

Landing record: the formula converged into the static `BoundedOutOfOrdernessWatermarkGenerator.watermarkFor(maxEventTimeMs, outOfOrdernessMillis)` (saturating underflow to `MIN_VALUE`); both `onPeriodicEmit` and `RedisPipelineRunner.candidateFor` go through it; the runtime main source set adds `implementation project(':watermark')`. The 1ms-earlier boundary semantics is flagged in `docs/watermark.md`, with tests pinning the new boundary: `RedisPipelineRunnerWatermarkTest` (994/1194), `RedisPipelineRunnerGapClosureTest` (94_999/99_999 + the `MIN_VALUE` guard), `RedisPipelineRunnerIdleFlushTest` (`t-1`), `BoundedOutOfOrdernessWatermarkGeneratorTest` (direct tests of the formula and saturation).

## Migration Path (Approach A, phased)

1. **Step 1 (minimal, landed 2026-10-11)**: the watermark convergence (the independent small item above); purely runtime-internal + the watermark module, no external compatibility impact.
2. **Step 2 (landed 2026-10-11)**: the keyed store gains the Map/List/Set state kinds + schema registration; add unit tests (including checkpoint enumeration of the new state kinds).

   Landing record: `RedisKeyedStateStore` gains `getMapState(name, keyType, valueType)`, `getListState(StateDescriptor)`, `getSetState(StateDescriptor)` — `RedisKeyedMapState/RedisKeyedListState/RedisKeyedSetState` (over the core `MapState/ListState/SetState` interfaces), laid out as one Redis Hash/List/Set per current key, key = the keyed state layout + `:{serialized current key}` (no sharding — each key's structure is itself the fan-out unit), fields/elements JSON-serialized; the `stateKeys` index registration + TTL inheritance (Map goes through `touch` gaining TTL + hot keys + capacity sampling; List/Set slide their EXPIRE on access, and hot-key detection does not apply since it works per Hash field); Map/List/Set schemas register by value/element type (`ensureSchemaForKey`). The checkpoint side's `RedisStateType` gains `LIST/SET`: `snapshotState` enumerates `RType.LIST/SET`, restore follows the same stage-then-rename flow and applies TTL. Tests: the three GapClosure unit tests (30 cases) + `RedisKeyedCollectionStatesIntegrationTest` (layout/index/TTL/clear + checkpoint LIST/SET snapshot restore and drift cleanup).
3. **Step 3 (major, landed 2026-10-12)**: `RedisStateBackend` becomes an adapter delegating to the keyed store; delete the `RedisValueState/RedisMapState/RedisListState/RedisSetState` old implementations; the migration doc gets the key/codec comparison table.

   Landing record: `RedisStateBackend` rewritten as a thin adapter over the keyed store — the constructor changed from `(redisson, keyPrefix)` to `(redisson, jobName[, stateKeyPrefix])` (default prefix `streaming:runtime`); internally it holds a store with a solo identity (`topic/consumerGroup/operatorId = standalone`, partition 0, no TTL, no sharding, no hot-key governance), pinning the thread-local current key to `solo` around every operation, and all four state kinds land in the standard keyed layout (`{prefix}:{job}:cg:standalone:topic:standalone:p:0:state:standalone:{name}[:s:solo]`, StringCodec + pure JSON, without the Redisson default codec's `@class` type info), registering the `stateKeys` index and `stateSchema` as usual (the FAIL policy fixed) — the checkpoint snapshot machinery works on adapter state directly. Keys/codec are incompatible with the old implementation (by design; old data has no production consumers); the comparison table and the "read old, write new" migration example are in `docs/state.md`'s "migration table". Companion changes: the state module adds `implementation project(':runtime')` (acyclic — the runtime main sources do not depend on state); the same batch aligns the keyed list's B-38 atomicity (`update`/`addAll` become a single `REDIS_WRITE_ATOMIC` batch — see POTENTIAL_BUGS B-38's addendum). Tests: `RedisStateBackendTest` rewritten as a mock version (pinning the solo layout/codec/index registration/delegation, 9 cases), `StateIntegrationTest` rewritten (real Redis, 7 cases including stateKeys/schema registration), `StateGrandStormTest` re-anchored onto `RedisStateBackend`, examples' `StateExample(+Test)` migrated; 4 old implementations and 5 old tests deleted.

## Non-Goals

- No new backend abstractions (RocksDB/heap etc.); the `StateBackend` interface already carries the need.
- No change to the checkpoint protocol or the stateKeys index format.
- No state-migration tool inside this design (old-key data volumes are small; the docs give rebuild guidance).
