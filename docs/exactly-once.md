# Exactly-Once Roadmap Design (Redis Runtime)

This page describes the roadmap and boundaries for `runtime` (especially its Redis runtime) to reach "end-to-end exactly-once side effects", laying out the candidate approaches and a recommended phased strategy.

> Terminology: exactly-once here means **sink-side effects happening exactly once**. In distributed systems, "a message is consumed exactly once" is often unreachable or prohibitively expensive; engineering practice pursues "effects exactly once", implemented via **transactional commit** or **idempotent writes**.

## Current State (present-day capabilities)

What the Redis runtime provides today is at-least-once plus a stronger alignment-based consistency foundation:

- ACK after `SUCCESS` by default (at-least-once).
- End-to-end checkpoints (experimental): with `deferAckUntilCheckpoint=true`, a successfully processed message is not ACKed until the checkpoint completes, at which point ACKs happen in one batch; checkpoint metadata carries `sinkCommitted` (a snapshot meta field plus a `runtime:sinkCommitted:{checkpointId}` marker), and recovery takes the latest checkpoint with `sinkCommitted=true` as the baseline — uncommitted checkpoints are never used as restore points. The single exception: the newest "stored but not committed" two-phase epoch (holding a transaction handle, neither discarded via `markTxnEpochAborted` nor superseded by a newer committed checkpoint) is restored together with its handle and compensated via `recoverAndCommit` (see `restoreFromLatestCheckpointOrNull` / `getLatestInDoubtTwoPhaseCheckpoint`).
- Sink hooks (experimental): `CheckpointAwareSink` supports `onCheckpointStart/onCheckpointComplete/onCheckpointAbort/onCheckpointRestore` for deferring side effects to the checkpoint completion point.
- Sink deduplication (best-effort): `sinkDeduplicationEnabled=true` performs "runtime deduplication" on `x-original-message-id`, lowering the probability of duplicate writes caused by replays/retries, but it is not a strict exactly-once guarantee.

This makes the checkpoint a consistent restore point for (state + offsets), more trustworthy than bare at-least-once; but side effects can still happen non-atomically around the checkpoint, and strict exactly-once side effects are not yet achieved.

## Constraints and Difficulties (why exactly-once is hard)

1. Cross-system atomicity is unattainable: exactly-once requires making "write the sink + commit the source offset (or ACK)" one indivisible atomic action; when the sink lives outside Redis (JDBC/Kafka/HTTP), it is inherently cross-system.
2. Redis Streams ACKs are not bound to external transactions: Redis's `XACK` cannot commit in the same transaction scope as an external system's transaction.
3. Failure windows: the classic "hardest" windows are:
   - checkpoint written (state+offsets) but sink commit failed/not executed;
   - sink commit executed but ACK not executed;
   - ACK executed but sink commit not executed (this one must be avoided).

The core of the roadmap design is therefore: **in any failure window, recovery logic must guarantee side effects land exactly once in the end** (either transactional commit, or guaranteed idempotent writes).

## Candidate Approaches (easy to hard)

### Approach A: Idempotent sinks (recommended as the v1 baseline)

Push "dedup/idempotency" down into the sink's target storage; a unique key / idempotent write interface ensures repeated writes do not produce duplicate effects.

Prefer a stable event id as the idempotency key, then `mq.Message.id`/`x-original-message-id`; in practice a business primary key is most common. At the implementation level:

- JDBC: `INSERT ... ON CONFLICT DO NOTHING` / `INSERT IGNORE` / unique index + upsert
- Redis: a Lua "check-and-set", or `SETNX`/`HSETNX` + TTL
- Kafka: idempotent producer (producer-side idempotency only; not equivalent to end-to-end exactly-once)

Combined with the runtime's existing three switches: `deferAckUntilCheckpoint=true` aligns the ACK to the checkpoint completion point; `CheckpointAwareSink` defers side effects to `onCheckpointComplete`; `sinkDeduplicationEnabled` can stay as an extra runtime-level safety net, but it cannot replace a truly idempotent sink.

Low implementation cost and broadly applicable; it can land incrementally without touching the runtime's core protocol; the price is that exactly-once depends on the external storage's idempotency capability, and the data model must have a unique key.

### Approach B: Two-Phase Commit Sink (2PC, recommended as v2)

A Flink-style two-phase-commit sink that treats the checkpoint as the transaction boundary:

1) `beginTxn()`: begin a sink transaction (or a "committable but invisible" write session)
2) `preCommit(txn)`: prepare the pre-checkpoint side effects (flush into the transaction/buffer)
3) Write the checkpoint (state+offsets+txn handle): persist the transaction handle into the checkpoint
4) `commit(txn)`: after the checkpoint succeeds, commit the sink transaction; then mark the checkpoint `sinkCommitted=true`; finally ACK/advance offsets

The API has landed: `core` defines `TwoPhaseCommitSink<T, Txn extends Serializable>`; for the full signatures and the deltas from the proposal see the As-built section below.

Recovery semantics are the crux. On restore, the `sinkCommitted` marker in the checkpoint decides: a present marker means the handle was already committed — skip; a missing marker (crash between store and commit) means `recoverAndCommit` compensates on the retained handle. ACKs happen after `markSinkCommitted`, so the state "ACK advanced but sink uncommitted" never occurs.

On transaction-capable sinks this yields strict exactly-once side effects; the price is implementation complexity — every sink must implement the transaction protocol, and it is unusable when the external system has no transactions.

The As-built follows the implementation landed on 2026-09-28; the deltas from the proposal above:

- API (`core/.../api/stream/TwoPhaseCommitSink.java`): `Txn beginTxn()` (no checkpointId argument; the runtime **lazily opens** it before the epoch's first message and opens a new epoch automatically after commit/abort), `invoke(T value, Txn txn)`, `preCommit(Txn)`, `commit(Txn)`, `abort(Txn)` (defaults to delegating `recoverAndAbort`), `Txn recoverAndCommit(Txn)` / `Txn recoverAndAbort(Txn)`. `Txn extends Serializable`. The single-argument `invoke(T)` is bridged to fail-fast (2PC sinks disallow direct writes).
- Handle persistence: `TwoPhaseCommitCoordinator` (runtime) encodes handles with Java serialization + Base64, storage-agnostic (works with both the in-memory and Redis checkpoints); snapshot key `runtime:txns`, map key `"runnerIndex:sinkIndex"`, empty maps write no key.
- The runtime ordering is fixed at `prepareCommit → storeCheckpoint(handle) → commit → markSinkCommitted → ack`; empty epochs also preCommit (the handle always lands in the checkpoint).
- A commit failure is not compensated with an abort: the handle is already in the checkpoint, and discarding it would lose data — the epoch stays in-doubt, and the recovery path replays `recoverAndCommit` from the stored handle (idempotent). Only preCommit failures or store failures go through `abort`.
- Recovery compensation: after restore, replay by runner index — `sinkCommitted` marker present → handle already expired, skip; marker missing (crash between store and commit) → `recoverAndCommit` each in turn; compensation failures only log and never block startup.
- The reference implementation is `RedisOutboxSink<T>` (Approach C's outbox is driven through the 2PC API: preCommit XADDs record by record, commit flips the epoch state to COMMITTED). Its delivery semantics are at-least-once; end-to-end exactly-once needs the target to be idempotent on a stable record id (Approach C's compromise).
- Tests: `TwoPhaseCommitSinkTest` (6), `TwoPhaseCommitCoordinatorTest` (9), `RedisPipelineRunnerTwoPhaseCommitTest` (5), `TwoPhaseCommitFaultInjectionTest` (11, including crash-after-store-before-commit / commit-throws / store-failure injections), `TwoPhaseCommitRecoveryCompensationTest` (4); plus 28 sink/dispatcher/fault cases on the v2.5 side. All of these are mock/unit-level tests; integration tests with real Redis fault injection and recovery are not yet provided.

### Approach C: Outbox / WAL (recommended as v2.5 or for specific scenarios)

Write side effects into a "recoverable outbox" (e.g. a Redis Stream/Hash/table) whose writes share the checkpoint's domain (inside Redis), then let an async dispatcher deliver the outbox to external systems.

External delivery becomes a retryable post-processing step, and the checkpoint only needs to guarantee outbox records are neither lost nor duplicated. The precondition is that the dispatcher must deliver idempotently (usually still requiring external idempotency or a delivery-dedup table); the price is added latency and storage, and the system becomes "eventually consistent".

### Approach D: Redis-internal exactly-once (single Redis instance / same-slot keys)

If the sink also lives in Redis (and the keys are guaranteed to be on the same Redis instance / same hash slot), you can pack writing the sink (HSET/SET/Stream add), updating the commit frontier (offset), and executing `XACK` into one Lua script executed atomically, obtaining "Redis-local" exactly-once.

Two restrictions: under Redis Cluster, cross-slot keys cannot execute atomically in one Lua script (unless hash tags force co-slotting); and this kind of exactly-once only holds when "the sink is also inside Redis".

There are two checkpoint-aligned enhancements. One is deferring sink side effects to the checkpoint completion point and committing them together after checkpoint complete — e.g. the "commit-on-checkpoint" pattern of `RedisCheckpointedIdempotentListSink`; the other is packing "write sink + XACK + advance commit frontier" into a single Lua atomic execution at checkpoint complete (`RedisAtomicCheckpointListSink` + `RedisExactlyOnceRecord`).

## Recommended Roadmap (phased)

### v1: Exactly-once by Idempotency (ship first, operable)

- State the external semantics explicitly: `deferAckUntilCheckpoint=true` + `CheckpointAwareSink` + "idempotent sink" = end-to-end effects exactly once (dependent on sink-side idempotency).
- Publish a standard idempotency-key recommendation: business primary key first; `x-original-message-id` second; TTL/cleanup policy required.
- Ship at least one "idempotent sink example" (e.g. a Redis idempotent writer or a JDBC upsert sink).
  - Reference implementations: `core` provides `IdempotentRecord<T>`; `runtime` provides `RedisIdempotentListSink<T>` (Lua atomic dedup + RPUSH).
  - Note: under Redis Cluster, Lua scripts require all KEYS in the same hash slot; give the dedup key and the sink key the same hash tag (e.g. `xxx:{job}:seen`, `xxx:{job}:list`).

### v2: Two-Phase Commit Sink (landed — see Approach B's As-built)

- `core`'s `TwoPhaseCommitSink<T, Txn>` and the runtime's `TwoPhaseCommitCoordinator` run `beginTxn -> invoke -> preCommit -> storeCheckpoint(txn handle) -> commit -> markSinkCommitted -> ack`.
- Recovery compensation is implemented: after restore, `recoverAndCommit` replays on the retained handles.
- Fault-injection coverage: `TwoPhaseCommitFaultInjectionTest` (11 cases, including commit-throws, crash-after-store-before-commit, store failure), `TwoPhaseCommitRecoveryCompensationTest` (4 cases); integration tests for real-Redis restart recovery are not yet provided.

### v3: Extend to more sinks (JDBC/Kafka/HTTP)

- JDBC: XA/local transactions + exactly-once (depends on the target database's capabilities)
- Kafka: if the sink is Kafka, it must combine with Kafka producer transactions; but with "source=Redis Streams" this is still a cross-system boundary, usually still needing outbox/2PC or business idempotency.
- HTTP: usually only "idempotent request + retry + dedup token" is possible; strict exactly-once is very hard.

## Relationship with Current Configuration and Operations

- With `deferAckUntilCheckpoint=true`, messages stay in the Redis Streams pending list longer:
  - `mq.claimIdleMs` (`MqOptions.claimIdleMs`, default `300000` i.e. 5 minutes; the Builder clamps ≤0 to 1) must be greater than the checkpoint period (interval + drain), otherwise pending entries may be claimed and processed twice. The Redis runtime validates this relationship at startup and logs a warning when violated (the `interval + drain >= claimIdleMs` branch in `RedisStreamExecutionEnvironment`).
- Engineering exactly-once in production requires:
  - an explicit checkpoint period and failure-recovery policy
  - an explicit lifecycle for sink idempotency keys (TTL, storage cost, reclamation)
  - observability for the failure windows (metrics + structured logs)
