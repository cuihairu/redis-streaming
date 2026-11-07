# 术语表

本表按源码中的运行时角色归组，而不是按 Gradle 目录名复述。证据锚点均使用仓库相对 `file:line`。

- 架构职责见 [架构设计](./Architecture.md)；本页是概念落位表。
- 拆分依据见 [源码分层分析](./Source-Layer-Analysis.md)。
- English version: [Glossary](../Glossary.md).

| 术语 | 英文 | 定义 | file:line |
|---|---|---|---|
| 编程契约 | Programming Contract | 所有执行引擎共用的流、键控状态、窗口、输出、检查点、水位线与租户契约，不绑定 Redis。 | core/src/main/java/io/github/cuihairu/redis/streaming/api/stream/DataStream.java:14 |
| 内存执行器 | In-Memory Runtime | 面向测试与演示的惰性执行运行时；用有界队列传递记录，算子状态留在进程内，不依赖 Redis。 | runtime/src/main/java/io/github/cuihairu/redis/streaming/runtime/StreamExecutionEnvironment.java:32 |
| Redis 执行器 | Redis Runtime | 生产单进程引擎；将 MQ 管道编译为消费者/Runner、定时器、检查点协调和作业生命周期操作。 | runtime/src/main/java/io/github/cuihairu/redis/streaming/runtime/redis/RedisStreamExecutionEnvironment.java:69 |
| 消息通道 | Message Channel | 以分区 Redis Streams 为数据与结果通道，提供消费组、租约、Pending 认领、重试/DLQ、commit frontier、保留和管理操作。 | mq/src/main/java/io/github/cuihairu/redis/streaming/mq/config/MqOptions.java:12 |
| 服务注册与发现 | Registry | 基于 Redis Set、Hash、ZSet、Pub/Sub 和脚本实现实例注册、心跳、过滤、负载均衡、健康检查、脚本化清理与变更通知。 | registry/src/main/java/io/github/cuihairu/redis/streaming/registry/keys/RegistryKeys.java:14 |
| 配置中心 | Config Center | 将版本化 group/data-id 配置存入 Redis Hash，提供历史、监听器、Pub/Sub 加定时重同步以及 CRUD/管理语义。 | config/src/main/java/io/github/cuihairu/redis/streaming/config/impl/RedisConfigService.java:19 |
| 管理状态 | Managed State | 通过状态描述符与 schema registry 暴露 Value/Map/Set/List 状态；内存运行时留在本地，Redis 后端物化为状态 Hash。 | core/src/main/java/io/github/cuihairu/redis/streaming/api/state/State.java:10 |
| 检查点协议 | Checkpoint Protocol | 对源偏移与算子状态做一致快照，支持恢复、保留、栅栏协调和两阶段输出恢复。 | checkpoint/src/main/java/io/github/cuihairu/redis/streaming/checkpoint/redis/RedisCheckpointStorage.java:19 |
| 时间语义 | Time Semantics | 提供事件时间赋值、水位线生成、空闲处理和处理时间触发，约束窗口推进。 | watermark/src/main/java/io/github/cuihairu/redis/streaming/watermark/WatermarkStrategy.java:21 |
| 窗口算子 | Window Operator | 定义滚动、滑动、会话分配及计数/事件时间/处理时间触发与驱逐器；执行由运行时委托完成。 | window/src/main/java/io/github/cuihairu/redis/streaming/window/assigners/TumblingWindow.java:15 |
| 增量聚合 | Incremental Aggregation | 聚合函数与 PV/UV/分位数/TopK 分析；状态落在 Redis ZSet、HyperLogLog 和桶索引。 | aggregation/src/main/java/io/github/cuihairu/redis/streaming/aggregation/analytics/UVCounter.java:24 |
| 表视图 | Table View | 实现流表一体：表物化键值状态，提供关系变换，并可选向 MQ 追加 changelog。 | table/src/main/java/io/github/cuihairu/redis/streaming/table/impl/RedisKTable.java:53 |
| 流连接 | Stream Join | 在键控流上做带时间/窗口约束的关联，并用 topic mode 作为跨引擎传输。 | join/src/main/java/io/github/cuihairu/redis/streaming/join/StreamJoiner.java:26 |
| 变更数据捕获 | CDC | 通过 MySQL binlog、PostgreSQL logical replication 和轮询连接器标准化数据库变更并交给 MQ Sink。 | cdc/src/main/java/io/github/cuihairu/redis/streaming/cdc/CDCManager.java:18 |
| 输入连接器 | Source Connector | Redis、Kafka、HTTP 等外部入口适配器；运行在 Redis 运行时编译器之外，回调注册的流接口。 | source/src/main/java/io/github/cuihairu/redis/streaming/source/redis/RedisListSource.java:26 |
| 输出连接器 | Sink Connector | Redis、Kafka、HTTP/file/console 输出契约的批量写入器；兼容的 sink 会被 Redis 运行时包装进投递协议。 | sink/src/main/java/io/github/cuihairu/redis/streaming/sink/redis/RedisHashSink.java:22 |
| 可靠性原语 | Reliability Primitives | 去重、重试/DLQ 边界、配额、幂等、outbox 派发和租户键迁移等构建块。 | reliability/src/main/java/io/github/cuihairu/redis/streaming/reliability/deduplication/Deduplicator.java:8 |
| 模式检测 | Pattern Detection | 复杂事件模式定义、序列匹配、有状态处理函数和算子集成。 | cep/src/main/java/io/github/cuihairu/redis/streaming/cep/PatternSequenceMatcher.java:25 |
| 作业控制面 | Job Control Plane | 声明式作业规格，基于 Redis 提交/升级/回滚/停止/审计/配额，并由 agent 应用期望状态。 | runtime/src/main/java/io/github/cuihairu/redis/streaming/runtime/redis/control/RedisJobControlPlane.java:54 |
| Spring 装配 | Spring Wiring | 条件装配、Redisson 与生命周期 Bean、Micrometer 采集、保留清理、控制面 agent 以及 `redis-streaming.*` 属性绑定。 | spring-boot-starter/src/main/java/io/github/cuihairu/redis/streaming/starter/properties/RedisStreamingProperties.java:11 |
| 可观察性 | Observability | 通过运行时 SPI 与 Micrometer 适配器暴露运行时/MQ 计数和指标。 | core/src/main/java/io/github/cuihairu/redis/streaming/api/metrics/StreamingMetricsCollector.java:9 |
| 示例与基准 | Examples and Benchmarks | 可执行用法组合和 Redis 实测吞吐/检查点入口，明确不作为发布库。 | examples/src/main/java/io/github/cuihairu/redis/streaming/examples/mq/MessageQueueExample.java:25 |
