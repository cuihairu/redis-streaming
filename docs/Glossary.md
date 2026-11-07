# Glossary

This glossary records the runtime architecture as implemented in source, not the Gradle directory names alone. Evidence anchors use repository-relative `file:line`.

- [Architecture](./Architecture.md) describes the layers; this page gives the normative terms.
- [Source Layer Analysis](./Source-Layer-Analysis.md) explains the split criteria.
- Chinese version: [术语表](./zh/Glossary.md).

| Term | English | Definition | Evidence |
|---|---|---|---|
| 编程契约 | Programming Contract | Runtime-independent stream, keyed-state, window, sink, checkpoint, watermark, and tenant contracts used by every execution engine. | core/src/main/java/io/github/cuihairu/redis/streaming/api/stream/DataStream.java:14 |
| 内存执行器 | In-Memory Runtime | Lazily executed, bounded-queue runtime for tests and demos; uses process-local operator state and no Redis dependency. | runtime/src/main/java/io/github/cuihairu/redis/streaming/runtime/StreamExecutionEnvironment.java:32 |
| Redis 执行器 | Redis Runtime | Production single-process engine that compiles MQ-backed pipelines into consumers/runners, timers, checkpoint coordination, and job lifecycle operations. | runtime/src/main/java/io/github/cuihairu/redis/streaming/runtime/redis/RedisStreamExecutionEnvironment.java:69 |
| 消息通道 | Message Channel | Partitioned Redis Streams plus consumer groups, leases, pending claims, retry/DLQ, commit frontiers, retention, and admin operations used as the data and result channel. | mq/src/main/java/io/github/cuihairu/redis/streaming/mq/config/MqOptions.java:12 |
| 服务注册与发现 | Registry | Instance registration, heartbeats, filters, load balancing, health checks, Lua-guarded cleanup, and change notifications over Redis sets, hashes, sorted sets, Pub/Sub, and scripts. | registry/src/main/java/io/github/cuihairu/redis/streaming/registry/keys/RegistryKeys.java:14 |
| 配置中心 | Config Center | Versioned group/data-id configuration stored in Redis hashes with history, listeners, lossy Pub/Sub plus scheduled resync, and CRUD/admin semantics. | config/src/main/java/io/github/cuihairu/redis/streaming/config/impl/RedisConfigService.java:19 |
| 管理状态 | Managed State | Value/map/set/list state behind a common descriptor and schema registry; in-memory runtime keeps it local while the Redis backend materializes it as state hashes. | core/src/main/java/io/github/cuihairu/redis/streaming/api/state/State.java:10 |
| 检查点协议 | Checkpoint Protocol | Consistent snapshots of source offsets and operator state with restore, retention, barrier coordination, and two-phase sink recovery. | checkpoint/src/main/java/io/github/cuihairu/redis/streaming/checkpoint/redis/RedisCheckpointStorage.java:19 |
| 时间语义 | Time Semantics | Event timestamp assignment, watermark generation, idle handling, and processing-time triggers that gate window firing. | watermark/src/main/java/io/github/cuihairu/redis/streaming/watermark/WatermarkStrategy.java:21 |
| 窗口算子 | Window Operator | Tumbling, sliding, and session assignment plus count/event-time/processing-time triggers and evictors; execution is delegated by runtimes. | window/src/main/java/io/github/cuihairu/redis/streaming/window/assigners/TumblingWindow.java:15 |
| 增量聚合 | Incremental Aggregation | Aggregate functions and PV/UV/quantile/TopK analytics; state uses sorted sets, HyperLogLog, and bucket indexes in Redis. | aggregation/src/main/java/io/github/cuihairu/redis/streaming/aggregation/analytics/UVCounter.java:24 |
| 表视图 | Table View | Stream/table duality: tables materialize key/value state, expose relational transformations, and optionally append changelog messages. | table/src/main/java/io/github/cuihairu/redis/streaming/table/impl/RedisKTable.java:53 |
| 流连接 | Stream Join | Correlation of keyed streams with time/within semantics and topic-mode transport for cross-engine joins. | join/src/main/java/io/github/cuihairu/redis/streaming/join/StreamJoiner.java:26 |
| 变更数据捕获 | CDC | Pluggable MySQL binlog, PostgreSQL logical-replication, and polling connectors that normalize database changes and hand them to MQ sinks. | cdc/src/main/java/io/github/cuihairu/redis/streaming/cdc/CDCManager.java:18 |
| 输入连接器 | Source Connector | External ingress adapters running outside the Redis runtime's MQ compiler and calling registered stream callbacks. | source/src/main/java/io/github/cuihairu/redis/streaming/source/redis/RedisListSource.java:26 |
| 输出连接器 | Sink Connector | Batched writers for Redis, Kafka, HTTP/file/console output contracts; Redis runtime wraps compatible sinks in its delivery protocol. | sink/src/main/java/io/github/cuihairu/redis/streaming/sink/redis/RedisHashSink.java:22 |
| 可靠性原语 | Reliability Primitives | Deduplication, retry/DLQ boundaries, quotas, idempotency, outbox dispatch, and tenant-key migration building blocks. | reliability/src/main/java/io/github/cuihairu/redis/streaming/reliability/deduplication/Deduplicator.java:8 |
| 模式检测 | Pattern Detection | Complex-event pattern definitions, sequence matching, stateful process functions, and operator integration. | cep/src/main/java/io/github/cuihairu/redis/streaming/cep/PatternSequenceMatcher.java:25 |
| 作业控制面 | Job Control Plane | Declarative job specs, Redis-backed submit/upgrade/rollback/stop/audit/quota operations, and agents that apply desired state. | runtime/src/main/java/io/github/cuihairu/redis/streaming/runtime/redis/control/RedisJobControlPlane.java:54 |
| Spring 装配 | Spring Wiring | Conditional auto-configuration, Redisson and lifecycle beans, Micrometer collectors, housekeeping, control-plane agents, and `redis-streaming.*` property binding. | spring-boot-starter/src/main/java/io/github/cuihairu/redis/streaming/starter/properties/RedisStreamingProperties.java:11 |
| 可观察性 | Observability | Runtime/MQ counters and metrics collectors exposed through the runtime-facing SPI and Micrometer adapters. | core/src/main/java/io/github/cuihairu/redis/streaming/api/metrics/StreamingMetricsCollector.java:9 |
| 示例与基准 | Examples and Benchmarks | Executable usage compositions and live-Redis throughput/checkpoint benchmark entry points, deliberately excluded from publication. | examples/src/main/java/io/github/cuihairu/redis/streaming/examples/mq/MessageQueueExample.java:25 |
