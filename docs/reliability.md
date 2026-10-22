# Reliability Module

Module: `reliability/`

## Module Responsibility

Reliability building blocks: an in-process dead-letter queue and retry policies, Redis deduplication, in-memory/Redis rate limiters, a rate-limiting sink decorator, and two static metric hooks. Package root `io.github.cuihairu.redis.streaming.reliability`.

24 main source files across four packages:

| Package | Files | Contents |
|---|---|---|
| `reliability` (root) | 6 | `DeadLetterQueue`, `FailedElement`, `FailureStrategy`, `ReliabilityConfig`, `RetryPolicy`, `RetryExecutor` |
| `reliability.deduplication` | 5 | `Deduplicator`, `SetDeduplicator`, `BloomFilterDeduplicator`, `WindowedDeduplicator`, `DeduplicatorFactory` |
| `reliability.ratelimit` | 9 | `RateLimiter`, `NamedRateLimiter`, `RateLimiterRegistry`, `RateLimitingSink`, 3 in-memory implementations, 2 Redis implementations |
| `reliability.metrics` | 4 | `ReliabilityMetrics`, `ReliabilityMetricsCollector`, `RateLimitMetrics`, `RateLimitMetricsCollector` |

Boundary notes:

- The root package's `DeadLetterQueue` is a **pure in-memory** queue (`ConcurrentLinkedQueue`); nothing lands in Redis. **The Redis-side DLQ lives in the mq module** (`io.github.cuihairu.redis.streaming.mq.dlq`'s `DeadLetterService` / `DeadLetterAdmin` / `DeadLetterConsumer`, stored as a Redis Stream with the default key `stream:topic:{topic}:dlq`, see `DlqKeys.dlq(topic)`) — see [MQ](MQ.md).
- The Redis implementations of dedup/rate limiting depend on Redisson (`implementation libs.redisson` in the module's `build.gradle`).
- The Spring Boot configuration keys are not in this module; see "Spring Boot configuration (starter)" below.

## Failure Strategies and Retry

### FailureStrategy (enum)

`FAIL_FAST` / `RETRY` / `SKIP` / `DEAD_LETTER_QUEUE` / `IGNORE` — the five values of `ReliabilityConfig`; this module does not wire these strategies into any execution engine's scheduler — `ReliabilityConfig` + `RetryExecutor` + `DeadLetterQueue` are building blocks for the upper layer to assemble itself.

### RetryPolicy (the retry policy)

`RetryPolicy implements Serializable`, Lombok `@Builder`; fields and Builder defaults:

| Field | Type | Default | Notes |
|---|---|---|---|
| `maxAttempts` | `int` | `3` | retry count (excluding the first attempt) |
| `initialDelay` | `Duration` | `Duration.ofMillis(100)` | the wait before the first retry |
| `maxDelay` | `Duration` | `Duration.ofSeconds(10)` | the per-wait cap (capped in ms) |
| `backoffMultiplier` | `double` | `2.0` | the exponential backoff factor |
| `exponentialBackoff` | `boolean` | `true` | whether exponential backoff is enabled |
| `retryableExceptions` | `Class<? extends Exception>[]` | `null` | whitelist: once set, only matching exceptions retry |
| `nonRetryableExceptions` | `Class<? extends Exception>[]` | `null` | blacklist: matching exceptions never retry (checked first) |

Key methods:

```java
long getDelayForAttempt(int attemptNumber); // 1-based; delay = initialDelay * backoffMultiplier^(n-1), capped at maxDelay; n<=0 returns 0
boolean isRetryable(Exception exception);   // blacklist first, then whitelist; when neither is set every exception returns true
static RetryPolicy defaultPolicy();         // = RetryPolicy.builder().build()
static RetryPolicy noRetry();               // maxAttempts(0)
static RetryPolicy fixedDelay(int maxAttempts, Duration delay); // exponentialBackoff(false)
```

### RetryExecutor (executing retries)

```java
RetryPolicy policy = RetryPolicy.builder().maxAttempts(3).build();  // from RetryExecutorTest
RetryExecutor executor = new RetryExecutor(policy);

String result = executor.execute(input -> "success", "input");       // <T,R> R execute(Function<T,R>, T)
executor.execute((RetryExecutor.RunnableWithException) () -> {       // execute(RunnableWithException)
    attempts.incrementAndGet();
    throw new IllegalStateException("boom");
});
executor.getPolicy();
```

Filtering retries by exception type (from `RetryExecutorRunnableTest`):

```java
RetryPolicy policy = RetryPolicy.builder()
        .maxAttempts(2)
        .initialDelay(Duration.ofMillis(1))
        .retryableExceptions(new Class[]{java.io.IOException.class})  // only IOException retries
        // or .nonRetryableExceptions(new Class[]{IllegalStateException.class})
        .build();
```

Behavior notes (source semantics):

- Total attempts = `maxAttempts + 1` (the first try + `maxAttempts` retries), never below 1.
- A caught `RuntimeException` whose cause is an `Exception` is unwrapped first, then judged by `isRetryable` (the whitelists/blacklists match the root cause).
- The retry interval uses `Thread.sleep`; on interruption it restores the interrupt flag and throws `RuntimeException("Retry interrupted", ie)`.
- After all attempts fail, the last (unwrapped) exception is thrown.

### ReliabilityConfig (the failure-handling configuration)

`ReliabilityConfig<T> implements Serializable`, Lombok `@Builder`:

| Field | Type | Default | Notes |
|---|---|---|---|
| `failureStrategy` | `FailureStrategy` | `FailureStrategy.RETRY` | the failure strategy |
| `retryPolicy` | `RetryPolicy` | `RetryPolicy.defaultPolicy()` | required by the RETRY strategy |
| `deadLetterQueue` | `DeadLetterQueue<T>` | `null` | required by the DEAD_LETTER_QUEUE strategy |
| `onFailureCallback` | `Function<FailedElement<T>, Void>` | `null` | the failure callback (logging/metrics) |
| `continueOnFailure` | `boolean` | `true` | whether to continue after a failure |
| `maxConsecutiveFailures` | `int` | `10` | the consecutive-failure cap; must be positive |

`validate()`'s four rules (violations throw `IllegalArgumentException`): the strategy is non-null; `RETRY` must carry a `retryPolicy`; `DEAD_LETTER_QUEUE` must carry a `deadLetterQueue`; `maxConsecutiveFailures > 0`.

Static factories: `withRetry()`, `withDeadLetterQueue(int queueSize)`, `failFast()` (`FAIL_FAST` + `continueOnFailure(false)`).

### DeadLetterQueue / FailedElement (the in-process dead-letter queue)

```java
DeadLetterQueue<String> dlq = new DeadLetterQueue<>();      // unbounded (Integer.MAX_VALUE)
DeadLetterQueue<String> small = new DeadLetterQueue<>(2);   // maxSize must be > 0, otherwise IllegalArgumentException (B-35)

dlq.add("element1", exception, 1);        // boolean: false when full (a CAS-based reservation that never passes maxSize)
FailedElement<String> failed = dlq.poll(); // removes and decrements; null when empty
dlq.peek(); dlq.getAll(); dlq.size(); dlq.isEmpty(); dlq.isFull(); dlq.clear(); dlq.getMaxSize();

failed.getElement(); failed.getException(); failed.getErrorMessage(); // "Unknown error" when the exception message is empty
failed.getTimestamp(); failed.getAttemptCount();
failed.getStackTrace();   // the stack trace as a string; "" when exception is null
failed.canRetry(5);       // attemptCount < maxAttempts
```

`clear()` polls element by element and decrements the count (rather than `queue.clear()` + zeroing), keeping the count consistent with concurrent adds (B-35). The queue's element type is `FailedElement<T>` (Lombok `@Data`, `Serializable`).

`FailureStrategy` and `ReliabilityConfig` in typical use (from `ReliabilityConfigTest`):

```java
ReliabilityConfig<String> config = ReliabilityConfig.<String>withDeadLetterQueue(1000);
config.validate();                                  // the DEAD_LETTER_QUEUE strategy validates the queue is configured
config.getDeadLetterQueue().add("payload", new IllegalStateException("boom"), 3);
```

## Message Deduplication

### The Deduplicator interface

```java
public interface Deduplicator<T> {
    boolean isDuplicate(T element);
    void markAsSeen(T element);
    default boolean checkAndMark(T element); // the default: isDuplicate + markAsSeen (not atomic; prefer the overridden versions)
    void clear();
    long getUniqueCount();
}
```

All three implementations store in Redis and extract the dedup key via `Function<T, String> keyExtractor`; a `null` element always returns "not duplicate" and writes nothing.

### SetDeduplicator (exact dedup)

```java
SetDeduplicator<String> d = new SetDeduplicator<>(redisson, "dedup", s -> s);  // from DeduplicatorsTest
d.checkAndMark("x");   // the underlying RSet.add: false the first time (new), true afterwards (duplicate) — atomic across processes too
d.isDuplicate("x");
d.getUniqueCount();    // RSet.size()
d.containsKey("k"); d.remove("k"); d.clear();
```

Stored as a Redis Set. Exact, no false positives; keys have **no TTL** — long-running deployments must `clear()`/`remove()` themselves. Use it when cross-process "check and mark" atomicity is required (the Bloom filter cannot provide it; see below).

### BloomFilterDeduplicator (probabilistic dedup)

```java
// the default false-positive rate 0.03
BloomFilterDeduplicator<String> d1 = new BloomFilterDeduplicator<>(redisson, "bf", 10, s -> "k1");
// an explicit false-positive rate
BloomFilterDeduplicator<String> d2 = new BloomFilterDeduplicator<>(redisson, "bf", 10, 0.001, s -> "k1");
// or via the factory
DeduplicatorFactory.createBloomFilter(redisson, "bf", 10L, s -> "k1");
DeduplicatorFactory.createBloomFilter(redisson, "bf", 10L, 0.001, s -> "k1");
```

- Constructor validation: `expectedInsertions <= 0` or `falseProbability` outside the open interval `(0, 1)` → `IllegalArgumentException`; the first construction `tryInit`s the Redis key (an existing one is not re-initialized).
- `checkAndMark` is atomic **within one process** via `synchronized` (B-19); Redisson's `RBloomFilter` has no server-side check-and-add, so **across processes** two instances may both see a "new element" — use `SetDeduplicator` for exact cross-process dedup.
- `getUniqueCount()` is a local count of this process's successful `add`s (not the Redis-side exact cardinality); `count()` returns the Bloom filter's approximate count; `getExpectedFalseProbability()` reads the false-positive rate back.
- `clear()` deletes the key and then `tryInit`s again (otherwise the deleted-but-uninitialized filter makes later operations throw, B-12).

### WindowedDeduplicator (time-window dedup)

```java
WindowedDeduplicator<String> d = new WindowedDeduplicator<>(redisson, "dedup:orders", Duration.ofHours(1), s -> s);
if (!d.checkAndMark(event)) {  // a new element → false
    process(event);
}
d.getWindowDuration();  // Duration
d.getRemainingTtl();    // the key's remaining TTL in ms; -1 = no expiry, -2 = the key does not exist
d.getUniqueCount();     // the in-window element count after pruning
d.clear();
```

Stored as a Redis Sorted Set with score = the last-seen millisecond timestamp:

- Each element expires at "last seen + window duration" (pruned by score on write, so the set's size ≈ traffic × window); the window boundary is **exclusive**: an element arriving exactly at the window duration no longer counts as a duplicate.
- `checkAndMark` decides atomically with `ZADD NX`, then refreshes an existing element's last-seen time (B-09).
- The whole key carries a fallback TTL of `windowDuration + TTL_MARGIN` (`TTL_MARGIN = Duration.ofSeconds(60)`), reclaiming the key after traffic stops.
- The first access migrates the legacy plain-Set layout (members taking the migration moment as their last-seen time).
- Constructor validation: `redissonClient`/`name`/`windowDuration`/`keyExtractor` non-null and `windowDuration` positive, otherwise `IllegalArgumentException`.

### DeduplicatorFactory

```java
DeduplicatorFactory.createBloomFilter(redisson, name, expectedInsertions, keyExtractor);
DeduplicatorFactory.createBloomFilter(redisson, name, expectedInsertions, falseProbability, keyExtractor);
DeduplicatorFactory.createSet(redisson, name, keyExtractor);
DeduplicatorFactory.createWindowed(redisson, name, windowDuration, keyExtractor);

// create by strategy (the strategies' default parameters are hardcoded in create)
Deduplicator<String> d = DeduplicatorFactory.create(
        DeduplicatorFactory.DeduplicationStrategy.WINDOWED, redisson, "name", s -> s);
```

The `DeduplicationStrategy` enum and `create(strategy, ...)`'s default parameters:

| Strategy | Implementation | Defaults |
|---|---|---|
| `BLOOM_FILTER` | `BloomFilterDeduplicator` | `expectedInsertions = 1_000_000` (false-positive rate 0.03) |
| `SET` | `SetDeduplicator` | none (an exact set) |
| `WINDOWED` | `WindowedDeduplicator` | `Duration.ofHours(1)` |

An unknown strategy throws `IllegalArgumentException`.

## Rate Limiting

### The RateLimiter interface

```java
public interface RateLimiter {
    default boolean allow(String key) { return allowAt(key, System.currentTimeMillis()); }
    boolean allowAt(String key, long nowMillis);  // true = allow; false = rate limited
}
```

`key` is the logical bucket key (e.g. a userId/IP). There is no `tryAcquire()`-style method; the decision points are `allow` / `allowAt`.

### In-memory implementations (in-process; for a single machine or tests)

| Class | Constructor | Validation |
|---|---|---|
| `InMemorySlidingWindowRateLimiter` | `(long windowMs, int limit)` | `windowMs > 0`, `limit > 0` |
| `InMemoryTokenBucketRateLimiter` | `(double capacity, double ratePerSecond)` | `capacity > 0`, `ratePerSecond > 0` |
| `InMemoryLeakyBucketRateLimiter` | `(double capacity, double leakRatePerSecond)` | `capacity > 0`, `leakRatePerSecond > 0` |

- Sliding window: a per-key timestamp queue; old timestamps `<= now - windowMs` are dropped and the request passes only when `size < limit`.
- Token bucket: tokens refill per key at millisecond precision, **new keys start full** (an initial burst allowed); requests are refused when tokens run out.
- Leaky bucket: drains at `leakRatePerSecond`; a request passes only when `water + 1 <= capacity`, and **new keys start empty**.
- All three implementations evict keys that have "fully drained their state" (threshold `DEFAULT_SWEEP_THRESHOLD = 256`; a package-visible three-arg constructor takes a test threshold), preventing high-cardinality keys from blowing up the map (B-34); observe via `trackedKeyCount()`.

```java
RateLimiter sliding = new InMemorySlidingWindowRateLimiter(1000, 5); // 5 per second
RateLimiter token = new InMemoryTokenBucketRateLimiter(10, 5);       // burst 10, steady 5/s
if (!sliding.allow("user:1")) { /* refuse */ }
sliding.allowAt("user:1", 1_000L); // test/virtual time
```

### Redis implementations (shared across instances)

```java
RateLimiter rl = new RedisSlidingWindowRateLimiter(redisson, "streaming:rl", 1000, 100);
RateLimiter tb = new RedisTokenBucketRateLimiter(redisson, "streaming:tb", 100, 50);
```

- `RedisSlidingWindowRateLimiter(RedissonClient, String keyPrefix, long windowMs, int limit)`: `windowMs`/`limit` must be positive; a null/blank `keyPrefix` falls back to `"streaming:rl"`. A Lua script atomically performs `ZREMRANGEBYSCORE` (trim out-of-window) → `ZCARD` → `ZADD` when under the limit (member `now:seq`, with seq generated by INCRBY on a same-slot `:seq` key) + `PEXPIRE window`. The data key is `keyPrefix + ":{" + key + "}"` and the seq key its `:seq` suffix (the brace hash tag guarantees same-slot in Cluster).
- `RedisTokenBucketRateLimiter(RedissonClient, String keyPrefix, double capacity, double ratePerSecond)`: `capacity`/`ratePerSecond` must be positive; a null/blank `keyPrefix` falls back to `"streaming:tb"`. A Lua script atomically refills + consumes on a HASH (fields `tokens`/`ts`); the key TTL = `max(2000ms, twice the full-refill duration)`. The data key is `keyPrefix + ":{" + key + "}:tb"`.
- Both run their Lua on every `allowAt`; there is no special handling of Lua return anomalies (a null return counts as a refusal).

### NamedRateLimiter / RateLimiterRegistry

```java
// wrap as a named limiter: every allowAt reports incAllowed/incDenied through RateLimitMetrics
RateLimiter named = new NamedRateLimiter("sliding-demo", new InMemorySlidingWindowRateLimiter(1000, 5));

// a named registry: defensively copied at construction (Map.copyOf); get() returns null for unregistered names; all() is an immutable view
RateLimiterRegistry registry = new RateLimiterRegistry(
        Map.of("api1", new InMemorySlidingWindowRateLimiter(1000, 10),
               "api2", new InMemoryTokenBucketRateLimiter(100, 50)));
RateLimiter l = registry.get("api1");   // may be null
registry.all();
```

### RateLimitingSink (rate limiting for a StreamSink)

```java
public class RateLimitingSink<T> implements StreamSink<T> {
    public enum DenyPolicy { DROP, THROW }
    // a null denyPolicy is treated as DROP
    public static <T> RateLimitingSink<T> drop(RateLimiter, Function<T,String> keySelector, StreamSink<T> delegate);
    public static <T> RateLimitingSink<T> throwing(RateLimiter, Function<T,String> keySelector, StreamSink<T> delegate);
}
```

`invoke(T value)`: takes the key via `keySelector` (a null key maps to the string `"null"`); when allowed it delegates to the delegate; when refused, `DROP` discards silently and `THROW` throws the nested `RateLimitingSink.RateLimitedException` (a `RuntimeException`). Full usage in the examples below.

## Metric Hooks

Static facades with Noop defaults; `setCollector` ignores null:

```java
RateLimitMetrics.get().incAllowed(name);   // RateLimitMetricsCollector: incAllowed(name) / incDenied(name)
RateLimitMetrics.get().incDenied(name);
```

- `RateLimitMetrics`'s only producer is `NamedRateLimiter` (every decision reports once wrapped).
- The DLQ's replay/delete/clear metrics do not run through this module: the mq module's DLQ operations all report into `mq.metrics.MqMetrics`, bridged to Micrometer by the starter's `MqMicrometerCollector` (`redis_streaming_dlq_replay_success_total` etc.; see docs/Metrics.md). A standalone `ReliabilityMetrics` dead bridge (zero callers) once existed and was removed in the metrics unification v1.
- The Spring Boot starter auto-installs `RateLimitMicrometerCollector` when Micrometer is on the classpath and a `MeterRegistry` bean exists, with the metric names:
  - `redis_streaming_rl_allowed_total` / `redis_streaming_rl_denied_total` (tag `name` = the limiter's name)

## Spring Boot Configuration (starter)

The auto-configuration class `RedisStreamingRateLimitAutoConfiguration` activates on `redis-streaming.ratelimit.enabled=true`; the configuration prefix is `redis-streaming` (`RedisStreamingProperties.RateLimitProperties`):

| Key | Type | Default | Notes |
|---|---|---|---|
| `redis-streaming.ratelimit.enabled` | boolean | `false` | the switch (affects auto-configuration only) |
| `...ratelimit.backend` | String | `memory` | `memory` or `redis`; redis without a `RedissonClient` falls back to memory with a warning |
| `...ratelimit.window-ms` | long | `1000` | the sliding-window width (the single default limiter when no `policies` are set) |
| `...ratelimit.limit` | int | `100` | the in-window allow cap |
| `...ratelimit.key-prefix` | String | `streaming:rl` | the Redis key prefix (backend=redis) |
| `...ratelimit.policies` | Map\<String, Policy\> | empty | named policies; when non-empty a `RateLimiterRegistry` is built by name |
| `...ratelimit.default-name` | String | `default` | the name of the single `RateLimiter` bean |

The `policies.<name>` (`RateLimitProperties.Policy`) fields:

| Field | Type | Default | Notes |
|---|---|---|---|
| `algorithm` | String | `sliding` | `sliding` / `token-bucket` / `leaky-bucket`; an unknown value warns and falls back to sliding |
| `backend` | String | `memory` | `memory` / `redis`; only `sliding` and `token-bucket` support redis — `leaky-bucket` is always the in-memory implementation |
| `window-ms` | long | `1000` | used by sliding |
| `limit` | int | `100` | used by sliding |
| `capacity` | double | `100.0` | the token/leaky bucket capacity |
| `rate-per-second` | double | `100.0` | the token refill rate / the leaky drain rate |
| `key-prefix` | String | `streaming:rl` | the redis-backend key prefix |

Spring injection and usage (note: the decision point is `allow`, and the refusal exception is `RateLimitingSink.RateLimitedException`):

```yaml
redis-streaming:
  ratelimit:
    enabled: true
    backend: redis
    default-name: api1
    policies:
      api1:
        algorithm: sliding
        window-ms: 1000
        limit: 10
      api2:
        algorithm: token-bucket
        capacity: 100
        rate-per-second: 50
```

```java
@Autowired
private RateLimiterRegistry rateLimiterRegistry;

public void handleRequest(String policyName) {
    RateLimiter limiter = rateLimiterRegistry.get(policyName);   // null when unregistered; null-check or fall back
    if (limiter == null || !limiter.allow("req")) {
        throw new RateLimitingSink.RateLimitedException("Rate limited");
    }
    // process the request
}
```

The auto-configuration provides these beans: a `RateLimiterRegistry` (every implementation wrapped in a `NamedRateLimiter`, which reports `redis_streaming_rl_*`) and a `@Primary RateLimiter` (the `default-name` entry; when missing it falls back to any registry entry, then to `new InMemorySlidingWindowRateLimiter(1000, 100)`).

## Usage Examples

### A rate-limited sink (from the examples' RateLimitExample)

```java
import io.github.cuihairu.redis.streaming.reliability.ratelimit.*;
import io.github.cuihairu.redis.streaming.sink.print.PrintSink;

RateLimiter sliding = new InMemorySlidingWindowRateLimiter(1000, 5); // 5 req/s
RateLimiter token = new InMemoryTokenBucketRateLimiter(10, 5);       // burst 10, steady 5/s
var sink = new PrintSink<String>("RL");

var slidingSink = RateLimitingSink.drop(new NamedRateLimiter("sliding-demo", sliding), s -> "user:1", sink);
var tokenSink = RateLimitingSink.drop(new NamedRateLimiter("token-demo", token), s -> "user:1", sink);

slidingSink.invoke("msg-1");   // calls over the limit are DROPped
```

### A named limiter with metrics + the refusal exception

```java
RateLimitingSink<String> sink = RateLimitingSink.throwing(
        new NamedRateLimiter("api1", new RedisSlidingWindowRateLimiter(redisson, "streaming:rl", 1000, 100)),
        s -> "user:1",                          // Function<T,String>: the rate-limit key per element
        delegateSink);                          // StreamSink<String>
try {
    sink.invoke("msg-1");                       // StreamSink#invoke; throws RateLimitingSink.RateLimitedException when limited
} catch (RateLimitingSink.RateLimitedException e) {
    // handle the refusal
}
```

### Dedup before processing (from DeduplicatorsTest / the WindowedDeduplicator contract)

```java
WindowedDeduplicator<String> dedup = new WindowedDeduplicator<>(
        redisson, "dedup:orders", Duration.ofHours(1), s -> s);

if (!dedup.checkAndMark("order-1")) {   // false = a new element (atomically marked); true = an in-window duplicate
    System.out.println("new element");
}
// or via the factory
Deduplicator<String> d = DeduplicatorFactory.create(
        DeduplicatorFactory.DeduplicationStrategy.SET, redisson, "dedup", s -> s);
```

### Retry + DLQ (from RetryExecutorTest / DeadLetterQueueTest)

```java
RetryExecutor retry = new RetryExecutor(RetryPolicy.builder()
        .maxAttempts(3)
        .initialDelay(Duration.ofMillis(100))
        .build());
String out = retry.execute(input -> "success", "input");   // throws the last (unwrapped) exception after all attempts fail

DeadLetterQueue<String> dlq = new DeadLetterQueue<>(1000);
if (!dlq.add("payload", new IllegalStateException("boom"), 3)) {
    // full: returns false, the element is refused
}
for (FailedElement<String> e : dlq.getAll()) {
    if (e.canRetry(5)) { /* decide whether to replay by attemptCount */ }
}
```

## Related Documentation

- [MQ](MQ.md) - the Redis-side DLQ (the `mq.dlq` package) and message retries
- [Spring-Boot-Starter](Spring-Boot-Starter.md) - rate-limit auto-configuration and the Micrometer bridge
- [exactly-once](exactly-once.md) - end-to-end semantics (where dedup sits among them)
