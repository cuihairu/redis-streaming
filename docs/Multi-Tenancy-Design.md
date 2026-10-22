# Multi-Tenancy Isolation and Quota Design

This page is for review and contains no code changes. Goal: the current code has **no tenant concept at all**; when multiple jobs/teams share one Redis, names collide, resources contend, and failure domains bleed into each other. This design lays out a phased plan for namespace isolation and quotas.

## Current State (fact inventory)

1. **Tenant concept (v1 landed 2026-10-11)**: `JobSpec.tenant` (default `StreamKeys.DEFAULT_TENANT`), `MqOptions.tenant`, `AuditEntry.tenant`, and `TenantQuotaPolicy` all exist; among this design's remaining items, the **proxy-decided** dispositions (2026-10-16): the in-flight quota has landed (see "Landing record: v2 quota increments" below), the tenant metric dimension has landed, thread and memory quotas gain no new dimension (reasons in that section), and tenant-level migration plus an ops CLI have landed (see "Landing record: the tenant key migration engine" below) — tenant injection into other modules' key prefixes is under "Landing record" below.
2. **Global static prefix**: mq's `StreamKeys.configure(controlPrefix, streamPrefix)` is a `static volatile` global (defaults `streaming:mq` / `stream:topic`); all MQ keys (partition streams, DLQ, meta, lease, retry, commit frontier, topics registry) derive from it. **When multiple jobs share a JVM this is a global setting; across JVMs, if two applications do not configure different prefixes, same-named topics directly share the same stream**.
3. **Runtime keys**: checkpoint/dedup use the `streaming:runtime:*` prefix (configurable); keyed state keys carry the job dimension (`{stateKeyPrefix}:{job}:...`), so job-level isolation exists naturally, but stateKeyPrefix itself is a global static.
4. **Backpressure/quota granularity**: `maxInFlight` and other memory gates are per-consumer-instance; **there is no cross-tenant rate/capacity quota** (no produce-rate limiting, no stream length quota — autoTrim only trims per-topic settings). 〔2026-10-16 proxy-decided addition: the cross-tenant **declarative** in-flight quota has landed (see "v2 quota increments"); runtime gates remain per-consumer.〕
5. **Observability**: metrics have no tenant/instance dimension (see the metrics unification design); the control plane's audit has actor/job but no tenant.
6. Reusable parts: `reliability` already has `RateLimiter` (a Redis token bucket, per-key limiting); the control plane already has spec storage + an authorizer hook + audit.

## Problems

- Same-named topics/jobs colliding across teams: streams, DLQs, leases, and control-plane specs all trample each other by name.
- One tenant's produce flood eats the shared stream/connection pool/consumer throughput, penalizing other tenants (failure domains and resource domains are not isolated).
- No per-tenant limits (rate, partition count, stream length, checkpoint frequency), and no per-tenant audit or metering.

## Candidate Approaches

### Approach A: Namespace isolation + Redis token-bucket quotas (recommended)

**Isolation = key-prefix injection; quota = reuse RateLimiter; no new infrastructure.**

- **Tenant identity**: `JobSpec` gains a `tenant` field (default `default`); MQ's `MqOptions` gains `tenant`; at startup, `StreamKeys.configure(controlPrefix, streamPrefix)` becomes **tenant-derived**: `{tenantPrefix}:{tenant}:{original key}`, or equivalently `StreamKeys` accepts instance configuration (each producer/consumer/env holds its own `StreamKeys` view, replacing the static volatile — the static volatile is retained as the single-tenant compatibility entry).
- **Control plane**: the spec keyspace is segmented per tenant (`...:{tenant}:jobs`), the authorizer receives the `(tenant, actor, op)` triple for its decision, and audit gains the tenant dimension.
- **Quotas (v1 does exactly these three; that is enough)**:
  1. **Produce rate**: producer sends pass through a `RateLimiter` (key=`tenant:topic`; over-limit defaults to fail-fast + metric, optionally blocking).
  2. **Stream capacity**: autoTrim caps configured per tenant (prevents one topic from growing without bound).
  3. **Total parallelism/partitions**: the control plane validates the tenant quota at spec creation (a Redis counter records the tenant's used parallelism/partitions).
- **Observability**: metrics gain the `tenant` dimension (landed together with the metrics unification design's dimension spec).

### Approach B: Independent Redis per tenant (DB or instance)

The most thorough isolation (network/memory/failure domains fully separated), but connection management, cross-tenant control-plane views, and ops costs all double. Fits as a **large-customer deployment shape** (documented support; the `tenant → Redis URL` mapping is the integrator's job); the framework does not build it into v1.

### Approach C: Pure naming convention (topics named `tenant:topic`), zero code

Smallest change, but unenforceable (one mistyped prefix and it leaks), and quotas/audit are still missing. Serve as the **documentation-level stance** until Approach A lands.

## Migration Path (Approach A, phased)

1. **Step 1**: make `StreamKeys` instantiable (static entry kept for compatibility), add `tenant` to `MqOptions`/`JobSpec`, and add the tenant segment to key derivation; with the default tenant `default` the keys are byte-identical to today (**zero migration cost**).
2. **Step 2**: produce-rate quota (RateLimiter attached to the producer) + per-tenant autoTrim caps; metrics gain the tenant dimension.
3. **Step 3**: the control plane segments the spec keyspace by tenant + quota validation (partitions/parallelism) + the audit dimension.

## Landing Record: Key-Prefix Tenant Injection (2026-10-15)

The unified contract lives in core's `Tenant`: the default tenant `default` produces no key segment at all (byte-identical with the old keys, zero migration), and a non-default tenant inserts a single `:tenant` segment **after the key prefix**; tenant names must match `[A-Za-z0-9._-]{1,64}` (no colons), and invalid values throw `IllegalArgumentException` at construction/startup rather than silently degrading into a shared keyspace.

| Module | Injection point | Key example |
|---|---|---|
| mq | `StreamKeys(prefix, streamPrefix, tenant)` (v1 already) | `streaming:mq:acme:...` |
| registry | `RegistryKeys(keyPrefix, tenant)` / `BaseRedisConfig.setTenant`; Lua scripts receive the **effective prefix** (prefix + tenant segment) | `redis_streaming_registry:acme:services:{svc}:instance:{id}` |
| config | `ConfigServiceConfig.formatKey` | `redis_streaming:acme:config:{group}:{dataId}` |
| state / checkpoint / sink dedup | `RedisRuntimeConfig.Builder.tenant`, derived uniformly at `build()` | `streaming:runtime:acme:...`, `streaming:runtime:checkpoint:acme:{job}:...` |
| sink data plane | tenant constructors on `RedisListSink` / `RedisStreamSink` / `RedisHashSink`, reusing `Tenant.scopedKey` to parse `prefix:tenant:name` (test-first c9cb60e, landed 5a9847e); for List/Stream an empty prefix = the bare name as-is, for HashSink an empty prefix falls back to the default prefix `app`; the old constructors' key shapes are byte-identical | `app:acme:orders` |
| starter | `redis-streaming.mq.tenant`, `redis-streaming.registry.tenant`, `redis-streaming.config.tenant` | — |

Four things to watch:

- **Call order does not matter**: `Builder.tenant(...)` only records the tenant; the key segment is derived at `build()`, so custom `stateKeyPrefix`/`checkpointKeyPrefix`/`sinkDedupKeyPrefix` get the tenant segment too, regardless of setter order (the earlier "custom prefix wins" behavior silently dropped the tenant — two tenants sharing one set of state keys).
- **The redelivery lock once missed the tenant segment**: `RedisMessageConsumer`'s redelivery mover hardcoded the lock key as `streaming:mq:retry:lock:{topic}`, bypassing both the control prefix and the tenant segment — two tenants with the same-named topic would serialize each other (one's redelivery blocking the other's). It is now derived from `StreamKeys.retryLockKey(topic)`; the default-tenant key is byte-identical with the old one.
- **The DLQ admin surface once missed the tenant segment**: `RedisDeadLetterAdmin.listTopics()`'s SCAN used `DlqKeys.dlq()` (no tenant segment) while the DLQ data plane wrote `StreamKeys.dlqKey()` (with tenant) — under tenant deployment, scanning up `acme:orders:dlq` read the topic as `acme:orders`, and the subsequent size/list/replay all landed on the non-existent `acme:acme:orders:dlq` (silently spinning). It now follows the service's key view (`RedisDeadLetterService.keys()`, the same source as the data plane); default-tenant behavior is byte-identical.
- **The starter's MQ admin beans once missed the tenant view**: the starter's four beans `messageQueueAdmin` / `deadLetterService` / `deadLetterConsumer` / `deadLetterQueueManager` each took `StreamKeys.shared()` (the process-level static tenant). That static tenant was set only by the `MessageQueueFactory` constructor's side effect and depended on bean creation order; when a user supplied their own factory bean (the normal `@ConditionalOnMissingBean` backdoor) it was never set, and all four beans fell to the `default` tenant — `redis-streaming.mq.tenant` silently failed and tenant data was written into the shared namespace. Each bean now builds its own tenant key view from `MqOptions` (`StreamKeys.of(opts)` / `RedisMessageQueueAdmin(client, opts)`), independent of creation order; default-tenant keys are byte-identical.
- **cdc needs no change**: CDC only produces into MQ topics, and topic keys are already tenant-segmented; sink target keys are still given explicitly by the caller by default (the old constructors are unchanged), the optional tenant constructors write keys as `prefix:tenant:name` (the sink data-plane row in the table above), and the framework-managed dedup key prefix is already segmented via `RedisRuntimeConfig.Builder.tenant`.

Isolation verification on real Redis (`@Tag("integration")`): one case each for registry and config ("two tenants with the same group/dataId do not read each other + the default tenant keeps the old key shape"), one for runtime ("with the same job name and the same custom prefix, two tenants' state keys and checkpoints are mutually invisible" — `RedisRuntimeTenantIsolationIntegrationTest`), and an mq case pinning the redelivery lock key's per-tenant segmentation.

## Landing Record: v2 Quota Increments (2026-10-16, proxy-decided)

**Declarative in-flight quota** (the memory-gate dimension): `JobSpec.maxInFlight` declares each consumer's in-flight cap (0 = the runtime default, i.e. unbounded), and the control plane validates per tenant that `Σ(parallelism × maxInFlight) ≤ maxTotalInFlightPerTenant` (a new `TenantQuotaPolicy` dimension; 0 = unlimited, the default policy unchanged so zero migration); submit and upgrade reject jobs that **do not declare** it — an unbounded job cannot be accounted for, and admitting it voids the quota. The declaration flows through `RedisJobLauncher.configFor` into `MqOptions.maxInFlight`, enforced by the existing per-consumer semaphore; `maxInFlight` is part of the `specHash` semantic input, so changing only the declaration triggers job drift detection. **Configuration surface**: all three quota items are bindable directly from starter properties (`redis-streaming.runtime.control-plane.max-jobs-per-tenant` / `max-total-parallelism-per-tenant` / `max-total-in-flight-per-tenant`, all default 0 = unlimited, zero migration), and the control-plane bean exposes the effective policy via `RedisJobControlPlane.quotaPolicy()`; previously the three quotas were Java-API-only, and this batch adds the YAML surface.

Proxy decisions and their reasons (the user may overturn them later):

- **No new thread quota dimension**: this engine pairs one consumer thread with each parallel subtask, so thread count ≡ `parallelism`, already covered by v1's `maxTotalParallelismPerTenant`; another "thread quota" would just be an alias of the same quantity — more configuration surface, no more constraint.
- **Memory quota is proxied by the in-flight cap**: no real heap/byte metering — message sizes are unbounded and cannot be aggregated across JVMs, so real metering could only be sampled estimates whose error exceeds the benefit; `in-flight × consumer threads` already gives an enforceable, explainable memory bound.
- **Tenant-level migration and ops CLI**: the hosting home was proxy-decided as `reliability`; the migration engine, the family table, and the CLI entry have all landed (see "Landing record: the tenant key migration engine" below; the CLI entry was originally listed as "to be scoped when real ops scenarios appear" — the 2026-10-16 second-round proxy decision found its unblocking condition is not external, i.e. it is a scheduling item, and un-shelved it for immediate landing).

## Landing Record: the Tenant Key Migration Engine (2026-10-16, proxy-decided)

The tenant segment sits **after** the key prefix, so a family of keys adopting (or leaving) a tenant is a pure **prefix move**: `stream:topic:orders:p:0 → stream:topic:acme:orders:p:0`. The engine lands in `reliability` as `TenantKeyMigrator`, hosted in `reliability` (core already exposes Redisson as `api`, so no new layering edge); the engine **only moves keys** and knows no module semantics — the move list is supplied by the caller (the default list is the table below).

Semantic highlights:

- **Atomic and lossless**: one `RENAMENX` per key; payloads, key types, and TTLs preserved as-is; no half-moved states; an existing target key **refuses to be overwritten** (recorded as skipped, the source key stays put) — never a force rename.
- **Dry-run by default**: `MigrationOptions.dryRun` defaults to `true` — scan and count without touching keys, and probe target collisions the same way, so a trial run shows whether the real run would be clean.
- **Re-entrant**: the move is idempotent per key; after an interruption, re-running only moves keys still under the source prefix; re-running a completed family is a clean no-op.
- **Nested targets are not re-adopted**: in the adoption direction `stream:topic:` → `stream:topic:acme:` the target namespace lies **inside** the source scan pattern, so keys already under the target prefix count as migrated and are skipped (otherwise re-runs would stack the `acme:` segment twice). In the rollback direction the target is not nested, so no special case is needed.
- **Overlapping families belong to the longest prefix**: when prefixes like `streaming:runtime:` and `streaming:runtime:checkpoint:` nest, a key belongs to the **longest** matching source prefix — moved exactly once, never into the wrong family.
- **One bad entry fails the whole run closed**: duplicate source prefixes or identity moves (source = target) in the list reject the run before even scanning — one family's typo cannot drag another family's keys along.
- **Scan before moving**: a family's keys are collected completely before the per-key renames, because renames mutate the keyspace the SCAN is walking.
- **Stop writers first**: keys still being written under the old namespace during migration would land in the old space and become invisible after the switch; stop jobs first, move the family keys, then flip the configuration.

Default move pairs per family (`tenant = acme` example; the tenant segment follows the `Tenant` contract, and an invalid tenant name throws before any pair is built). The prefixes in the table are the **real defaults** in each module's source, now pinned in `TenantKeyFamilies` (the CLI's `--family` takes the first column's name; `--prefix` overrides the second column):

| Family (CLI name) | Source prefix | Adoption target prefix |
|---|---|---|
| mq-control | `streaming:mq` | `streaming:mq:acme` |
| mq-data | `stream:topic` | `stream:topic:acme` |
| runtime-state | `streaming:runtime` | `streaming:runtime:acme` |
| checkpoint | `streaming:runtime:checkpoint:` | `streaming:runtime:checkpoint:acme:` |
| sink-dedup | `streaming:runtime:sinkDedup:` | `streaming:runtime:sinkDedup:acme:` |
| control-plane | `streaming:runtime:control:` | `streaming:runtime:control:acme:` |
| registry | `redis_streaming_registry` | `redis_streaming_registry:acme` |
| config | `redis_streaming` | `redis_streaming:acme` |

Two shape details:

- **The trailing colon has two states**: the mq / runtime-state / registry / config four families' prefixes carry **no** trailing colon (consumers append it themselves), while the checkpoint / sink-dedup / control-plane three families **do** (the runtime config appends the tenant segment directly at the end). `TenantKeyFamilies.scopedPrefix` gives each state its correct joining; mechanically using `Tenant.scopedPrefix` would produce a double colon like `checkpoint::acme` for colon-terminated prefixes.
- **Two nestings**: runtime-state is the string prefix of the checkpoint / sink-dedup / control-plane three rows; and **config (`redis_streaming`) is the string prefix of registry (`redis_streaming_registry`)**. Nested pairs must move **in the same run**; the engine dispatches by longest-prefix ownership; moving only the wide prefix would land checkpoints at `streaming:runtime:acme:checkpoint:...` and registry keys at `redis_streaming:acme_registry:...` — the wrong places. The CLI **refuses to run** a `--family` subset that would swallow an unselected nested family (listing the swallowed ones; `--allow-overlap` opts in explicitly). Leaving a tenant is the same table with its two columns swapped (`--dir out`).

Source prefixes must **delimit exactly the family being moved**: a wide prefix's scan also hits all keys already carrying tenant segments; run a dry-run before adoption and check the `wouldMove` counts; if the existing keyspace already contains segments that coincidentally equal the tenant name (e.g. a topic literally called `acme`), a too-small dry-run `wouldMove` is the tell.

### Run Entry (the CLI, `reliability`'s `TenantKeyMigrationCli`)

```
java -cp <deps> io.github.cuihairu.redis.streaming.reliability.migration.TenantKeyMigrationCli \
  --tenant acme                      # dry run, all 8 families, default redis://127.0.0.1:6379
  --tenant acme --apply              # actually move
  --tenant acme --dir out --apply    # rollback (move the tenant segment out)
  --tenant acme --family mq-data     # a subset (a nested-pair subset is refused)
  --tenant acme --prefix mq-data=myapp:stream   # deployments with customized prefixes
```

Dry-run by default; `--scan-count` passes through SCAN COUNT; exit codes: `0` clean / `1` not clean (collisions or per-key failures) / `2` usage error. Ops order is unchanged: stop jobs and writers → move keys → switch tenant configuration.

Verification: `TenantKeyMigratorTest` 16 unit tests (mock `RKeys`: prefix validation, fail-closed, dry-run default, collision refusal, per-key failure isolation, nested ownership, nested targets not re-moved) + `TenantKeyMigrationCliTest` 21 unit tests (all flags and all errors parsed, exit codes, swallow-refusal and opt-in, direction and custom prefixes, help without touching Redis) + `TenantKeyFamiliesTest` 16 unit tests (table truths, adoption/rollback inverses, tenant contract, custom overrides, the two nesting swallow decisions) + `TenantKeyMigratorIntegrationTest` 6 integration + `TenantKeyMigrationCliIntegrationTest` 6 integration (real Redis: CLI end-to-end adoption preserving TTL/payload, dry-run leaves everything as-is, a second run is a no-op, rollback, collision exit code 1, swallow-refusal without connecting).

With this, the design has **no remaining items**: the migration engine, the family table, and the run entry have all landed (the CLI entry, originally "to be scoped when real ops scenarios appear", was un-shelved and landed by the 2026-10-16 second-round proxy decision: its unblocking condition is not external — it was a scheduling item and thus proxy-decidable).

## Non-Goals

- No tenant authentication/authorization system (the control plane's pluggable authorizer remains; authentication belongs to the security design).
- No per-tenant connection pools (the Redisson pool is shared per instance; tenant-level connection isolation belongs to Approach B's deployment shape).
- No tenant-level billing/metering reports (the quota metrics already support it; reports are a separate proposal).
