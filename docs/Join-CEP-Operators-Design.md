# Join / CEP Operator Design

> Status: design finalized (Phase 1 shipped with its implementation). Source: architecture-review leftover item C — "`join`/`cep`: wrap as DataStream operators (currently pure in-memory utility classes, unable to join a pipeline); pick one semantics for join between the join and table modules".
> This page was written first and Phase 1's implementation followed it; the Phase 2/3 items are design-only until implemented.

## 1. Background and Problem

Before Phase 1, the `join` and `cep` modules were **caller-driven, pure in-memory utility libraries** (Phase 1 wrapped them as DataStream operators; usage in [Join.md](Join.md) / [CEP.md](CEP.md); the problem statement of the time is kept here):

- `join.StreamJoiner`: `processLeft/processRight` fed element by element, synchronously returning the pairs, buffers in an instance-local `ConcurrentHashMap`;
- `cep.PatternSequenceMatcher` / `PatternMatcher`: the caller called `process(event, timestamp)` inside its own message loop.

Before Phase 1 neither was **wired into the `DataStream` operator chain**: users could not write pipelines like `env.fromMqTopic(...).keyBy(...).<join|match>` and had to build their own consumption loops and handle partitioning and lifecycle themselves. Meanwhile both execution engines (the runtime's in-memory engine and the runtime's Redis engine) already provide a uniform stateful operator substrate:

```java
KeyedStream.process(KeyedProcessFunction<K, I, O>)   // implemented in both engines
```

where the Redis engine's `process` fully supports processing-time / event-time timers (`RedisStreamBuilder` wires `ctx.registerProcessingTimeTimer/registerEventTimeTimer` → the operator's `onProcessingTime/onEventTime`). **Becoming operators therefore requires no engine changes** — packing the utility logic into a `KeyedProcessFunction` runs on both engines.

## 2. Picking One Semantics: the Positioning of the DataStream-Side Join

The repo has two "joins":

| | `join` module | `table` module (KTable join) |
|---|---|---|
| Input model | two **event streams** + a time window | a **changelog table** (bounded, replayable) |
| Output | emit on every pairing (append) | a table view / changelog |
| State | dual buffering during the window | full key-value |

**Decision: the DataStream operator-layer join = stream-stream windowed join (`join` module semantics).** KTable joins keep the table semantics inside the table module; no stream-operator form is duplicated for them. Reasons:

1. The input models differ — two unbounded event streams vs a bounded changelog; forcing them together would maim one side;
2. KTable joins already have their own documentation and test coverage (`docs/Table.md`); the semantics stand on their own;
3. The stream runtime's actual white space is precisely the stream-stream join operator (Flink likewise ships both, with distinct semantics).

The boundary is written into both docs (`docs/Join.md`, `docs/Table.md`): "pairing stream with stream by time window" uses the join operator; "aligning table with table by key" uses KTable.

## 3. Join as an Operator

### 3.1 Input Model: Three Options

A stream-stream join is a two-input operator, while both engines' pipelines are single-source today (`RedisPipelineDefinition`: one topic, one group). Three options:

| Option | Shape | Engine change | Assessment |
|---|---|---|---|
| A. Engine-level dual source | `fromMqTopics(A, B)`; the runner consumes two topics/two groups in one loop with dual offset checkpoints | large (runner/checkpoint/recovery all need dual-sourcing) | the "correct" shape but heavy; coupled with dynamic scaling (a P4 item); deferred |
| B. Envelope multiplexing | each side `map`s into an `Envelope` (tag=left/right, joinKey, timestamp, payload) written into the **same** join input topic; `keyBy(joinKey).process(JoinOperator)` | **zero** (pure operators + user-level maps) | usable immediately, works on both engines; the same key is naturally routed to the same runner by the MQ partition hash; checkpoints reuse the single-topic offset semantics |
| C. `DataStream.join(other)` sugar | `a.join(b).where(...).equalTo(...).window(...).apply(...)` | expands at composition time into B (envelopes + bridging maps) | syntactic sugar, Phase 2 |

**Decision: Phase 1 lands B; Phase 2 lands C as B's sugar; A is designed together with P4 dynamic scaling.**

B's costs, stated: one extra MQ hop (both sides first write into the join input topic). For jobs already built on the Redis Streams MQ this is a homogeneous operation; throughput-critical deployments can revisit A after Phase 2; the envelope is a public API (a new type in the `join` module), tagged left/right and carrying the original event timestamp — the timestamp is extracted **at the envelope-building end** by `JoinConfig`'s extractors and travels with the envelope, so JoinOperator does no reflective time extraction from payloads.

### 3.2 `StreamJoinOperator<K, L, R, O>` (the Phase 1 core class, in the `join` module)

```java
// io.github.cuihairu.redis.streaming.join.operator
Envelope<K, L, R>          // the per-stream envelope: tag(LEFT/RIGHT), joinKey, timestamp, payload(L|R, one of them)
StreamJoinOperator.asKeyedProcessFunction(JoinConfig<L,R,K> cfg, JoinFunction<L,R,O> fn)
                           // yields KeyedProcessFunction<K, Envelope<K,L,R>, O>
```

Semantics (point-for-point aligned with the existing `StreamJoiner` where possible; its window-predicate logic is reusable):

- The instance keeps `leftBuffers / rightBuffers` per key (the same `Map<K, List<TimestampedElement>>` shape as `StreamJoiner`);
- The pairing predicate anchors on the **left element's timestamp** (following `StreamJoiner`'s corrected semantics: whichever side arrives first, the asymmetric windows `afterOnly/beforeOnly` decide identically);
- Outer joins (LEFT/RIGHT/FULL_OUTER) follow the B-36 semantics: an unmatched element **immediately** emits `join(elem, null)`; when the late counterpart hits within the window it **emits again** `join(L, R)` — downstream must tolerate the duplicate records per key (documented explicitly; not a waiting/retract implementation);
- State governance follows `JoinConfig.maxStateSize` (over-cap evicts element by element, oldest timestamp first) and `stateRetentionTime` (cleanup is **event-driven**: each pairing entry clears expired elements against the maximum timestamp across both buffers, point-for-point like `StreamJoiner.cleanup`; the operator registers no engine timers; a window span > retention is already rejected in `JoinConfig.validate()`);
- Relationship with `StreamJoiner`: `StreamJoiner` stays as-is (standalone scenarios and existing tests), and `StreamJoinOperator` **delegates to the same logic** for window predicates and eviction (extract a shared helper, or embed one copy of the implementation plus differential tests pinning behavioral equality), avoiding a third window judgment.

### 3.3 Consistency Boundary (upgraded to at-least-once recomputation from Phase 2)

- **Phase 1 (historical)**: operator buffers lived in the operator instance's memory and did not enter checkpoints — after failover the unpaired half was lost, and the join output under failover was a combination of at-most-once (lost halves) + at-least-once at the envelope layer.
- **Phase 2 (landed 2026-10-12)**: both buffers moved into keyed `ValueState` (one JSON document per key, read/written via `ctx.getState`, see §3.4), snapshotted and restored by the existing state/checkpoint machinery. Failover semantics upgrade to **at-least-once recomputation**: MQ replays plus state restoration resume pairing, and replayed envelopes may pair again — dedup is downstream idempotency's or the sink's job.
- Two **scope narrowings** relative to the raw joiner's instance-level accounting (both hygiene adjustments; the window predicate still guards correctness — retention/eviction are only memory management):
  - `maxStateSize` counts **per key** (both sides combined), no longer instance-wide across keys;
  - the retention cleanup's reference time is **that key's own latest timestamp**; an idle key's expired elements live until that key's next event (or are evicted by the cap) and are no longer swept away by other keys' activity.

### 3.4 State Layout (the join / cep operators, Phase 2)

- **Channel**: `ctx.getState(new StateDescriptor<>(stateName, String.class))` — `Context.getState` is the default method added in Phase 2 (throws `UnsupportedOperationException` by default; each engine overrides and wires it: the in-memory engine to `InMemoryKeyedStateStore`, the Redis engine to the keyed store). Under generic erasure, Redis JSON deserialization would degrade to `LinkedHashMap`, so the operators hold their own `ObjectMapper` and encode buffers as **JSON strings** in `ValueState<String>`.
- **Deterministic naming**: the Redis engine's state keys look like `prefix:jobName:cg:group:topic:p:pid:state:operatorId:stateName`, with operatorId generated deterministically (`streamId-keyBy-N`), so stateName must be fixed — `__operator:join:buffers` / `__operator:cep:partials` — for a restarted job to find the previous instance's documents (a random name would throw away the persistence). Two operators of the same kind cannot hang off the same keyed stream (`process()` returns a `DataStream`, not a `KeyedStream`), so a single fixed name has no conflicts.
- **The join document**: `JoinKeyBuffers{left[], right[]}`, elements self-describing JSON (`BufferedElement{type, json, timestamp}`, type recording the runtime class name) — each side decodable independently, regardless of which side's envelope arrived first.
- **The cep document**: `PersistedMatcherState<T>{partials[]}` (`PartialMatchState{startTimestamp, currentStep, eventsByStep}`).
- Mappers uniformly use `findAndRegisterModules()` + disable `WRITE_DATES_AS_TIMESTAMPS` (events may carry `Instant` and other java.time fields) + disable `FAIL_ON_UNKNOWN_PROPERTIES`; `jackson-datatype-jsr310` joins the join/cep modules as an `implementation` dependency.

## 4. CEP as an Operator

### 4.1 `PatternSequenceProcessFunction<K, T>` (in the `cep` module)

```java
// io.github.cuihairu.redis.streaming.cep.operator
new PatternSequenceProcessFunction<>(pattern, timestampExtractor)
                            // yields KeyedProcessFunction<K, T, EventSequence<T>>
// plus a 3-arg overload: adds maxTrackedKeys (0 = unbounded)
```

Semantics:

- The instance keeps a `PatternSequenceMatcher` per key (each key's matching state is independent, no cross-talk);
- `within(duration)` deadlines are handled by the matcher's own **event-driven cleanup** — the `process(event, timestamp)` entry first drops expired partials by the event timestamp (a timed-out partial = no match), and the operator registers no engine timers; an idle key's leftover partials are cleaned when that key's next event arrives, or evicted by the key cap;
- The key cap (`maxTrackedKeys`, default 10000, 0 = unbounded): evicts the least-recently-active key's matcher, preventing unbounded growth of the matcher map under high-cardinality keys;
- Output reuses `cep.EventSequence<T>`;
- Single-condition matching (`Pattern` / `PatternMatcher`) does not become an operator: needs without sequence state are served by `filter`, and bounded extension sequences are deferred to future requirements — no speculative API.

### 4.2 State and Recovery (in keyed state from Phase 2)

**Phase 1 (historical)**: partial-match state lived in the operator instance's memory, outside checkpoints; failover lost unfinished sequences (lighter than join's consequence — what is lost are unfinished sequences, producing no wrong output, only misses).

**Phase 2 (landed 2026-10-12)**: a **write-through cache** structure —

- The in-instance matcher map remains the hot cache (one `PatternSequenceMatcher` per key); after each event is processed the key's keyed `ValueState<String>` is **written through** (`clear()` when there are no partials);
- On a cache miss, **hydrate first**: deserialize that key's partial list from the keyed state back into the matcher;
- Eviction tombstones (`evictedKeys`): keys evicted by `maxTrackedKeys` must **not** be resurrected from state with stale partials within this instance's lifetime (preventing evicted old state from mismatching new events).

**Why snapshots restore exactly**: a matcher's partial step numbers only ever increase, so the flat event order equals the order of concatenating the per-step sequences in ascending step order — `PatternSequenceMatcher.exportPartialMatches()` exports (step-ascending deep copies), `restorePartialMatches()` replays `addEvent` in that order, and events and timestamps are restored one by one (pinned by `PatternSequenceMatcherStateExportTest`).

## 5. Phased Delivery

| Phase | Content | Status |
|---|---|---|
| Phase 1 | `Envelope` + `StreamJoinOperator` + `PatternSequenceProcessFunction`; integration tests on both the in-memory and Redis engines; "operator form" sections added to `docs/Join.md` / `docs/CEP.md`; this document written | shipped in its batch |
| Phase 2 | the `DataStream.join(other)` sugar (expanding at composition time into envelopes + bridging); both operators' buffers move into keyed `ValueState` inside checkpoints; evaluate event-time join windows once the `TimestampAssigner` overload lands | **the buffer migration shipped in its batch** (2026-10-12, blocker A cleared, see §5.1); the sugar landed on the in-memory side (2026-10-14, see §5.2); the Redis side's U3 three-stage orchestration also landed (2026-10-14, at `RedisStreamExecutionEnvironment.join`, see docs/Union-DualSource-Design.md §5), clearing blocker B |
| Phase 3 | engine-level dual-source pipelines (Approach A), event-time watermark-aligned windows, retract/waiting semantics (eliminating the outer join's second emission) | **landed**: `fromMqTopics` dual-source reads with per-source aligned watermarks (781485d, 8538f14); `emitUnmatchedImmediately=false` waiting/closing semantics (5dc27c2); join's 172 unit tests + runtime's 918 unit tests green |

### 5.1 Blocker A: the Context state-access surface — cleared (2026-10-12)

The original blocker: `KeyedProcessFunction.Context` had only clock+timer methods and no `getState(...)` channel, so neither engine could hand a keyed-state handle to an operator.

How it was cleared (that batch):

- `core`: `Context.getState(StateDescriptor)` added as a **default method** that throws `UnsupportedOperationException` by default (preserving binary compatibility for existing self-implemented Contexts), with a contract test pinned;
- `runtime`: the in-memory engine's `ContextImpl` wired to `InMemoryKeyedStateStore`, the Redis engine's anonymous Context wired to the keyed store's `getValueState`;
- `join` / `cep`: both operators' buffers moved to keyed `ValueState<String>` (self-managed JSON codecs, see §3.4), upgrading failover semantics (§3.3/§4.2);
- Proof: module-level tests simulate a restart with "the same store + brand-new function instances" — join pairings and cep partials both resume from the previous instance's state.

### 5.2 Blocker B: the `DataStream.join(other)` sugar — cleared (2026-10-14; both the in-memory side and the Redis side's U3 landed)

The design expands at composition time into envelopes + bridging, which requires merging the two sides into one join input (an MQ topic or an in-engine union) — the `DataStream.union(...)` dual-source merge primitive has landed (a default method establishing the contract + the in-memory engine's round-robin implementation, 2026-10-14); the in-memory engine has no topic substrate, and the design itself placed "engine-level dual-source pipelines (Approach A)" in Phase 3.

**How it was cleared (in-memory side, 2026-10-14, following `docs/Union-DualSource-Design.md`'s recommended path)**:

- `DataStream.union(DataStream<? extends T>)` default method establishes the contract (the Redis engine's default throws unsupported and points to the join-topic mode); the in-memory engine implements round-robin interleaving (11 tests);
- `StreamJoinOperator.join(left, right, config, fn)` static factory (join module): envelope maps + union + keyBy on the envelope key + the operator, pinned by differential comparison against the hand-written envelope path (7 tests) — the key is the partitioning key, so "envelope key disagreeing with the keyBy selector silently missing" is structurally impossible on the sugar path.

**The Redis side's U3 landed (2026-10-14)**: the gap that a static factory cannot reach the execution environment (`RedisStreamBuilder.env` is private) was closed by landing it as an instance method — `RedisStreamExecutionEnvironment.join(joinTopic, left, right, config, fn)` registers the left/right "land envelopes into the join topic" pipelines and the merged-consumption pipeline (the three-way proxy decision and rejected options in `docs/Union-DualSource-Design.md` §5). The join topic's lifecycle rules were fixed with that decision: the topic name is given explicitly by the user (charset `[A-Za-z0-9_.-]+`, feeding the stable source id `join-<topic>`), the consumer group derives as `<topic>-join`, trim/TTL follows the MQ's existing stream retention governance (the sugar never deletes topics on its own), and a second call with the same joinTopic is rejected by the stable-source-id duplicate-registration guard (fail-fast). The dual-engine differential comparison is pinned (`RedisEnvJoinSugarTest#redisJoinSugarMatchesTheHandWrittenEnvelopeExpansion`).

**Not-implemented list** (on record):
- ~~`DataStream.union(...)` (or an equivalent merge primitive) on both engines~~ (2026-10-14: landed on the in-memory side; the Redis side follows the design's join-topic mode, i.e. U3, with no separate engine-level implementation);
- ~~`DataStream.join(other)` sugar expanding into envelope maps + merge + keyBy + `StreamJoinOperator`~~ (2026-10-14: the in-memory side landed as `StreamJoinOperator.join`; the Redis side as the `RedisStreamExecutionEnvironment.join` three-stage orchestration; pinned by the dual-engine differential comparison);
- event-time join window evaluation (depends on the `TimestampAssigner` overload landing).

## 6. Impact and Compatibility

- The `join` / `cep` modules' existing public APIs are **untouched** (`StreamJoiner` / `PatternSequenceMatcher` etc. stay as-is; `PatternSequenceMatcher` gains one export/restore pair, purely additive); all new types live in each module's new `operator` subpackage;
- The Phase 1 operator implementations add no module dependencies; **Phase 2** adds `implementation libs.jackson.jsr310` to both modules (the state JSON must serialize java.time fields), `core` gains the `Context.getState` default method, and `runtime` gets the two wirings;
- Existing self-implemented `KeyedProcessFunction.Context`s are unaffected (the default method throws unsupported; the contract is unchanged);
- The window-predicate differential tests against `StreamJoiner` stay pinned; new state-surface unit tests (buffers/partials resuming across instances, retention/cap behavior, JSON round-trips) + dual-engine integration tests (the Redis leg gated on reachability).
