# The In-Memory Engine's Operator-State Lifecycle and Window Accumulator Registration Design

This page is for review and contains no code changes. Goal: define the in-memory engine's operator-state lifecycle and restart-semantics contract so window accumulators can register as snapshot sources with the checkpoint coordinator (the todo's top item), and close at the contract level the correctness trap of "lazily created tables at registration + replay at restore injecting stale buckets into new windows".

> Status: landed 2026-10-14, implemented per recommendations D1-a/D2-a/D3-a/D4-a/D5-a/D6-a (the landed semantics in docs/Checkpoint-Barrier-Design.md Approach A ② and its landing record).

## Background

Checkpoint protocol v1/v2 have landed: the `InMemoryCheckpointCoordinator`'s `checkpointLock()` is the single consistent cut, on which all registered-store mutations and snapshot/restore synchronize; the per-record barrier executes the record body wholly inside the store's `fenceLock()` (= the registered `checkpointLock`). The registration surface has generalized to `registerSnapshotSource(name, snapshot, restore)`, and `WatermarkState` is already registered as a `watermark-N` snapshot source.

The window accumulator (the bucket table of `InMemoryWindowedStream`) is the only operator state not yet registered. The todo's top item, "register the in-memory engine's window accumulators with the checkpoint coordinator", depends on this page.

## Current State (fact inventory)

1. **The bucket table is a `drive()` method local**: `drive()` (L264-315) creates `Map<WindowKey<K>, Bucket<T, R>> buckets = new LinkedHashMap<>()` fresh on every call (L268), runs the whole input, and returns the complete result list. Terminal operators (reduce/sum/count/aggregate/apply) have anonymous iterators that call the `*All` once on the first `hasNext()` and cache the result — **one iteration = one drive() = one bucket table**.
2. **One sink = one full traversal**: `addSink` (L192-208) fetches `recordIteratorSupplier.get()` through `iterator()` (L79-89); each sink gets its own fresh iterator. Two sinks on one DataStream = two independent drive()s = two independent bucket tables, each emitting in full — the existing "replay per consumer" semantics.
3. **No coordinator reference, no locking**: the `InMemoryWindowedStream` constructor takes only `(keyedIteratorSupplier, windowAssigner, watermarkState)` (L64-75); the `InMemoryKeyedStream.window()` call site (L302-308) holds `checkpointCoordinator` but does not pass it through. There is no synchronization on the bucket table.
4. **`R` is generic per terminal operator**: reduce/sum → T, count → Long, aggregate/apply → R. A stream-level field cannot be generic.
5. **The end-of-input flush does not clean up**: after input is exhausted, each remaining bucket gets `trigger.onEventTime(window.getEnd(), window)` + `fire(...)` (L308-313), **without purge or remove** — buckets stay in the table. The mid-stream close paths (`closeReachedWindows` L322-344 / `closeReachedWindowsByProcessingTime` L353-377) call `it.remove()` only on FIRE_AND_PURGE/PURGE.
6. **Triggers have no snapshot API**: `WindowAssigner.Trigger` has only onElement/onEventTime/onProcessingTime, no snapshot/restore. The session-merge path already sets the precedent: merged buckets keep the first absorbed bucket's trigger instance and drop the rest's trigger state (L418-419, "the trigger API has no merge callback").
7. **The in-memory engine's sources replay in full**: a `Supplier<Iterator>` starts from the beginning on every `get()`. There is no offset/position concept, so consumed records cannot be skipped.

## Decision Space

### D1 Lifecycle ownership: one bucket table per drive() (recommended) vs shared across terminal operators

- **Per drive()**: promote the bucket table from a `drive()` local to an operator-instance field created "per terminal-operator consumption", with `drive()` receiving that instance instead of creating one. Semantics identical with today: one table per sink, each emitting in full, and after the end-of-input flush the table becomes unreachable together with the instance.
- **Shared across terminal operators**: multiple sinks of one DataStream share one bucket table. Preserving "each sink emits in full" would require clearing the table after each flush, but then a crash/checkpoint sees nothing; and concurrent consumption of one DataStream is undefined in the in-memory engine anyway.

Recommend **D1-a: one per drive()**. Reasons: zero semantic change (today behaves exactly so), and the registration surface only needs to hook once at instance creation.

### D2 Registration timing: lazily at first drive() (recommended) vs immediately at `window()`

- **Lazy**: isomorphic with `WatermarkState` — the `assignTimestampsAndWatermarks` pipeline segment auto-registers while holding the coordinator. The window operator creates the instance and registers at the first drive(); unconsumed terminal operators produce no registration.
- **Immediate**: register at the `window()` call. Even a terminal operator never consumed would produce a registration and snapshot entries nobody claims at restore.

Recommend **D2-a: lazy**. The coordinator's `latestRestoredStateByStoreId` deferred-restore semantics apply to snapshot sources too — when restore happened before registration, the registration point picks up the latest restored state.

### D3 Restore semantics: FRESH (default) vs ADOPT (explicit opt-in)

This is this page's core trap. The in-memory engine's sources replay in full: one "restart" = a new coordinator + the same source running again. If restore injected stale buckets into new windows (ADOPT), the new drive() would re-accumulate the **entire history** into the restored buckets — every record counted twice.

- **FRESH (default)**: restored window state is written into the instance's "read-only checkpoint mirror" (`restoredView`) and never injected into the live bucket table; drive() starts from an empty table. Restore semantics = observable (checkpoints contain window state, queryable) + auditable, without changing results.
- **ADOPT (explicit opt-in)**: only meaningful when the source can replay a suffix (carrying positions, able to skip consumed records). All in-memory sources today replay in full, so ADOPT defaults off; revisit when resumable sources appear.

Recommend **D3-a: FRESH by default; ADOPT leaves an interface but is not implemented**. This captures the benefits of registering with the checkpoint coordinator (consistent cuts, observability, auditability) immediately, while the correctness trap is closed at the contract level.

### D4 Snapshot granularity: a per-record fence (recommended) vs a whole-drive() fence

- **Per record**: the drive() loop body (assignWindows + bucket updates + trigger callbacks + mid-stream close scans) executes wholly inside `fenceLock()`, isomorphic with the keyed path's per-record barrier. A checkpoint triggered mid-record blocks until the current record finishes writing and sees its whole effect.
- **Whole drive()**: the entire drive() holds the fence. On long streams, checkpoints would block for the entire run — de facto stop-the-world.

Recommend **D4-a: per record**. Note that trigger callbacks inside the drive() loop may run user code (onElement calling fire), so the fence's hold time includes user trigger code — the same nature as the keyed path holding user processElement code; acceptable. The end-of-input flush also enters the fence (same reasoning as the keyed path's `drainAllTimers()`).

### D5 Snapshot content: copy bucket contents; do not copy trigger state

- Bucket table copy: a `LinkedHashMap` shallow copy plus each bucket's accumulator value (`FoldState.acc` / `AggregateState.acc` / `SumState.total+sample` / `CountState.count`) and `lastTimestamp`. The raw path copies the `ArrayList`; the incremental path's `BucketState` copies by reference — the in-memory coordinator stores live references, the same nature as the keyed store's HashMap copies. User `AggregateFunction.Accumulator` objects cannot be deep-copied; the contract requires them not to be mutated in place after the snapshot point (the same nature as keyed-store value objects).
- **Trigger instances are not copied**: no snapshot API. Restored buckets get fresh `getDefaultTrigger()` instances — trigger state (e.g. CountTrigger's count) is lost across restore. Consistent with the session-merge path's existing boundary (triggers have no merge callback); declared explicitly in the contract.

Recommend **D5-a**.

### D6 Naming and deregistration

- One instance per drive() needs a collision-free registration name. Following `watermark-N`'s skip-on-collision pattern: `window-N`, skipping on collision.
- The coordinator currently has **no deregistration API**. A finished run's instance state stays in later checkpoint cuts — acceptable: it is that run's final state, a correct historical record. No deregistration API is introduced (avoiding the semantic confusion of "window state vanishing from checkpoint cuts").

Recommend **D6-a**.

## Recommended Contract (summary)

1. The DataStream returned by `InMemoryWindowedStream`'s terminal operators carries a "created per consumption" operator instance (bucket table + registration name + read-only `restoredView`).
2. The instance is created at the first drive() and calls `registerSnapshotSource("window-N", snapshot, restore)`; `window-N` skips numbers on collision.
3. The drive() loop body and end-of-input flush execute wholly inside `fenceLock()` (the per-record fence).
4. `snapshot()`: copies the bucket table inside the fence (contents + lastTimestamp); trigger instances are not copied.
5. `restore()`: FRESH by default — writes the read-only `restoredView`, never injecting the live bucket table; ADOPT leaves an interface, unimplemented.
6. No deregistration API; a finished run's window state stays in later checkpoint cuts.

## Correctness Argument

- **The trap is closed**: under FRESH, restore never injects live buckets, so full replay cannot double-count.
- **No deadlock**: the fence = the registered `checkpointLock`, disjoint from the emission lock (the two-lock contract); `synchronized` is reentrant, so any bucket-update self-synchronization idles inside the fence.
- **No tearing**: the per-record fence + copying inside the fence = a checkpoint sees the whole record's effect, isomorphic with the keyed path.
- **Zero semantic change**: one table per sink, each emitting in full, the end-of-input flush not cleaning up — all preserved as today.

## Landing Steps (after review approval)

1. TDD: write tests first pinning "the checkpoint cut contains window bucket contents", "restore defaults to FRESH and injects nothing", "the per-record fence blocks mid-record checkpoints", "trigger state is lost across restore".
2. Implement: `InMemoryWindowedStream` passes the coordinator through; terminal-operator instantiation + lazy registration; the drive() per-record fence; snapshot/restore.
3. Docs: `docs/checkpoint.md` gains the window-accumulator registration semantics; `Checkpoint-Barrier-Design.md` Approach A ② closes its open point.

## Non-Goals

- The Redis engine's window state (it has its own window path, outside this page).
- Trigger-state snapshots (no API; awaits Trigger interface evolution).
- In-memory sources with replayable suffixes (ADOPT's prerequisite; awaits source interface evolution).
- A deregistration API.
