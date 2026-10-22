# Aggregation

Module: `aggregation/`

## Module Responsibility

An implementation library on top of core's `AggregateFunction` interface (`io.github.cuihairu.redis.streaming.api.stream.AggregateFunction`), in two layers:

- **`incremental`**: `AggregateFunction` implementations for exact within-window aggregation (PV / UV / Top-K / quantiles) that feed `WindowedStream.aggregate` inside a stream pipeline, with state carried by checkpoints.
- **`analytics`**: standalone Redis rolling-window analysis services (PVCounter / UVCounter / TopKAnalyzer / QuantileAnalyzer) with their own storage, query APIs, TTL, and cleanup threads — queryable across processes, with no stream execution environment.

The layers complement each other: **within-window computation goes through `incremental`; cross-process, recoverable queries go through `analytics`**.

The module has 8 main source files: 4 in `incremental`, 4 in `analytics`. Historically the module also shipped a batch-style API (`AggregationFunction`, `aggregation.TimeWindow/TumblingWindow/SlidingWindow`, `functions/`, `WindowAggregator`) incompatible with core's incremental interfaces and unable to join the window pipeline; it was removed on 2026-10-12 (the unification plan: [Aggregation-Unification-Design.md](Aggregation-Unification-Design.md)).

## Incremental Aggregation (`aggregation.incremental`)

All implement core's `AggregateFunction<IN, R>` (the 4 methods + merge; the accumulator is JSON-serializable) and can be passed directly to `KeyedStream.window(...).aggregate(fn)`:

| Class | Output | Semantics | Notes |
|---|---|---|---|
| `PVAggregateFunction<T>` | `Long` | record count in the window | counting |
| `UVAggregateFunction<T>` | `Long` | distinct keys in the window | exact dedup via HashSet |
| `TopKAggregateFunction<T>` | `List<TopKEntry<T>>` | in-window count Top-K | exact HashMap counting + sort at fire time; ties broken by item ascending (matching `TopKAnalyzer`'s tie policy); `TopKEntry(item, count)` |
| `QuantileAggregateFunction` | `Double` | the in-window quantile | nearest-rank `floor(q×(n-1))`, the same definition as `QuantileAnalyzer.quantile`; an empty window returns `null`; the constructor clamps `q` into [0,1] |

```java
KeyedStream<String, Event> keyed = env.fromElements(events).keyBy(Event::getPage);
keyed.window(TumblingWindow.ofMinutes(1))
        .aggregate(new PVAggregateFunction<>())
        .addSink(result -> log.info("pv={} ", result));
```

Top-K / UV element requirements: the `keyBy` key is the counted/deduplicated object (TopK counts keys, UV dedups keys); to count another field, `map` a projection first.

## Analyzers (`aggregation.analytics`)

### PVCounter (page-view counting)

Backed by one Sorted Set per page (score = the event timestamp), counting PVs in the **rolling window** `[now - windowSize, now]`. The constructor starts a cleanup thread (period `max(1s, windowSize)`); call `close()` when done.

```java
PVCounter pv = new PVCounter(redisson, "demo:agg", Duration.ofMinutes(10));
long n = pv.recordPageView("home");                    // records and returns the current window count
long m = pv.recordPageView("home", timestamp);         // with event time; events earlier than the window start are rejected (not written); future timestamps are stored but not counted
long c = pv.getPageViewCount("home");                  // the current rolling-window count
long r = pv.getPageViewCount("home", start, end);      // a custom range; start inclusive, end exclusive
pv.resetPageViewCount("home");                         // clears the page and removes it from the index
PVCounter.PVStatistics st = pv.getStatistics();        // getTotalPages() / getTotalViews() / getTimestamp()
pv.close();                                            // stops the cleanup thread
```

- A null/blank `page` makes the count methods return 0 and the write methods do nothing.
- Redis keys: `<keyPrefix>:pv:<page>` (zset), `<keyPrefix>:pv:pages` (the page index set).

### UVCounter (unique-visitor counting)

HyperLogLogs bucketed by time bucket (default 1 minute); queries union the buckets covering the rolling window with `countWith`. Implements `AutoCloseable` (the cleanup thread starts at construction).

```java
UVCounter uv = new UVCounter(redisson, "demo:agg", Duration.ofMinutes(10));                    // bucketSize = 1 minute
UVCounter uv2 = new UVCounter(redisson, "demo:agg", Duration.ofMinutes(10), Duration.ofSeconds(30));

boolean isNew = uv.add("home", "visitor-1", Instant.now());   // whether this visitor is new to the bucket
long uvCount = uv.count("home");                              // the rolling window (up to now)
long uvAt   = uv.count("home", now);                          // the rolling window (up to a given instant)
long uvRange = uv.count("home", start, end);                  // a custom range (a bucket-aligned approximation)
uv.reset("home");
uv.close();
```

- A null/zero/negative `bucketSize` falls back to 1 minute; when `windowSize` spans more than 10000 buckets the constructor throws `IllegalArgumentException`.
- A null/blank `page` or `visitorId` makes `add` return false.
- Redis keys: `<keyPrefix>:uv:<page>:<bucketStartMillis>` (HLL), `<keyPrefix>:uv:pages`, `<keyPrefix>:uv:<page>:buckets` (the index set); key expiry is `windowSize + 2*bucketSize`.

### TopKAnalyzer (in-window Top-K)

Records item weights in fixed buckets (10 per window, bucket length `windowSize/10`, at least 1ms) and aggregates the buckets covering the trailing window at query time. Ties rank by name ascending.

```java
TopKAnalyzer top = new TopKAnalyzer(redisson, "demo:agg", 10, Duration.ofMinutes(5));  // k=10
top.recordItem("pages", "/home");                       // weight +1; returns the bucket's current score
top.recordItem("pages", "/home", 2.5);                  // custom weight
List<TopKAnalyzer.TopKItem> items = top.getTopK("pages");       // item/score/timestamp, score descending
List<TopKAnalyzer.TopKItemWithRank> ranked = top.getTopKWithRanks("pages"); // with rank (1-based)
int rank = top.getRank("pages", "/home");               // 1-based; -1 when unscored in the window
double score = top.getScore("pages", "/home");          // the in-window total; 0.0 when none
top.removeItem("pages", "/home");                       // removes from all live buckets; returns whether anything was removed
top.reset("pages");                                     // deletes every bucket the window covers
```

- Constructor constraints: `k <= 0` or a non-positive `windowSize` throws `IllegalArgumentException`; a null `redissonClient`/`keyPrefix` throws NPE.
- Each bucket keeps at most `2k` entries (beyond that, evicted by score ascending, ties by name descending); bucket TTL = `windowSize + 2` bucket lengths.
- Redis keys: `<keyPrefix>:topk:<category>:b:<bucketIndex>`.

### QuantileAnalyzer (rolling-window quantiles)

Two Sorted Sets per metric (a time index with score = timestamp, a value index with score = observation); the cleanup thread starts at construction; implements `AutoCloseable`.

```java
QuantileAnalyzer q = new QuantileAnalyzer(redisson, "demo:agg", Duration.ofMinutes(5));
q.record("latency", 12.3);                       // records at the current time
q.record("latency", 8.1, timestamp);             // with timestamp; timestamps earlier than the window start or later than now are rejected (not written)
Double p50 = q.quantile("latency", 0.5);         // q clamped into [0,1]; null with no samples
Double p95 = q.p95("latency");                   // p50()/p95()/p99() shortcuts
q.close();
```

- Redis keys: `<keyPrefix>:quantile:<metric>:ts`, `<keyPrefix>:quantile:<metric>:v`, index `<keyPrefix>:quantile:metrics`.
- The quantile picks a single entry from the value index by rank (`floor(q*(n-1))`), no interpolation.

## Configuration

The module has no configuration-file keys; every parameter is a constructor argument (the constructor constraints above are the validation rules). The Redis key prefix is each constructor's `keyPrefix`.

## Minimal Example (Redis available)

```java
// 1) window pipeline: a core AggregateFunction implementation, following the stream execution environment
KeyedStream<String, String> keyed = new InMemoryKeyedStream<>(pages::iterator, p -> p);
keyed.window(TumblingWindow.ofMillis(60_000))
        .aggregate(new TopKAggregateFunction<String>(5))
        .addSink(top -> log.info("top: {}", top));

// 2) cross-process rolling queries: analytics services talking to Redis directly
PVCounter pv = new PVCounter(redisson, "example", Duration.ofMinutes(10));
pv.recordPageView("home");

TopKAnalyzer top = new TopKAnalyzer(redisson, "example", 5, Duration.ofMinutes(5));
top.recordItem("pages", "home");

QuantileAnalyzer q = new QuantileAnalyzer(redisson, "example", Duration.ofMinutes(5));
q.record("latency", 20.0);
Double p95 = q.p95("latency");

pv.close();
q.close();
```

A complete runnable sample lives at `examples/.../aggregation/StreamAggregationExample.java` (both layers demoed together; needs Redis); the incremental functions' unit tests are in `aggregation/.../incremental/IncrementalAggregatePipelineTest.java` (fed for real into `WindowedStream.aggregate` through the public InMemory entry points).

## References

- [Architecture](Architecture.md)
- [window](window.md)
