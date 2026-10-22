# Metrics

The framework has exactly one outward observability path: Micrometer `MeterRegistry` (auto-bridged by the spring-boot-starter — see "Static collector hooks and starter auto-install" below). Prometheus scraping rides the Micrometer ecosystem (`micrometer-registry-prometheus` / Spring Boot Actuator). The homegrown `metrics` module (`MetricCollector`/`InMemoryMetricCollector`/`MetricRegistry`/`MetricTimer`/`prometheus.*`) was removed on 2026-10-12 ([Metrics-Unification-Design.md](Metrics-Unification-Design.md) plan A v2); for migrating old users see "From InMemoryMetricCollector to Micrometer" below.

## Static collector hooks and starter auto-install

Each module exposes a static facade (a Noop by default; `setCollector` ignores null):

| Facade | Collector interface | Starter-installed implementation | Metric prefixes |
|---|---|---|---|
| `mq.metrics.MqMetrics` | `MqMetricsCollector` | `starter.metrics.MqMicrometerCollector` | `redis_streaming_mq_*` / `redis_streaming_dlq_*` |
| `runtime...metrics.RedisRuntimeMetrics` | `api.metrics.StreamingMetricsCollector` (public SPI, core module) | `starter.metrics.RedisRuntimeMicrometerCollector` | `redis_streaming_runtime_*` |
| `reliability.metrics.RateLimitMetrics` | `RateLimitMetricsCollector` | `starter.metrics.RateLimitMicrometerCollector` | `redis_streaming_rl_*` |
| `mq.metrics.RetentionMetrics` | `RetentionMetricsCollector` | `starter.metrics.RetentionMicrometerCollector` | `redis_streaming_retention_*` / `redis_streaming_mq_trim_*` |

Install condition: `io.micrometer.core.instrument.MeterRegistry` on the classpath and such a bean present in the container (the install beans in `RedisStreamingAutoConfiguration` / `RedisStreamingMqAutoConfiguration` are guarded by `@ConditionalOnClass` + `@ConditionalOnBean`). You can also call `XxxMetrics.setCollector(...)` manually.

CDC is a separate path: `CDCManager` directly exposes `CDCMetrics` snapshots (no static facade), which `starter.metrics.CDCMetricsMicrometerBinder` reads to export gauges — this needs the cdc module on the classpath and the application to have registered its own `CDCManager` bean; the framework never creates connectors for you.

## Naming Convention (docs/Metrics-Unification-Design.md plan A v1)

New metrics all use `redis.streaming.<module>.<metric>` (dot-separated, Micrometer-native style) with dimensional tags such as `job` / `topic` / `group`; the carrying scope and mechanism of the `tenant` dimension are specified under "tenant dimension spec" below.

Existing metric names (the snake_case prefixes `redis_streaming_mq_*`, `redis_streaming_rl_*`, etc.) **stay unchanged** to avoid breaking existing dashboards/alerts; gap-filler metrics added for them also keep the old prefix (e.g. DLQ replay `redis_streaming_dlq_replay_success_total`). Plan v2 (the `metrics` module deleted, Prometheus output delegated to `micrometer-registry-prometheus`, landed 2026-10-12) is in `Metrics-Unification-Design.md`.

The only metrics named under the new convention so far are the CDC exporter (`redis.streaming.cdc.*`, tag `connector`).

## tenant Dimension Spec (MQ business metrics)

Under multi-tenant deployment, the `tenant` dimension of MQ metrics follows these three rules (design basis: [Multi-Tenancy-Design.md](Multi-Tenancy-Design.md) plan A; the SPI landed first in commit a8f8a18, consumer-side wiring completed 2026-10-15).

**1. Which metrics carry tenant (a closed list)**

| Metric (legacy name) | Type | Where tenant enters |
|---|---|---|
| `redis_streaming_mq_produced_total` | counter | `RedisMessageProducer` / `DefaultBroker` (both produce-write paths) → `incProduced(tenant, topic, pid)` |
| `redis_streaming_mq_consumed_total` | counter | `RedisMessageConsumer` → `incConsumed(tenant, topic, pid)` |
| `redis_streaming_mq_acked_total` | counter | same (SUCCESS path) |
| `redis_streaming_mq_retried_total` | counter | same (requeue path) |
| `redis_streaming_mq_dead_total` | counter | same (DLQ path, including payload-missing) |
| `redis_streaming_mq_handle_latency_ms` | timer | same |
| `redis_streaming_mq_rate_limited_total` (tags: `tenant`, `topic`; no `partition`) | counter | `SendQuota` → `incRateLimited(tenant, topic)` (tenant-native, no legacy variant, hence always tagged with tenant and gated by no switch) |

Metrics **without** tenant: DLQ replay/delete/clear, the in-flight/backpressure/partition lease gauges (they already locate by `consumer`, and DLQ keys are themselves partitioned per tenant); all runtime metrics (located by `job`; the job name only needs to be unique within the tenant); CDC metrics (located by `connector`). Control-plane metrics (not yet built) carry tenant by design.

**2. How tenant reaches the collector (mechanism)**

- SPI layer: `MqMetricsCollector` offers `(tenant, topic, partitionId)` `default` overloads for the seven counters/timers above; the defaults delegate to the legacy signatures — the same non-breaking dimension-addition pattern as `StreamingMetricsCollector.incHandleError(..., result)`, so existing implementations compile and run unchanged.
- Call layer: producer/consumer/SendQuota always call the tenant-aware variants; the tenant comes from configuration (`MqOptions.tenant`, normalized via `StreamKeys.of(options)`, default `default`).
- **Export layer (no tag by default)**: the starter's built-in `MqMicrometerCollector` overrides only the legacy signatures, so tenant is dropped at the export surface — the series set of existing dashboards/alerts stays byte-for-byte identical. Deployments wanting the dimension extend the bridge themselves (override the tenant-aware variants and `.tag("tenant", tenant)`); the built-in bridge ships an opt-in switch `redis-streaming.mq.metrics-tenant-tag` (off by default, a starter-only property — not in `MqOptions`, no effect on mq key layout): when on, the six counters/timers in the table switch to series tagged with `tenant`, while the tenant-less series written via the legacy signatures remain untouched (the two coexist under different cache keys). The bridge also emits a one-time WARN about cardinality budgeting when the number of distinct `tenant×topic` pairs exceeds a threshold (default 1 000, matching the green-zone bound in the next section).

**3. Cardinality budget**

The tenant tag only multiplies the series of item 1 in the list: cardinality = |tenant| × |topic| × |partition| (timers additionally carry percentile histogram buckets). Budget cap per instance: **100 000 series**. With partitions ≤ 8, |tenant| × |topic| ≤ 1 000 is the green zone (≤ 8 000 series per metric); 1 000–12 000 is the yellow zone (needs review — usually a sign topics should be consolidated or tenants split into separate deployments); beyond 12 000 is the red zone (refuse to enable the tenant tag on that instance; split per plan B's tenant-independent deployments). Tenant names themselves are constrained by `Tenant.normalize` to `[A-Za-z0-9._-]{1,64}` and are not a cardinality amplification point.

### MQ Metrics (Micrometer)

From `MqMicrometerCollector` (every counter/timer carries `topic` and `partition` tags; gauges below):

- `redis_streaming_mq_produced_total` / `redis_streaming_mq_consumed_total` / `redis_streaming_mq_acked_total` / `redis_streaming_mq_retried_total` / `redis_streaming_mq_dead_total` / `redis_streaming_mq_payload_missing_total` (counters)
- `redis_streaming_mq_handle_latency_ms` (timer)
- `redis_streaming_mq_inflight` / `redis_streaming_mq_max_inflight` / `redis_streaming_mq_max_leased_partitions` (gauges, tag `consumer`)
- `redis_streaming_mq_backpressure_wait_total` (counter) / `redis_streaming_mq_backpressure_wait_ms` (timer), tag `consumer`
- `redis_streaming_mq_eligible_partitions` / `redis_streaming_mq_leased_partitions` (gauges, tags `consumer`, `topic`, `group`)
- DLQ replay/cleanup (tags `topic`, `partition`; delete/clear have no partition tag): `redis_streaming_dlq_replay_success_total` / `redis_streaming_dlq_replay_failure_total` (counters), `redis_streaming_dlq_replay_latency_ms` (timer), `redis_streaming_dlq_deleted_total` (counter), `redis_streaming_dlq_cleared_total` (counter, stepping per deleted record)

Plus `MqMetricsBinder` (functional gauges over `MessageQueueAdmin`/`DeadLetterService`): `redis_streaming_mq_topics_total`, `redis_streaming_mq_messages_total` (sum of per-topic lengths), `redis_streaming_mq_dlq_total` (sum of per-topic DLQs); `RetentionFrontierMetricsBinder` exposes `redis_streaming_mq_frontier_age_ms`.

### Runtime Metrics (Micrometer)

From `RedisRuntimeMicrometerCollector` (hooked via `RedisRuntimeMetrics.setCollector(...)`):

- Job/pipeline: `redis_streaming_runtime_job_started_total` / `job_canceled_total` (tag `job`); `pipeline_started_total` / `pipeline_start_failed_total` / `handle_success_total` (tags `job`, `topic`, `group`); `handle_error_total` additionally carries a `result` dimension (the disposition of failed records: `retry` / `fail` / `dead_letter`, parsed from `processingErrorResult` and the runner's return value; the old three-argument calls and custom collectors still feed the original series without the `result` tag)
- Handle latency: `redis_streaming_runtime_handle_latency_ms` (timer, tags `job`, `topic`, `group`)
- Checkpoints: `checkpoint_triggered_total` / `checkpoint_completed_total` / `checkpoint_failed_total` (counters, tag `job`); `checkpoint_duration_ms` / `checkpoint_drain_duration_ms` / `checkpoint_store_duration_ms` / `checkpoint_sink_commit_duration_ms` (timers, tag `job`)
- Keyed state: `redis_streaming_runtime_keyed_state_read_total` / `write_total` / `delete_total` / `hot_key_total` (counters) and `read_latency_ms` / `write_latency_ms` (timers), `size_fields` (DistributionSummary), tags `job`, `topic`, `group`, `operator`, `state`, `partition`
- Event time/watermarks: `event_time_timer_queue_size` (gauge), `watermark_ms` (gauge), tags `job`, `topic`, `group`
- Windows: `window_fired_total` / `window_late_dropped_total` (counters), tags `job`, `topic`, `group`, `operator`, `state`, `partition` (note: the tag key is `state`, but the value fed is the window-name parameter)

### CDC Metrics (Micrometer, `redis.streaming.cdc.*`)

`CDCMetricsMicrometerBinder` binds at construction and `bind()` is idempotent and re-callable (newly registered connectors get bound; already-bound ones are skipped). Each gauge carries a `connector=<name>` tag, and the value is read live from `CDCManager.getMetrics(name)` at scrape time (0 when the connector is gone):

| Metric | Source (CDCMetrics) |
|---|---|
| `redis.streaming.cdc.events.total` | `totalEventsCaptured` |
| `redis.streaming.cdc.events.inserted` / `.updated` / `.deleted` / `.schema_changed` | insert/update/delete/schemaChange event counts |
| `redis.streaming.cdc.snapshot.records` | snapshot record count |
| `redis.streaming.cdc.errors.total` | error count |
| `redis.streaming.cdc.latency.avg.milliseconds` | average event latency |
| `redis.streaming.cdc.event.rate.per.second` | event rate |
| `redis.streaming.cdc.event.time.epoch.milliseconds` | latest event time (epoch ms; null → 0) |
| `redis.streaming.cdc.commit.time.epoch.milliseconds` | latest commit time (epoch ms; null → 0) |

## From InMemoryMetricCollector to Micrometer (migration table)

Deleted `metrics` module classes → their Micrometer counterparts (one-to-one semantics; naming per the previous section):

| Old (deleted) | Micrometer counterpart | Notes |
|---|---|---|
| `MetricCollector` (interface) | `MeterRegistry` | The collection facade is the registry; no homegrown abstraction anymore |
| `InMemoryMetricCollector` | `SimpleMeterRegistry` | In-process implementation, for tests/no-export scenarios |
| `incrementCounter(name)` / `(name, amount)` | `Counter.builder(name).register(registry).increment(amount)` | Count series are distinguished by tag combinations |
| `setGauge(name, value[, tags])` | `Gauge.builder(name, obj, valueFn).tags(...).register(registry)` | A Micrometer gauge is a weakly-referenced reading function, not set-and-forget |
| `recordHistogram(name, value)` | `DistributionSummary.builder(name).register(registry).record(value)` | Distribution statistics |
| `markMeter(name)` | `Counter` (or a `Timer`, which carries a count) | Micrometer has no separate meter type; rates are derived by the monitoring system from counters |
| `recordTimer(name, millis)` | `Timer.builder(name).register(registry).record(millis, MILLISECONDS)` | Produces percentiles/throughput automatically |
| `Metric` / `MetricType` | `Meter` / `Meter.Type` (COUNTER/GAUGE/TIMER/…) | Snapshot reads become registry `find(name).tags(...)` |
| `MetricRegistry` (fan-out to multiple collectors) | Multiple `MeterRegistry`s + `CompositeMeterRegistry` | Fan-out semantics carried by the composite registry |
| `MetricTimer.start(name, collector)` / `time(...)` | `Timer.Sample` (`sample.start()` → `sample.stop(timer)`) or `registry.timer(name).record(() -> ...)` | Same for AutoCloseable timing |
| `prometheus.PrometheusMetricCollector` + `PrometheusExporter` | `micrometer-registry-prometheus`'s `PrometheusMeterRegistry` + `HTTPServer` (or Spring Boot Actuator `/actuator/prometheus`) | Prometheus output is delegated wholesale to Micrometer |

The existing snake_case metric names (`redis_streaming_mq_*` etc.) are unaffected by the removal — they always lived in the starter's `*MicrometerCollector`s and never depended on the deleted homegrown facades.

## Minimal Example (Micrometer + Prometheus)

```java
// spring-boot-starter: add micrometer-registry-prometheus + actuator and /actuator/prometheus exists
// manual: hold a SimpleMeterRegistry or PrometheusMeterRegistry yourself
MeterRegistry registry = new SimpleMeterRegistry();
MqMetrics.setCollector(new MqMicrometerCollector(registry));   // unnecessary when the starter auto-installs

// any Micrometer instrument works directly:
Counter.builder("redis.streaming.mq.consumed.total")
       .tag("topic", "t1").tag("partition", "0")
       .register(registry).increment();
```

## References

- Spring-Boot-Starter.md - the Micrometer bridge and auto-install conditions
