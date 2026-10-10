# 指标体系统一设计（四套 → Micrometer 单门面）

本文供评审，不含代码改动。目标：仓库目前有四套互不连通的"指标"代码，其中两套是死代码；收敛为以 Micrometer 为唯一门面的一套。

## 现状（事实盘点）

| 位置 | 是什么 | 生产侧使用情况 |
| --- | --- | --- |
| `metrics` 模块（`MetricCollector/MetricRegistry/InMemoryMetricCollector/MetricTimer/prometheus` 等） | 自建指标门面 + 内存实现 + Prometheus 输出 | **零生产消费者**（除 examples），事实上的死代码 |
| `reliability` `metrics/ReliabilityMetrics` | 指标桥接 | **零调用者**，死桥 |
| `cdc` `CDCMetrics` | 不可变 POJO（计数快照） | CDC 连接器在用，但**没有 Micrometer 桥**，用户拿不到运行时可观测数据 |
| `runtime` `RedisRuntimeMetrics`（+`RedisRuntimeMetricsCollector`） | 运行时指标（watermark、延迟、in-flight 等） | **唯一真正在跑的**，runtime 内部多处调用 |

其他事实：

1. 只有 `spring-boot-starter` 依赖 Micrometer（`api libs.micrometer.core`），且已有先例：RateLimit 指标通过 `@ConditionalOnClass(MeterRegistry)` 桥接到 Micrometer（`RedisStreamingAutoConfiguration`）。
2. `metrics` 模块无 Micrometer 依赖，与 starter 桥各走各路。
3. 命名格式至少三种（`topic.group`、`job.topic.group`、camel/snake 混用），跨模块无法聚合对比。
4. `runtime` 指标带作业/主题/消费组维度（如 `setWatermarkMs(job, topic, group, ms)`），是四套里唯一带完整维度的。

## 问题

- 用户接 Micrometer/Prometheus 时只有 RateLimit 与 runtime 部分指标可见；CDC、reliability 全部不可观测。
- `metrics` 模块维护成本照付（测试、覆盖率门禁、API 演进），却没人用。
- 自建门面无法复用 Micrometer 生态（registry 自动配置、Boot 指标导出、直方图发布等）。

## 可选方案

### 方案 A：Micrometer 为唯一门面，runtime 指标为基准（推荐）

- **标准**：对外可观测性只有一条路——Micrometer `MeterRegistry`。命名规范统一为 `redis.streaming.<module>.<metric>`（小写点分），维度统一带 `job`/`topic`/`group`/`tenant`（预留）。
- **runtime 指标提升为公共 SPI**：`RedisRuntimeMetrics` 的接口部分抽到公共位置（或提取 `StreamingMetrics` 接口），实现保留 runtime；starter 用与 RateLimit 相同的 `@ConditionalOnClass(MeterRegistry)` 模式自动桥接。
- **CDC**：`CDCMetrics` 保留为纯快照 POJO（对进程内轮询有用），另加一个 Micrometer 导出器（starter 自动装配时绑定到 registry），连接器每轮采集后打点。
- **清理**：删除 `reliability` `ReliabilityMetrics` 死桥；`metrics` 模块在 v2 删除（走 major），其 prometheus 输出由 Micrometer 的 `micrometer-registry-prometheus` 承担。

### 方案 B：保留自建 `metrics` 门面，为各模块补桥接

把 starter 的 Micrometer 桥换成"桥到 metrics 模块"，再由 metrics 模块输出 Prometheus。多一层自建抽象要长期维护，且与 Spring Boot 生态的既有习惯相反——**不推荐**。

### 方案 C：各模块各自直接依赖 Micrometer 打点

不抽公共接口，各模块自己 `Metrics.counter(...)`。短期最快，但命名/维度约定必然漂移（现状已经是这样），需要文档纪律兜底——作为方案 A 落地过程中的过渡形态可以接受，终态不推荐。

## 迁移路径（方案 A 分期）

1. **v1**：runtime 指标接口公共化 + starter 自动桥接 Micrometer；CDC 导出器；统一命名规范写入文档；删除 `ReliabilityMetrics` 死桥。
2. **v2（major）**：删除 `metrics` 模块；文档给出"从 InMemoryMetricCollector 到 Micrometer"的迁移对照表。

## 非目标

- 不自建指标存储/时序库；传输（Pushgateway、OTLP 等）交给 Micrometer。
- 不做指标卡口限流/采样（如需要，Micrometer 有 `MeterFilter`）。
- 不在本设计内扩展 metrics 覆盖面（新指标按需另提）。
