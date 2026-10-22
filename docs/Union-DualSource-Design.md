# Union / Dual-Source Merge Primitive Design (Blocker B record, 2026-10-12)

Status: **design page; the recommended path's step 1 (the union primitive) and step 2 (the sugar on both sides) have both landed 2026-10-14** (the Redis side's U3 three-stage orchestration landed at `RedisStreamExecutionEnvironment.join`, see §5). This page answers `docs/Join-CEP-Operators-Design.md` §5.2's blocker B — the `DataStream.join(other)` sugar needs the two sides merged into one input first, and the `DataStream` API has no merge primitive. It fixes the candidate approaches, the trade-offs, the recommended path, and the not-yet-implemented list.

## 1. Problem

- Every `DataStream<T>` transformation is a **single-source chain** (map/filter/keyBy/…), with no `union(other)` or equivalent dual-source merge;
- The in-memory engine is a **pull-style iterator chain** (`Supplier<Iterator<InMemoryRecord<T>>>`) with no topic substrate;
- The Redis engine's `RedisPipelineDefinition` is **one topic + one consumer group**; `RedisStreamExecutionEnvironment.executeAsync()` can run **multiple** pipelines concurrently, but pipelines have no data-exchange channel between them — the operator chains of two pipelines cannot merge into one keyed operator;
- So what the join sugar's "expand at composition time into envelopes + bridging" lacks is not code but **merge-point semantics**: where to merge, how to preserve order, and how watermarks/checkpoints align.

## 2. Constraints (all must hold simultaneously)

1. **Envelope encapsulation unchanged**: the merged product still travels as `Envelope<K, L, R>` (side + joinKey + timestamp); the operator side (`StreamJoinOperator`) changes zero;
2. **Deterministic state keys not broken**: the Redis engine's state keys contain `topic:p:pid`; after merging, the join operator still sits on **one keyBy of one pipeline** (`process()` returns a `DataStream`, not a `KeyedStream`), with stateName fixed (`__operator:join:buffers`) and unaffected — a merge scheme must not split the join operator across multiple pipelines;
3. **Cross-source order is naturally undefined**: there is no total order between the left and right streams; merging only needs **intra-source order** (Redis Streams' single-stream read order and the in-memory iterator order both satisfy it); the join semantics themselves do not depend on cross-source order (the window predicate is interval logic, not temporal logic);
4. **Checkpoint/watermark channels are per-pipeline**: if merging introduces a second read source, watermark/barrier alignment must be designed explicitly (a Phase 3 event-time join prerequisite); not promised in this batch;
5. **Minimal engine-surface changes**: any new API must have landings in both the in-memory and Redis engines — no opening usable by only one engine (the existing "default method throws unsupported" pattern can bridge, but must be stated).

## 3. Candidate Approaches

### Approach U1: `DataStream.union(DataStream other)` as an engine primitive

- In-memory engine: interleaving iterators (round-robin or flattened), stateless, cheap;
- Redis engine: **touches the `RedisPipelineDefinition` architecture** — the single-source assumption runs through the runner's commit/watermark/barrier channels; moving to a multi-source reader means redesigning partition assignment, consumer-group coordination, and checkpoint alignment; not a gap a default method can bridge;
- Verdict: cheap on the in-memory side; the Redis side is the real content of Phase 3's "engine-level dual-source pipeline (Approach A)" — implementing it early drags Phase 3's core work in.

### Approach U2: an MQ topic as the merge point (automatic multi-pipeline orchestration + merged consumption)

- The sugar expands into: the left source maps into `Envelope`s and writes the join input topic, the right source likewise, and a consumption pipeline reads from that topic carrying the join operator;
- Redis engine: **zero engine change** — the merge point is the Redis Stream itself (a single stream is a total order; intra-source order is naturally satisfied), and one `fromMqTopic` pipeline suffices; the price is one user-visible topic more (lifecycle and trim ownership must be defined);
- In-memory engine: no topic, so the sugar expands into iterator interleaving (same as U1's in-memory side);
- Verdict: cheapest on the Redis side, but **the two sides' expansion products are asymmetric** (Redis via a topic, in-memory via iterators), and it requires both sources to be producers able to write the topic — if a source is another pipeline's output, the pipeline→topic bridge is still missing.

### Approach U3: dual-pipeline production + single-pipeline consumption (an orchestrator merging U2)

- One `executeAsync()` registers **three** pipelines: the left source pipeline (map into envelopes → an MQ sink writes the join topic), the right source pipeline (likewise), and the consumption pipeline (`fromMqTopic(joinTopic)` → keyBy → the join operator);
- Zero engine change, and either source may be an arbitrary pipeline (the bridging problem solved); the price is envelopes landing in Redis once (latency + one copy of storage), plus the join topic's trim/TTL policy;
- Verdict: **the only path that fully carries the sugar semantics under the current engine architecture**, and a tightening of U2.

## 4. Recommended Path

1. **Step 1 (a small primitive, reviewed first)**: `DataStream.union(DataStream<? extends T>)` implemented on the in-memory engine only (iterator interleaving), the Redis engine's default throwing unsupported with a pointer to "use the join-topic mode" — the primitive establishes the contract first, and the Redis landing points to U3;
2. **Step 2 (the sugar, expanding U3)**: `DataStream.join(other, config, fn)` on the Redis engine expands into the U3 three-stage orchestration (the join topic name user-given, envelope maps, the merged-consumption pipeline), and on the in-memory engine into map + union + keyBy + operator; the join topic's lifecycle follows the job (trim via the MQ's stream retention governance). Implementation note (2026-10-14): `Envelope`/`StreamJoinOperator` live in the join module while `DataStream` lives in core — the sugar's signature cannot reference the operator type directly, so the landing must be a join-module extension or a runtime/starter-layer addition. **Both landings are fixed (2026-10-14)**: the in-memory sugar lands as the join module's `StreamJoinOperator.join(left, right, config, fn)` (a static factory composing map→union→keyBy→process using only core's public surface; the envelope key doubles as the keyBy key, so a key-selector mismatch is structurally impossible on the sugar path); the Redis side's U3 three-stage orchestration lands as `RedisStreamExecutionEnvironment.join(joinTopic, left, right, config, fn)` (an instance method; the three-way proxy decision on the landing and the rejected options in §5);
3. **Step 3 (the Phase 3 merged design)**: the Redis engine's native multi-source reads (U1's Redis side) together with event-time watermark alignment — that is the final topic-free form; the U3 sugar then switches its underlying implementation with the user API unchanged.

## 5. Not-Yet-Implemented List (recorded in this batch; implemented in later batches after review)

- [x] the `DataStream.union(...)` contract + the in-memory implementation + the Redis engine's explicit unsupported (the default-method pattern) (2026-10-14, 11 tests);
- [x] the `DataStream.join(other, ...)` sugar, Redis side: the U3 three-stage orchestration (join-topic naming/trim/lifecycle, merged-consumption pipeline registration) (2026-10-14, 10 tests incl. 1 integration). **Landing proxy decision (2026-10-14, one of three)**: land on the `RedisStreamExecutionEnvironment.join(joinTopic, left, right, config, fn)` instance method, with the runtime main sources adding `implementation project(':join')`. Reasons: the runtime main sources already depend on operator modules like window/checkpoint/watermark (join's `testImplementation` status only recorded that "no main code used it at the time", not a layering rule), and the sugar belongs to the same "operator libraries composed by the engine" category; the env is the natural owner of the three-stage orchestration (registering the two envelope-landing pipelines and the merged-consumption pipeline are both env duties); and the signature has the joinTopic given explicitly by the user, matching `fromMqTopic`'s naming habit. Rejected options: (a) a new join-runtime bridge module — a settings/build/publishing/coverage pipeline for one factory method, and a user-visible API split in two; (b) exposing the env on `DataStream` — either a core→runtime reverse dependency (wrong direction for an API module) or a new core SPI for a single call site. The join-topic lifecycle rules (fixed with the decision): the topic name is user-given with charset `[A-Za-z0-9_.-]+` (feeding the stable source id `join-<topic>`); the consumer group derives as `<topic>-join`; trim/TTL follow the MQ's existing stream retention governance, and the sugar never deletes topics on its own; a second call with the same joinTopic is rejected by the stable-source-id duplicate-registration guard (fail-fast);
- [x] the `DataStream.join(other, ...)` sugar, in-memory side: the `StreamJoinOperator.join(left, right, config, fn)` static factory (envelope maps + union + keyBy + operator; timestamps via config's extractors, falling back to system time like `StreamJoiner`) (2026-10-14, 7 tests, differential comparison against the manual envelope path);
- [x] dual-engine integration tests: the join sugar's differential comparison (Redis side: `RedisEnvJoinSugarTest#redisJoinSugarMatchesTheHandWrittenEnvelopeExpansion` — the manual envelope expansion and `env.join` run the same job, same input, same fixtures, asserting point-identical output sets after a 500ms grace, 2026-10-14; the in-memory differential in `StreamJoinOperatorSugarTest#sugarMatchesManualEnvelopePathOutput`);
- [ ] event-time join window evaluation (depends on the `TimestampAssigner` overload and watermark alignment; with Phase 3).

## 6. Relationship with Existing Docs

- `docs/Join-CEP-Operators-Design.md` §2 (semantic positioning) and §5.2 (the blocker B record): this page expands them;
- `docs/Join.md`'s operator section: gains the "sugar usage" subsection once the merge point lands;
- `docs/Join-CEP-Operators-Design.md` §3.4: the merge does not change the state layout (the join operator remains single-pipeline single-keyBy).
