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
- Checkpointing is single-process stop-the-world; with the optional job-global control gate (`jobControlGateEnabled`, default follows `leaderElectionEnabled`) enabled, checkpoints and scaling of same-`jobName` instances are serialized across instances via Redis leases (see [runtime.md](../runtime.md) §2.6). For end-to-end semantics still prefer idempotent sinks or Redis-only atomic sinks.
- Rolling upgrade: scale up new-version instances first, watch leased partitions and error rates until stable, then scale old-version instances down gradually.

## 9) Job control plane (optional, declarative upgrade/rollback)

Job submit/upgrade/rollback/stop can be managed centrally through the Redis control plane (design and key layout in the Chinese [Control-Plane-Design.md](../Control-Plane-Design.md); starter wiring in the "Job control plane" section of [Spring-Boot-Starter-en.md](Spring-Boot-Starter-en.md)):

- **Topology**: any process with `redis-streaming.runtime.control-plane.enabled=true` acts as the submit entry point (CLI / ops service / application); executor-side instances enable `redis-streaming.runtime.agent.enabled=true`. The control plane only writes the spec (desired state); agents reconcile periodically: claim (`SET NX EX`, TTL=max(3×poll,10s)) → deploy → report status.
- **Upgrade semantics**: a config/factory/descriptor change is a full upgrade (best-effort checkpoint → cancel → redeploy, brief interruption); a parallelism-only change takes the `scaleParallelism` fast path (no restart). `rollback` returns to the previous spec version.
- **Multi-instance arbitration**: a job is deployed by exactly one agent, guaranteed by the claim key. If that agent dies the job disappears with the process (no automatic failover); after the claim TTL expires, run `resume` on another instance to bring it back. Intended stop/restart goes through `stop`/`resume`.
- **Rolling upgrade advice**: `resume` on a new instance first (the claim picks the least-loaded agent), watch `status:<job>` stabilize, then `stop` the old instance; for rollback just run `rollback` (spec revert triggers a full agent upgrade).
- **Audit**: operations and failure reports go to the `prefix + audit` stream (near-capped at `audit-max-entries`), read newest-first with `tailAudit`; denied authorization attempts are also recorded.

## 10) Security hardening (Redis ACL / TLS / credential management)

By default the framework creates only a minimal "single server + optional password" client (details in the Chinese [Security-Hardening-Design.md](../Security-Hardening-Design.md)). Three paths for production:

### a) ACL username (Redis 6+)
```yaml
redis-streaming:
  redis:
    address: redis://redis.internal:6379
    username: app-reader      # omitted = the default user
    password: ${env:REDIS_PASS}   # ${env:VAR} placeholder: resolved from the environment at startup, missing variable fails startup
```
The `${env:VAR}` placeholder works for both `username` and `password`, keeping credentials out of config files. Dedicate an ACL user to the framework; minimal rule example (adjust to your key prefix):
```
user default off
user streaming on >CHANGE_ME ~streaming:* +ping +hello +select +del +exists +expire +pexpire +ttl +pttl +type +scan
    +get +set +setnx +mget +mset +incr +incrby +hget +hmget +hset +hsetnx +hgetall +hdel +hlen +hkeys +hvals
    +sadd +srem +smembers +scard +sismember +zadd +zrem +zrange +zrangebyscore +zrangebyscorewithscores
    +zrevrange +zcard +zscore +zcount +zremrangebyscore +llen +lpush +rpush +lrange +lindex +ltrim +rpoplpush
    +xadd +xlen +xrange +xrevrange +xread +xreadgroup +xack +xgroup +xinfo +xtrim +xdel +xautoclaim +xpending
    +eval +evalsha +script +subscribe +unsubscribe +publish +punsubscribe +psubscribe
```

### b) TLS (rediss://)
Use `rediss://host:6379` directly as the single-server address; truststore/hostname-verification and other netty/SSL details are covered by the (c) hook — the framework does not expose per-item config for them.

### c) ConfigCustomizer SPI (unified advanced hook)
Register any number of `ConfigCustomizer` beans; they are invoked in `@Order` after the built-in single-server config and before `Redisson.create`, and can override/append any Redisson setting (cluster, sentinel, read/write splitting, codec, TLS context, etc.):
```java
@Bean
ConfigCustomizer tls() {
    return config -> config.useSingleServer().setSslEnableEndpointIdentification(true);
}
```
Note: if the application provides its own `RedissonClient` bean (e.g. redisson-spring-boot-starter), the framework steps back entirely — this hook and the built-in client construction both become inert.
