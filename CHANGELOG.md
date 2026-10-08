# Changelog

All notable changes to this project are documented here (Conventional Commits style; versions derive from `v*` git tags via axion-release).

## [Unreleased]

### Added
- **`DeliveryGuarantee` declaration surface** (`core` `api.stream`): sinks declare their capability via `StreamSink.deliveryGuarantee()` — plain sinks default to `AT_LEAST_ONCE`; `TwoPhaseCommitSink` and the Redis exactly-once building-block sinks (`RedisAtomicCheckpointListSink`, `RedisIdempotentListSink`, `RedisCheckpointedIdempotentListSink`, `RedisOutboxSink`) declare `EFFECTIVELY_ONCE`.
- **Benchmark module** (`benchmark`, not published / not coverage-gated): MQ produce+consume throughput with p50/p95/p99 end-to-end latency, and `triggerCheckpointNow()` drain+snapshot duration on a live Redis runtime pipeline (`./gradlew :benchmark:run`, `REDIS_URL` / `BENCH_*` env knobs).
- **join/cep operatorization** (phase 1 of `docs/Join-CEP-Operators-Design.md`): `join.operator.StreamJoinOperator` adapts the windowed stream-stream join to a `KeyedProcessFunction` over `join.operator.Envelope` (both sides map into tagged envelopes on one join-input stream; zero engine changes, runs on both engines); `cep.operator.PatternSequenceProcessFunction` runs `PatternSequenceMatcher` per key with a bounded key registry (`maxTrackedKeys`). Operator buffer state stays partition-local (not checkpointed) in this phase; `StreamJoiner` gains explicit `(element, key, timestamp)` overloads as the delegate entry point.

### Changed
- **Dependencies are now centrally managed** via the Gradle version catalog (`gradle/libs.versions.toml`); module build scripts reference `libs.*` accessors.
- **SLF4J unified to 2.0.17** across all modules (previously mixed 1.7.36 / 2.0.17; Redisson requires the 2.x API).
- **Redisson upgraded 3.52.0 → 4.7.0** (latest stable).
  - Migrated to Redisson 4.x APIs: `StreamMessageId`/`StreamGroup`/`StreamInfo`/`PendingEntry` moved to `org.redisson.api.stream`; `RScript.ReturnType` constants renamed (`INTEGER→LONG`, `MULTI→LIST`, `STATUS→STRING`); `RKeys.expire(Duration, String...)`.
  - [Redisson 4.x `StreamMessageId.MIN` sends the `-` special stream id, which requires **Redis ≥ 7.0**. New code (`RedisStreamSource`) uses an explicit `0-0` id for Redis 6 compatibility; library users passing `MIN` to `XGROUP CREATE` on Redis 6 should switch to an explicit id.]
- `sink.redis.RedisStreamSink` now performs real **XADD** onto a Redis Stream and implements `core StreamSink`. The previous (misnamed) List behavior is preserved as the new `sink.redis.RedisListSink`.
- `core StreamSink` gained an optional lifecycle: `open()` / `close()` default methods. Both runtime engines now call them (InMemory engine around the terminal iteration; the Redis engine lazily on first message and on job close). Implementations holding resources should override them.
- `core StreamSource` gained the symmetric `open()` / `close()` lifecycle, wired into the in-memory engine's `addSource`.
- `spring-boot-starter`: `RedisStreamingAutoConfiguration` is now a small `@AutoConfiguration` owning the shared `RedissonClient`; registry/discovery/config/mq/ratelimit beans moved to their own `RedisStreaming*AutoConfiguration` classes (same conditions and semantics).
- `registry.BaseRedisConfig` now extends `config.BaseRedisConfig` (removes a forked duplicate).
- `registry` `MessagingProtocol` trimmed to Redis-based protocols only (Kafka/Pulsar/RabbitMQ/NATS/MQTT constants had no implementation).
- Repository hygiene: committed `node_modules/` (3442 files) and other stray artifacts removed; process docs archived under `docs/archive/`.

### Added
- `cdc.CDCSource` — `StreamSource<ChangeEvent>` adapter draining any `CDCConnector` into the streaming API.
- `cdc.mq.ChangeEventQueueSink` — `StreamSink<ChangeEvent>` bridging change events onto an MQ topic.
- `source.redis.RedisStreamSource` — real XREADGROUP-based `StreamSource` (pairs with `RedisStreamSink`).
- **Two-phase commit sinks**: `core` `TwoPhaseCommitSink` API (serializable txn) plus Redis-runtime coordination — checkpoint flow runs preCommit → store txn handle in the checkpoint → commit → mark sinkCommitted → ack; recovery replays `recoverAndCommit` / `recoverAndAbort` for stored handles. Unit tests include fault-injection lanes for each crash window.
- **Outbox delivery**: `RedisOutboxSink` (buffers per transaction, XADDs epoch/seq entries, flips epoch markers atomically) with `RedisOutboxDispatcher` — ordered dispatch of COMMITTED epochs, ABORTED discard, pending retry, DLQ overflow; delivery is at-least-once (target side keys on record id for end-to-end exactly-once).
- **Exactly-once building blocks**: `RedisIdempotentListSink` (Lua atomic dedup), `RedisCheckpointedIdempotentListSink`, `RedisAtomicCheckpointListSink` (sink write + XACK + commit-frontier update in a single Lua script), `CheckpointAwareSink` + `deferAckUntilCheckpoint` (ack deferred until checkpoint completes). Design and boundaries: `docs/exactly-once.md`.
- **Leader election and HA takeover** (opt-in): `RedisRuntimeConfig.leaderElectionEnabled` (default false) / `leaderLeaseTtl` / `leaderRenewInterval` with `RedisLeaderElector` (SET NX PX lease + Lua renew/release); fencing token per leadership epoch, restore rejects stale-leader snapshots by max token; on takeover the checkpoint id counter realigns from storage so a new leader never overwrites the dead leader's last checkpoint.
- **Job operations** on `RedisJobClient`: `pause()` / `resume()` / `inFlight()`, `triggerCheckpointNow()`, `getLatestCheckpoint()`, `diagnostics()`; stable consumer naming `jobName-jobInstanceId-{n}`.
- **Checkpointing knobs**: periodic `checkpointInterval` snapshots (offsets + keyed state), `restoreFromLatestCheckpoint`, `checkpointDrainTimeout` (stop-the-world drain), sink dedup `sinkDeduplicationEnabled`, `checkpointKeyPrefix` / `checkpointsToKeep`.
- **Processing-error policy**: `processingErrorResult` (`RETRY` default / `DEAD_LETTER` / `FAIL`) with structured error context headers for poison messages.
- **State governance**: `stateTtl`, `stateSizeReportEveryNStateWrites`, `keyedStateShardCount` (hash sharding), hot-key detection and response — `keyedStateHotKeyFieldsWarnThreshold` / `keyedStateHotKeyWarnInterval` / `keyedStateHotKeyPolicy` (`LOG_ONLY` | `THROTTLE` | `FAIL_FAST`, the latter via `KeyedStateHotKeyException` into the MQ retry/DLQ path) with `keyedStateHotKeyThrottleMaxMs`.
- **Observability**: Micrometer collectors across runtime/mq/reliability/retention with Spring Boot auto-install; MDC correlation (`mdcEnabled`, `mdcSampleRate`, `rs.*` keys); Actuator health + `/actuator/prometheus` in the starter; `runtime` metrics for checkpoint/state/window/watermark/timer/handler.
- **Parallelism and backpressure basics**: `pipelineParallelism(n)` with fixed partition assignment, `maxInFlight` permit limiting, `maxLeasedPartitionsPerConsumer` lease cap; window runtime knobs `watermarkOutOfOrderness` / `windowAllowedLateness` / `windowMaxFiresPerRecord`; thread-pool isolation `timerThreads` / `checkpointThreads` and `eventTimeTimerMaxSize` bound.
- `DataStream.assignTimestampsAndWatermarks(WatermarkGenerator)` is now a real Redis-engine operator (monotonic watermark propagation; the `(TimestampAssigner, generator)` overload is not yet wired).
- `runtime` windowed-operator and watermark characterization/integration tests; `examples` Spring Boot starter sample app with annotated `application.yml`.

### Fixed
- **spring-boot-starter**: logback pinned to 1.4.14 (1.5.x removed `LoggerContext.getConfigurationLock()` which Spring Boot 3.2's logging system requires).
- **spring-boot-starter**: empty default `redis-streaming.redis.password` no longer sends an `AUTH` command (broke servers without a password).
- **spring-boot-starter**: `MqHealthIndicator` bean moved to a class-level `@ConditionalOnClass` nested configuration (actuator is optional; method-level guards did not prevent return-type introspection). Micrometer collector/installer beans gained `@ConditionalOnBean` guards so the starter now boots without actuator/MeterRegistry.
- Redis engine: a consumer failing to `resume()` after a checkpoint left the remaining consumers paused indefinitely.
- Silent `catch (Exception ignore)` blocks in the checkpoint manager / keyed-state store that could hide lost state snapshots are now logged (warn for correctness issues, debug for metrics noise).

## [0.2.1] and earlier
See the git history and `docs/archive/COMPLETION_REPORT.md`.
