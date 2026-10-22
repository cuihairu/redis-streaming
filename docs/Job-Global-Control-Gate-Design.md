# The Job-Level Control Gate Design (defect (2) scoped by the 2026-10-15 patrol proxy decision; **implemented, landed 2026-10-15**)

Status: **landed** (design draft → implemented the same day). This design answers defect (2), found incidentally in the `docs/Checkpoint-Barrier-Design.md` review — the control gate is per-env-instance rather than a job-level global lock. Implementation: `runtime/redis/internal/JobControlGate` (the primitive) + `RedisStreamExecutionEnvironment` wiring; landing details in §7.

## 1. Problem

`runtime/redis/internal/CheckpointGate` (split into two levels 2026-10-14) is a **JVM-local `AtomicBoolean`**: its mutual exclusion covers the control operations of one `RedisStreamExecutionEnvironment` instance (aligned checkpoints, rescaling). Across instances there is no constraint:

- leader election only coordinates **periodic triggering** (`leaderRenewExecutor` decides who opens periodic checkpoints); it does not protect manual `triggerCheckpointNow()`, nor `scaleParallelism()`;
- therefore two `LaunchedJobClient`s of the same job name (overlapping HA takeovers, an ops tool starting a second client, dual control-plane agents, etc.) can run aligned checkpoints **simultaneously**, each in its own instance. Each instance pauses its own consumers, drains its own way, and snapshots into storage:
  - **Torn snapshots**: instance A's consumers keep processing records inside instance B's snapshot window (A cannot see B's full-silence) — B's offsets+state snapshot then covers no whole-job quiesced cut;
  - **Out-of-order storage**: the two writers each `allocateCheckpointId`, and when the later starter writes first the larger id does not necessarily land last; the follower's manual checkpoint is a designed-legal path (carrying a shared epoch token), so restore's max-fencing-token selection **cannot** distinguish the two concurrent writers (same token);
  - the renew-before-store gate landed 2026-10-15 (defect (1)'s fix) only stops writers that "**held the lease but lost it**", not two writers that are **legitimately concurrent**.

## 2. Goals and Non-Goals

**Goal**: extend `CheckpointGate`'s existing two-level mutual exclusion (a serialization level + a full-silence level) to be **job-global** (a Redis namespace), so that at any instant at most one control operation on a given jobName runs on any instance.

**Non-goals**:
- Do not replace `CheckpointGate` — it stays as the in-process layer (the cross-instance gate wraps it; the two-level nesting order is unchanged);
- Do not redesign leader election — the cross-instance gate is orthogonal: the leader coordinates "who opens periodic checkpoints"; the cross-instance gate guarantees "at most one control operation at any instant";
- Do not change the follower's manual-checkpoint legitimacy (the path pinned by `RedisLeaderElectionIntegrationTest`).

## 3. Candidate Approaches

### Approach A: a per-job Redis lease gate (recommended)

Control operations atomically contend for two **TTL lease locks** at begin and Lua compare-and-release at end:

- Keys: `<stateKeyPrefix>:<jobName>:ctl:excl` and `ctl:silence`, values `jobInstanceId:opId`;
- Acquisition order matches `CheckpointGate`: `excl` first, then `silence`, released in reverse; the class invariant `silence ⇒ excl` holds across instances too, so no deadlock;
- TTL ≈ `checkpointDrainTimeout + snapshot slack` (same magnitude; refined in the landing batch), kept alive while held by a renewal thread (the leader lease's approach); a crashed holder self-heals via TTL expiry;
- Redis unreachable → refuse the control operation (fail-safe: better no checkpoint than a torn checkpoint);
- Layered defense with the renew-before-store gate (defect (1)): the cross-instance gate handles "legitimately concurrent" writers; the renewal gate handles "lease lost" writers.

### Approach B: storage-side fencing-token strictness

Make `storeCheckpoint` verify token == the current fence counter top and the leader key's holder being the writer. **No**: the follower's manual checkpoint by design carries a shared epoch token (not a term token), and B would block it too, conflicting with §2's non-goals; and the token is per-epoch, not per-write, so it cannot express "this write is still inside this cut".

### Approach C: forbid followers from manual triggering

`triggerCheckpointNow` refuses when `isLeader()==false`. **No**: it only trims the writer count without removing the overlap window (the old leader finishing while the new leader starts); and it directly kills the follower manual path pinned by tests. Not adopted.

## 4. Interaction with the Existing Control Plane

- **2PC**: the cross-instance gate must cover the whole `onCheckpointStart → prepareTwoPhaseCommits → storeCheckpoint → commitTwoPhaseCommits → markSinkCommitted` span (i.e. the exclusive lease held until phase 2 completes), otherwise two instances could preCommit/commit the same sink concurrently;
- **defer-ack**: `drainForAck`'s freeze and `ackAll` must happen inside one exclusive lease (already guaranteed intra-instance by `triggerCheckpointWhilePaused`'s ordering; holds cross-instance as well);
- **`refreshCheckpointIdFromStorage`**: the cross-instance gate removes the main source of "two writers allocating interleaved ids"; the takeover alignment logic is unchanged.

## 5. Risks

| Risk | Mitigation |
| --- | --- |
| A control operation outlives the TTL and the lock is taken over | renewal thread while held + TTL ≈ a multiple of the drain timeout; the taker's begin pre-checks (takeover allowed only when the old holder's instance is off the leader key) |
| A crashed holder leaves the lock stuck | TTL expiry self-heals (the recovery path already tolerates TTL-scale lag) |
| Redis unreachable | refuse the control operation; checkpoints already fail under outage, so no new failure surface |
| Deadlock | two-level ordered acquisition + reverse-order release; the `silence ⇒ excl` invariant holds cross-instance; `CheckpointGateTest`'s lattice-test pattern is replicated in the landing batch as the cross-instance version |

## 6. Test Plan (the landing batch: tests before implementation)

1. **Dual-client integration**: two `LaunchedJobClient`s of one jobName; while A's `triggerCheckpointNow()` runs, B's concurrent trigger must wait/fail fast (semantics: a new `controlGateWait` setting, default fail-fast with a clear diagnostic);
2. **Rescale mutual exclusion**: while A's `scaleParallelism` runs, both B's checkpoint and B's rescale serialize;
3. **TTL self-healing**: the lock-holding instance is killed (no end sent); the second client's begin succeeds after TTL expiry;
4. **Redis unreachable**: begin refuses; the job's data plane is unaffected;
5. **Lattice invariant**: the cross-instance contention test (multi-thread/multi-client mixed begins, zero overlap).

## 7. Landing Record (2026-10-15, implemented per this design)

Proceeded tests-first; (i)–(iv) all landed:

1. **The primitive** `runtime/redis/internal/JobControlGate`: `SET NX PX` acquisition (reentrant refresh for the same holder value), Lua compare-and-expire renewal, Lua compare-and-delete release, and `tryBeginControlOp(waitMillis)` polling wait (default 0 = fail fast). Keys `<stateKeyPrefix>:<jobName>:ctl:excl` / `:ctl:silence`, values `jobInstanceId:opSeq` — each begin increments `opSeq`, so a begin whose release never reached Redis cannot reuse the old value and must wait for TTL expiry; an expired release (compare-and-delete) is a no-op to the takeover holder. Acquisition failure (including Redis unreachable) always returns false without throwing; a silence-stage failure mid-acquisition hands back the already-won excl stage (preventing a stuck lock from blocking all future control operations).
2. **Switch policy**: the gate follows `leaderElectionEnabled` by default (`jobControlGateEnabled` defaults to null = follow election) and can be overridden either way with `jobControlGateEnabled(Boolean)` without touching election itself; `controlGateWait` defaults to `Duration.ZERO` (fail fast, no queueing behind another's control operation); negatives refuse. TTL = `max(30s, 2 × checkpointDrainTimeout)`, renewal period = TTL/3, kept alive by a daemon thread while held.
3. **Wiring**: inside the in-process block of the single choke point `triggerCheckpointInternal` (shared by periodic and manual) and `scaleParallelism`, the cross-instance gate is always the innermost layer (the in-process `CheckpointGate` acquired first, the cross-instance lease second — a consistent global lock order → no deadlock). **A null gate means "not configured", not "refused"** — the two predicates are kept separate at every call site, avoiding misreading unconfigured as refused (the landing got this wrong once and the wiring unit test pinned it). Refusals follow the existing conventions: `triggerCheckpointNow()` returns null before any consumer is paused; `scaleParallelism` returns false.
4. **§6's tests** all green: `JobControlGateTest` (14 unit tests, a fake Redis lattice + TTL assertions: the fake refuses TTL-less acquire/renew exactly like real Redis), `RedisStreamExecutionEnvironmentControlGateTest` (3 wiring unit tests), `JobControlGateIntegrationTest` (7 integration tests: dual-client mutual exclusion, two-env serialization, cross-instance lattice contention, TTL self-heal + expired release no-op, Redis-unreachable refusal, gate-off fully inert, follower manual checkpoints still persist with the epoch token); `RedisLeaderElectionIntegrationTest` keeps pinning the election semantics with the gate explicitly off.
5. Landing lesson (for future changes of this kind): **integration tests must finalize before the implementation** — the unit-test fake Redis did not validate ARGV arity, so acquire/renew missing the TTL (`ARGV[2]` absent → Redis refuses → the gate always refuses) looked like a normal refusal in unit tests and only surfaced when integration tests ran against real Redis; the fake now mirrors that refusal semantics.
