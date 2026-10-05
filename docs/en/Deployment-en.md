# Installation & Deployment

[中文](../Deployment.md) | [English](Deployment-en.md)

---

Minimum production-readiness points and key configuration defaults. Defaults are taken from the repository source (`MqOptions`, `RedisRuntimeConfig`, `RedisStreamingProperties`).

## 1) Runtime requirements
- Java 17+ (build scripts set `options.release = 17`)
- Redis: CI/test environments use `redis:7-alpine` (see `docker-compose*.yml`); the framework stays compatible with Redis 6 (for example `source.redis.RedisStreamSource` uses an explicit `0-0` instead of `StreamMessageId.MIN`, which requires Redis ≥ 7.0)

## 2) Redisson integration (recommended)
The framework itself only ships the simplified single-server `redis-streaming.redis.*` configuration (see the `RedisStreamingProperties` javadoc in `spring-boot-starter`). For production cluster/sentinel deployments use the official redisson-spring-boot-starter, versioned in line with the Redisson this repository depends on (`gradle/libs.versions.toml` currently has `redisson = 4.7.0`; the `3.29.0` in some starter javadoc comments is a pre-upgrade leftover):
```gradle
implementation 'org.redisson:redisson-spring-boot-starter:<version matching redisson in libs.versions.toml>'
```

Cluster example (redisson-cluster.yaml):
```yaml
clusterServersConfig:
  nodeAddresses: ["redis://10.0.0.1:6379", "redis://10.0.0.2:6379"]
  password: your_pwd
  scanInterval: 2000
  connectTimeout: 10000
  timeout: 3000
```
application.yml:
```yaml
spring:
  redis:
    redisson:
      file: classpath:redisson-cluster.yaml
```

## 3) Starter configuration keys (redis-streaming.*)

| Key | Default | Notes |
|---|---|---|
| `redis-streaming.redis.address` | `redis://127.0.0.1:6379` | single-server Redis address (development/test only) |
| `redis-streaming.registry.enabled` | `true` (active by default) | registry auto-configuration |
| `redis-streaming.discovery.enabled` | `true` (active by default) | discovery auto-configuration |
| `redis-streaming.config.enabled` | `true` (active by default) | config center auto-configuration |
| `redis-streaming.mq.enabled` | `true` (active by default) | MQ auto-configuration |
| `redis-streaming.ratelimit.enabled` | `false` (enable explicitly) | rate-limit auto-configuration |
| `redis-streaming.registry.auto-register` | `true` (active by default) | auto-registers this service instance |

(Effective semantics come from the `matchIfMissing` settings of each `@ConditionalOnProperty`.)

## 4) Key MQ consumer defaults (MqOptions)

| Setting | Default | Notes |
|---|---|---|
| `workerThreads` | `8` | execution thread count |
| `schedulerThreads` | `2` | scheduler pool (lease renewal / rebalance / pending scan) |
| `maxInFlight` | `0` (0 = unlimited) | global in-flight concurrency cap (backpressure) |
| `maxLeasedPartitionsPerConsumer` | `0` (0 = follows `workerThreads`) | per-instance leased-partition cap |
| `claimIdleMs` | `300000` (5 minutes) | pending entries become claimable only after this idle time |
| `claimBatchSize` | `50` | entries claimed per batch |
| `pendingScanIntervalSec` | `30` | pending scan interval |
| `renewIntervalSec` | `3` | lease renewal interval |
| `retryMaxAttempts` | `5` | retry count |
| `retryBaseBackoffMs` | `1000` | exponential backoff base |
| `retryMaxBackoffMs` | `60000` | backoff cap |
| `retentionMaxLenPerPartition` | `100000` | per-partition stream length cap (approximate trim) |
| `retentionMs` | `0` (0 = disabled) | time-based retention |
| `trimIntervalSec` | `60` | background trim period |
| `ackDeletePolicy` | `none` (`none` / `immediate` / `all-groups-ack`) | post-ack delete policy |

## 5) Key Redis runtime defaults (RedisRuntimeConfig)

| Setting | Default | Notes |
|---|---|---|
| `pipelineParallelism` | `1` | in-process subtask parallelism |
| `timerThreads` | `1` | processing-time timer pool |
| `checkpointThreads` | `1` | checkpoint scheduling/execution threads |
| `checkpointDrainTimeout` | `30s` | drain window before a checkpoint (0/negative falls back to 30s) |
| `eventTimeTimerMaxSize` | `100000` | event-time timer queue cap |
| `windowMaxFiresPerRecord` | `256` | max windows fired per record |
| `watermarkOutOfOrderness` | `Duration.ZERO` | out-of-orderness tolerance (watermark = maxTs − outOfOrderness) |
| `mdcEnabled` | `false` | MDC log correlation switch |
| `mdcSampleRate` | `1.0` (0~1) | MDC sampling rate |

## 6) Observability
- Enable Actuator + Prometheus; scrape `/actuator/prometheus`
- Meter name prefixes (registered by the spring-boot-starter Micrometer collectors/binders):
  - `redis_streaming_mq_*` (produce/consume/ack/retry/lease/retention trim/frontier)
  - `redis_streaming_runtime_*` (job/pipeline/handle latency, checkpoints, keyed state, windows, watermark, timer queue)
  - `redis_streaming_rl_*` (rate-limit allow/deny)
  - `redis_streaming_dlq_*` (DLQ replay/delete/clear)
  - Full list: the Chinese [Metrics](../Metrics.md)
- Trace/log correlation: `RedisRuntimeConfig.mdcEnabled(true)` + `mdcSampleRate(0~1)` (MDC keys: `rs.job` / `rs.topic` / `rs.group` / `rs.consumer` / `rs.id` / `rs.key` / `rs.partition`)

## 7) Go-live checklist
- Redis connectivity and permissions verified
- Consumer group assignment balanced; pending scan/claim strategy understood (claims start after 5 minutes idle by default)
- DLQ replay rehearsed; growth alerts configured (`redis_streaming_mq_dlq_total`, `redis_streaming_dlq_*`)

## 8) Multi-instance and rolling upgrade notes
- Scale one job horizontally through consumer groups; combine with `MqOptions.maxLeasedPartitionsPerConsumer` (default 0 = follows `workerThreads`) to avoid lease over-provisioning.
- Checkpointing is single-process stop-the-world (no cross-instance barriers); for multi-instance deployments prefer idempotent sinks or Redis-only atomic sinks to keep end-to-end semantics consistent.
- Rolling upgrade: scale up new-version instances first, watch leased partitions and error rates until stable, then scale old-version instances down gradually.
