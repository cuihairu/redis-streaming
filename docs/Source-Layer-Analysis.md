# Source Layer Analysis

Analysis basis: repository `HEAD` `768841207cba69987c1f15b6e07cbfb658d594aa`. It reads Java sources, Gradle dependencies, resource schemas, and the VitePress site; build outputs and lockfile churn are excluded. Related concepts live in [Glossary](./Glossary.md), and the layered map is embedded in [Architecture](./Architecture.md).

## Layers

1. **Contracts and runtime**
   - Contract: `core` holds no Redis dependency and defines `DataStream`, keyed state, sinks, checkpoint, and window operations (`core/src/main/java/io/github/cuihairu/redis/streaming/api/stream/DataStream.java:14`).
   - Execution: `runtime` has two distinct engines: a bounded-queue in-memory environment (`runtime/src/main/java/io/github/cuihairu/redis/streaming/runtime/StreamExecutionEnvironment.java:32`) and a production Redis compiler (`runtime/src/main/java/io/github/cuihairu/redis/streaming/runtime/redis/RedisStreamExecutionEnvironment.java:69`).
   - State storage: engine-owned process-local maps or Redis keyed-state hashes; configuration comes from `RedisRuntimeConfig` and `MqOptions`.
   - Lifecycle: in-memory execution is terminal-operation-driven; Redis runtime registers definitions once, validates topic/group ownership, restores, creates consumers/runners, then starts checkpoint coordination (`runtime/src/main/java/io/github/cuihairu/redis/streaming/runtime/redis/RedisStreamExecutionEnvironment.java:448`).
   - Call surface: user code -> environment/builder -> `MessageQueueFactory` -> Redisson and runner callbacks -> sink/checkpoint hooks.

2. **Infrastructure channels**
   - MQ is the data/result channel. Its schema covers tenants, partitions, workers, leases, claims, retry, key prefixes, retention, ACK policy, and quotas (`mq/src/main/java/io/github/cuihairu/redis/streaming/mq/config/MqOptions.java:12`); Redis key shapes are centralized in `mq/src/main/java/io/github/cuihairu/redis/streaming/mq/partition/StreamKeys.java:17`.
   - Registry stores service indexes, details, heartbeats and filters in Redis (`registry/src/main/java/io/github/cuihairu/redis/streaming/registry/keys/RegistryKeys.java:14`), drives health from client/server probes, and uses Lua for guarded mutation (`registry/src/main/java/io/github/cuihairu/redis/streaming/registry/lua/RegistryLuaScriptExecutor.java:28`).
   - Config uses Redis hash fields as the record layout, Lua for versioned writes/history, Pub/Sub for notifications, and a scheduled resync to repair missed notifications (`config/src/main/java/io/github/cuihairu/redis/streaming/config/impl/RedisConfigService.java:19`, `:503`).
   - Call surface: runtime, registry consumers, Spring beans, and admin callers call these services through Java APIs; Redisson is the transport; Pub/Sub is event-only and is backed by polling where lossiness matters.

3. **Semantic operators**
   - State/checkpoint/watermark/window define state, snapshot/recovery, and time behavior; implementations are injectable or delegated by the runtime.
   - Window and aggregation compose at build time: aggregation depends on window and runtime APIs (`aggregation/build.gradle:14`), and runtime depends on MQ/window/checkpoint/watermark/join/state/cep (`runtime/build.gradle:28`).
   - State storage: memory, Redis hashes for keyed state, Redis buckets for checkpoints, ZSets/HyperLogLog for analytics (`aggregation/src/main/java/io/github/cuihairu/redis/streaming/aggregation/analytics/UVCounter.java:80`), and table hashes/changelog topics (`table/src/main/java/io/github/cuihairu/redis/streaming/table/impl/RedisKTable.java:91`).
   - Call surface: users call operators on `DataStream`/`KeyedStream`; the chosen runtime compiles them into local execution or MQ/Redis pipeline operations.

4. **Boundary adapters**
   - CDC normalizes database protocols and publishes change events; source/sink connect Kafka, Redis, HTTP and other boundaries. Their inputs are builder/config objects; their channel is the connector callback or MQ producer.
   - Reliability supplies cross-cutting semantics instead of owning a pipeline: retry/DLQ, deduplication, quotas, idempotency, outbox, and tenant-key migration.
   - CEP is a semantics module attached through a process function/operator rather than a transport boundary.
   - Storage spans Redis, external databases/Kafka/HTTP, and process memory; connector lifecycles are explicit start/stop/close unless embedded in another lifecycle.

5. **Control, wiring, and delivery**
   - Job control plane keeps declarative specs and desired-state transitions in Redis (`runtime/src/main/java/io/github/cuihairu/redis/streaming/runtime/redis/control/RedisJobControlPlane.java:54`); Job Agent polls and applies them (`runtime/src/main/java/io/github/cuihairu/redis/streaming/runtime/redis/control/JobAgent.java:40`).
   - Spring reads `redis-streaming.*` (`spring-boot-starter/src/main/java/io/github/cuihairu/redis/streaming/starter/properties/RedisStreamingProperties.java:11`), conditionally wires Redisson and feature beans, and adapts runtime/MQ/retention/rate-limit metrics to Micrometer.
   - Metrics capability is split between the runtime SPI and Spring Micrometer adapters; `settings.gradle:39` has no standalone `metrics` module. Examples/benchmark consume public APIs and stay unpublished (`settings.gradle:40`, `settings.gradle:41`).
   - Lifecycle follows Spring bean scopes plus explicit service `start/stop/close`; examples/benchmark are process-lifetime demos.

## Implementation, configuration, protocol, and documentation map

| Layer | Implementation | Configuration schema | Protocol file | Copy placement |
|---|---|---|---|---|
| Contracts and runtime | `core/src/main/java/.../api/**`, `runtime/src/main/java/.../runtime/**` | `runtime/src/main/java/io/github/cuihairu/redis/streaming/runtime/redis/RedisRuntimeConfig.java:17` | no standalone file; Redis Streams IDs/Lua are encoded in runtime/MQ code | `docs/Core.md`, `docs/runtime.md`, and translations |
| Infrastructure channels | `mq/src/main/java`, `registry/src/main/java`, `config/src/main/java` | `MqOptions`, `NamingServiceConfig`, config `BaseRedisConfig` | Redis Streams entries and Lua fragments in service classes; no generated protocol artifact | `docs/MQ-Design.md`, `docs/Registry-Design.md`, `docs/config.md`, and translations |
| Semantic operators | `state`, `checkpoint`, `watermark`, `window`, `aggregation`, `table`, `join` modules | constructors/builders, state descriptors, runtime config inherited by execution | window/join semantics are Java API; checkpoint envelope is `Checkpoint` | `docs/state.md`, `docs/checkpoint.md`, `docs/watermark.md`, `docs/window.md`, `docs/Aggregation.md`, `docs/Table.md`, `docs/Join.md`, and translations |
| Boundary adapters | `cdc`, `source`, `sink`, `reliability`, `cep` | connector builders/config classes and reliability constructors | CDC event records and sink payloads are Java/JSON, not schema files | `docs/CDC.md`, `docs/source-sink.md`, `docs/reliability.md`, `docs/CEP.md`, and translations |
| Control, wiring, and delivery | `runtime/.../control`, `spring-boot-starter`, examples, benchmark | `RedisStreamingProperties`, JobSpec/agent/config properties, `application.yml` example | control audit/spec JSON and auto-configuration imports | `docs/Control-Plane-Design.md`, `docs/Spring-Boot-Starter.md`, `docs/Examples.md`, and translations |

## Why these layers

1. Contracts first: `core` depends on no project module, while every other module depends on it; this keeps engines and adapters from leaking their transports into user APIs (`core/build.gradle:17`, `runtime/build.gradle:28`).
2. Runtime is not transport: both execution engines implement the same stream contracts, but the memory engine uses bounded local queues (`runtime/src/main/java/io/github/cuihairu/redis/streaming/runtime/StreamExecutionEnvironment.java:34`) while Redis runtime uses MQ consumers and Redisson (`runtime/src/main/java/io/github/cuihairu/redis/streaming/runtime/redis/RedisStreamExecutionEnvironment.java:563`); putting both under runtime avoids inventing a directory per engine while keeping production behavior auditable.
3. Channels are shared infrastructure because runtime imports MQ (`runtime/build.gradle:28`), table changelogs reuse MQ producers (`table/src/main/java/io/github/cuihairu/redis/streaming/table/impl/RedisKTable.java:73`), and CDC publishes into MQ (`cdc/src/main/java/io/github/cuihairu/redis/streaming/cdc/mq/ChangeEventQueueSink.java:20`); a channel layer prevents these users from reimplementing partitioning, leases, retries, ACK/frontier, and retention.
4. Registry/config split along call pattern: registry continuously mutates instance records and heartbeats, while config performs versioned document CRUD and listener resync; their Redis key schemas and failure recovery differ (`registry/src/main/java/io/github/cuihairu/redis/streaming/registry/keys/RegistryKeys.java:32`, `config/src/main/java/io/github/cuihairu/redis/streaming/config/impl/RedisConfigService.java:503`).
5. Semantic operators are isolated from engines: window/CEP APIs are pure contracts and runtime owns their firing schedule (`runtime/build.gradle:28`); aggregation builds on window (`aggregation/build.gradle:14`). This lets semantics evolve without changing Redis runtime scheduling or MQ payload layout.
6. State and checkpoints are separate concepts, not one folder: state defines live keyed data and schema (`state/src/main/java/io/github/cuihairu/redis/streaming/state/redis/RedisStateBackend.java:32`), while checkpoint coordinates source offsets and operator snapshots (`runtime/src/main/java/io/github/cuihairu/redis/streaming/runtime/redis/internal/RedisRuntimeCheckpointManager.java`); separating them keeps restore-time coordination independent of per-record state access.
7. Boundary adapters have their own lifecycles and protocol parsers. MySQL/PostgreSQL connectors own protocol clients and polling (`cdc/src/main/java/io/github/cuihairu/redis/streaming/cdc/impl/MySQLBinlogCDCConnector.java:41`), source/sink call external clients, and CEP/reliability semantics must remain callable without the Spring layer.
8. Control is separate from data execution because submit/upgrade/rollback/audit/quota operate on desired state rather than records (`runtime/src/main/java/io/github/cuihairu/redis/streaming/runtime/redis/control/RedisJobControlPlane.java:109`); Spring wiring remains separate because feature activation is environment-scoped (`spring-boot-starter/src/main/java/io/github/cuihairu/redis/streaming/starter/properties/RedisStreamingProperties.java:11`).
9. Delivery composition is separate from library semantics: examples consume nearly all feature modules (`examples/build.gradle:14`) and benchmark needs live MQ/runtime behavior (`benchmark/build.gradle:14`); `settings.gradle:39` has no metrics entry, so publishing delivery modules would expand the API surface and couple users to demo drivers.
