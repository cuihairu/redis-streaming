# Watermark Module

Module directory: `watermark/`. Provides built-in implementations and composition strategies for the core `WatermarkGenerator` / `TimestampAssigner` interfaces. The module depends only on `core`; the runtime main source set also depends on this module — the `RedisPipelineRunner` heuristic watermark shares the same formula with this module via `BoundedOutOfOrdernessWatermarkGenerator.watermarkFor(...)` (state unification design step 1, 2026-10-11).

## Core Concept

A watermark is the assertion that "events with timestamp ≤ T will not arrive again"; it drives event-time window triggering and late-data handling. This is why the bounded-out-of-orderness watermark uses `maxTs − δ − 1` rather than `maxTs − δ`: an event at exactly `maxTs − δ` may still occur. The core `Watermark` is a comparable value carrying a `long timestamp`, and `Watermark.maxWatermark()` equals `Long.MAX_VALUE`.

## Built-in Generators (`watermark.generators` package)

| Class | Semantics | Auxiliary getters |
|---|---|---|
| `AscendingTimestampWatermarkGenerator<T>` | Assumes ascending timestamps: `onEvent` records `max(current value, event timestamp − 1)`, `onPeriodicEmit` emits it; initial value `Long.MIN_VALUE` | `getCurrentWatermark()` |
| `BoundedOutOfOrdernessWatermarkGenerator<T>` | Allows out-of-orderness `δ`: `onEvent` records the maximum observed timestamp `maxTs`, `onPeriodicEmit` emits `maxTs − δ − 1`; constructor takes a `Duration`; initial `maxTimestamp = Long.MIN_VALUE + δ + 1` (first emit is `Long.MIN_VALUE`) | `getMaxTimestamp()`, `getMaxOutOfOrdernessMillis()` |

Both are `Serializable` (stateful; a new instance is required for each use).

```java
import java.time.Duration;
import io.github.cuihairu.redis.streaming.watermark.generators.BoundedOutOfOrdernessWatermarkGenerator;

// Event is the user's event type
BoundedOutOfOrdernessWatermarkGenerator<Event> gen =
        new BoundedOutOfOrdernessWatermarkGenerator<>(Duration.ofSeconds(5));
// events 1000/2000/3000 with δ=5s → emit Watermark(3000 - 5000 - 1 = -2001)
// (the watermark module unit test BoundedOutOfOrdernessWatermarkGeneratorTest asserts the same number)
```

## WatermarkStrategy (composition pattern)

`WatermarkStrategy<T>` (`io.github.cuihairu.redis.streaming.watermark.WatermarkStrategy`) bundles a "generator factory + timestamp assigner" so it can be passed across engines:

| Static factory | Behavior |
|---|---|
| `forMonotonousTimestamps()` | Produces an `AscendingTimestampWatermarkGenerator` |
| `forBoundedOutOfOrderness(Duration)` | Produces a `BoundedOutOfOrdernessWatermarkGenerator` |
| `forGenerator(Supplier<WatermarkGenerator<T>>)` | Custom generator factory |
| `noWatermarks()` | Private `NoWatermarkGenerator`; `onEvent`/`onPeriodicEmit` never emit watermarks |

Instance methods:

- `WatermarkGenerator<T> createWatermarkGenerator()` — each call yields a new instance from `supplier.get()`
- `TimestampAssigner<T> getTimestampAssigner()`
- `long extractTimestamp(T event, long recordTimestamp)` — uses the assigner when present, otherwise returns `recordTimestamp` unchanged
- `WatermarkStrategy<T> withTimestampAssigner(TimestampAssigner<T>)` — returns a new strategy carrying the assigner (strategies themselves are immutable)

Embedded types: `TimestampAssigner<T>` (extends the core interface of the same name, convenient for lambda syntax) and `SerializableBiFunction<T,U,R>` (a serializable-function marker).

```java
import java.time.Duration;
import io.github.cuihairu.redis.streaming.watermark.WatermarkStrategy;

// Event is the user's event type and must provide getEventTime()
WatermarkStrategy<Event> strategy = WatermarkStrategy
        .<Event>forBoundedOutOfOrderness(Duration.ofSeconds(5))
        .withTimestampAssigner((e, recordTs) -> e.getEventTime());

long ts = strategy.extractTimestamp(event, recordTs);      // e.getEventTime()
WatermarkGenerator<Event> generator = strategy.createWatermarkGenerator();
```

## Usage in Both Engines

```java
// Option 1: pass the core interface directly (both the in-memory and Redis engines implement this overload)
stream.assignTimestampsAndWatermarks(new BoundedOutOfOrdernessWatermarkGenerator<>(Duration.ofSeconds(5)));

// Option 2: also re-assign event time (only the in-memory engine implements it;
// the Redis engine does not override this overload, so core's default implementation runs and throws
// UnsupportedOperationException("This runtime does not support event-time timestamp assignment");
// runner-level event-time propagation would be required, see todo.md B2)
stream.assignTimestampsAndWatermarks(
        (TimestampAssigner<Event>) (e, recordTs) -> e.getEventTime(),
        WatermarkStrategy.<Event>forBoundedOutOfOrderness(Duration.ofSeconds(5)).createWatermarkGenerator());
```

In both engine implementations watermarks only ever advance monotonically: the in-memory engine's `WatermarkState.emit` accepts only watermarks greater than the current value; the Redis engine's `Context.raiseWatermark` updates with `Math.max`.

### The Redis engine's watermark model

- Default (`assignTimestampsAndWatermarks` not called): `RedisPipelineRunner` computes `event time = message.getTimestamp()` at the entry of each message's processing chain (falling back to the delivery time when there is no timestamp), maintains a global maximum, and the watermark is `max(event time) − watermarkOutOfOrderness − 1` (the same formula as `BoundedOutOfOrdernessWatermarkGenerator.watermarkFor`; the Builder defaults to `Duration.ZERO`, and negative values throw `IllegalArgumentException` during configuration validation). **Boundary semantics change (2026-10-11)**: previously the runtime lacked the final `-1`, so the watermark meant "events with time > w will not arrive any earlier"; after convergence it uniformly means "events `≤ w` will not arrive", point-for-point identical with the watermark module's generator, and the late-data boundary moved 1ms earlier overall.
- After the call: the user generator's `onEvent`/`onPeriodicEmit` are invoked on every message (per record, not on a periodic timer), and emitted watermarks flow through `WatermarkOutput.emitWatermark` → `Context.raiseWatermark`, **monotonically raising** the global watermark (never lowering what the heuristic has already reached). The integration test `RedisRuntimeWindowedStreamIntegrationTest` proves that with `watermarkOutOfOrderness=10s` configured, a window the heuristic cannot advance can be triggered early by the user generator.
- Window trigger timing: watermark advancement happens at the entry of the message processing chain, and window operators check for due windows while processing each record (trigger count per record is capped by `windowMaxFiresPerRecord`, with the unused budget carried to the next record); when idle flushing (RT-M3, `RedisRuntimeConfig.watermarkIdleTimeout`, off by default) pushes the watermark to `MAX_VALUE`, window operators drain their due sets without limit via the flush hook — see docs/runtime.md 2.4 for details.

## Known Limitations

- `WindowAssigner.getDefaultTrigger()` is now wired into both engines' window operators (formerly a dead interface; wiring record in todo.md B3): one trigger instance per (partition, key, window) bucket. `onElement` is called when an element arrives (`FIRE` emits early and keeps state, `FIRE_AND_PURGE` emits early and clears the bucket, `PURGE` discards the bucket, `CONTINUE` keeps accumulating); `onEventTime` is called during due checks (Redis engine: `CONTINUE` defers closing this round, `PURGE` discards without emitting, `FIRE`/`FIRE_AND_PURGE` emit and close; in-memory engine: when input is exhausted, `onEventTime(window.getEnd(), window)` is called once per remaining bucket with a `+inf` watermark, then flushing). The built-in `TumblingWindow`/`SlidingWindow`/`SessionWindow` `getDefaultTrigger()` all return a new `EventTimeTrigger` (`onElement`→`CONTINUE`, `onEventTime` when due→`FIRE_AND_PURGE`); the pre-wiring pure-close behavior is point-for-point equivalent to it (equivalence tests pin this, see todo.md B3). `onProcessingTime` is still not called by any engine (neither engine has processing-time window timers; windows trigger only with watermarks).
- `StreamSource.SourceContext.getCheckpointLock()`: the in-memory engine returns a source-level emission lock (producers block across a full queue while holding it; consumers never take it, so backpressure cannot deadlock); checkpoint consistency is carried by `InMemoryCheckpointCoordinator.checkpointLock()` (a separate coordinator lock) — see docs/Checkpoint-Barrier-Design.md; the Redis engine has no `StreamSource` path (the source entry point is `fromMqTopic`).

## References

- [Core.md](Core.md) · [window.md](window.md) · [runtime.md](runtime.md)
