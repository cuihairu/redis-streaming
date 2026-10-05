# Deployment Guide

[中文](../Deployment.md) | [English](Deployment-en.md)

---

This page provides minimal production deployment guidance and key configuration defaults. All defaults are taken from the repository source code (`MqOptions`, `RedisRuntimeConfig`, `RedisStreamingProperties`).

## 1) Runtime Requirements

- Java 17+ (build script sets `options.release = 17`)
- Redis: CI/test environments use `redis:7-alpine` (see `docker-compose*.yml`); the framework maintains compatibility with Redis 6 (e.g., `source.redis.RedisStreamSource` uses explicit `0-0` instead of `StreamMessageId.MIN` which requires Redis ≥ 7.0).

## 2) Redisson Integration (Recommended)

The framework only provides a simplified single-server configuration via `redis-streaming.redis.*` (see `spring-boot-starter`'s `RedisStreamingProperties` javadoc). For production clusters/sentinel, use the official `redisson-spring-boot-starter` with a version aligned to this repo's Redisson dependency (`gradle/libs.versions.toml` currently `redisson = 4.7.0`; the starter class javadoc's `3.29.0` is a stale value from before the upgrade):

```gradle
implementation 'org.redisson:redisson-spring-boot-starter:<version matching libs.versions.toml redisson>'
```

Cluster example (`redisson-cluster.yaml`):
```yaml
clusterServersConfig:
  nodeAddresses: ["redis://10.0.0.1:6379", "redis://10.0.0.2:6379"]
  password: your_pwd
  scanInterval: 2000
  connectTimeout: 10000
  timeout: 3000
```
`application.yml`:
```yaml
spring:
  redis:
    redisson:
      file: classpath:redisson-cluster.yaml
```

Sentinel example (`redisson-sentinel.yaml`):
```yaml
sentinelServersConfig:
  masterName: mymaster
  sentinelAddresses: ["redis://10.0.0.1:26379", "redis://10.0.0.2:26379"]
  password: your_pwd
  database: 0
  checkSentinelsList: true
```
```yaml
spring:
  redis:
    redisson:
      file: classpath:redisson-sentinel.yaml
```

Once `redisson-spring-boot-starter` is present, you can drop `redis-streaming.redis.*`; the starter detects your `RedissonClient` and skips its internal single-server client.

## 3) Starter Configuration Keys (`redis-streaming.*`)

| Key | Default | Description |
|---|---|---|
| `redis-streaming.redis.address` | `redis://127.0.0.1:6379` | Single-server Redis address (dev/test only) |
| `redis-streaming.registry.enabled` | `true` (effective by default) | Registry auto-configuration |
| `redis-streaming.discovery.enabled` | `true` (effective by default) | Service discovery auto-configuration |
| `redis-streaming.config.enabled` | `true` (effective by default) | Config center auto-configuration |
| `redis-streaming.mq.enabled` | `true` (effective by default) | MQ auto-configuration |
| `redis-streaming.ratelimit.enabled` | `false` (must enable explicitly) | Rate limiting auto-configuration |
| `redis-streaming.registry.auto-register` | `true` (effective by default) | Auto-register this service instance |

(Effective semantics come from each `@ConditionalOnProperty`'s `matchIfMissing` setting.)

## 4) MQ Consumer Key Defaults (`MqOptions`)

| Config | Default | Description |
|---|---|---|
| `workerThreads` | `8` | Execution thread count |
| `schedulerThreads` | `2` | Scheduler pool (lease renew/rebalance/pending scan) |
| `maxInFlight` | `0` (0 = unlimited) | Global in-flight concurrency limit (backpressure) |
| `maxLeasedPartitionsPerConsumer` | `0` (0 = `workerThreads`) | Max partitions a single instance can lease |
| `claimIdleMs` | `300000` (5 min) | Pending entry idle threshold for reclaim |
| `claimBatchSize` | `50` | Batch size per reclaim |
| `pendingScanIntervalSec` | `30` | Pending scan interval |
| `renewIntervalSec` | `3` | Lease renewal interval |
| `retryMaxAttempts` | `5` | Max retry attempts |
| `retryBaseBackoffMs` | `1000` | Exponential backoff base |
| `retryMaxBackoffMs` | `60000` | Backoff ceiling |
| `retentionMaxLenPerPartition` | `100000` | Per-partition stream length cap (approximate trim) |
| `retentionMs` | `0` (0 = disabled) | Time-based retention |
| `trimIntervalSec` | `60` | Background trim interval |
| `ackDeletePolicy` | `none` (`none` / `immediate` / `all-groups-ack`) | Delete-after-ACK policy |

## 5) Redis Runtime Key Defaults (`RedisRuntimeConfig`)

| Config | Default | Description |
|---|---|---|
| `pipelineParallelism` | `1` | Per-process subtask parallelism |
| `timerThreads` | `1` | Processing-time timer thread pool |
| `checkpointThreads` | `1` | Checkpoint scheduling/execution threads |
| `checkpointDrainTimeout` | `30s` | Pre-checkpoint drain window (0/negative falls back to 30s) |
| `eventTimeTimerMaxSize` | `100000` | Event-time timer queue capacity |
| `windowMaxFiresPerRecord` | `256` | Max windows a single record can fire |
| `watermarkOutOfOrderness` | `Duration.ZERO` | Out-of-orderness tolerance (watermark = maxTs − outOfOrderness) |
| `mdcEnabled` | `false` | MDC log correlation switch |
| `mdcSampleRate` | `1.0` (0~1) | MDC sampling rate |

## 6) Observability

- Enable Actuator + Prometheus; scrape `/actuator/prometheus`
- Metric prefixes (registered by spring-boot-starter's Micrometer collectors/binders):
  - `redis_streaming_mq_*` (produce/consume/ack/retry/lease/retention trim/frontier)
  - `redis_streaming_runtime_*` (job/pipeline/handle latency, checkpoint, keyed state, window, watermark, timer queue)
  - `redis_streaming_rl_*` (rate limit allow/deny)
  - `redis_streaming_dlq_*` (DLQ replay/delete/clear)
  - Full list: [Metrics](../Metrics.md)
- Trace/log correlation: `RedisRuntimeConfig.mdcEnabled(true)` + `mdcSampleRate(0~1)` (MDC keys: `rs.job` / `rs.topic` / `rs.group` / `rs.consumer` / `rs.id` / `rs.key` / `rs.partition`)

## 7) Pre-Flight Checks

- Redis connectivity/permissions verified
- Consumer group assignment balanced; pending scan/reclaim strategy ready (default reclaim after 5 min idle)
- DLQ replay procedure exercised; growth alerts configured (`redis_streaming_mq_dlq_total`, `redis_streaming_dlq_*`)

## 8) Multi-Instance & Rolling Upgrade Guidelines

- Scale the same job horizontally via consumer groups; combine with `MqOptions.maxLeasedPartitionsPerConsumer` (default 0 = `workerThreads`) to avoid over-leasing.
- Checkpoint is per-process stop-the-world (no cross-instance barrier); for multi-instance deployments prefer idempotent sinks or Redis-only atomic sinks to ensure end-to-end consistency.
- Rolling upgrade: scale up new-version instances first, observe leased partitions and error rates stabilize, then gradually scale down old-version instances.

---

**Version**: 0.2.0
**Last Updated**: 2026-10-05

Related documentation:
- [Spring Boot Starter](Spring-Boot-Starter-en.md)
- [MQ Guide](MQ-Guide-en.md)
- [Architecture](Architecture-en.md)