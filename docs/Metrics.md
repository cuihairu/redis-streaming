# Metrics

Module: `metrics/`

## 模块职责

框架自用的指标采集抽象与 Prometheus 桥接。8 个主源码文件，包根 `io.github.cuihairu.redis.streaming.metrics`：

| 类 | 角色 |
|---|---|
| `MetricCollector` | 采集接口（counter/gauge/histogram/meter/timer + tags） |
| `InMemoryMetricCollector` | 进程内存实现（线程安全） |
| `Metric` / `MetricType` | 指标快照值对象与五种类型 |
| `MetricRegistry` | 多采集器注册与广播 |
| `MetricTimer` | 计时工具（`AutoCloseable`） |
| `prometheus.PrometheusMetricCollector` | 桥接到 `io.prometheus.simpleclient` |
| `prometheus.PrometheusExporter` | `/metrics` HTTP endpoint（`AutoCloseable`） |

边界说明：

- Prometheus client 在本模块 `build.gradle` 中是 **`compileOnly`** 依赖（`libs.prometheus.client` / `libs.prometheus.httpserver`），使用 `prometheus.*` 类时需要自行把 simpleclient 加进运行时 classpath。
- Micrometer 集成不在本模块：采集器在 `spring-boot-starter` 的 `starter.metrics.*MicrometerCollector`，通过各模块的静态单例（`MqMetrics` / `RedisRuntimeMetrics` / `RateLimitMetrics` 等）挂接。
- 本模块的类是通用工具：除示例和测试外，仓内主源码没有强绑定 `MetricRegistry` 的调用方。

## MetricCollector 接口

`public interface MetricCollector extends Serializable`，11 个方法：

```java
void incrementCounter(String name);                       // +1
void incrementCounter(String name, long amount);          // 步进；amount < 0 抛 IllegalArgumentException
void setGauge(String name, double value);
void setGauge(String name, double value, Map<String, String> tags);
void recordHistogram(String name, double value);
void markMeter(String name);                              // 事件速率打点
void recordTimer(String name, long durationMillis);
void incrementCounter(String name, Map<String, String> tags);
Map<String, Metric> getMetrics();                         // 全量快照
Metric getMetric(String name);                            // 不存在返回 null
void clear();
```

`MetricType` 枚举：`COUNTER`（单调递增）/ `GAUGE`（可上下）/ `HISTOGRAM`（分布）/ `METER`（事件速率）/ `TIMER`（耗时）。

`Metric`：`Metric.builder(name, type)` 起链（`name` 空、`type` 为 null 抛 `IllegalArgumentException`），链式 `.value(double)` / `.timestamp(long)`（默认当前毫秒）/ `.tag(k, v)`（null 键值跳过）/ `.tags(Map)`（null 跳过）/ `.build()`。读取：`getName()` / `getType()` / `getValue()` / `getTimestamp()` / `getTags()`（返回拷贝）/ `getTag(key)`。

## InMemoryMetricCollector

```java
InMemoryMetricCollector collector = new InMemoryMetricCollector();
collector.incrementCounter("mq_messages_total");        // -> 1
collector.incrementCounter("mq_messages_total", 5);     // -> 6
collector.setGauge("active_consumers", 3);
collector.recordHistogram("latency", 12.5);
collector.markMeter("requests");
collector.recordTimer("handle", 42);
collector.incrementCounter("hits", Map.of("tag", "v")); // 同时更新带标签序列 hits.tag_v
collector.getCounterValue("mq_messages_total");         // 6
collector.getGaugeValue("active_consumers");            // 3.0（不存在返回 0.0）
collector.getHistogramState("latency");                 // HistogramState：getCount/getSum/getMax/getMin/getMean
collector.getMetric("requests"); collector.getMetrics(); collector.clear();
```

实现细节（源码语义）：

- `incrementCounter(name, amount)` 对负步进抛 `IllegalArgumentException`。
- `markMeter` 内部用独立键 `name + ".meter"` 计数，避免与 `incrementCounter(name)` 互相污染；`getMetrics()` 里 meter 的 value 是累计次数。
- 带 tags 的序列按「键名 + 按 key 排序的 `.k_v` 拼接」形成独立序列（`name.tag_v`），同时也会更新不带 tag 的裸名（计数双重记账：裸名 +1、tagged 序列 +1）。
- `HISTOGRAM` 的 `Metric.value` 存最后一次样本（向后兼容），聚合统计在 `HistogramState`（count/sum/max/min/mean）。
- `getMetrics()` 返回拷贝；`clear()` 清空 counter/gauge/metric/histogram 四张表。

## MetricRegistry（多采集器）

```java
MetricRegistry registry = new MetricRegistry();     // 构造时注册 "default" -> InMemoryMetricCollector
registry.registerCollector("prom", new PrometheusMetricCollector());  // 名字为 "default" 时同时替换 defaultCollector
registry.getCollector("prom"); registry.getDefaultCollector();        // 无参构造的默认是 InMemoryMetricCollector
registry.incrementCounter("x");    // 广播到全部已注册采集器
registry.setGauge("g", 1.0); registry.recordHistogram("h", 2.0);
registry.markMeter("m"); registry.recordTimer("t", 100L);
registry.getAllMetrics();          // 合并全部采集器的指标
registry.clearAll(); registry.getCollectorCount();
```

`registerCollector("default", c)` 会同步移动 `getDefaultCollector()`，避免新旧默认采集器之间指标分裂。

## MetricTimer（计时）

```java
try (MetricTimer timer = MetricTimer.start("handle", collector)) {
    work();
}                                    // close() 记录耗时

MetricTimer.time("step", collector, () -> work());            // Runnable
String r = MetricTimer.time("step", collector, () -> "done"); // Callable<T>，返回结果
long ms = timer.stop();                                       // 返回毫秒；只记录一次
```

`stop()` 与 `close()` 复用同一个记录槽（`AtomicBoolean`），重复调用不会重复上报；`collector` 为 null 时只计时不记录。

## PrometheusMetricCollector

```java
PrometheusMetricCollector collector = new PrometheusMetricCollector("streaming"); // 默认 namespace 也是 "streaming"
// new PrometheusMetricCollector()                     -> namespace "streaming"，注册 defaultRegistry
// new PrometheusMetricCollector(namespace, registry)  -> 测试用独立 CollectorRegistry（null 回退 defaultRegistry）

collector.incrementCounter("mq_messages_total");
collector.setGauge("active_consumers", 3);
collector.incrementCounter("hits", Map.of("consumer", "c1"));
collector.getNamespace();
```

实现细节（源码语义）：

- 名字清洗：`[^a-zA-Z0-9_:]` → `_`，首字符不合法也替换为 `_`（符合 Prometheus 命名规则）。
- 无 tag 时创建 `Counter.build().namespace(ns).name(sanitized).register(registry)`；有 tag 时 metric 键为 `sanitized + "_labeled"`、label 名按字典序排序。
- 标签 schema 校验：同名 metric 的 label 集合不一致 → `IllegalArgumentException`（防止 label 漂移）。
- 类型冲突快速失败：同一清洗后名字已被注册成另一种类型（counter/gauge/histogram 互斥）→ `IllegalArgumentException`，而不是让 simpleclient 深处抛 "Collector already registered"。
- `recordTimer(name, millis)` → 以秒（`millis / 1000.0`）写入 Histogram，同时把 `Metric.value` 记为毫秒；`markMeter(name)` → 走 counter 累加。
- 带 tag 的 `getMetrics()` 序列键与 `InMemoryMetricCollector` 相同（`.k_v` 排序拼接）。
- `clear()` 从 registry 反注册全部 Counter/Gauge/Histogram 并清空内部缓存。

## PrometheusExporter（HTTP 暴露）

```java
try (PrometheusExporter exporter = new PrometheusExporter(9090)) {   // AutoCloseable
    collector.incrementCounter("mq_messages_total");
    exporter.getPort();            // 实际监听端口（HTTPServer 解析后）
    exporter.getMetricsUrl();      // http://localhost:<port>/metrics
}                                  // close() -> stop() 关闭 HTTPServer
```

- 无参构造监听**默认端口 9090**；构造即启动（`new HTTPServer(port)`），端口非法（如 -1）由 HTTPServer 抛 `IllegalArgumentException`；端口 `0` 为临时端口。
- 挂在 `CollectorRegistry.defaultRegistry` 上——需要与 `new PrometheusMetricCollector(...)`（默认 registry）配套使用才会暴露出来。

## 静态采集器挂钩与 starter 自动安装

各模块都有一个静态门面（默认 Noop，`setCollector` 忽略 null）：

| 门面 | 采集器接口 | starter 安装的实现 | 指标前缀 |
|---|---|---|---|
| `mq.metrics.MqMetrics` | `MqMetricsCollector` | `starter.metrics.MqMicrometerCollector` | `redis_streaming_mq_*` / `redis_streaming_dlq_*` |
| `runtime...metrics.RedisRuntimeMetrics` | `RedisRuntimeMetricsCollector` | `starter.metrics.RedisRuntimeMicrometerCollector` | `redis_streaming_runtime_*` |
| `reliability.metrics.RateLimitMetrics` | `RateLimitMetricsCollector` | `starter.metrics.RateLimitMicrometerCollector` | `redis_streaming_rl_*` |
| `mq.metrics.RetentionMetrics` | `RetentionMetricsCollector` | `starter.metrics.RetentionMicrometerCollector` | `redis_streaming_retention_*` / `redis_streaming_mq_trim_*` |

安装条件：classpath 有 `io.micrometer.core.instrument.MeterRegistry` 且容器中存在该 Bean（`RedisStreamingAutoConfiguration` / `RedisStreamingMqAutoConfiguration` 中的 `@ConditionalOnClass` + `@ConditionalOnBean` 安装 Bean）。也可手动调用 `XxxMetrics.setCollector(...)`。

CDC 是另一条通路：`CDCManager` 直接暴露 `CDCMetrics` 快照（无静态门面），由 `starter.metrics.CDCMetricsMicrometerBinder` 读快照导出 gauge——需 cdc 模块在 classpath 且应用自己注册了 `CDCManager` Bean，框架不会替用户创建连接器。

## 命名规范（docs/Metrics-Unification-Design.md 方案 A v1）

新指标一律使用 `redis.streaming.<module>.<metric>`（点分、Micrometer 原生风格），维度 tag 沿用 `job` / `topic` / `group` 等；多租户批后新增的 `tenant` 维度保留给控制面指标，业务指标暂不携带。

存量指标名（`redis_streaming_mq_*`、`redis_streaming_rl_*` 等蛇形前缀）**保持不变**，避免打爆已有 dashboard/告警；只有为它们补齐的缺口指标也沿用旧前缀（如 DLQ 重放 `redis_streaming_dlq_replay_success_total`）。两套名字的现状与 v2（`metrics` 模块删除、prometheus 输出交给 `micrometer-registry-prometheus`，走 major 版本）见 `Metrics-Unification-Design.md`。

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

- job/管道：`redis_streaming_runtime_job_started_total` / `job_canceled_total`（tag：`job`）；`pipeline_started_total` / `pipeline_start_failed_total` / `handle_success_total` / `handle_error_total`（tags：`job`、`topic`、`group`）
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

## 最小示例（Prometheus）

```java
import io.github.cuihairu.redis.streaming.metrics.prometheus.PrometheusExporter;
import io.github.cuihairu.redis.streaming.metrics.prometheus.PrometheusMetricCollector;

PrometheusMetricCollector collector = new PrometheusMetricCollector("streaming");
try (PrometheusExporter exporter = new PrometheusExporter(9090)) {
    collector.incrementCounter("mq_messages_total");
    collector.setGauge("active_consumers", 3);
    collector.setGauge("active_consumers", 3, Map.of("consumer", "c1")); // 带标签序列
    // scrape: http://localhost:9090/metrics
}
```

## References

- Spring-Boot-Starter.md - Micrometer 桥接与自动安装条件
