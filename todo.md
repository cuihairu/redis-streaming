# 测试覆盖率提升待办清单

> 生成时间：2025-12-30
> 总体覆盖率：55% (指令) / 44% (分支)
> 目标：将所有包的覆盖率提升至 70% 以上

## 优先级 1：覆盖率 < 20%（紧急）

### 1. io.github.cuihairu.redis.streaming.cdc.impl - ✅ 已完成（2026-09-27）
**原状态：** 5%（数据过期；补测前实测包内已约 86% 指令 / 74% 分支）
**当前状态：** **98% 指令 / 87% 分支**（JaCoCo，单元测试）；各核心类：
- `AbstractCDCConnector` - 100% 指令 / 97% 分支
- `DatabasePollingCDCConnector` - 99% 指令 / 92% 分支
- `MySQLBinlogCDCConnector` - 94% 指令 / 79% 分支
- `PostgreSQLLogicalReplicationCDCConnector` - 98% 指令 / 83% 分支
- `TableFilter` / `DriverManagerMySQLColumnNameResolver` / `BackpressureSettings` - 100% / 100%

**已添加的测试（新增 4 个单元测试类 + 1 个集成测试类，约 50 个用例）：**
- [x] **单元测试**（不依赖外部环境）
  - CDC 配置构建测试（配置工厂、非法值容错、列解析降级与缓存）
  - 事件过滤测试（TableFilter include/exclude、无 TableMap 静默跳过）
  - 错误处理测试（启动失败清理、调度器关闭异常吞掉、抛异常监听器不杀死轮询循环、反压中断不推进水位）
  - 重试/恢复分支测试（初始连接失败清理与 UNHEALTHY、重连循环退避/恢复/停止、槽位失效检测、限速重连）
  - DatabasePolling 水位线分支（批量截断并列组、abort/interrupt 不持久化超发水位，H2 内存库驱动）
- [x] **集成测试**（`CDCPositionResumeIntegrationTest`，@Tag("integration")，无环境时自动跳过）
  - MySQL Binlog：水位线读取 → 停机窗口写入 → 从记录的 binlog 位点重启恢复（停机窗口重放 ≥1、位点前变更零重放）
  - PostgreSQL 逻辑复制：slot 停机保留 WAL → 重启同 slot 续读（无丢失）
  - 连接失败重试 / 断连恢复：见既有 `CDCDisconnectReconnectIntegrationTest`（上轮 CDC-H3 已补）

**测试环境要求：**
- MySQL 5.7+（启用 binlog）/ PostgreSQL 10+（wal_level=logical）——仅集成测试需要，单元测试零外部依赖

**遗留观察（后续任务候选，本次未改动生产代码）：**
- test_decoding 对 `character varying` 类型的输出在 `[类型]` 标签内含不带引号的空格，`parseColumnData` 的 quote-aware 分词器会在该空格处切断列 token（`v[character varying]:'x'` → 键变成 `varying]`，半截 `v[character` 被丢弃）。CDC-M7 修复了"值内空格"，未覆盖"类型名内空格"。集成测试改用 TEXT 列规避。

---

### 2. io.github.cuihairu.redis.streaming.window - ✅ 已完成（2026-09-27）
**原状态：** 18%（数据过期；补测前实测 :window 模块已约 99% 指令 / 90% 分支——历史测试已覆盖大部分清单场景）
**当前状态：** **100% 指令 / 100% 分支**（JaCoCo，:window 模块全部 3 个包 7 个类）；examples 模块的 `WindowExample` 亦已可测试化并由测试驱动（原 0%）

**本次补充（`WindowAssignerResidualCoverageTest` + `WindowExampleTest`，示例侧仅做可见性改造）：**
- [x] **时间窗口分配测试**：TimeWindow contains/merge/intersects/maxTimestamp（既有 TimeWindowTest 等），新增滑动分配的半开区间排除分支与 Long.MAX_VALUE 溢出守卫分支
- [x] **滚动窗口聚合测试**：对齐（floorMod 前纪元负时间戳）、批量时间戳→窗口映射、size<=0 校验（既有 TumblingWindowAssignerTest；聚合执行链路由 runtime/aggregation 模块承担，见清单第 5 项）
- [x] **滑动窗口聚合测试**：重叠窗口确定性与成员断言、size==slide、大 slide、边界时间戳、size/slide<=0 校验（既有）+ 新增 start+size==timestamp 精确排除
- [x] **会话窗口测试**：gap 种子窗口、shouldMerge 相交/相邻判定、零 gap、大 gap（既有）+ 新增 supportsWindowMerging 契约（session=true，tumbling/sliding=接口默认 false）
- [x] **窗口触发器测试**：EventTime 水位触发/CONTINUE、ProcessingTime 到期触发、CountTrigger 计数触发与 maxCount<=0 校验（既有 CountTriggerTest / ProcessingTimeTriggerTest / EventTimeTriggerTest）
- [x] **示例可测试化**：WindowExample 四个演示方法 private→包可见（无语义改动），examples 模块新增 WindowExampleTest 驱动 main 与各演示段并断言五类语义

---

### 3. io.github.cuihairu.redis.streaming.starter.autoconfigure - ✅ 已完成（2026-09-27，实测 100%/100%）
**说明：** 原清单中的嵌套类（MqConfiguration 等）已不存在——配置类早已重构为顶层文件（`RedisStreamingMqAutoConfiguration` / `RedisStreamingRegistryAutoConfiguration` / `RedisStreamingConfigServiceAutoConfiguration` / `RedisStreamingDiscoveryAutoConfiguration` / `RedisStreamingRateLimitAutoConfiguration`），由 `RedisStreamingAutoConfiguration` 通过 `@Import` 聚合；原 0%~6% 的类现全部 **100% 指令 / 100% 分支**。

**已添加的测试（`RedisStreamingAutoConfigurationLoadingTest`，15 个用例，纯单元零 Redis 依赖）：**
- [x] **Spring Boot 集成测试**（ApplicationContextRunner 加载测试）
  - MQ 自动配置加载测试（默认装配 MqOptions/Admin/DLQ/Producer/ReplayHandler，用户 RedissonClient 优先）
  - Registry 自动配置加载测试（默认 scored 策略 + ClientInvoker；strategy=wrr → WeightedRoundRobinLoadBalancer）
  - ConfigService 自动配置测试（enabled=false → 无 configService bean；启用时用户 mock 优先）
  - RateLimit 自动配置测试（**无 matchIfMissing，默认禁用**，显式 enabled=true 才装配；policies Map 绑定 → NamedRateLimiter 注册表，未知算法回退 sliding）
  - 条件注解（@ConditionalOnProperty）测试（mq/registry/discovery/config 默认启用可独立关闭；ratelimit 默认禁用）
  - 自动配置属性绑定测试（mq worker-threads/default-partition-count/key-prefix/retry-max-attempts/lease-ttl-seconds → MqOptions；broker.type=jdbc + 用户 DataSource → JdbcBrokerFactory）
  - Bean 覆盖测试（用户 NamingService/ServiceDiscovery/ConfigService/MqOptions/RateLimiter 抑制自动 bean；NamingService 单独即可抑制 discovery 的元组条件 @ConditionalOnMissingBean({NamingService, ServiceDiscovery})）
  - micrometer 装配（有 MeterRegistry bean → RateLimit/Mq/Reliability 采集器创建；无 → 全部缺席）
  - 真实 redissonClient 工厂方法（Redisson.create 会**急切连接**非惰性——按可达性假设门控，本地有 Redis 才执行并 shutdown，CI 无 Redis 自动跳过）

---

### 4. io.github.cuihairu.redis.streaming.checkpoint - ✅ 已完成（2026-09-27，实测 100%/100%）
**侦查发现：** 原 21% 数据过期——补测前 checkpoint 模块两个包实测已 100%/100%（主包）与 97%/98%（redis 包），既有 12 个测试类已覆盖创建/提交/恢复/快照序列化/协调器四类场景；残差仅在 `RedisCheckpointCoordinator.restoreFromCheckpoint` 的 sink 派发路径（"已完成 + 非空快照 → 逐条交给 BiConsumer"，单测 lane 从未喂过非空快照）。清单点名的 `CheckpointExample` 实际在 examples 模块（`examples.checkpoint` 包）而非 checkpoint 模块。

**本轮新增测试（85 个测试全绿）：**
- [x] **单元测试**
  - Checkpoint 创建和提交测试（既有 `DefaultCheckpointTest` + 协调器 trigger/ack/timeout 5 套件）
  - Checkpoint 恢复测试（新增 `RedisCheckpointCoordinatorRestoreSinkTest`：sink 逐条派发+计数、两参重载日志 sink 委托、sink 异常返回 -1；补齐 redis 包残差 → **100% 指令 / 100% 分支**）
  - StateSnapshot 序列化测试（既有 `DefaultCheckpointTest` + 集成 lane round-trip）
  - Checkpoint 协调器测试（既有 trigger/ack/timeout/edge/completion-branch/cleanup-race 套件）
- [x] **集成测试**（按仓库「可达性假设门控」口径：本地有 Redis 才执行并 shutdown，无 Redis 自动跳过）
  - 新增 `CheckpointRedisLiveIntegrationTest`（进默认 `test` 任务，门禁内实际执行）：分布式协调端到端（2 任务 ack 完成含快照状态的 checkpoint → 全新 storage/coordinator 实例重启视角经 sink 恢复）+ B-14 不完整 checkpoint 不作为恢复点
  - 新增 `examples.checkpoint.CheckpointExampleTest`（2 用例：main 端到端 + 四个演示段可观测效果断言；示例演示方法改包级可见，仅示例侧改动）
  - 既有 3 个 `@Tag("integration")` 类与 `CheckpointGrandStormTest` 补 `Assumptions.assumeTrue(reachable)` 门控（此前无 Redis 环境下 `integrationTest`/storm 会硬失败）

---

## 优先级 2：覆盖率 20%-50%（重要）

### 5. io.github.cuihairu.redis.streaming.runtime.internal - ✅ 已完成（2026-09-27，实测 100%/99%）
**侦查发现：** 原 39% 数据过期——补测前包实测已 **100% 指令 / 99% 分支**：`InMemoryWindowedStream` 100%/100%（非 0%）、`InMemoryKeyedStream` 100%/100%（非 39%，全部匿名迭代器亦 100%）。清单要求五类场景均有既有测试背书（见下）。残差仅两处，本轮已处理。

**清单五类场景 → 既有背书：**
- [x] 窗口分配和触发测试：`InMemoryWindowedStreamTriggerTest`（FIRE / FIRE_AND_PURGE / PURGE / CONTINUE + 端输入 flush）
- [x] 窗口聚合函数测试（sum, count, avg, min, max）：`InMemoryWindowedStreamTest` 聚合用例 + `NumberAggregationUtilsCoverageTest`（含 BigDecimal/BigInteger 分支）
- [x] KeyedStream 分区测试：`InMemoryKeyedStreamTest`/`EdgeCaseTest`（keyBy 路由、process、reduce/aggregate）
- [x] 窗口水印对齐测试：`WatermarkStateTest` + `InMemoryKeyedStreamTest.eventTimeTimerFiresWhenWatermarkAdvances` + watermark 生成器用例
- [x] 窗口过期清理测试：`InMemoryWindowedStreamTriggerTest` 的 PURGE / FIRE_AND_PURGE 清桶断言（`bucket.elements.clear()` 路径）

**本轮新增（`InMemoryInternalResidualCoverageTest`，2 用例）：**
- [x] Timer 回调抛异常 → 包装为 RuntimeException("Keyed process timer callback failed")、原异常为 cause、`finally` 恢复时间戳（`TimerQueue.fire` catch 臂语义钉住）
- [x] `InMemoryCheckpointCoordinator.restoreFromCheckpoint` 跳过快照中无条目的后注册 store（不清空其状态；checkpoint 包分支残差同型修复）
- 说明：包级分支 99% 为测试侧天花板——唯一漏分支是 `TimerQueue.fire` 枚举 switch 的合成 default 臂，计时器只可能以 PROCESSING_TIME/EVENT_TIME 注册，测试不可达，闭合需改生产代码（超出本轮"只补测试"边界）
- [ ] ~~集成测试：端到端窗口处理 / 窗口状态恢复~~ —— redis 引擎侧已有 `RedisRuntimeWindowedStreamIntegrationTest`（6 用例）与 checkpoint/restore 集成套件，见 runtime/redis 清单

---

### 6. io.github.cuihairu.redis.streaming.source.kafka - ✅ 单测项闭合（2026-09-29 实测 100% 指令/95% 分支）
**关键问题：** ~~Kafka 数据源测试不足~~（过期：36% 为旧数据；2026-09-29 新鲜 `:source:test :source:jacocoTestReport` 实测本包 78%/81% → 补测后 **100% 指令/95% 分支**，`KafkaSource` 唯一类 428 条指令 0 missed）
**未覆盖的关键类：**
- `KafkaSource` - ✅ 100%（原列 36%，8 个方法未覆盖）

**建议添加的测试类型：**
- [x] **单元测试**（本轮新增 `KafkaSourceLifecycleCoverageTest` 10 用例，接既有 `KafkaSourceTest` 12 用例）：三个真实 `KafkaConsumer` 构造器（bootstrapServers / ObjectMapper / Properties 三种入口）——**Kafka 客户端惰性连接**，构造器不拨 broker，故用不可达地址 `127.0.0.1:1` 即可真跑并断言 topic/isRunning/close；构造器 10 条 `requireNonNull` 守卫（Properties/topic/ObjectMapper/valueClass/Consumer/handler）；`autoSubscribe=true` 公开构造器订阅 vs 包私有构造器不订阅；consume 循环的**逐条失败隔离**（handler 抛异常只记日志跳过、后续记录继续投递，且 tombstone(null value) 不进 handler）；`ensureAssigned()` 三态（已分配→不 poll；未分配→poll(ZERO) 后再短 poll 的兜底；poll 抛异常→吞掉仍执行 seek）
- [ ] **集成测试**（需要 Kafka 环境）——**本轮不派**：`docker-compose.test.yml` 未提供 Kafka broker、本机无 Kafka 容器；且 `**/kafka/**` 已从聚合覆盖率门禁 classDirs 中排除（build.gradle `covClassDirs`），故此条纯属"真 broker 端到端"收益，挂起不阻塞 99% 门禁。触发方式：起 Kafka 后以 `@Tag("integration")` + `KAFKA_BOOTSTRAP_SERVERS` 环境变量守卫新增用例
  - Kafka 消息消费测试
  - 分区分配测试
  - Offset 提交测试
  - Consumer 重启恢复测试
  - 反序列化错误处理测试

**唯一残差：** `KafkaSource` L260 `close()` 的 `if (consumer != null)` 假臂——`consumer` 为 final 字段且构造器已 `requireNonNull`，测试不可达（与 config L521、registry.lua L894-896 同型防御性天花板，闭合需改生产代码超范围）

---

### 7. io.github.cuihairu.redis.streaming.cdc - ✅ 单测项完成（2026-09-28，实测包级 99% 指令/96% 分支）
**侦查发现：** 原 44% 与各"0%"条目均数据过期——四组单测在既有测试中已基本存在，实测 `cdc` 包 **99%/96%**、`cdc.impl` 97%/87%、`cdc.mq` 100%/100%。按行覆盖标记：`CDCManager` 100%（CDCManagerTest 24 用例 + CDCManagerLifecycleCoverageTest + CDCManagerResidualCoverageTest）、`ChangeEvent` 100%、`CDCConfigurationBuilder`（含 DefaultCDCConfiguration 内部类）100%、`CDCEventListener` 100%；唯一残差是 `CDCConnectorFactory` L34 的枚举 switch 合成 default 臂——枚举仅 3 常量且全被 case 处理、`switch(null)` 抛 NPE 不会走 default，测试不可达（与 runtime `TimerQueue.fire` 同型天花板，闭合需改生产代码超范围）。

**清单四组单测 → 背书/新增：**
- [x] CDC 管理器生命周期测试：`CDCManagerTest`（24 用例，start/stop/生命周期/Mock 连接器）+ `CDCManagerLifecycleCoverageTest` + `CDCManagerResidualCoverageTest`
- [x] CDC 配置构建器测试：`CDCConfigurationBuilderTest`（10 场景：各连接器专属配置/自定义属性/默认值/工厂方法/名称校验）+ `CDCConfigValidationCoverageTest` + `CDCConfigurationDtoTest` + `CDCConfigurationTest`
- [x] 变更事件序列化/反序列化测试：**本轮新增 `ChangeEventSerializationTest`（5 用例）**——全字段 Jackson 往返（含 Instant 时间戳，jsr310 注册）、7 种 EventType 表驱动往返、无参构造器 null 字段往返、手写 JSON 容忍解析、线上 JSON 形状钉住；注：Lombok `is*()` 谓词会序列化出无 setter 的属性，读取端必须关闭 FAIL_ON_UNKNOWN_PROPERTIES（仓库惯例 `findAndRegisterModules()` 不够）；MQ 桥实际走 `ChangeEventQueueSink.toPayload` 的 payload-map 而非 Jackson（已有 `ChangeEventQueueSinkTest` 背书）
- [x] 连接器工厂测试：`CDCConnectorFactoryTest`（3 类型枚举+字符串+大小写变体+非法名 IAE）+ `CdcFactorySourceCoverageTest`
- [x] 集成测试（CDC 启动停止 / 多连接器并发）：**本轮新增 `CDCManagerLifecycleMultiConnectorIntegrationTest`（3 用例，@Tag("integration")，10 轮 --rerun 全绿）**——真实 H2 JDBC + 真实 Redis（REDIS_URL）：(1) manager start/幂等 stop/重启——基线跳过存量行、运行期捕获、停机窗口行经重启按保留水位补收（CDC resume 语义 + CDC-M6 调度器重建）；(2) 3 连接器并发推模式互不串扰（onEvents 回调/健康/指标断言齐全，调度器独占批次不漏给 pull poll）；(3) 变更事件经 `ChangeEventQueueSink` 桥接真实 Redis MQ topic、真实消费者断言逐行 after-image 送达与分区键互异。守卫：Redis 不可达时 Assumptions 自动 SKIP，触发方式已注明 TESTING.md（H2 腿无外部依赖恒跑）。注：H2 将未加引号列名大写化，after-image 键为大写列名，`generateKey` 的 "id" 小写取键在 H2 下走全值 join 兜底——桥接用例不钉该内部格式

---

### 8. io.github.cuihairu.redis.streaming.source.redis - ✅ 已完成（2026-10-10 收口）
**关键问题：** ~~Redis 数据源测试不足~~（已过期：2026-09-28 实测包级 line 99%/branch 96%，`RedisListSource` 仅剩 1 未覆盖行；2026-10-10 union 报告复核该行）
**未覆盖的关键类：**
- `RedisListSource` - 47% (10 个方法未覆盖)（已过期：实际仅 L263 一条指令未覆盖）
  - **L263 判定为防御天花板（2026-10-10）**：`pollBatch` 定时任务首行 `if (!running) return;`——`stop()` 先翻转 running 再 `task.cancel(false)`，该守卫仅在"tick 已入队执行 vs stop 翻转"的微秒级竞态窗口可达；`scheduleAtFixedRate` 不重入（在途执行期间取消则后续 run 直接抑制），任何确定性测试序列都无法命中，压测循环命中则为非确定性 flaky 覆盖（与门禁摆动教训同型）。同型先例：config L521、registry.lua L894-896、KafkaSource L260。不追。

**建议添加的测试类型：**
- [x] **集成测试**（需要 Redis）（2026-09-28 补齐：`RedisListSourceIntegrationTest` 7 用例，10 轮重复全绿 —— List 数据读取/FIFO 排空、空/不存在列表全 API 读空且零建 key、consume 持续消费、poll 与 pollBatch 跨 tick 批投递、record 类型 JSON 反序列化往返、连接失败降级 null/空不抛。两处注明：① 该类**无 BLPOP/BRPOP**——实现为 LPOP（`remove(0)`）+ 空转退避轮询，consume 循环即其"阻塞"行为，按实际行为覆盖；② Redisson 3.29 `create()` 为急切连接、死端点无法产出客户端对象，连接失败用例改用已 shutdown 客户端（每条命令必失败）走同类 catch 路径）
  - Redis List 数据读取测试
  - List 阻塞弹出（BLPOP/BRPOP）测试
  - 批量读取测试
  - 空列表处理测试
  - Redis 连接失败测试

---

### 9. io.github.cuihairu.redis.streaming.sink.kafka - ✅ 单测项闭合（2026-10-05 实测包级 100% 指令/91% 分支，单测口径）
**关键问题：** ~~Kafka Sink 测试不足~~（过期：52% 为旧数据；2026-10-05 新鲜 `:sink:test :sink:jacocoTestReport` 实测 `sink.kafka` **100%/91%**，非 0%/52%——既有 `KafkaSinkTest` 系列已饱和）

**建议添加的测试类型：**
- [ ] **集成测试**（需要 Kafka 环境）——**不派**：`docker-compose.test.yml` 未提供 Kafka broker，本机无 Kafka；且 `**/kafka/**` 已从聚合覆盖率门禁 classDirs 中排除，此条纯属"真 broker 端到端"收益。触发方式：起 Kafka 后以 `@Tag("integration")` + `KAFKA_BOOTSTRAP_SERVERS` 环境变量守卫新增用例（同第 6 条 source.kafka 口径）
  - Kafka 消息发送测试
  - 分区路由测试
  - 序列化测试
  - 错误重试测试
  - 事务性发送测试

---

## 优先级 3：覆盖率 50%-70%（中等）

### 10. io.github.cuihairu.redis.streaming.mq.impl - ✅ 集成+单测完成（2026-09-29；单测 95% 见下方既有注记，集成五条已背书）
**关键问题：** ~~MQ 核心实现测试覆盖不足~~（过期：2026-09-29 新鲜 `:mq:test :mq:jacocoTestReport` 实测包级 95% 指令/91% 分支；`RedisMessageProducer` **100%/100%**（0 missed——`RedisMessageProducerTest` + `RedisMessageProducerSprintCoverageTest` + `RedisMessageProducerAsyncPathsTest` 覆盖，非 0%）、`BrokerBackedProducer` 100%/100%、`StreamEntryCodec` 99%/96%、`DlqConsumerAdapter` 98%/93%、`PayloadLifecycleManager` 99%/98%；唯一 <95% 类为 `RedisMessageConsumer` 91%/87%，残差 31 指令/8 分支为调度/重投防御臂，与上方案 C 专项线相邻不追）

**建议添加的测试类型：**
- [x] **集成测试**（2026-09-29 收口：五条全部有真实 Redis 集成套件背书；本轮新增 `ProduceConsumeEndToEndIntegrationTest`（2 用例，@Tag("integration")，10 轮 --rerun 全绿）补齐"端到端"条）
  - 消息发送和接收端到端测试：**本轮新增**——(a) 4 分区 12 消息逐字段保真（payload/topic/key/用户 header 存活；transport 注入的 x-payload-*/partitionId 内部头不拦截），(b) 独立消费组扇出（两组各收全量、无串扰）；另实测注记：消费侧 Message.id 为 Redis stream entry id 而非生产侧生成 id，消息关联须用 key
  - 死信队列转发测试：`RetryAndDlqIntegrationTest` + `MissingPayloadDlqIntegrationTest` + `DlqCodecCompatibilityIntegrationTest`
  - 消费组管理测试：`CommitFrontierMultiGroupIntegrationTest` + `LeaseOwnershipIntegrationTest` + `PendingClaimIntegrationTest` + `MaxLeasedPartitionsIntegrationTest`
  - 消息确认和重试测试：`AckNonePolicyIntegrationTest` + `AckDeletePolicyIntegrationTest` + `AckAllGroupsPolicyIntegrationTest` + `RetryMover*IntegrationTest`
  - Stream 数据结构序列化测试：`DlqCodecCompatibilityIntegrationTest` + `PayloadLifecycleIntegrationTest`
  - **遗留发现（不阻塞本条，待 mq 专项轮处理）**：fresh 消费组下分区 worker 间歇性推迟该分区首个 backlog 条目投递（实测 ~40% 轮次出现 `[1..7,0]` 或首条超 30s 窗口；条目最终仍送达——at-least-once 不丢，但位置不保序）。顺序敏感负载在消费端读路径修复前不可依赖跨条目顺序；本轮 E2E 用例已按此边界收窄断言并在 javadoc 注明
- [x] **单元测试**（已饱和：2026-09-28 实测 `:mq:test` 包级 mq.impl 95%、mq.dlq 95%、mq.config 100%，全模块无 <90% 类 —— 本节与上方未覆盖清单为过期数据；"StreamEntry 编解码/消息生命周期" 单测已由既有套件覆盖，无需新增）

---

### 11. io.github.cuihairu.redis.streaming.mq.dlq - ✅ 单测+集成收口（2026-09-29 实测包级 95% 指令/91% 分支）
**关键问题：** ~~死信队列功能测试不足~~（过期：2026-09-29 新鲜 `:mq:test :mq:jacocoTestReport` 实测——`RedisDeadLetterAdmin` **100%/95%**（非 0%）、`RedisDeadLetterService` **100%/96%**（非 71%）、`RedisDeadLetterConsumer` 88%/82%（非 39%）、`DeadLetterCodec` 100%/95%、`DlqKeys` 100%；既有单测 `RedisDeadLetter*Test`/`*SprintCoverageTest`/`*ResidualCoverageTest` 系列已饱和，唯一 <90% 类 RedisDeadLetterConsumer 残差 9 分支为循环/关闭防御臂）

**建议添加的测试类型：**
- [x] **集成测试**（2026-09-29 收口：4/5 条既有真实 Redis 套件背书，1 条按当前实现不适用）
  - 死信队列写入测试：`RetryAndDlqIntegrationTest`（重试耗尽进 DLQ）+ `MissingPayloadDlqIntegrationTest`（payload 丢失进 DLQ）
  - 死信消息消费测试：`DlqConsumerLoopIntegrationTest` + `DeadLetterStackIntegrationTest` + `DlqPendingReclaimIntegrationTest`
  - 死信队列重试测试：`DeadLetterConsumerRetryFailIntegrationTest` + `DlqReplayAndAdminIntegrationTest`（replay 重放与 payload-hash TTL 刷新）
  - 死信队列管理操作测试：`DlqAdminOpsIntegrationTest` + `DlqReplayAndAdminIntegrationTest`（list/replay/replayAll/delete/clear）
  - 过期死信清理测试：**按当前实现不适用**——mq.dlq 全包不存在 TTL/过期清理生产 API（仅 delete/clear/replay；replay 时 payload-hash 的 TTL 刷新为内部细节，已由 `DlqReplayAndAdminIntegrationTest` 覆盖）。若需该功能应作为生产特性单列开发，不在测试轮冒充

---

### 12. io.github.cuihairu.redis.streaming.config.impl - ✅ 单测+集成收口（2026-09-29；单测 99%/93% 见上方注记，集成条已背书）
**关键问题：** 配置中心实现测试不足（已过期：2026-09-28 实测包级 line 99%/branch 93%，config 接口包 100%/100%；`ConfigService`/`RedisConfigCenter`/`RedisConfigService` 三类 0 未覆盖行，唯一残差 RedisConfigService L521 catch-ignore 臂为防御性天花板——`handleConfigChangeEvent` 内部已捕获全部 Exception，外层臂仅 Error 可达。下方集成项仍需 Redis 环境，挂起不派）
**未覆盖的关键类：**
- `RedisConfigCenter` - 0% (14 个方法未覆盖)
- `RedisConfigService` - 58% (3 个方法未覆盖)

**建议添加的测试类型：**
- [x] **集成测试**（2026-09-29 收口：4/5 条既有真实 Redis 套件背书，1 条按当前实现不适用）
  - 配置发布和订阅测试：`ConfigServiceIntegrationTest`（publish→getConfig 往返、`RedisConfigCenter` 生命周期/元数据/无描述发布/removeConfig）
  - 配置版本管理测试：`ConfigServiceIntegrationTest`（ConfigHistory 含 version 断言）+ `ConfigServiceListenerHistoryIntegrationTest`
  - 配置变更通知测试：`ConfigChangeSingleDeliveryIntegrationTest`（单次投递）+ `ConfigResyncIntegrationTest`（权威态 resync）+ `RedisConfigServiceListenerTest` + `testRedisConfigCenterListener`
  - 配置历史查询测试：`ConfigServiceListenerHistoryIntegrationTest` + `ConfigHistorySizeZeroIntegrationTest`
  - 配置权限测试：**按当前实现不适用**——config 模块生产代码无任何 permission/auth 特性（接口面仅 publish/get/remove/listener/history），条目描述的功能不存在；如需应作为生产特性单列，不在测试轮冒充

---

### 13. io.github.cuihairu.redis.streaming.registry.impl - ✅ 数据过期（2026-09-29 实测 registry.impl 99% 指令，集成 76 用例全绿）
**关键问题：** ~~服务注册实现测试不足~~（过期：44%/48%/58% 为旧数据；2026-09-29 新鲜 `:registry:test :registry:jacocoTestReport` 实测——`RedisNamingService` **100%**、`RedisServiceProvider` **98%**、`RedisServiceConsumer` **98%**、`InstanceEntryCodec` 100%，包级 99% 指令；`registry.lua` 包 98% 指令/81% 分支，残差 RegistryLuaScriptExecutor L894-896 catch 为防御性天花板——try 体内仅 final String 的 null/isEmpty 判断与 StringBuilder 操作，任何入参不可抛 Exception，不追）

**建议添加的测试类型：**
- [x] **集成测试**（2026-09-29 `:registry:integrationTest --rerun-tasks` 实跑真实 Redis：76 用例 0 失败 0 跳过；5 项按既有套件对照，全部落地）
  - 服务注册和发现测试：`RedisNamingServiceLifecycleIntegrationTest`（register→心跳→discover→deregister 全生命周期）+ `RegistryIntegrationTest`（单实例/多实例/重复注册覆盖）+ `RegistryBatchOpsIntegrationTest`（批量 register/update/unregister）
  - 服务健康检查测试：`ConsumerHealthEventIntegrationTest`（健康失败事件到达监听器、退订停检）+ `ProviderCleanupHealthIntegrationTest`（过期清理回调 + HealthCheckManager 驱动）+ `ConsumerCacheHealthIntegrationTest`（缓存刷新/活查询/健康上报）+ `RegistryConsumerCoverageIntegrationTest`（心跳过期 → discoverHealthy 剔除、缺心跳分 → 全空）+ `RegistryAdminServiceCoverageIntegrationTest`（admin 视角实例/指标/健康）
  - 服务元数据管理测试：`RegistryIntegrationTest.testMetadataManagement`（3 键 metadata 注册 → discover 往返逐键断言）
  - 服务实例过滤测试：`RegistryConsumerCoverageIntegrationTest.discoverVariantsAndHeartbeatValidation`（`discoverByMetadata` region=cn/us 各 1、`discoverByFilters`、`discoverHealthyByFilters`、`discoverHealthyByMetadata` 四变体过滤）+ `RedisNamingServiceChooseIntegrationTest.chooseHealthyInstanceWorksThroughLoadBalancers`（仅健康实例进入选择）+ `ClientSelectorIntegrationTest`（fallback + scored LB 过滤选择）
  - 命名服务解析测试：`RedisNamingServiceChooseIntegrationTest`（choose 经 LoadBalancer 解析 + subscribe/unsubscribe 往返）+ `RedisNamingServiceLifecycleIntegrationTest.serviceConsumerDirectApi` + `LoadBalancerIntegrationTest`（scored LB 按 cpu/latency 选低者）+ `DiscoveredInstancesCacheIntegrationTest`（缓存实例跨服务存活/注销逐出）

---

### 14. io.github.cuihairu.redis.streaming.mq.config - ✅ 数据过期（2026-09-29 实测 100% 指令/100% 分支，0 missed/26 分支全绿）
**关键问题：** ~~MQ 配置构建器分支覆盖不足~~（过期：62%/Builder 49% 为旧数据；2026-09-29 新鲜 `:mq:test :mq:jacocoTestReport` 实测 MqOptions 100%、MqOptions.Builder 100%/100%，既有 `MqOptionsTest`（93 用例全绿 0 跳过）已穷尽全部 33 个 setter 与全部钳制/忽略分支）

**建议添加的测试类型：**
- [x] **单元测试**（既有 `MqOptionsTest` 已覆盖，按类对照）
  - 配置参数校验测试：字符串 setter 的 null/blank/empty 逐分支用例（含 `dlqConsumerSuffix` 允许空串的特殊分支）
  - 默认值测试：`testDefaultValues` 断言全部 33 个字段默认值
  - Builder 模式各种组合测试：`testBuilderWithMultipleOptions`/`testBuilderChaining`/`testBuilderReturnsNewInstanceEachTime`
  - 必填参数缺失测试：Builder 无必填参数——空 builder 直接 build 即合法（`testDefaultValues` 即该场景）
  - 参数范围校验测试：每个数值 setter 的 0/负数钳制用例（min=1 / min=0 两档钳制语义均已钉住）

---

## 优先级 4：覆盖率 65%-70%（需要小幅提升）

### 15. io.github.cuihairu.redis.streaming.table.impl - ✅ 完成（2026-09-29 实测单测包级 99% 指令/99% 分支 + 集成条 5/5 落地）
**关键问题：** ~~KTable 实现测试不足~~（过期：2026-09-29 新鲜 `:table:test :table:jacocoTestReport` 实测——`RedisKGroupedTable` **100%/100%**（0 missed，`RedisKGroupedTableInferenceCoverageTest` 等覆盖，非 0%）、`RedisKTable` 100%/100%、`InMemoryKGroupedTable` 100%/100%、`InMemoryKTable` 97%/90%；包残差 6 指令/1 分支，单测远超 70% 目标）

**建议添加的测试类型：**
- [x] **集成测试**（2026-09-29 实跑真实 Redis：新增 `RedisKTableGroupingUpdateIntegrationTest` 2 用例补齐分组聚合/更新传播缺口；模块全套 172 用例 0 失败 0 跳过）
  - 分组表聚合测试：`RedisKTableGroupingUpdateIntegrationTest.groupedAggregationsMaterializeIntoRedisBackedTables`（groupBy→`count`/`reduce`/`aggregate` 物化为 Redis 表：EU/US 计数与求和逐键断言、null 组键条目从物化中剔除、结果表命名 `<source>:groupBy:<op>:<millis>` 归源表命名空间、结果表自身可再写再读）
  - 表 Join 测试：`RedisKTableOperationsIntegrationTest.storageViewsAndJoins`（join 内连接丢弃未匹配键、leftJoin 对缺失右侧以 null 进 joiner、跨 Redis/内存表实现互 join）
  - 表更新传播测试：`RedisKTableGroupingUpdateIntegrationTest.updatesPropagateToQueriesGroupedViewsAndSiblingInstances`（同 key 覆写→get/getState/size 即时反映且不增长；重物化 count/aggregate 读到新值 10+2——快照式视图无陈旧无幽灵条目）
  - 表状态持久化测试：同测试的 sibling 实例段（同表名二次构造 `RedisKTable` 共享 Redis Hash——先写后建读到全量状态、sibling 写回原实例可见）+ `RedisKTableOperationsIntegrationTest`（put→get→delete 全落 Redis）
  - 表查询测试：`RedisKTableOperationsIntegrationTest`（get/getState/size/getTableName/toString、mapValues 单参与双参视图物化、filter 视图存活项、toStream 快照入内存引擎）

---

# Runtime（企业级能力）待办清单

> 目标：将 `runtime` 从“最小可用单机运行时”逐步提升到可上线、可运维、可扩展的企业级运行时。
> 范围：`runtime`（in-memory + Redis runtime）以及与 `mq/state/checkpoint/reliability/metrics` 的集成。
> 状态：P0-P3 已完成（2026-01-01）；P4 为下一阶段（分布式/高可用/控制面）。

## P0：可上线稳定性（必须）
- [x] Redis runtime `KeyedStream.map/reduce/sum`（已实现：2026-01-01）
- [x] 失败策略可配置（异常时：RETRY/DEAD_LETTER/FAIL），并记录结构化错误日志（topic/group/id/key）
- [x] Poison message 处理：支持直接进入 DLQ（配置 `processingErrorResult=DEAD_LETTER`），并附带错误上下文 headers
- [x] 作业生命周期：`executeAsync`/`cancel` 幂等、可重试、启动失败自动清理，避免资源泄漏
- [x] 作业运维控制（基础）：`RedisJobClient.pause()/resume()/inFlight()`（已实现：2026-01-01）
- [x] 单元/集成测试基线：覆盖失败/DLQ、生命周期、key 语义等核心路径
- [x] Consumer 命名稳定化：`jobName-jobInstanceId-{n}`（减少重启后 consumer group 垃圾）
- [x] Redis runtime metrics 基础埋点（job/pipeline/handle success/error/latency）+ Spring Boot Starter Micrometer 安装

## P1：一致性与容错（企业级核心）
- [x] 状态 key 稳定化：支持显式 `sourceId`（`fromMqTopicWithId(...)`），并移除 operator/state 内部 UUID
- [x] keyed state 按 partition 隔离（基于 MQ `partitionId` header）
- [x] 端到端 Checkpoint：source offset + state + sink 协调，支持恢复（至少 at-least-once 可恢复）（已实现：2026-01-01）
  - [x] source offset 恢复（基础）：consumer group 缺失时，从 MQ commit frontier（acked id）重建 group 起点，避免误删 group 后全量重放
  - [x] 周期性 checkpoint（实验）：`checkpointInterval` 将 offsets+keyed state 快照保存到 Redis（可配置 `checkpointKeyPrefix`/`checkpointsToKeep`）
  - [x] stop-the-world：checkpoint 时 pause consumer 并等待 in-flight drain（`checkpointDrainTimeout`）
  - [x] restore from latest checkpoint（实验）：`restoreFromLatestCheckpoint=true` 启动前恢复 offsets+state
  - [x] sink 去重（实验）：`sinkDeduplicationEnabled=true` 在重试/回放时避免重复调用 sink（基于 `x-original-message-id`）
  - [x] 手动触发 checkpoint（实验）：`RedisJobClient.triggerCheckpointNow()`
  - [x] checkpoint 协调 sink（实验）：`CheckpointAwareSink` + `deferAckUntilCheckpoint=true`（checkpoint complete 后 commit sink 并 ACK offsets）
  - [x] sink restore hook：启动恢复时调用 `CheckpointAwareSink.onCheckpointRestore(checkpointId)`（已实现：2026-01-01）
- [x] restore 策略：`deferAckUntilCheckpoint=true` 时仅从 `sinkCommitted=true` 的 checkpoint 恢复（已实现：2026-01-01）
  - [x] 配置安全提示：defer-ack + `mq.claimIdleMs`/checkpoint interval/drain 不匹配时 WARN（已实现：2026-01-01）
  - [x] sinkCommitted marker：checkpoint 提交后写入 marker key（避免与 checkpoint key 同前缀的辅助 key 被误读，并为后续原子化预留）（已实现：2026-01-01）
- [x] Exactly-once 路线设计（可选）：事务性 sink / 幂等 sink / 两阶段提交（明确边界与限制）（已产出：`docs/exactly-once.md`，2026-01-01）
  - [x] v1（推荐）：Exactly-once by Idempotency（定义幂等键规范 + 给出示例 sink）（已实现：2026-01-01）
    - [x] `IdempotentRecord<T>`（稳定幂等键载体）（已实现：2026-01-01）
    - [x] Redis 幂等 sink 示例：`RedisIdempotentListSink<T>`（Lua 原子去重 + RPUSH）（已实现：2026-01-01）
    - [x] integration test：无 runtime dedup 时，幂等 sink 仍可防止重试重复写入（已实现：2026-01-01）
  - [x] v2：Two-Phase Commit Sink（2PC）API 设计与实现（类似 Flink 两阶段提交）（✅ 2026-10-10 状态对齐：下列 core/runtime/故障注入/集成四个子项全部落地，见各自"已实现"注记）
    - [x] core：新增 `TwoPhaseCommitSink` API（Txn 可序列化进 checkpoint）（已实现：2026-09-28，`core/.../api/stream/TwoPhaseCommitSink.java`：beginTxn/invoke(value,txn)/preCommit/commit，默认 abort 委托 recoverAndAbort，Txn extends Serializable，单参 invoke 桥接为 fail-fast；单测 `TwoPhaseCommitSinkTest`）
    - [x] runtime：checkpoint 流程加入 `preCommit -> storeCheckpoint(txn) -> commit -> mark sinkCommitted -> ack`（已实现：2026-09-28，`TwoPhaseCommitCoordinator`（懒开启事务/prepare/commit/abort，handle=Java 序列化+Base64）+ `RedisPipelineRunner.prepareTwoPhaseCommits/commitTwoPhaseCommits/abortTwoPhaseCommits` + manager overload 快照键 `runtime:txns`（"runnerIndex:sinkIndex"->handle，空 map 不写键）+ env 暂停窗口内 preCommit->store(txn)->commit->markSinkCommitted->ack）
    - [x] runtime：恢复流程加入 txn 补偿（`recoverAndCommit/recoverAndAbort`）（已实现：2026-09-28，restore 后按 runner 索引回放：marker 存在→句柄已过期跳过；marker 缺失（store 后 commit 前崩溃）→对存储句柄逐个 recoverAndCommit（幂等）；补偿失败仅记日志不阻断启动）
    - [x] unit 故障注入：checkpoint 已写入但 commit 未执行 / commit 抛错 / store 失败（`TwoPhaseCommitFaultInjectionTest` 7 例 + coordinator/runner 单测 13+6 例；Redis mock 注入）
    - [x] integration tests：Redis 真实环境下的 2PC 端到端故障注入与恢复验证（条目过期收口 2026-10-08：`TwoPhaseCommitOutboxEndToEndIntegrationTest` 4 用例（happy path 配对投递、store 后 commit 前崩溃→restore 重放句柄 recoverAndCommit、preCommit 失败 abort+deferred-ack 重投下一 epoch、abort checkpoint 不误认领已丢弃记录）+ `TwoPhaseCommitRecoveryCompensationTest` 4 用例（未标 commit 的 checkpoint 句柄重放 / sinkCommitted 不重放 / 无句柄不动 sink / in-doubt epoch 采纳），提交 31d1579（2026-09-28）与 5817958（2026-10-05）晚于本条「本轮不派」注记）
  - [x] v2.5：Outbox/WAL（Redis 内 outbox + 异步投递器），作为跨系统 exactly-once 的折中方案（已实现：2026-09-28，`runtime/redis/sink`：`RedisOutboxSink<T>` 实现 `TwoPhaseCommitSink<IdempotentRecord<T>,OutboxTxn>`——invoke 内存缓冲→preCommit 逐条 XADD 入 outbox 流（epoch/seq/id/payload 字段，seq 为 epoch 内序）→commit/recoverAndCommit 单 HSET 把 `<outboxKey>:epochs` 状态翻成 COMMITTED（幂等，整 epoch 原子可见）→abort/recoverAndAbort 写 ABORTED；嵌套 record `OutboxTxn(epoch)` 可 Java 序列化进 checkpoint；env 侧经 `instanceof TwoPhaseCommitSink` 自动接入 v2 协调器，零环境改动。`RedisOutboxDispatcher<T>` 异步投递器（消费者组）：COMMITTED 按序投递+ack+XDEL、ABORTED 丢弃、无 marker 头部阻塞等 runtime 补偿、投递失败留 pending 按 retryIdle 重试、超 maxAttempts 转 `<outboxKey>:dlq`（附 failedAttempts/dlqReason/dlqTime）、group 创建容忍 BUSYGROUP。单测与故障注入 28 例（sink 11/dispatcher 11/fault 6）×10 轮全绿——store 后 commit 前崩溃→句柄恢复投递、commit 后 markSinkCommitted 前崩溃→幂等重放不重投、abort 丢弃不投递、preCommit 半程失败→残迹随 abort 丢弃、晚提交 epoch 头部阻塞、投递后 ack 前崩溃→pending 重投。语义注明：投递为 at-least-once，端到端 exactly-once 需目标端按稳定 record id 幂等（方案 C 折中：最终一致+可重试后处理））
  - [x] v3：Redis-only exactly-once（Lua 原子写 sink + 更新 offsets + XACK；Redis Cluster 需同 hash slot）（✅ 2026-10-10 状态对齐：两个子项 sink 均已实现并有测试背书；同 hash slot 约束仍为文档化限制——atomic Lua 原子性要求 sink 流与 commit frontier key 同 slot，跨 slot 场景应改走 v2 outbox 路线）
    - [x] Redis-only commit-on-checkpoint sink：`RedisCheckpointedIdempotentListSink<T>`（checkpoint complete 后 flush side effects）（已实现：2026-01-01）
    - [x] Redis-only atomic commit（单 Lua）：`RedisAtomicCheckpointListSink` + `RedisExactlyOnceRecord`（写 sink + XACK + commit frontier 原子提交）（已实现：2026-01-01）
- [x] State 演进：state schema/versioning、兼容升级策略、回滚策略（已实现：2026-01-01）
  - [x] `StateDescriptor.schemaVersion`（默认 1）
  - [x] Redis runtime state schema 元数据与校验（`stateSchemaEvolutionEnabled` + `stateSchemaMismatchPolicy=FAIL/CLEAR/IGNORE`）
  - [x] Checkpoint snapshot/restore state schema 元数据（避免恢复后 schema 丢失）
  - [x] 状态迁移工具/策略（已给出策略并决策"不建专用迁移工具"：2026-10-10）——框架已提供两条迁移路径所需的原语：①**离线重放**（推荐）：停消费→`StateDescriptor.schemaVersion`+1→`stateSchemaMismatchPolicy=CLEAR`（或 IGNORE 兼容读）→从 MQ topic 历史重放（consumer group 首建固定 `0-0`，topic 有 retention）或走 CDC snapshot 存量回填（`snapshot.enabled=true` 且 mode≠never 时存量行走 INSERT 事件）重建 keyed state；keyed state 键含 `{stateKeyPrefix}:{job}:cg:{group}:topic:{topic}:p:{pid}:...`，换 stateKeyPrefix 即天然新命名空间，旧数据不动。②**在线滚动**：按分区/实例滚动重启，`stateSchemaMismatchPolicy=CLEAR` 逐桶丢弃不兼容旧值（或 IGNORE 强读），偏斜窗口=一次重启；schema 元数据随 checkpoint snapshot/restore 往返，滚动中不会丢版本信息。**不建**字段级映射/转换框架（业务自定义），理由：策略开关 + checkpoint schema 元数据 + MQ 历史重放三者已能表达"重建"这一唯一通用做法，字段映射是各业务自己的语义，框架无法通用化
- [x] State 治理：TTL/清理策略、热点 key 保护、state size 上报与告警（已实现：2026-10-01）
  - [x] State TTL：`RedisRuntimeConfig.stateTtl(...)`（对 keyed state Redis hash key 写入后 best-effort expire）
  - [x] State size 上报（抽样）：`RedisRuntimeConfig.stateSizeReportEveryNStateWrites(n)`（每 N 次 state 写入上报一次 hash 字段数）
  - [x] 热点 key 保护（基础）：`RedisRuntimeConfig.keyedStateShardCount(n)`（按 key hash 分片，降低单个 hash 过热/过大风险，默认 1 不启用）
  - [x] 热点 key 告警（阈值+限频日志+指标）：`keyedStateHotKeyFieldsWarnThreshold`/`keyedStateHotKeyWarnInterval`
  - [x] 热点 key 处置（阈值/采样/限流/降级/DLQ）：`RedisRuntimeConfig.keyedStateHotKeyPolicy(LOG_ONLY/THROTTLE/FAIL_FAST)` + `keyedStateHotKeyThrottleMaxMs`——采样命中阈值后按 `keyedStateHotKeyWarnInterval` 开启逐键处置窗口，窗口内每笔写入限流（THROTTLE 有界 sleep 背压）或快速失败（FAIL_FAST 抛 `KeyedStateHotKeyException`，走 MQ 重试/退避并最终入 DLQ），默认 LOG_ONLY 仅保留告警（已实现：2026-10-01）

## P2：可扩展与性能
- [x] 并行度模型（基础）：`pipelineParallelism(n)` + 分区固定分配（`partitionId % n`）以实现多子任务并行（已实现：2026-01-01）
- [x] 背压（基础）：`MqOptions.maxInFlight(n)`（consumer 级全局并发上限，信号量限流）（已实现：2026-01-01）
- [x] 并行度/背压追踪（基础）：in-flight、permit wait、eligible/leased partitions（MQ Micrometer 指标）（已实现：2026-01-01）
- [x] 分区 lease 防超配（基础）：`MqOptions.maxLeasedPartitionsPerConsumer(n)`（默认=workerThreads，避免拿到 lease 但线程不足导致分区饿死）（已实现：2026-01-01）
- [x] 资源模型：线程池/队列容量/批大小可配；安全默认值；压测基准与调优指南（已实现：2026-01-01）
  - [x] `timerThreads`/`checkpointThreads` 资源隔离（避免每 runner 创建线程池）
  - [x] `eventTimeTimerMaxSize` 上限保护（防止 event-time timer 队列无界增长）
- [x] Window 运行时支持（Redis runtime）：事件时间/水位线/触发器/迟到数据处理（明确语义）（已实现：2026-01-01）
  - [x] watermark：`watermarkOutOfOrderness`（允许乱序；watermark=maxEventTime-outOfOrderness）
  - [x] late：`windowAllowedLateness`（final fire 延后到 windowEnd+lateness）
  - [x] per-record 开销上限：`windowMaxFiresPerRecord`

## P3：可观测与运维
- [x] Metrics 全链路：吞吐、延迟、pending、重试、DLQ、state 读写、定时器队列等（已实现：2026-01-01）
  - [x] runtime：`redis_streaming_runtime_*`（checkpoint/state/window/watermark/timer/handler）
  - [x] mq/reliability/retention：Micrometer collector + Spring Boot auto-install
- [x] Trace/日志：可关联 messageId、jobName、operatorId；支持采样（已实现：2026-01-01）
  - [x] `mdcEnabled` + `mdcSampleRate`（MDC keys：`rs.job/rs.topic/rs.group/rs.consumer/rs.id/rs.key/rs.partition`）
- [x] 运行时诊断：健康检查、指标导出、运行时配置 dump、死锁/卡顿定位（已实现：2026-01-01）
  - [x] `RedisJobClient.diagnostics()`（best-effort runtime snapshot）
  - [x] Spring Boot Actuator health + `/actuator/prometheus` 指标导出（starter）
- [x] 多环境部署：Docker/K8s 参考部署，滚动升级与回滚建议（已实现：2026-01-01）

## P4：分布式与高可用（下一阶段）
> 方向决策（2026-10-10 拍板）：P4 内先做**动态伸缩**（并行度变更/分区再均衡/checkpoint 向前兼容），再做**控制面**（job submit/upgrade/rollback API + 权限审计）；§B 双执行引擎统一押后。
- [x] 多节点协调：leader election + fencing token（防止 split-brain / 双写）：`RedisRuntimeConfig.leaderElectionEnabled`（默认 false 行为不变）+ `leaderLeaseTtl`/`leaderRenewInterval`——`RedisLeaderElector` Redis 租约选举（SET NX PX + Lua CAS 续租/释放），仅 leader 跑周期 checkpoint 调度；fencing token 按 leadership epoch INCR，checkpoint meta 携带 token，restore 按 max token 过滤拒绝 stale leader 快照（已实现：2026-10-01）
- [x] 作业 HA：节点宕机自动接管（checkpoint/offset/state 一致性保证）（已实现：2026-10-01）
  - 前：接管只有选举语义（lease TTL 过期后 follower 抢到租约），但 `RedisRuntimeCheckpointManager.nextCheckpointId` 是构造时 `initNextId()` 的一次性快照——follower 运行期间死 leader 持续写入，接管后本地计数仍从陈旧值继续分配，**覆盖死 leader 最后写入的 checkpoint**（offset/状态恢复可能读到回退的历史）
  - 后：接管分支（`startLeaderCoordination` 的 acquire 成功路径）先执行 `refreshCheckpointIdFromStorage()` 把计数对齐 storage max+1（只进不退；storage 故障保持本地值，下次接管再对齐），再分配 id；fencing token 按 leadership epoch INCR 刷新 + restore 按 max token 过滤 stale 快照 + CAS 释放防死实例回收新租约——故障注入集成测试（kill -9 语义：反射关停 renew/checkpoint 调度器且不释放 lease，模拟进程直接被杀）验证接管后新 leader checkpoint id 严格大于死 leader 末值、死 leader checkpoint 全部完好（摘除修复该用例必挂：`expected inst-A but was inst-B`），及旧实例事后优雅 cancel 不夺回租约；3 个单测覆盖计数抬升/只进不退/storage 故障三臂；`leaderElectionEnabled=false` 默认路径零改动
- [x] 动态伸缩：并行度变更、分区再均衡、checkpoint 向前兼容（已实现：2026-10-10）
  - [x] 分区再均衡（MQ 层）：`ReassignableMessageConsumer.updatePartitionAssignment(topic, modulo, remainder)`——运行期重钉存活 consumer 的指派；移出指派的分区即时停 worker 并以 owner 身份释放租约（同组新属主无需等 TTL 即可接管），新纳入的分区由周期再均衡取回；`Subscription.partitionModulo/Remainder` 转 volatile（rebalance/renew 调度线程读、运行期写）
  - [x] 并行度变更（runtime 层）：`RedisJobClient.scaleParallelism(n)`——对每条管道增/删子任务到 n 个并把存活 consumer 重钉到 `partitionId % n == subtaskIndex`；缩容先重钉幸存者再 stop+close 被移除 consumer（runner 不 close：sink 由同管道 runner 共享，cancel 时统一关闭）；扩容先重钉再补建子任务（runner+consumer，命名 seq 单调不复用）；与检查点流互斥（复用 `checkpointing` CAS，有界等待在途检查点）；consumers/runners 转 copy-on-write（cancel 无锁遍历安全）；`diagnostics().liveParallelism` 反映实时并行度
  - [x] checkpoint 向前兼容（验证）：keyed state 与 checkpoint 本就按 `(job,topic,group,partition,…)` 键控、不含并行度/子任务维度——集成测试钉死两形态：并行度 2 写检查点、并行度 3 恢复后计数延续（非全新起点）；1→3→2 在途伸缩三轮全量计数精确（不丢不重）
- [x] 控制面：job submit/upgrade/rollback API、权限控制与审计（已实现：2026-10-10，v1.0/v1.1/v1.2 三期全清）
  - [x] v1.0 设计 + 存储与 API（已实现：2026-10-10）：`docs/Control-Plane-Design.md`（声明式 spec + 期望状态对账方案 A，K8s controller 最小移植，非目标/分阶段明确）；`runtime/redis/control` 新增 `JobSpec/JobState/JobStatus/AuditEntry/JobControlOp/ControlPlaneAuthorizer/ControlPlaneAccessDeniedException/JobControlPlane/RedisJobControlPlane`——Redis 布局 `streaming:runtime:control:{jobs,versions,history:<job>,status:<job>,audit}`；submit（HSETNX 去重 + 版本置 1 + PENDING_DEPLOY）/upgrade（Lua 单步 CAS：读版本→比对→写 spec+升版本+RPUSH 旧版入 history+LTRIM 封顶，原子，并发写拒绝）/rollback（取 history 末项内容+版本递增，连续回滚逐级回退）/stop/resume（期望状态位）/reportStatus（FAILED 才入审计）/tailAudit（XREVRANGE 倒序）；授权钩子（拒绝先审计后抛）；审计 best-effort 不阻断操作。单测 26 例（mock Redis，含 mutator 原地改污染 oldJson 快照的修复钉子、审计写失败被吞、CAS null/冲突、坏 JSON、审计流坏行跳过）+ 真实 Redis 集成 6 例（往返/外部写者 CAS 冲突/逐级回滚/状态迁移/审计内容与倒序/history 封顶），`:runtime:test` + `:runtime:integrationTest` 全绿；全量门禁 `clean test jacocoRootCoverageVerification` 绿（新包 97% 指令，残余 4 行为 SHA-256/Jackson 不可达防御臂）。已注记教训：`any(Object[].class)` 原数组匹配在 Mockito 4.6.1 下不命中 varargs，须逐位 any()（仓库既有同型 stub 为假匹配装饰）
  - [x] v1.1：JobAgent 对账（认领/部署/升级/回滚执行/失败报告）+ 集成测试（已实现：2026-10-10）：`JobAgent` 执行侧对账器（周期 poll `list/status`，期望状态驱动，控制面从不直接触碰运行中作业）：spec 删除→cancel+遗忘；DESIRED_STOPPED→cancel+确认；非本地+PENDING_DEPLOY/FAILED→认领（`SET NX EX` 于 `claim:<job>`，TTL=max(3×poll,10s)，串行化并发 agent）→launch→报 RUNNING，失败→FAILED+释放认领（下轮重试）；非本地+RUNNING→他实例所有，不自动接管（需显式 resume 重新激活）；hash 漂移→parallelism-only 走 `scaleParallelism` 快速路径，其余全量升级（best-effort checkpoint→cancel→重 launch，checkpoint 失败不阻断升级，升级失败报 FAILED 并遗忘待下轮重试）；`claimPrefix=null` 关闭认领（单实例/测试）。配套 `JobPipelineFactory`（管道图是 lambda 无法持久化，spec 按名引用工厂，agent 从本地注册表解析、config map 逐字透传由工厂自行解释）+ `JobLauncher/RedisJobLauncher`（spec→env(jobName,parallelism)→factory.build→executeAsync；未注册工厂 ISE；`RedisJobControlPlane.prefix()` 开放供 agent 派生认领键前缀）。单测 17 例（含 start/stop 幂等与 reconcileSafe 吞异常、checkpoint 失败不阻断、升级失败→FAILED+遗忘、认领释放/cancel 异常被吞、claims-disabled 零 bucket 交互）+ RedisJobLauncher 单测 4 例（空管道 executeAsync 守卫先于 Redis 触达，mock redisson 即可测 create/build/守卫全链）+ 真实 Redis 集成 9 例（agent 全生命周期 deploy→config 升级→scale 快路径→stop→resume→close，双实例认领仲裁与失败移交，真实管道 launch→cancel），全绿；全量门禁 `clean test jacocoRootCoverageVerification` 绿。Mockito 链式桩注意：`doThrow().doNothing()` 仅限 void 方法，非 void 返回值方法用 `doThrow().doReturn(...)`）
  - [x] v1.2：starter 自动装配 + 文档增补（已实现：2026-10-10）：`spring-boot-starter` 新增 `RedisStreamingRuntimeAutoConfiguration`（opt-in 两级开关 `redis-streaming.runtime.control-plane.enabled` / `…agent.enabled`，均默认 false）——控制面开：`ControlPlaneAuthorizer`（默认 allowAll，用户 Bean 优先）+ `RedisJobControlPlane`（prefix/audit/history 键族可配）；agent 开：默认 `RedisJobLauncher` 按 **Spring Bean 名**注册容器内全部 `JobPipelineFactory`（`spec.pipelineFactory` 须相等）+ `JobAgent`（initMethod=`start` 即首轮立即对账，destroyMethod=`close` 关时取消本地作业；`instance-id` 缺省自动生成主机名-随机；`claim-prefix` 缺省取 `<prefix>claim:`，空串关闭认领）。自动配置结构表/配置项参考/典型用例/常见问题与 `docs/Deployment.md` §9（控制面运维：仲裁语义/无自动 failover/滚动升级与回滚建议）同步增补。单测 5 例（ApplicationContextRunner：默认不装配、控制面默认授权与 prefix、用户授权 Bean 优先、agent 未开控制面不启动、工厂按 Bean 名注册生效——空管道守卫前可证工厂收到 spec）+ 全模块绿；全量门禁 `clean test jacocoRootCoverageVerification` 绿
- [ ] 多租户隔离：资源配额（线程/内存/in-flight）、指标维度隔离（方案设计已写 2026-10-10：[Multi-Tenancy-Design.md](docs/Multi-Tenancy-Design.md)——StreamKeys 实例化 + tenant 字段零迁移注入、RateLimiter 三项配额、控制面按租户分段；待评审后实施）
- [ ] 安全：Redis ACL/TLS、secret 管理、配置加密/脱敏（方案设计已写 2026-10-10：[Security-Hardening-Design.md](docs/Security-Hardening-Design.md)——username+惰性凭证+ConfigCustomizer SPI 为 v1，配置加密/secret 引用/脱敏为 v2；待评审后实施）

---

## 其他零覆盖率包（2026-10-05 实测刷新，单测口径；现仅剩示例代码）

以下包覆盖率为 0%，全部为 examples 模块示例代码，可选择性添加测试：

- `io.github.cuihairu.redis.streaming.examples.mq` - 0% → **94% 指令/95% 分支**
- `io.github.cuihairu.redis.streaming.examples.registry` - 0% → **92%/62%**
- `io.github.cuihairu.redis.streaming.examples.streaming` - 0% → **92%/77%**
- `io.github.cuihairu.redis.streaming.examples.state` - 0% → **99%/100%**
- `io.github.cuihairu.redis.streaming.examples.aggregation` - 0% → **96%/75%**
- `io.github.cuihairu.redis.streaming.examples.ratelimit` - 0% → **94%/100%**
- `io.github.cuihairu.redis.streaming.examples.springboot` - 0%（需 Spring Boot 上下文，由 starter 装配测试覆盖其配置类）

**六个示例包脱离零位的打法（2026-10-07）**：沿用 WindowExample/CheckpointExample 先例——示例侧仅做可测试化（演示方法改包可见/返回可观测摘要/加 topic·服务名前缀构造器隔离长寿命 Redis 残留，语义不变），测试侧 reachability-gate（socket 探测 `REDIS_URL`，不可达即跳过；RateLimit 纯内存不设门）。新增 7 个测试类 10 用例：`RateLimitExampleTest`（限流断言用大窗口确定性数字）、`StateExampleTest`、`StreamAggregationExampleTest`、`MessageQueueExampleTest`、`ServiceRegistryExampleTest`（共享注册中心故用宽松断言）、`CustomPrefixExampleTest`、`ComprehensiveStreamingExampleTest`。examples 模块整体 0% → 93% 指令/83% 分支（examples 不计入 `jacocoRootCoverageVerification` 聚合门禁）。

**原清单中的非示例条目已全部脱离零位（2026-10-05 新鲜实测，`:*:test` + 各模块 jacocoTestReport）：**

- `starter.service` 0% → **100% 指令/100% 分支**
- `starter.processor` 0% → **100%/90%**
- `starter.health` 0% → **100%**
- `state`（模块）0% → `state.redis` **98%/75%**；`state.backend` 仅 1 个纯接口 `StateBackend`，无可执行指令（报告不列出）
- `mq.broker.jdbc` 0% → **95.6% 指令/86.1% 分支**
- `mq.admin.model` 0% → **100%**
- `mq.admin` 42% → **100%/100%**
- `config.event` 48% → **94%**
- `table` 54% → `table` **100%**、`table.impl` **99%/99%**（见第 15 条）
- 另：`examples.checkpoint` **96%/75%**、`examples.window` **99%/90%**（第 2、4 条已补测驱动）

---

## 测试环境准备建议

### 1. Docker Compose 测试环境
使用项目现有的 `docker-compose.test.yml` 启动测试环境：

```bash
# 启动所有测试服务
docker-compose -f docker-compose.test.yml up -d

# 查看服务状态
docker-compose -f docker-compose.test.yml ps

# 查看日志
docker-compose -f docker-compose.test.yml logs
```

### 2. 测试数据准备
- **MySQL**: 创建测试数据库，启用 binlog（row 模式）
- **PostgreSQL**: 创建测试数据库，配置逻辑复制
- **Kafka**: 创建测试 topic
- **Redis**: 使用默认配置

### 3. 测试标签规范
- 单元测试：无标签（默认）
- 集成测试：`@Tag("integration")`
- 需要特定环境的测试：
  - `@Tag("redis")` - 需要 Redis
  - `@Tag("mysql")` - 需要 MySQL
  - `@Tag("postgresql")` - 需要 PostgreSQL
  - `@Tag("kafka")` - 需要 Kafka

---

## 测试编写指南

### 单元测试示例
```java
@Test
void testSomething() {
    // Given
    SomeConfig config = new SomeConfig();
    // When
    SomeResult result = someClass.doSomething(config);
    // Then
    assertThat(result).isNotNull();
    assertThat(result.getValue()).isEqualTo(expected);
}
```

### 集成测试示例
```java
@Tag("integration")
@Tag("redis")
@Test
void testRedisIntegration() {
    // Given
    RedisClient client = createRedisClient();
    // When
    client.set("key", "value");
    String result = client.get("key");
    // Then
    assertThat(result).isEqualTo("value");
}
```

---

## 执行计划

### 阶段 1：核心功能测试（优先级 1）（2026-10-10 按 `@Tag("integration")` 文件数核对收口）
- [x] CDC 实现集成测试（cdc 模块 8 个集成测试文件）
- [x] Spring Boot 自动配置测试（starter 6 个集成测试文件，含 ApplicationContextRunner 装配矩阵）
- [x] Checkpoint 功能测试（checkpoint 4 个）
- [x] 窗口功能测试（window 1 个 + runtime 侧窗口/触发器集成套件）

**预计总计：12-17 天**

### 阶段 2：关键集成测试（优先级 2）（2026-10-10 按 `@Tag("integration")` 文件数核对收口）
- [x] 运行时核心测试（runtime 30 个集成测试文件，含窗口/触发器/幂等 sink/outbox/控制面 agent）
- [x] Redis Source/Sink 集成测试（source 3 / sink 2 个集成文件）；Kafka broker 腿仍挂起待 broker（见上文 source.kafka/sink.kafka 条目，`**/kafka/**` 已排除出覆盖率门禁）
- [x] CDC 接口层测试（cdc 8 个集成文件）
- [x] MQ 核心实现测试（mq 62 个集成文件，含 DLQ/租约/提交前沿/回放）

**预计总计：13-17 天**

### 阶段 3：完善测试覆盖（优先级 3-4）（2026-10-10 按 `@Tag("integration")` 文件数核对收口）
- [x] 服务注册测试（registry 30 个集成文件）
- [x] 配置中心测试（config 16 个）
- [x] 死信队列测试（mq DLQ 集成套件：`DlqConsumerLoopIntegrationTest`/`DlqPendingReclaimIntegrationTest`/`DlqReplayAndAdminIntegrationTest` 等）
- [x] MQ 配置测试（mq 62 个集成文件，含 `CommitFrontierUpdate`/`CommitFrontierAtomicity`/`LeaseOwnership` 等配置与租约语义）
- [x] KTable 表操作测试（table 6 个集成文件，含 changelog 全历史重放与双组广播）

**预计总计：9-14 天**

### 总体时间估算：34-48 个工作日

---

## 成功标准

- [x] 总体指令覆盖率 ≥ 98%（2026-09-24 实测 **99.210%**，60496/60978 指令，missed 482；原 0.99 聚合门槛已于 2026-10-05 改为单测确定性口径：INSTRUCTION ≥ 0.95 且 CLASS ≥ 0.99，仅吃 `jacoco/test.exec`——聚合 0.99 门在运行间掷硬币，同码 CI 出过 0.98 与 0.99+ 双结果；并集仍经 `jacocoRootReport` 报告上传 Codecov。2026-10-05 单测口径实测 INSTRUCTION 0.95674 / CLASS 0.99488）
- [x] 总体分支覆盖率 ≥ 60%（实测 **90.88%**，4675/5144 分支）
- [ ] 所有核心包（mq, registry, cdc）覆盖率 ≥ 70%
- [ ] 所有关键业务类覆盖率 ≥ 80%
- [ ] CI/CD 集成测试通过率 100%

---

## 附录：覆盖率报告位置

- HTML 报告：`build/reports/jacoco/jacocoRootReport/html/index.html`
- 生成命令：`./gradlew jacocoRootReport`

---

# 架构评审重构进度与遗留待办

> 记录时间:2026-09-19。全仓设计评审(四路并行分析 core/runtime、mq/registry/config、功能模块层、工程化)后的修复轮。
> 验证:`./gradlew clean check`(全部单测 + `@Tag("integration")` 集成测试 + JaCoCo 门槛)在 Redis 6.2 / JDK 21 / Gradle 8.5 下全绿。**该轮变更已全部 git commit(至 a841acf)。**
> 2026-09-20 追加修复:C.5 CDC 调度丢事件/快照语义、Storms 测试工具去重(20 份副本→test-support 共享)与 invoked 统计修复、docs mermaid 经 vitepress-plugin-mermaid 正确接线、清理误入库的 sink/storm 垃圾文件。
> 2026-09-24 覆盖率收口:指令 99.210%(60496/60978,missed 482)/分支 90.88%;门槛 minimum=0.99(实测水位),`./gradlew clean check` 全绿。**JaCoCo 排除项逐条记录**:本轮无新增排除——残余 482 条多为不可达防御 catch/死分支,全部位于含可执行代码的类内,按口径不整类排除;既有排除为 `**/kafka/**`(外部服务包装类)、`MySQLBinlogCDCConnector*`/`PostgreSQLLogicalReplicationCDCConnector*`(需真实 binlog/逻辑复制环境,无法本地确定性测试)。

## 已完成(本轮)

- [x] **P0 仓库清理**:git 删除已入库的 `node_modules/`(3442 文件)与根 package.json/package-lock.json(docs 站点用 docs/ 独立依赖,CI 不受影响);删除 `C…compile_error.txt`(全角冒号垃圾文件)、`refactor-packages.sh`(macOS-only 一次性脚本)、未跟踪 `REDIS===`;`COMPLETION_REPORT/REFACTORING_CHECKLIST/REFACTORING_COMPLETE/MIGRATION_TO_CENTRAL_PORTAL` 移入 `docs/archive/` 并更新引用;`.gitignore` 补 node_modules/、`*.factorypath`
- [x] **P0 版本统一**:启用 `gradle/libs.versions.toml`(26 个坐标:Redisson/jackson/slf4j/junit/mockito/lombok/springboot 等),全部模块 build.gradle 改用 `libs.*`;slf4j 统一 2.0.17(消除 1.7/2.0 漂移)
- [x] **P0 Redisson 3.52.0 → 4.7.0** 并完成 API 迁移:`StreamMessageId/StreamGroup/StreamInfo/PendingEntry` 移入 `org.redisson.api.stream`;`RScript.ReturnType` `INTEGER→LONG`、`MULTI→LIST`、`STATUS→STRING`;`RKeys.expire(String,long,Unit)`→`expire(Duration,String...)`;`setPassword` 保留旧 setter + `@SuppressWarnings("deprecation")`(4.x 推荐 CredentialsResolver);final 值对象的 mockito 测试改真实实例(DeadLetterQueueManagerTest、DlqConsumerAdapterTest、RedisMessageProducerTest、RedisMessageQueueAdminBehaviorTest、DefaultBrokerUnitTest)
- [x] **P1 StreamSink 生命周期**:core `StreamSink` 增加 `open()/close()` 默认方法;InMemory 引擎 `addSink` 包 try/finally;Redis 引擎 `RedisPipelineRunner` 首条消息幂等 open、close 时释放 sink
- [x] **P1 runtime 依赖修正**:state/watermark 降为 testImplementation(main 零引用);window 保留(修正 FQN 隐式依赖)
- [x] **P1 吞异常治理**:修复 checkpoint 后 `pc.resume()` 单点失败导致其余 consumer 永久暂停的死锁(env);CheckpointManager/KeyedStateStore 25 处静默 `catch (Exception ignore)` 分级为 warn(影响状态快照完整性)/debug(指标类)
- [x] **P1 去重**:`addNumbers`/`castToSameNumberType` 4 份副本收敛为 `runtime.internal.NumberAggregationUtils`
- [x] **P2 名实相符**:`sink.redis.RedisStreamSink` 改为真 XADD(`RStream`+`StreamAddArgs`)并实现 core `StreamSink`;原 List 语义拆为 `RedisListSink implements StreamSink`(该类全仓无其它引用,破坏面为零)
- [x] **P2 CDC 接入主 API**:新增 `cdc.CDCSource implements StreamSource<ChangeEvent>`(有界排空:连续 N 次空 poll 即返回,兼容拉式引擎),含 3 个单测
- [x] **P2 registry 分叉消除**:`registry.BaseRedisConfig` 改为继承 `config.BaseRedisConfig`(registry 对 config 升为 implementation);`MessagingProtocol` 删除无实现的 Kafka/Pulsar/RabbitMQ/NATS/MQTT 常量,仅保留 Redis 系协议并加防回归测试;删除 `MessageQueueFactory` 对遗留 `RedisMessageProducer` 的死 import(保留该类以兼容公开 API)
- [x] **P3 JaCoCo 文档对齐**:AGENTS.md/CLAUDE.md 声明现实门槛为 25% 聚合,80%/70% 标注为 aspirational(后门槛已上调至 70%,见 build.gradle `jacocoRootCoverageVerification`)
- [x] **executeAsync 拆分**(400 行→~125 行):`ENSURE_GROUP_LUA` 常量、`createMessageHandler`、`optionsForSubtask`、匿名 JobClient→命名内部类 `LaunchedJobClient`、关停序列收敛为 `stopConsumersQuietly/closeRunnersQuietly/shutdownExecutorQuietly`;并规范 80 行遗留 tab 缩进

## 遗留待办(大型专项,建议单独立项)

### A. 上帝类继续拆分
- [x] `runtime/redis/internal/RedisStreamBuilder`:五个窗口方法收敛为 `registerWindowedOperator(kind, guard, accumulator, emitter)` 模板(文件 1123→1008 行;提交 7f2550a);前置特征测试 b4d0cb3。窗口成员编解码(windowMember/parseWindow/decodeKey)保留在 `RedisWindowedStreamImpl` 内(纯搬移收益低)
- [x] `mq/impl/RedisMessageConsumer` 双路径合并(提交 0188d31):broker/直连两套 per-record 循环收敛为 `processIncomingRecord`,四份 handle* 合并为 `dispatchResult`+直调 `requeueOrDeadLetter`(1140→1082 行;mq+runtime 集成测试全绿)。按 订阅/重试/租约 拆协作类:未做(风险收益比差,遗留)
- [x] `spring-boot-starter`:核心类改为 `@AutoConfiguration`(90 行,持有 RedissonClient)并按 feature 拆出 5 个顶层配置类(registry/discovery/config/mq/ratelimit),经 @Import 保持原求值顺序与 @EnableRedisStreaming 语义(提交 c84c5f5);starter 38 个装配测试全绿

### B. 双执行引擎统一(核心架构债)
- [ ] 为 `StreamExecutionEnvironment` 与 `RedisStreamExecutionEnvironment` 定义公共 Environment 抽象(调查结论:两者公开面交集仅 fromCollection/fromElements/addSource,Redis 引擎无对应实现;空壳接口无价值,须与 B2/B4 一并设计)
- [ ] InMemory 引擎支持无界源与增量窗口(现为"先跑完 source 物化成 List"的批式模型,无限源会 OOM;`InMemoryKeyedStream.window` 丢弃 watermarkState/coordinator,累加器不可快照)
- [x] Redis 引擎接入 core `WatermarkGenerator`:`DataStream.assignTimestampsAndWatermarks(gen)` 现为真实算子(ctx.raiseWatermark 单调推进水位线),集成测试证明用户生成器能越过配置的 10s outOfOrderness 启发式提前触发窗口;`(TimestampAssigner, gen)` 重载仍未接入(需要 runner 改事件时间传播模型,归入 B2)
- [x] 让 `WindowAssigner.getDefaultTrigger`/window 模块 Trigger 真正被调用(原死接口):Redis 引擎窗口算子按 (partition,key,window) 桶持触发器实例,`onElement`(FIRE 提前发射保留状态/FIRE_AND_PURGE 发射并清桶/PURGE 丢弃)与 `onEventTime`(CONTINUE 推迟关闭/PURGE 静默丢弃)均已接入执行路径,默认 `EventTimeTrigger` 行为与接入前逐点等价(等价用例钉住);`onProcessingTime` 仍未接(两引擎均无 processing-time 窗口定时器,与 docs/watermark.md 触发时机描述一致)。单测 `RedisWindowedStreamTriggerTest`(5 用例)、集成测试 `RedisWindowedStreamTriggerIntegrationTest`(窗口未关时 FIRE 提前发射端到端成立)
- [ ] runtime 用 state 模块实现替换自行开发 `RedisKeyedStateStore`(消除两套 keyed state);评估移除 `WatermarkState` 与 watermark 模块的第三份水位线逻辑（方案设计已写 2026-10-10：[State-Unification-Design.md](docs/State-Unification-Design.md)——反向结论：以 runtime keyed store 为标准实现、state 模块退为 API 面+适配层（checkpoint stateKeys 契约/TTL/schema 在 runtime 侧），水位线以 watermark 模块 `max-ooo-1` 语义为准收敛 candidateFor 的 `-1` 分叉；待评审后实施）
- [x] 补 runtime 窗口/水位线测试:`RedisRuntimeWindowedStreamIntegrationTest`(6 用例覆盖五个窗口算子 + 用户生成器)、`RedisPipelineRunnerWatermarkTest` 增 raiseWatermark 用例;`@Tag("integration")` 文件 runtime 现 2 个(其余测试项继续见上文覆盖率清单)

### C. 孤岛模块接入或降级
- [ ] `aggregation`:与 core `AggregateFunction`、window 模块的第三套 `TimeWindow/TumblingWindow` 统一(现三套并行抽象互不兼容,喂不进 `WindowedStream.aggregate`)（方案设计已写 2026-10-10：[Aggregation-Unification-Design.md](docs/Aggregation-Unification-Design.md)——core 增量接口为唯一标准，aggregation 重定位为高阶实现库（TopK/分位/PVUV 改实现 AggregateFunction），批式接口与 aggregation.TimeWindow deprecated 后删；待评审后实施）
- [x] `join`/`cep`:包装为 DataStream 算子(现为纯内存工具类,无法参与 pipeline);join 与 table 的 join 语义二选一——Phase 1 落地 2026-10-08:设计先行 `docs/Join-CEP-Operators-Design.md`(信封多路复用输入模型零引擎改动;DataStream 侧 join=stream-stream windowed join,KTable join 保持表语义,边界入 Join.md/Table.md),实现 `join.operator.{Envelope,StreamJoinOperator}`(委托 StreamJoiner 新增的显式 key/ts 重载,窗口谓词/外连接/淘汰语义逐点复用)+ `cep.operator.PatternSequenceProcessFunction`(每 key 独立 matcher、within 事件驱动清理、maxTrackedKeys 空闲优先逐出);单测 12 例(含与 StreamJoiner 的差分对照)+ 双引擎集成 4 例(InMemory 普通车道 + Redis MQ topic 门控车道)全绿;join/cep 既有公开 API 零改动。Phase 2(`DataStream.join` 语法糖、缓冲入 keyed state)与 Phase 3(双源 pipeline)仅设计未实现
- [x] `table.RedisKTable.toStream()`:由静态快照导出改为持续 changelog(已实现:2026-10-10,设计见 [Table-Changelog-Design.md](docs/Table-Changelog-Design.md)):`RedisKTable.withChangelog()` 显式开启后 put/put(k,null) 双写 `PUT/DEL` 事件到 MQ topic `table-changelog:<tableName>`(消息 key=tableName,HashPartitioner 同 key 同分区保序;best-effort 发送失败 WARN 不抛,主存为准);`toStream(env)` 切换为 `fromMqTopic` 持续事件流——consumer group 首建固定 `0-0`,全历史重放=状态完整重建(Flink KTable.toStream 语义),PUT→(k,v)、DEL→(k,null);默认组内单播,`toStream(env, group)` 重载每组全量(跨组广播);执行模型=调用方环境:`toStream(env[, group])` 把管道挂到调用方 `RedisStreamExecutionEnvironment`,由其 `executeAsync()` 启动并持 `RedisJobClient` 管生命周期(Redis 引擎管道无法从隐藏环境启动——首版隐藏 env 在 CI 集成测试以 CCE 实证,已改为 env 重载+无参 changelog 模式抛 ISE 指路);未开启保持静态快照且无参可用(现有调用方零影响,默认关因每 put 写放大对查询型表是错误取舍);`clear()/delete()` 不写 changelog(流式场景用逐 key delete 代替);InMemoryKTable 不改。单测 12 例(事件编解码往返、开关语义、未知 op 拒绝、坏 payload 包装、emit 全链:生产者注入 seam 验证 PUT/DEL 事件内容+失败 future 吞+send 抛吞、env 重载参数校验+惰性构建、无参 changelog 拒绝)+ 真实 Redis 集成 2 例(全历史重放:全事件到达+a 的 DEL 在 PUT 之后,跨分区到达顺序不精确断言防分区扩容乱序假红 + 持续跟随新事件;显式双组各自全量排序比较),全绿
- [x] 桥接完成(本轮):新增 `source.redis.RedisStreamSource implements StreamSource`——真 XREADGROUP、自动建组(用 `0-0` 而非 `StreamMessageId.MIN`,后者需 Redis≥7)、ack、有界排空;单测 4 个 + 真 Redis round-trip 集成测试。CDC→mq:`cdc.mq.ChangeEventQueueSink implements StreamSink<ChangeEvent>`(自描述 payload、key 作分区键、失败上抛),cdc 增 mq 依赖;单测 3 个。`RedisListSource` 保留原样(其名字与 List 语义相符,Consumer 风格工具类无错)
- [ ] `metrics` 模块与 `RedisRuntimeMetrics`/`CDCMetrics`/`MqMetrics` 四套体系统一（方案设计已写 2026-10-10：[Metrics-Unification-Design.md](docs/Metrics-Unification-Design.md)——Micrometer 唯一门面，runtime 指标提为公共 SPI+starter 桥接、CDC 导出器、删 ReliabilityMetrics 死桥与 metrics 模块；待评审后实施）
- [x] `reliability` 与 mq 的 DLQ/重试、runtime 幂等 sink 的去重职责划界(已划界:2026-10-10,决策记录 [Dedup-Retry-DLQ-Boundary-Design.md](docs/Dedup-Retry-DLQ-Boundary-Design.md))——现状事实:`mq`/`runtime` main 对 reliability 零 import(跨层引用只在 starter 的 metrics/ratelimit 桥接与 examples),两套重试/死信抽象并行存在且各自为政(`mq.retry.RetryPolicy` 接口 vs `reliability.RetryPolicy` 配置 POJO;mq 持久化 DLQ vs reliability 内存 DeadLetterQueue);划界三层:投递层 mq 只管投递失败(lease/pending+退避重试+持久化死信+重放,不做内容去重)、用户/算子层 reliability 只管业务函数重试与内容去重(不得接进 MQ 消费失败出口——内存 DLQ 崩溃丢事件)、Sink 端 runtime 管至少一次投递→恰好一次效果(dedupSetKey 幂等键+checkpoint 同步刷,不再叠 Deduplicator);不变量已入记录:MqHeaders.DEFER_ACK 正交、`FAIL`/`DEAD_LETTER` 写成功才 ack、DLQ 写失败保持 PEL 未 ack 等重投不丢事件;残留债:两个 `RetryPolicy` 同名不同型,建议 v2 把 mq 侧改名 `DeliveryRetryPolicy`(破坏性重命名,与指标统一同一 major 周期,本批不动)

### C.5 CDC 拉取模型陷阱(已修复 2026-09-20)
- [x] `AbstractCDCConnector.startScheduledPolling` 原先周期调用 `poll()` 并**丢弃取回的事件**,与外部拉取消费者竞争队列。已修复:调度器把批次交付给 `CDCEventListener.onEvents(connectorName, events)`(新增 default 回调),事件不再丢失;拉取消费者应设 `pollingIntervalMs=0`。含测试 `AbstractCDCConnectorTest.scheduledPollingDeliversEventsToListenerInsteadOfDropping`。
- [x] `DatabasePollingCDCConnector.initializeLastPolledValues` 跳过存量行问题已与 `snapshot.enabled`/`snapshot.mode` 语义对齐:`snapshot.enabled=true` 且 mode≠never 时启动捕获存量行为 INSERT 事件(`onSnapshotStarted`/`onSnapshotCompleted`),否则基线取 MAX 跳过存量(默认,已文档化)。含 3 个行为测试;见 docs/CDC.md「Polling 语义」。

### D. registry 瘦身(破坏公开 API,建议放到 1.0 周期)
- [ ] `metrics/` APM 采集 14 类(与 metrics 模块重叠)、`client/` RPC 调用端(与 reliability 重叠)、`WebSocketHealthChecker`(实为裸 TCP,等价 TcpHealthChecker)、四套同义接口(Registry/Provider、Discovery/Consumer)与 `RedisNamingService` 纯委托门面
- [ ] `config.RedisConfigCenter` 冗余门面;两个 `BaseRedisConfig` 已合并,继续收敛 `ConfigManager/ConfigService/ConfigCenter` 接口堆叠

### E. 其他
- [x] examples 新增 `springboot.StarterExampleApplication` + 注释版 `application.yml`(registry/discovery/config/mq/ratelimit 全键样例)。**首次真实启动 starter 暴露并修复 4 个潜伏 bug**:logback 1.5.13 与 Spring Boot 3.2 不兼容(`LoggerContext.getConfigurationLock` 移除,降到 1.4.14)、默认空密码仍发送 AUTH 导致连接失败(改为仅非空才 set)、`MqHealthIndicator` bean 在无 actuator 时使配置类内省失败(下沉到类级 `@ConditionalOnClass` 嵌套配置)、5 个 micrometer collector/installer bean 缺 `@ConditionalOnBean(MeterRegistry/collector)` 守卫。示例已在本地 Redis 端到端跑通(注册/配置/MQ 全通)
- [x] `StreamSource` 生命周期与 StreamSink 对称补齐:`open()/close()` 默认方法,InMemory 引擎 addSource 已接线(source.open → run → finally close)
- [ ] `SourceContext.getCheckpointLock` 真实接入(in-memory 引擎返回的 `new Object()` 无任何 `synchronized` 使用者;Redis 引擎尚无 SourceContext 调用路径。需与检查点屏障协议一并设计)
- [x] 发布说明记录 Redisson 4.7.0 升级与 API 迁移(README/docs 已同步版本号;CHANGELOG [Unreleased] 已含该条目,2026-10-05 另补齐 2PC sink/outbox/leader 选举/状态治理/可观测等未记录特性条目)
- [ ] 覆盖率:延续上文"优先级 1-4"清单(15 条已全部收口,余 sink/source kafka 集成腿挂起待 broker);门禁现行为单测确定性口径 INSTRUCTION ≥ 0.95 且 CLASS ≥ 0.99(2026-10-05 起,见"成功标准"注),上调档视实测覆盖而定
