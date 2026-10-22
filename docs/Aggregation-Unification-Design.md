# Aggregation Unification Design (three stacks → one)

This page is for review and contains no code changes. Goal: converge the repo's three unrelated "aggregation/window" abstractions into one, eliminating the state of "one concept defined in three places with mutually incompatible semantics".

## Current State (fact inventory)

| Location | Abstraction | Shape | Semantics |
| --- | --- | --- | --- |
| `core` `api/stream/AggregateFunction<IN,OUT>` | aggregation function | an interface, 4 methods + the nested `Accumulator` (`createAccumulator/add/getResult/merge`), `Serializable` | **incremental**: fed element by element, results taken at window fire; merge supports cross-partition merging |
| `aggregation` `AggregationFunction<T,R>` | aggregation function | `@FunctionalInterface`, `apply(Collection<T>)` + default `getName()` | **batch**: collects the whole set before computing; no incrementality, no merge |
| `window` `TimeWindow` | time window | a class (`long start/end` ms, `maxTimestamp()=end-1`, `contains`), implementing `WindowAssigner.Window` | a millisecond interval, working with the runtime/window engines |
| `aggregation` `TimeWindow` | time window | an interface (`Duration getSize()/getSlide()`, default `isSliding()`) | Duration semantics — **same name, different type** from the above |

Key facts:

1. **The three stacks are fully decoupled**: core's incremental interface, the aggregation module's batch interface, and the window module's window types reference nothing of each other's, with no bridge.
2. **The aggregation module has no production-side consumers**: outside `examples`, no production code depends on `AggregationFunction`/`aggregation.TimeWindow`; it is a self-contained system (`functions/`, `analytics/`, `WindowAggregator`, `TumblingWindow/SlidingWindow`).
3. **core's incremental interface is "alive"**: the runtime window operators run on core's `AggregateFunction` + the window module's assigner/trigger.
4. The name collision: `TimeWindow` means different things in the two packages (a millisecond interval vs a Duration spec) — a trap for both reading and search.

## Problems

- A user writing an aggregation function faces two incompatible interfaces; picking the wrong one cannot join the runtime window pipeline.
- The aggregation module's high-value components (TopK, quantiles, PV/UV etc. under `analytics/`) cannot be used by the runtime window directly due to interface incompatibility — duplicated construction.
- `aggregation.TimeWindow` and `window.TimeWindow` collide by name.

## Candidate Approaches

### Approach A: core's incremental interface as the only standard; the aggregation module becomes a high-level library (recommended)

- **The standard**: aggregation functions recognize only core's incremental `AggregateFunction` (4 methods + merge), because the window engines and the checkpoint semantics are built on the incremental model; the batch interface cannot express merge (cross-partition/cross-window).
- **The aggregation module repositioned**: no longer "another API", but a **high-level implementation library** over the core interface — TopK/quantiles/PVUV rewritten as `AggregateFunction` implementations (or providing the classic incremental-accumulator structures: Sketch/Heap/HHyperLogLog++/CountMin).
- **The batch interface retires**: `AggregationFunction` marked `@Deprecated` (with a migration note: batch scenarios can use `Stream.collect`, or land an `AggregateFunction` first and call `getResult`), deleted after one major version.
- **Window-type convergence**: `aggregation.TimeWindow` (the interface) is deleted in favor of `window.TimeWindow`; the aggregation module's `TumblingWindow/SlidingWindow` (Duration specs) stay as "spec" objects, but convert into assigners producing `window.TimeWindow`.

### Approach B: both tracks coexist + an adapter

Keep both interfaces; the aggregation module provides an `AggregateFunctionAdapter` (wrapping a batch function as incremental: accumulating a List internally, applying at getResult). The smallest change, but it disguises O(n) in-memory batch accumulation as incremental — memory explodes on large windows — **not recommended**; acceptable only as the v1 migration period's temporary bridge.

### Approach C: reverse unification (the batch interface as the standard)

Infeasible. Window firing, checkpointing, and out-of-order tolerance all require incremental semantics; the batch interface cannot carry them.

## Migration Path (Approach A, phased)

1. **v1 (the compatibility period, landed 2026-10-11)**: the `analytics/` high-level aggregations each gain an `AggregateFunction` implementation (new code, old classes untouched); the docs point at the core interface; `AggregationFunction` and `aggregation.TimeWindow` marked `@Deprecated`.

   Landing record: the new `aggregation.incremental` package gains four core `AggregateFunction` implementations — `TopKAggregateFunction` (exact HashMap counting + sort at fire, tie policy matching `TopKAnalyzer`: score descending, item ascending, results as `TopKEntry(item,count)`), `QuantileAggregateFunction` (nearest-rank `floor(q×(n-1))`, the same definition as `QuantileAnalyzer.quantile`, `null` for an empty window), `PVAggregateFunction` (counting), `UVAggregateFunction` (exact HashSet dedup). One factual correction made in design: the four analytics classes are not classic accumulators (Sketch/Heap) but Redis-backed rolling-window services (with their own storage/query APIs/TTL/cleanup threads), so per Approach A's bracketed alternative "provide the classic incremental-accumulator structures" the landing is a new implementation for exact in-window computation, not a retrofit of the Redis services — the two positions complement: within-window goes through the incremental package, cross-process/recoverable queries through analytics. Batch interface retirement: `AggregationFunction` and `aggregation.TimeWindow` marked `@Deprecated` (javadoc pointing at core's `AggregateFunction` and the window module's assigners); existing deprecated-API users (the five `functions/` classes, `WindowAggregator`, `TumblingWindow/SlidingWindow`, examples' `StreamAggregationExample`) get class-level `@SuppressWarnings("deprecation")` (mandatory under the -Werror gate), commented as to be handled with v2. Tests: unit tests for the four functions (tie policy / nearest-rank equivalence / merge semantics / serialization round-trips) + `IncrementalAggregatePipelineTest`'s 4 cases fed for real into `WindowedStream.aggregate` via the public `InMemoryKeyedStream` + `window.assigners.TumblingWindow` (pinning the unification motive that "the batch interface cannot enter the window pipeline"); `aggregation:check` (unit + integration) fully green. v2 (examples migration, deleting the deprecated classes, the WindowAggregator disposal) follows the major cycle.
2. **v2 (major)**: all of `examples` moves to the new interfaces; the deprecated classes are deleted; the aggregation module's javadoc states the "implementation library over the core interface" positioning.

   Landing record (2026-10-12): examples' `StreamAggregationExample` rewritten as a two-layer demo — the window pipeline through the four `incremental` implementations (via the public `InMemoryKeyedStream` + `window.assigners.TumblingWindow`, one result per pipeline per fire), cross-process queries keeping `analytics`' direct `PVCounter`/`TopKAnalyzer` usage; tests moved to observable assertions over the final snapshots (pv≥1, uv within [1, page count], top hitting the page set, sum>0, p90≥50ms). **The deletion went further than the v1 plan**: not just the two `@Deprecated` classes but the whole batch API surface — the root package's `AggregationFunction`/`TimeWindow`/`TumblingWindow`/`SlidingWindow`/`WindowAggregator`, the five `functions/` classes and their 14 tests (`git rm` of 24 files). Reasons: Approach A's end state is "an implementation library over the core interface"; the batch interface has no merge semantics and cannot enter the window pipeline, and keeping `WindowAggregator`/Duration-spec windows would only preserve a third abstraction (an inventory found no real consumers for a "retrofitted" `WindowAggregator` — its capability is fully covered by the analytics services' queryable rolling windows plus the incremental package's in-window computation, so delete rather than retrofit); the `Storms.grandStorm` seed class switched from the deleted `WindowAggregator` to the surviving `PVCounter`. Docs synced: `docs/Aggregation.md` rewritten in full to the two-layer positioning (8 main source files), the README/README.zh module entries and usage examples moved to the new API, POTENTIAL_BUGS B-37's addendum archived. `aggregation:check` + `examples:test` fully green.

## Non-Goals

- No change to core `AggregateFunction`'s shape (the 4 methods + merge already cover the need; no extension necessary).
- No new window-assigner abstraction; the window module stays as-is.
- No change to aggregation-result persistence/Redis serialization formats.
