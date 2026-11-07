# 源码分层分析

分析基线：仓库 `HEAD` `768841207cba69987c1f15b6e07cbfb658d594aa`。范围为 Java 源码、Gradle 依赖、资源 schema 和 VitePress 文档站；排除构建产物与锁文件噪音。概念见 [术语表](./Glossary.md)，分层地图内嵌于 [架构设计](./Architecture.md)。

## 抽象层

1. **契约与运行时**
   - 契约：`core` 不依赖项目模块，定义 `DataStream`、键控状态、输出、检查点与窗口操作（`core/src/main/java/io/github/cuihairu/redis/streaming/api/stream/DataStream.java:14`）。
   - 执行：`runtime` 包含两个引擎——有界队列内存环境（`runtime/src/main/java/io/github/cuihairu/redis/streaming/runtime/StreamExecutionEnvironment.java:32`）和生产 Redis 编译器（`runtime/src/main/java/io/github/cuihairu/redis/streaming/runtime/redis/RedisStreamExecutionEnvironment.java:69`）。
   - 状态存储：引擎自有进程内 Map 或 Redis keyed-state Hash；配置来自 `RedisRuntimeConfig` 与 `MqOptions`。
   - 生命周期：内存执行由终结操作触发；Redis 运行时一次性注册定义，校验 topic/group 归属，恢复状态，创建消费者/Runner，再启动 checkpoint 协调（`runtime/src/main/java/io/github/cuihairu/redis/streaming/runtime/redis/RedisStreamExecutionEnvironment.java:448`）。
   - 调用面：用户代码 -> 环境/Builder -> `MessageQueueFactory` -> Redisson 与 Runner 回调 -> sink/checkpoint 钩子。

2. **基础通道**
   - MQ 是数据/结果通道。schema 覆盖租户、分区、线程、租约、认领、重试、键前缀、保留、ACK 策略和配额（`mq/src/main/java/io/github/cuihairu/redis/streaming/mq/config/MqOptions.java:12`）；Redis 键形集中在 `mq/src/main/java/io/github/cuihairu/redis/streaming/mq/partition/StreamKeys.java:17`。
   - Registry 将服务索引、详情、心跳和过滤信息写入 Redis（`registry/src/main/java/io/github/cuihairu/redis/streaming/registry/keys/RegistryKeys.java:14`），由客户端/服务端探针驱动健康检查，并用 Lua 做受保护变更（`registry/src/main/java/io/github/cuihairu/redis/streaming/registry/lua/RegistryLuaScriptExecutor.java:28`）。
   - Config 用 Redis Hash 字段承载记录，用 Lua 做版本写入/历史，Pub/Sub 做通知，定时重同步修复丢失通知（`config/src/main/java/io/github/cuihairu/redis/streaming/config/impl/RedisConfigService.java:19`、`:503`）。
   - 调用面：运行时、registry 消费者、Spring Bean 和管理端通过 Java API 调用；Redisson 是传输层；Pub/Sub 只承载事件，易丢失处由轮询兜底。

3. **语义算子**
   - State/checkpoint/watermark/window 定义状态、快照恢复与时间行为；实现可注入或由运行时委托。
   - Window 与 aggregation 在构建期组合：aggregation 依赖 window/runtime API（`aggregation/build.gradle:14`），runtime 依赖 MQ/window/checkpoint/watermark/join/state/cep（`runtime/build.gradle:28`）。
   - 状态存储：内存、keyed-state Redis Hash、checkpoint Redis Bucket、分析 ZSet/HyperLogLog（`aggregation/src/main/java/io/github/cuihairu/redis/streaming/aggregation/analytics/UVCounter.java:80`）、表 Hash/changelog topic（`table/src/main/java/io/github/cuihairu/redis/streaming/table/impl/RedisKTable.java:91`）。
   - 调用面：用户在 `DataStream`/`KeyedStream` 上调用算子；所选运行时把语义编译为本地执行或 MQ/Redis 管道操作。

4. **边界适配**
   - CDC 标准化数据库协议并发布变更事件；source/sink 连接 Kafka、Redis、HTTP 等边界。输入是 builder/config 对象，通道是连接器回调或 MQ producer。
   - Reliability 提供横切语义，不拥有管道：重试/DLQ、去重、配额、幂等、outbox 和租户键迁移。
   - CEP 是通过 process function/operator 挂接的语义模块，不是传输边界。
   - 存储横跨 Redis、外部数据库/Kafka/HTTP 和进程内存；连接器显式 start/stop/close，除非被外层生命周期托管。

5. **控制、装配与交付**
   - 作业控制面把声明式规格和期望状态转换写入 Redis（`runtime/src/main/java/io/github/cuihairu/redis/streaming/runtime/redis/control/RedisJobControlPlane.java:54`）；Job Agent 轮询并应用（`runtime/src/main/java/io/github/cuihairu/redis/streaming/runtime/redis/control/JobAgent.java:40`）。
   - Spring 读取 `redis-streaming.*`（`spring-boot-starter/src/main/java/io/github/cuihairu/redis/streaming/starter/properties/RedisStreamingProperties.java:11`），条件装配 Redisson 与功能 Bean，并把运行时/MQ/保留/限流指标适配到 Micrometer。
   - 指标能力拆分在 runtime SPI 和 Spring Micrometer 适配器之间；`settings.gradle:39` 中没有独立 metrics 模块。examples/benchmark 消费公共 API 且不发布（`settings.gradle:40`、`settings.gradle:41`）。
   - 生命周期遵循 Spring Bean 作用域和显式 service `start/stop/close`；示例/基准是进程生命周期演示。

## 实现、配置、协议与文档落位

| 层 | 实现位置 | 配置 schema | 专属协议文件 | 文案挂载 |
|---|---|---|---|---|
| 契约与运行时 | `core/src/main/java/.../api/**`、`runtime/src/main/java/.../runtime/**` | `runtime/src/main/java/io/github/cuihairu/redis/streaming/runtime/redis/RedisRuntimeConfig.java:17` | 无独立文件；Redis Streams ID/Lua 编码在 runtime/MQ 代码内 | `docs/Core.md`、`docs/runtime.md` 与中文镜像 |
| 基础通道 | `mq/src/main/java`、`registry/src/main/java`、`config/src/main/java` | `MqOptions`、`NamingServiceConfig`、config `BaseRedisConfig` | 服务类内有 Redis Streams 记录和 Lua 片段；无生成式协议工件 | `docs/MQ-Design.md`、`docs/Registry-Design.md`、`docs/config.md` 与中文镜像 |
| 语义算子 | `state`、`checkpoint`、`watermark`、`window`、`aggregation`、`table`、`join` | 构造器/Builder、StateDescriptor、执行时继承 RedisRuntimeConfig | 窗口/join 语义是 Java API；checkpoint 信封是 `Checkpoint` | `docs/state.md`、`docs/checkpoint.md`、`docs/watermark.md`、`docs/window.md`、`docs/Aggregation.md`、`docs/Table.md`、`docs/Join.md` 与中文镜像 |
| 边界适配 | `cdc`、`source`、`sink`、`reliability`、`cep` | connector builder/config、reliability 构造器 | CDC event/sink payload 为 Java/JSON，无 schema 文件 | `docs/CDC.md`、`docs/source-sink.md`、`docs/reliability.md`、`docs/CEP.md` 与中文镜像 |
| 控制、装配与交付 | `runtime/.../control`、`spring-boot-starter`、examples、benchmark | `RedisStreamingProperties`、JobSpec/agent/config 属性、`application.yml` 示例 | 控制 audit/spec JSON 与自动配置 imports | `docs/Control-Plane-Design.md`、`docs/Spring-Boot-Starter.md`、`docs/Examples.md` 与中文镜像 |

## 拆分理由

1. 契约先行：`core` 不依赖其他项目模块，其他模块依赖它，避免把引擎/适配器传输语义泄漏进用户 API（`core/build.gradle:17`、`runtime/build.gradle:28`）。
2. 运行时不等于传输：两个引擎实现同一流契约，但内存引擎用本地有界队列（`runtime/src/main/java/io/github/cuihairu/redis/streaming/runtime/StreamExecutionEnvironment.java:34`），Redis 运行时用 MQ 消费者和 Redisson（`runtime/src/main/java/io/github/cuihairu/redis/streaming/runtime/redis/RedisStreamExecutionEnvironment.java:563`）；同层归并避免按引擎造目录，同时保持生产行为可审计。
3. 通道是共享基础设施：runtime 导入 MQ（`runtime/build.gradle:28`），table changelog 复用 MQ producer（`table/src/main/java/io/github/cuihairu/redis/streaming/table/impl/RedisKTable.java:73`），CDC 发布进 MQ（`cdc/src/main/java/io/github/cuihairu/redis/streaming/cdc/mq/ChangeEventQueueSink.java:20`）；通道层可避免重复实现分区、租约、重试、ACK/frontier 和保留。
4. Registry/config 按调用模式拆分：registry 持续变更实例与心跳，config 做版本化文档 CRUD 与监听器重同步；两者 Redis 键 schema 和失败恢复不同（`registry/src/main/java/io/github/cuihairu/redis/streaming/registry/keys/RegistryKeys.java:32`、`config/src/main/java/io/github/cuihairu/redis/streaming/config/impl/RedisConfigService.java:503`）。
5. 语义算子与引擎隔离：window/CEP API 是纯契约，运行时持有触发调度（`runtime/build.gradle:28`）；aggregation 构建在 window 上（`aggregation/build.gradle:14`）。语义演进不必改动 Redis 运行时调度或 MQ payload。
6. State 与 checkpoint 是不同概念，不应合并成一个文件夹：state 管理活跃 keyed 数据与 schema（`state/src/main/java/io/github/cuihairu/redis/streaming/state/redis/RedisStateBackend.java:32`），checkpoint 协调源偏移与算子快照（`runtime/src/main/java/io/github/cuihairu/redis/streaming/runtime/redis/internal/RedisRuntimeCheckpointManager.java`）；分离使恢复协调独立于逐条状态访问。
7. 边界适配器有独立生命周期和协议解析：MySQL/PostgreSQL 连接器拥有协议客户端和轮询（`cdc/src/main/java/io/github/cuihairu/redis/streaming/cdc/impl/MySQLBinlogCDCConnector.java:41`），source/sink 调外部客户端，CEP/reliability 语义必须能脱离 Spring 使用。
8. 控制与数据执行分离：提交/升级/回滚/审计/配额作用于期望状态而非记录（`runtime/src/main/java/io/github/cuihairu/redis/streaming/runtime/redis/control/RedisJobControlPlane.java:109`）；Spring 装配再独立，因为功能启用是环境作用域决策（`spring-boot-starter/src/main/java/io/github/cuihairu/redis/streaming/starter/properties/RedisStreamingProperties.java:11`）。
9. 交付组合与库语义分离：examples 几乎消费所有功能模块（`examples/build.gradle:14`），benchmark 依赖实测 MQ/runtime 行为（`benchmark/build.gradle:14`）；`settings.gradle:39` 无 metrics 条目，发布交付模块会扩大 API 面并把用户耦合到演示驱动。
