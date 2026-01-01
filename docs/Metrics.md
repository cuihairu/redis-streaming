# Metrics

Module: `metrics/`

Micrometer/Prometheus metrics integration.

## Scope
- 指标采集抽象（`MetricCollector`）
- Prometheus exporter（HTTP endpoint）与 Prometheus collector 实现

## Key Classes
- `MetricCollector`
- `prometheus.PrometheusMetricCollector`
- `prometheus.PrometheusExporter`
- `starter.metrics.MqMicrometerCollector`（MQ 内部指标，Micrometer）

## Minimal Sample (Prometheus)
```java
import io.github.cuihairu.redis.streaming.metrics.prometheus.PrometheusExporter;
import io.github.cuihairu.redis.streaming.metrics.prometheus.PrometheusMetricCollector;

PrometheusMetricCollector collector = new PrometheusMetricCollector("streaming");
try (PrometheusExporter exporter = new PrometheusExporter(9090)) {
    collector.incrementCounter("mq_messages_total");
    collector.setGauge("active_consumers", 3);
    // scrape: http://localhost:9090/metrics
}
```

## MQ Metrics (Micrometer)

When using `spring-boot-starter`, you can install a Micrometer collector for MQ via `MqMetrics.setCollector(...)`.

Common metric names:
- `redis_streaming_mq_inflight` (gauge, tag: `consumer`)
- `redis_streaming_mq_max_inflight` (gauge, tag: `consumer`)
- `redis_streaming_mq_max_leased_partitions` (gauge, tag: `consumer`)
- `redis_streaming_mq_backpressure_wait_total` (counter, tag: `consumer`)
- `redis_streaming_mq_backpressure_wait_ms` (timer, tag: `consumer`)
- `redis_streaming_mq_eligible_partitions` (gauge, tags: `consumer`, `topic`, `group`)
- `redis_streaming_mq_leased_partitions` (gauge, tags: `consumer`, `topic`, `group`)

## Runtime Metrics (Micrometer)

When using `spring-boot-starter`, Redis runtime metrics are auto-installed via `RedisRuntimeMetrics.setCollector(...)`.

Common metric names:
- `redis_streaming_runtime_job_started_total` (counter, tag: `job`)
- `redis_streaming_runtime_pipeline_started_total` (counter, tags: `job`, `topic`, `group`)
- `redis_streaming_runtime_handle_success_total` / `redis_streaming_runtime_handle_error_total` (counter, tags: `job`, `topic`, `group`)
- `redis_streaming_runtime_handle_latency_ms` (timer, tags: `job`, `topic`, `group`)
- `redis_streaming_runtime_checkpoint_triggered_total` / `completed_total` / `failed_total` (counter, tag: `job`)
- `redis_streaming_runtime_checkpoint_duration_ms` / `drain_duration_ms` / `store_duration_ms` / `sink_commit_duration_ms` (timer, tag: `job`)
- `redis_streaming_runtime_keyed_state_*` (read/write/delete counters + read/write latency timers; tags: `job`, `topic`, `group`, `operator`, `state`, `partition`)
- `redis_streaming_runtime_event_time_timer_queue_size` (gauge, tags: `job`, `topic`, `group`)
- `redis_streaming_runtime_watermark_ms` (gauge, tags: `job`, `topic`, `group`)
- `redis_streaming_runtime_window_fired_total` / `window_late_dropped_total` (counter, tags: `job`, `topic`, `group`, `operator`, `state`, `partition`)

## References
- Spring-Boot-Starter.md
- Spring-Boot-Starter-en.md
