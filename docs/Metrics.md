# Metrics

框架的对外可观测性只有一条路：Micrometer `MeterRegistry`（spring-boot-starter 自动桥接，见下文「静态采集器挂钩与 starter 自动安装」）。Prometheus 抓取走 Micrometer 生态（`micrometer-registry-prometheus` / Spring Boot Actuator）。自建的 `metrics` 模块（`MetricCollector`/`InMemoryMetricCollector`/`MetricRegistry`/`MetricTimer`/`prometheus.*`）已于 2026-10-12 删除（[Metrics-Unification-Design.md](Metrics-Unification-Design.md) 方案 A v2），旧用户迁移见下文「从 InMemoryMetricCollector 到 Micrometer」。

## 静态采集器挂钩与 starter 自动安装

各模块都有一个静态门面（默认 Noop，`setCollector` 忽略 null）：

| 门面 | 采集器接口 | starter 安装的实现 | 指标前缀 |
|---|---|---|---|
| `mq.metrics.MqMetrics` | `MqMetricsCollector` | `starter.metrics.MqMicrometerCollector` | `redis_streaming_mq_*` / `redis_streaming_dlq_*` |
| `runtime...metrics.RedisRuntimeMetrics` | `api.metrics.StreamingMetricsCollector`（公共 SPI，core 模块） | `starter.metrics.RedisRuntimeMicrometerCollector` | `redis_streaming_runtime_*` |
| `reliability.metrics.RateLimitMetrics` | `RateLimitMetricsCollector` | `starter.metrics.RateLimitMicrometerCollector` | `redis_streaming_rl_*` |
| `mq.metrics.RetentionMetrics` | `RetentionMetricsCollector` | `starter.metrics.RetentionMicrometerCollector` | `redis_streaming_retention_*` / `redis_streaming_mq_trim_*` |

安装条件：classpath 有 `io.micrometer.core.instrument.MeterRegistry` 且容器中存在该 Bean（`RedisStreamingAutoConfiguration` / `RedisStreamingMqAutoConfiguration` 中的 `@ConditionalOnClass` + `@ConditionalOnBean` 安装 Bean）。也可手动调用 `XxxMetrics.setCollector(...)`。

CDC 是另一条通路：`CDCManager` 直接暴露 `CDCMetrics` 快照（无静态门面），由 `starter.metrics.CDCMetricsMicrometerBinder` 读快照导出 gauge——需 cdc 模块在 classpath 且应用自己注册了 `CDCManager` Bean，框架不会替用户创建连接器。

## 命名规范（docs/Metrics-Unification-Design.md 方案 A v1）

新指标一律使用 `redis.streaming.<module>.<metric>`（点分、Micrometer 原生风格），维度 tag 沿用 `job` / `topic` / `group` 等；多租户批后新增的 `tenant` 维度保留给控制面指标，业务指标暂不携带。

存量指标名（`redis_streaming_mq_*`、`redis_streaming_rl_*` 等蛇形前缀）**保持不变**，避免打爆已有 dashboard/告警；只有为它们补齐的缺口指标也沿用旧前缀（如 DLQ 重放 `redis_streaming_dlq_replay_success_total`）。v2（`metrics` 模块已删除、Prometheus 输出交给 `micrometer-registry-prometheus`，2026-10-12 落地）见 `Metrics-Unification-Design.md`。

目前按新规范命名的只有 CDC 导出器（`redis.streaming.cdc.*`，tag `connector`）。

### MQ 指标（Micrometer）

来自 `MqMicrometerCollector`（每个计数/计时带 `topic`、`partition` tag；gauge 见下）：

- `redis_streaming_mq_produced_total` / `redis_streaming_mq_consumed_total` / `redis_streaming_mq_acked_total` / `redis_streaming_mq_retried_total` / `redis_streaming_mq_dead_total` / `redis_streaming_mq_payload_missing_total`（counter）
- `redis_streaming_mq_handle_latency_ms`（timer）
- `redis_streaming_mq_inflight` / `redis_streaming_mq_max_inflight` / `redis_streaming_mq_max_leased_partitions`（gauge，tag：`consumer`）
- `redis_streaming_mq_backpressure_wait_total`（counter）/ `redis_streaming_mq_backpressure_wait_ms`（timer），tag：`consumer`
- `redis_streaming_mq_eligible_partitions` / `redis_streaming_mq_leased_partitions`（gauge，tags：`consumer`、`topic`、`group`）
- DLQ 重放/清理（tags：`topic`、`partition`；delete/clear 无 partition tag）：`redis_streaming_dlq_replay_success_total` / `redis_streaming_dlq_replay_failure_total`（counter）、`redis_streaming_dlq_replay_latency_ms`（timer）、`redis_streaming_dlq_deleted_total`（counter）、`redis_streaming_dlq_cleared_total`（counter，按删除条数步进）

另有 `MqMetricsBinder`（基于 `MessageQueueAdmin`/`DeadLetterService` 的函数式 gauge）：`redis_streaming_mq_topics_total`、`redis_streaming_mq_messages_total`（各 topic 长度求和）、`redis_streaming_mq_dlq_total`（各 topic DLQ 求和）；`RetentionFrontierMetricsBinder` 暴露 `redis_streaming_mq_frontier_age_ms`。

### Runtime 指标（Micrometer）

来自 `RedisRuntimeMicrometerCollector`（`RedisRuntimeMetrics.setCollector(...)` 挂接）：

- job/管道：`redis_streaming_runtime_job_started_total` / `job_canceled_total`（tag：`job`）；`pipeline_started_total` / `pipeline_start_failed_total` / `handle_success_total`（tags：`job`、`topic`、`group`）；`handle_error_total` 另带 `result` 维度（失败记录的处置去向：`retry` / `fail` / `dead_letter`，由 `processingErrorResult` 与 runner 返回值解析；旧的三参调用与自定义采集器仍走无 `result` tag 的原序列）
- 处理耗时：`redis_streaming_runtime_handle_latency_ms`（timer，tags：`job`、`topic`、`group`）
- checkpoint：`checkpoint_triggered_total` / `checkpoint_completed_total` / `checkpoint_failed_total`（counter，tag：`job`）；`checkpoint_duration_ms` / `checkpoint_drain_duration_ms` / `checkpoint_store_duration_ms` / `checkpoint_sink_commit_duration_ms`（timer，tag：`job`）
- keyed state：`redis_streaming_runtime_keyed_state_read_total` / `write_total` / `delete_total` / `hot_key_total`（counter）与 `read_latency_ms` / `write_latency_ms`（timer）、`size_fields`（DistributionSummary），tags：`job`、`topic`、`group`、`operator`、`state`、`partition`
- 事件时间/水位线：`event_time_timer_queue_size`（gauge）、`watermark_ms`（gauge），tags：`job`、`topic`、`group`
- 窗口：`window_fired_total` / `window_late_dropped_total`（counter），tags：`job`、`topic`、`group`、`operator`、`state`、`partition`（注意：tag 键为 `state`，值取的是窗口名参数）

### CDC 指标（Micrometer，`redis.streaming.cdc.*`）

`CDCMetricsMicrometerBinder` 构造即绑定、`bind()` 幂等可重调（新注册的连接器补绑，已绑的不重复）。每个 gauge 带 `connector=<name>` tag，值在抓取时实时读 `CDCManager.getMetrics(name)`（连接器已移除则回 0）：

| 指标 | 来源（CDCMetrics） |
|---|---|
| `redis.streaming.cdc.events.total` | `totalEventsCaptured` |
| `redis.streaming.cdc.events.inserted` / `.updated` / `.deleted` / `.schema_changed` | insert/update/delete/schemaChange 事件数 |
| `redis.streaming.cdc.snapshot.records` | 快照记录数 |
| `redis.streaming.cdc.errors.total` | 错误计数 |
| `redis.streaming.cdc.latency.avg.milliseconds` | 平均事件时延 |
| `redis.streaming.cdc.event.rate.per.second` | 事件速率 |
| `redis.streaming.cdc.event.time.epoch.milliseconds` | 最近事件时间（epoch 毫秒，null → 0） |
| `redis.streaming.cdc.commit.time.epoch.milliseconds` | 最近提交时间（epoch 毫秒，null → 0） |

## 从 InMemoryMetricCollector 到 Micrometer（迁移对照表）

已删除的 `metrics` 模块旧类 → Micrometer 对应写法（语义一一对应，命名规范见上一节）：

| 旧（已删除） | Micrometer 对应 | 说明 |
|---|---|---|
| `MetricCollector`（接口） | `MeterRegistry` | 采集门面由 registry 承担，不再自建抽象 |
| `InMemoryMetricCollector` | `SimpleMeterRegistry` | 进程内实现，测试/无导出场景 |
| `incrementCounter(name)` / `(name, amount)` | `Counter.builder(name).register(registry).increment(amount)` | 计数序列按 tag 组合区分 |
| `setGauge(name, value[, tags])` | `Gauge.builder(name, obj, valueFn).tags(...).register(registry)` | Micrometer gauge 是弱引用读数函数，不是 set-and-forget |
| `recordHistogram(name, value)` | `DistributionSummary.builder(name).register(registry).record(value)` | 分布统计 |
| `markMeter(name)` | `Counter`（或 `Timer` 携带计数） | Micrometer 无独立 meter 类型；速率由监控系统按 counter 派生 |
| `recordTimer(name, millis)` | `Timer.builder(name).register(registry).record(millis, MILLISECONDS)` | 自动产出百分位/吞吐 |
| `Metric` / `MetricType` | `Meter` / `Meter.Type`（COUNTER/GAUGE/TIMER/…） | 快照读取改为 registry 对应 `find(name).tags(...)` |
| `MetricRegistry`（多采集器广播） | 多个 `MeterRegistry` + `CompositeMeterRegistry` | 广播语义由复合 registry 承担 |
| `MetricTimer.start(name, collector)` / `time(...)` | `Timer.Sample`（`sample.start()` → `sample.stop(timer)`）或 `registry.timer(name).record(() -> ...)` | AutoCloseable 计时同理 |
| `prometheus.PrometheusMetricCollector` + `PrometheusExporter` | `micrometer-registry-prometheus` 的 `PrometheusMeterRegistry` + `HTTPServer`（或 Spring Boot Actuator `/actuator/prometheus`） | Prometheus 输出整体交给 Micrometer |

存量蛇形指标名（`redis_streaming_mq_*` 等）不受本次删除影响——它们从来都在 starter 的 `*MicrometerCollector` 里，与被删的自建门面无关。

## 最小示例（Micrometer + Prometheus）

```java
// spring-boot-starter 环境：引入 micrometer-registry-prometheus + actuator 即有 /actuator/prometheus
// 手动环境：SimpleMeterRegistry 或 PrometheusMeterRegistry 自行持有
MeterRegistry registry = new SimpleMeterRegistry();
MqMetrics.setCollector(new MqMicrometerCollector(registry));   // starter 自动安装时无需手动 set

// 任意 Micrometer 仪表可直接使用：
Counter.builder("redis.streaming.mq.consumed.total")
       .tag("topic", "t1").tag("partition", "0")
       .register(registry).increment();
```

## References

- Spring-Boot-Starter.md - Micrometer 桥接与自动安装条件
