# Retry / Dead-Letter / Dedup Boundary Design

This page is a **decision record** (no code changes). The three concepts "retry", "dead letter", and "deduplication" each appear in two to three places in the repo, with no dependencies among them; this page fixes each layer's ownership and the hand-over contracts so future implementations converge on it.

## Current State (fact inventory)

The three layers of facilities are **not wired together**: `mq` and `runtime` main code have zero imports of `reliability` (verified by grep); cross-layer references exist only in `spring-boot-starter` (the `ReliabilityMetrics`/`RateLimitMetrics` → Micrometer bridges and configuration classes) and `examples` (ratelimit use cases), and all of them touch only `reliability.metrics`/`reliability.ratelimit`, never retry or dead letters. In short, two parallel retry/DLQ abstractions coexist, each going its own way.

| Facility | Location | Shape | Failure semantics |
| --- | --- | --- | --- |
| Delivery retry | `mq.retry.DeliveryRetryPolicy` (interface: `getMaxAttempts` / `nextBackoffMs`) + `ExponentialBackoffRetryPolicy` | delivery-layer retry decisions, paired with lease/pending takeover | re-deliver until the cap, then to the dead letter |
| Delivery dead letter | the `mq.dlq` package (`DeadLetterService` / `RedisDeadLetterService` / `DeadLetterConsumer` / `ReplayHandler` / `DeadLetterAdmin`) + `DeadLetterQueueManager` | persisted in a Redis stream | ack only after a successful write; **a failed write keeps the entry in the PEL un-acked awaiting redelivery (no event loss)** |
| User-side retry | `reliability.RetryExecutor` + `RetryPolicy` (a POJO config: maxAttempts/initialDelay/backoff) | in-JVM synchronous retry of a `Function` | on exhaustion the exception goes to the caller |
| User-side dead letter | `reliability.DeadLetterQueue` (`ConcurrentLinkedQueue` + `FailedElement`, `Serializable`, capped at maxSize) | **in-process memory** | lost on crash; fit only for in-JVM tooling |
| User-side dedup | `reliability.Deduplicator`: `SetDeduplicator` (Redis `RSet`) / `BloomFilterDeduplicator` (Redis `RBloomFilter`) / `WindowedDeduplicator` (Redisson + window) | cross-process (Redis-backed) or window-local | handled by the caller per the returned flag |
| Sink idempotency | `runtime.redis.sink.RedisIdempotentListSink` (`dedupSetKey` + `dedupTtl`) / `RedisCheckpointedIdempotentListSink` (buffered records flushed with the checkpoint) | exactly-once sink-side output | replay idempotent; the buffer lands atomically with the checkpoint |
| ACK control | `MqHeaders.DEFER_ACK` | consumer-side deferred acknowledgement | orthogonal to retry/dead letter |

## Decision: Responsibilities of the Three Layers

1. **The delivery layer (mq) alone owns "what happens when a message fails to be consumed"**: retry (lease/pending takeover + backoff), dead-letter persistence, replay. It does **no content deduplication** — it is a transport layer and cannot understand business semantics.
2. **The user/operator layer (reliability) owns "business-logic retry and content dedup"**: `RetryExecutor` wraps user functions; `Deduplicator` dedups on business keys. It does **not take over MQ delivery failures** — the semantics of a failed delivery (PEL, replay, DLQ persistence) must stay in mq.
3. **The sink side (runtime) owns "at-least-once delivery → exactly-once effect"**: with the `dedupSetKey` idempotency key + the checkpoint-synchronized flush, replays produce no duplicate output. **A sink must not stack a `Deduplicator` on top** (the idempotency key is already the sink-level dedup).

Hand-over contracts (already honored by existing code, recorded here as invariants):

- An MQ consumption outcome has three states: success → ack; `RETRY` before the cap → re-deliver; `FAIL`/`DEAD_LETTER` → to the dead letter **and acked only after a successful write** — a failed DLQ write keeps the entry in the PEL un-acked awaiting redelivery, guaranteeing no event loss.
- The user-side `DeadLetterQueue` is for in-JVM tooling only (e.g. offline replay, batch-job helpers) and **must not serve as the handling outlet for MQ consumption failures**: a crash loses events.
- For dedup that must cross JVMs (deduplicating business keys when multiple instances consume the same topic) → use the Redis-backed `SetDeduplicator`/`BloomFilterDeduplicator`, not `WindowedDeduplicator` or the in-process `DeadLetterQueue`.

## Anti-Patterns (do not do)

- Calling `RetryExecutor` around a business function inside the MQ consumption loop and managing the PEL yourself: PEL/replay semantics must have exactly one owner (mq's).
- Wiring `reliability.DeadLetterQueue` into the MQ delivery path: an in-memory queue and a persistent DLQ have conflicting semantics.
- Hanging a `Deduplicator` and an idempotent sink on the same sink: two dedup keys with different sources create duplicates or misses.
- Driving mq delivery retries with `reliability.RetryPolicy` (the config POJO): they are different types; the mq side only accepts the `mq.retry.DeliveryRetryPolicy` interface.

## Known Debt (not handled in this batch)

- **`RetryPolicy` with the same name but different types**: resolved — the mq side was renamed to `DeliveryRetryPolicy` on 2026-10-12 (recorded in the javadoc of `mq/src/main/java/.../retry/DeliveryRetryPolicy.java`), while the `reliability.RetryPolicy` config POJO keeps its name (metrics unification plan: [Metrics-Unification-Design.md](Metrics-Unification-Design.md)).
- The `reliability` `RetryPolicy` is a `@Builder` config while the mq side is an interface; unifying them into an "interface + config" shape of the same mold needs an API review — untouched in this batch.

## Non-Goals

- This batch merges/deletes no retry or dead-letter classes (backward compatibility preserved).
- No new retry-scheduling component is introduced (backpressure-style retry scheduling belongs to the multi-tenant quota design, see [Multi-Tenancy-Design.md](Multi-Tenancy-Design.md)).
- The DLQ write-failure "no ack, await redelivery" semantics is unchanged.
