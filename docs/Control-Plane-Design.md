# Control Plane Design (Job Submit / Upgrade / Rollback + Permissions and Audit)

This page describes the scope, constraints, and phased strategy of the `runtime` control plane (P4). The problem the control plane solves: **how jobs are declared, stored, upgraded, and rolled back, and who may do these things and what they did** — while the jobs themselves keep running on the existing execution engines (`RedisStreamExecutionEnvironment` + `RedisJobClient`).

## Current State (present-day capabilities)

- Jobs are built and started inside the application process: `RedisStreamExecutionEnvironment.executeAsync()` returns a `RedisJobClient` (an in-process handle: `cancel/pause/resume/scaleParallelism/triggerCheckpointNow/diagnostics`).
- A job's "reconstruction information" (how the pipeline is assembled, what the configuration is) exists only in the starting process's code; Redis holds state and offsets (checkpoint/offset keyed by `job/topic/group/partition`), but there is no **storage for the job spec**.
- Adjacent capabilities already in place (reused directly by the control plane):
  - `scaleParallelism(n)`: change parallelism at runtime (2026-10-10).
  - Checkpoint forward compatibility: restore across parallelism changes verified (2026-10-10).
  - `RedisLeaderElector`: lease-based election + fencing token (2026-10-01).
  - Key prefix conventions: `streaming:runtime:*` (checkpoint/sinkDedup etc.).

## Constraints and Difficulties

1. **Pipelines are not serializable**: a DataStream graph is composed of lambdas and cannot be stored in Redis. Job specs must therefore be **declarative**: `(pipeline factory name, config map)`; factories register locally on the execution side (the same shape as CDC connector type registration).
2. **Lifecycle ownership crosses processes**: `RedisJobClient` is a JVM-local handle; the control plane cannot directly operate a job in another process. The execution side needs a **resident agent** to reconcile "desired state → local actual state", while the control plane only writes desired state.
3. **Upgrade window semantics**: upgrade = stop old, start new; backlog within the window is not lost (the consumer group and offsets persist, at-least-once), and keyed state migrates across parallelism via checkpoints (verified).
4. **Concurrent writes to the same spec**: two operators upgrading simultaneously would overwrite each other — the spec version number is CAS'd (a Lua compare-and-set), and the loser is rejected.
5. **Permissions**: the framework does not invent an authentication system (same idea as `LoadBalancer`); it provides a **pluggable authorizer** interface + full audit, and the audit record precedes the authorization decision.

## Candidate Approaches

### Approach A: Declarative specs + desired-state reconciliation (recommended v1)

Control plane = spec storage + history + audit + authorization hooks; on the execution side a `JobAgent` (in-app, opt-in) polls/subscribes to the desired state and reconciles locally (launch/stop/upgrade). This is a minimal transplant of the K8s controller model: **no new process needed**; multiple instances each manage their own local jobs, and cross-instance coordination happens only on the spec storage.

### Approach B: Framework-managed executor processes

The control plane launches jobs directly into dedicated worker processes. Best isolation, but it drags in process management and packaging/distribution — deferred to v2 and beyond.

### Approach C: Pure spec/config storage (no execution)

A subset of Approach A, landed first as the v1.0 increment.

## V1 Scope (Approach A)

### Data Model and Redis Layout

Prefix `streaming:runtime:control:` (configurable, following the runtime prefix conventions):

| key | type | content |
| --- | --- | --- |
| `jobs` | Hash | jobName → JobSpec JSON |
| `history:<job>` | List | historical spec versions (the rollback source, capped at N entries) |
| `status:<job>` | Hash | state (`DESIRED_STOPPED/PENDING_DEPLOY/RUNNING/FAILED`), instanceId, error message, updatedAt |
| `audit` | Stream | `{ts, actor, op, job, fromVersion, toVersion, allowed, detail}`, XTRIMmed by length |

`JobSpec` fields: `jobName`, `pipelineFactory` (factory name), `config` (`Map<String,String>`), `parallelism`, `version` (monotonically increasing, the CAS basis), `description`, `updatedBy`, `updatedAt`, `specHash` (content hash, used for reconciliation comparison).

### API (v1.0: storage and clients)

`JobControlPlane`:

- `submit(JobSpec)`: create (duplicate names rejected) → write `jobs` + `status=PENDING_DEPLOY` + audit.
- `get/list`: read a spec / enumerate all.
- `upgrade(jobName, mutator)`: CAS the version → push the old version into `history` → update `jobs` → audit.
- `rollback(jobName)`: take the last entry of `history` as the new desired version (also CAS + audited; the version number still increments, history is preserved as-is).
- `stop(jobName)` / `resume(jobName)`: flip the desired-state bit.
- `reportStatus(job, state, instanceId, detail)`: for `JobAgent` use only.
- `tailAudit(limit)`: audit queries.

Authorization and audit: every mutating method writes the audit record first (actor/op/outcome), then calls `ControlPlaneAuthorizer.authorize(actor, op, job)`; refusals throw `ControlPlaneAccessDeniedException`. The default authorizer allows everything.

### Execution Side (v1.1: JobAgent reconciliation)

An opt-in in-app component with a reconcile loop:

1. Fetch all specs + local `status`, reconcile against locally started jobs.
2. `PENDING_DEPLOY` and unclaimed → claim via `SET NX` (instanceId + TTL; lease semantics of the same shape as the MQ lease) → build the pipeline locally via the factory → `executeAsync` → `reportStatus(RUNNING)`.
3. Desired version ≠ local `specHash` → upgrade flow: `pause` → `triggerCheckpointNow()` → `cancel` → rebuild `executeAsync` → `reportStatus(RUNNING)`; any step failing calls `reportStatus(FAILED)` (the old job is stopped, the backlog is not lost, retries are driven by the reconcile loop). When only `parallelism` changes, the `scaleParallelism` fast path is taken instead, avoiding a full job restart.
4. `DESIRED_STOPPED` and locally held → `cancel` → `reportStatus(DESIRED_STOPPED)`.

Instances each reconcile their own (claiming and ownership are isolated); deployments wanting stricter single-writer guarantees can layer leader election so only the leader agent claims new jobs.

### Permissions and Audit (v1.2: starter integration)

- Spring Boot starter: `redis-streaming.runtime.control-plane.enabled=true` auto-configures `JobControlPlane`, and `redis-streaming.runtime.agent.enabled=true` additionally configures `JobAgent` (the first reconcile round runs at startup); a user-provided `authorizer` bean wins.
- Actor source: passed explicitly as a `JobControlPlane` method argument (ops tools/UI are responsible), falling back to the system property `user.name`.

### Secret Reference Convention (landed 2026-10-12, Security-Hardening plan C2)

- **Convention**: keys in `config` prefixed `secret.` hold **references**, not plaintext, in two schemes: `env:NAME` (environment variable) and `sys:NAME` (system property); the resolution point is on the execution side in `RedisJobLauncher.launch` (the factory receives a resolved copy of the spec; when there is no secret key the original spec passes through unchanged). Resolution failure (the source is missing) fails the deployment (`reportStatus(FAILED)`), and the error names only the key and the missing source — never the value.
- **Lenient compatibility**: `secret.*` values without a scheme pass through literally (the convention is advisory, not enforced; existing specs are unaffected); the control plane logs a warning for such entries at submit/upgrade. Integrators needing Vault/KMS implement their own `JobLauncher` (the framework ships no client — see Security-Hardening's non-goals).
- **specHash masking**: secret **literals** in the hash input are replaced with `***` (hashes of low-entropy plaintext are vulnerable to dictionary attacks); secret **references** participate as-is (they are pointers, not material, and reference changes must be visible to the agent's drift detection — otherwise re-binding `env:A→env:B` would not trigger a redeploy).
- **Audit carries no config**: the audit stream has fixed fields (ts/tenant/actor/op/jobName/versions/allowed/detail) and never records config content; `detail` is composed of agent exception messages, and parser errors carry no values.
- **Rotation semantics**: changing an environment variable/system property's value neither needs nor should touch the spec (the reference is unchanged, specHash is unchanged); to make a running job pick up the new value, run one upgrade to trigger a redeploy (any spec field change bumps version, and the reconcile loop re-runs resolution).

## Non-Goals (v1)

- Cross-process resource quotas and multi-tenant isolation (later P4 items).
- Built-in authentication, secret management (only the authorization hook and audit are provided).
- Framework-managed executor processes (Approach B, v2+).
- Automatic failover launch (after an instance dies, restarting the job is decided by humans/external orchestration; the control plane only exposes the observable state `FAILED/renew timeout`).

## Relationship with Existing Configuration and Operations

- Upgrade/rollback reuse at-least-once + checkpoint semantics; no new delivery guarantees are introduced.
- `triggerCheckpointNow` (with `deferAckUntilCheckpoint=true`) minimizes duplication in the upgrade window; jobs without checkpoints upgrade as a pure at-least-once replay window.
- The audit stream should be wired into existing monitoring (a Micrometer counter can be added at implementation time).

## Phased Plan

- v1.0: `JobSpec` + Redis storage + the `JobControlPlane` API (submit/upgrade/rollback/stop/resume/list/audit) + the authorization hook; unit tests (mock Redisson) + integration tests (real Redis).
- v1.1: `JobAgent` reconciliation (claim/deploy/upgrade/rollback execution/failure reporting) + integration tests (upgrades preserve state, rollback, parallelism-only changes take the fast path).
- v1.2: starter auto-configuration + docs (Spring-Boot-Starter.md / Deployment.md additions).
