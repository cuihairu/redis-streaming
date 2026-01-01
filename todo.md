# 测试覆盖率提升待办清单

> 生成时间：2025-12-30
> 总体覆盖率：55% (指令) / 44% (分支)
> 目标：将所有包的覆盖率提升至 70% 以上

## 优先级 1：覆盖率 < 20%（紧急）

### 1. io.github.cuihairu.redis.streaming.cdc.impl - 5%
**关键问题：** CDC 实现类几乎完全没有测试覆盖
**未覆盖的关键类：**
- `MySQLBinlogCDCConnector` - 0% (21 个方法未覆盖)
- `PostgreSQLLogicalReplicationCDCConnector` - 0% (24 个方法未覆盖)
- `DatabasePollingCDCConnector` - 0% (24 个方法未覆盖)
- `AbstractCDCConnector` - 0% (31 个方法未覆盖)

**建议添加的测试类型：**
- [ ] **集成测试**（必需）
  - MySQL Binlog CDC 集成测试（需要 MySQL 环境）
  - PostgreSQL 逻辑复制 CDC 集成测试（需要 PostgreSQL 环境）
  - 数据库轮询 CDC 集成测试
  - CDC 连接失败重试测试
  - CDC 断连恢复测试
  - Binlog/逻辑复制位置偏移量测试
- [ ] **单元测试**
  - CDC 配置构建测试
  - 事件过滤测试
  - 错误处理测试

**测试环境要求：**
- MySQL 5.7+（启用 binlog）
- PostgreSQL 10+（启用逻辑复制）
- 测试数据库和表

---

### 2. io.github.cuihairu.redis.streaming.window - 18%
**关键问题：** 示例代码未被测试覆盖
**未覆盖的关键类：**
- `WindowExample` - 0% (7 个方法未覆盖)

**建议添加的测试类型：**
- [ ] **单元测试**（将示例改为可测试的代码）
  - 时间窗口分配测试
  - 滚动窗口聚合测试
  - 滑动窗口聚合测试
  - 会话窗口测试
  - 窗口触发器测试

---

### 3. io.github.cuihairu.redis.streaming.starter.autoconfigure - 27%
**关键问题：** Spring Boot 自动配置未被测试
**未覆盖的关键类：**
- `RedisStreamingAutoConfiguration.MqConfiguration` - 0%
- `RedisStreamingAutoConfiguration.RegistryConfiguration` - 0%
- `RedisStreamingAutoConfiguration.ConfigServiceConfiguration` - 0%
- `RedisStreamingAutoConfiguration.DiscoveryConfiguration` - 0%
- `RedisStreamingAutoConfiguration` - 6%

**建议添加的测试类型：**
- [ ] **Spring Boot 集成测试**
  - MQ 自动配置加载测试
  - Registry 自动配置加载测试
  - ConfigService 自动配置测试
  - RateLimit 自动配置测试
  - 条件注解（@ConditionalOnProperty）测试
  - 自动配置属性绑定测试
  - Bean 覆盖测试

---

### 4. io.github.cuihairu.redis.streaming.checkpoint - 21%
**关键问题：** 示例代码未被测试覆盖
**未覆盖的关键类：**
- `CheckpointExample` - 0% (7 个方法未覆盖)

**建议添加的测试类型：**
- [ ] **单元测试**
  - Checkpoint 创建和提交测试
  - Checkpoint 恢复测试
  - StateSnapshot 序列化测试
  - Checkpoint 协调器测试
- [ ] **集成测试**
  - Redis Checkpoint 存储测试
  - 分布式 Checkpoint 协调测试

---

## 优先级 2：覆盖率 20%-50%（重要）

### 5. io.github.cuihairu.redis.streaming.runtime.internal - 39%
**关键问题：** 运行时核心实现测试不足
**未覆盖的关键类：**
- `InMemoryWindowedStream` - 0% (18 个方法未覆盖)
- `InMemoryKeyedStream` - 39% (部分聚合方法未覆盖)
- `InMemoryKeyedStream$6` - 0% (迭代器未覆盖)

**建议添加的测试类型：**
- [ ] **单元测试**
  - 窗口分配和触发测试
  - 窗口聚合函数测试（sum, count, avg, min, max）
  - KeyedStream 分区测试
  - 窗口水印对齐测试
  - 窗口过期清理测试
- [ ] **集成测试**
  - 端到端窗口处理测试
  - 窗口状态恢复测试

---

### 6. io.github.cuihairu.redis.streaming.source.kafka - 36%
**关键问题：** Kafka 数据源测试不足
**未覆盖的关键类：**
- `KafkaSource` - 36% (8 个方法未覆盖)

**建议添加的测试类型：**
- [ ] **集成测试**（需要 Kafka 环境）
  - Kafka 消息消费测试
  - 分区分配测试
  - Offset 提交测试
  - Consumer 重启恢复测试
  - 反序列化错误处理测试

---

### 7. io.github.cuihairu.redis.streaming.cdc - 44%
**关键问题：** CDC 接口层未完全测试
**未覆盖的关键类：**
- `CDCManager` - 0% (28 个方法未覆盖)
- `CDCConfigurationBuilder.DefaultCDCConfiguration` - 26%
- `ChangeEvent` - 0%
- `CDCConnectorFactory` - 0%
- `CDCEventListener` - 0% (接口)

**建议添加的测试类型：**
- [ ] **单元测试**
  - CDC 管理器生命周期测试
  - CDC 配置构建器测试
  - 变更事件序列化/反序列化测试
  - 连接器工厂测试
- [ ] **集成测试**
  - CDC 启动和停止测试
  - 多连接器并发测试

---

### 8. io.github.cuihairu.redis.streaming.source.redis - 47%
**关键问题：** Redis 数据源测试不足
**未覆盖的关键类：**
- `RedisListSource` - 47% (10 个方法未覆盖)

**建议添加的测试类型：**
- [ ] **集成测试**（需要 Redis）
  - Redis List 数据读取测试
  - List 阻塞弹出（BLPOP/BRPOP）测试
  - 批量读取测试
  - 空列表处理测试
  - Redis 连接失败测试

---

### 9. io.github.cuihairu.redis.streaming.sink.kafka - 52%
**关键问题：** Kafka Sink 测试不足
**未覆盖的关键类：**
- `KafkaSink` - 52% (5 个方法未覆盖)

**建议添加的测试类型：**
- [ ] **集成测试**（需要 Kafka 环境）
  - Kafka 消息发送测试
  - 分区路由测试
  - 序列化测试
  - 错误重试测试
  - 事务性发送测试

---

## 优先级 3：覆盖率 50%-70%（中等）

### 10. io.github.cuihairu.redis.streaming.mq.impl - 49%
**关键问题：** MQ 核心实现测试覆盖不足
**未覆盖的关键类：**
- `RedisMessageProducer` - 0% (8 个方法未覆盖)
- `DlqConsumerAdapter` - 15% (9 个方法未覆盖)
- `RedisMessageConsumer` - 54% (18 个方法未覆盖)
- `StreamEntryCodec` - 50% (11 个方法未覆盖)

**建议添加的测试类型：**
- [ ] **集成测试**
  - 消息发送和接收端到端测试
  - 死信队列转发测试
  - 消费组管理测试
  - 消息确认和重试测试
  - Stream 数据结构序列化测试
- [ ] **单元测试**
  - StreamEntry 编解码测试
  - 消息生命周期管理测试

---

### 11. io.github.cuihairu.redis.streaming.reliability.dlq - 51%
**关键问题：** 死信队列功能测试不足
**未覆盖的关键类：**
- `RedisDeadLetterAdmin` - 0% (10 个方法未覆盖)
- `RedisDeadLetterConsumer` - 39% (5 个方法未覆盖)
- `RedisDeadLetterService` - 71% (2 个方法未覆盖)

**建议添加的测试类型：**
- [ ] **集成测试**
  - 死信队列写入测试
  - 死信消息消费测试
  - 死信队列重试测试
  - 死信队列管理操作测试
  - 过期死信清理测试

---

### 12. io.github.cuihairu.redis.streaming.config.impl - 55%
**关键问题：** 配置中心实现测试不足
**未覆盖的关键类：**

---

# Runtime（企业级能力）待办清单

> 目标：将 `runtime` 从“最小可用单机运行时”逐步提升到可上线、可运维、可扩展的企业级运行时。
> 范围：`runtime`（in-memory + Redis runtime）以及与 `mq/state/checkpoint/reliability/metrics` 的集成。

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
  - [ ] v2：Two-Phase Commit Sink（2PC）API 设计与实现（类似 Flink 两阶段提交）
    - [ ] core：新增 `TwoPhaseCommitSink` API（Txn 可序列化进 checkpoint）
    - [ ] runtime：checkpoint 流程加入 `preCommit -> storeCheckpoint(txn) -> commit -> mark sinkCommitted -> ack`
    - [ ] runtime：恢复流程加入 txn 补偿（`recoverAndCommit/recoverAndAbort`）
    - [ ] integration tests：故障注入（checkpoint 已写入但 commit 未执行/commit 抛错）与恢复验证
  - [ ] v2.5：Outbox/WAL（Redis 内 outbox + 异步投递器），作为跨系统 exactly-once 的折中方案
  - [ ] v3：Redis-only exactly-once（Lua 原子写 sink + 更新 offsets + XACK；Redis Cluster 需同 hash slot）
    - [x] Redis-only commit-on-checkpoint sink：`RedisCheckpointedIdempotentListSink<T>`（checkpoint complete 后 flush side effects）（已实现：2026-01-01）
    - [x] Redis-only atomic commit（单 Lua）：`RedisAtomicCheckpointListSink` + `RedisExactlyOnceRecord`（写 sink + XACK + commit frontier 原子提交）（已实现：2026-01-01）
- [x] State 演进：state schema/versioning、兼容升级策略、回滚策略（已实现：2026-01-01）
  - [x] `StateDescriptor.schemaVersion`（默认 1）
  - [x] Redis runtime state schema 元数据与校验（`stateSchemaEvolutionEnabled` + `stateSchemaMismatchPolicy=FAIL/CLEAR/IGNORE`）
  - [x] Checkpoint snapshot/restore state schema 元数据（避免恢复后 schema 丢失）
  - [ ] 状态迁移工具/策略：提供在线迁移或离线重放方案（按业务自定义）
- [ ] State 治理：TTL/清理策略、热点 key 保护、state size 上报与告警
  - [x] State TTL：`RedisRuntimeConfig.stateTtl(...)`（对 keyed state Redis hash key 写入后 best-effort expire）
  - [x] State size 上报（抽样）：`RedisRuntimeConfig.stateSizeReportEveryNStateWrites(n)`（每 N 次 state 写入上报一次 hash 字段数）
  - [x] 热点 key 保护（基础）：`RedisRuntimeConfig.keyedStateShardCount(n)`（按 key hash 分片，降低单个 hash 过热/过大风险，默认 1 不启用）
  - [x] 热点 key 告警（阈值+限频日志+指标）：`keyedStateHotKeyFieldsWarnThreshold`/`keyedStateHotKeyWarnInterval`
  - [ ] 热点 key 处置（阈值/采样/限流/降级/DLQ）

## P2：可扩展与性能
- [x] 并行度模型（基础）：`pipelineParallelism(n)` + 分区固定分配（`partitionId % n`）以实现多子任务并行（已实现：2026-01-01）
- [x] 背压（基础）：`MqOptions.maxInFlight(n)`（consumer 级全局并发上限，信号量限流）（已实现：2026-01-01）
- [x] 并行度/背压追踪（基础）：in-flight、permit wait、eligible/leased partitions（MQ Micrometer 指标）（已实现：2026-01-01）
- [x] 分区 lease 防超配（基础）：`MqOptions.maxLeasedPartitionsPerConsumer(n)`（默认=workerThreads，避免拿到 lease 但线程不足导致分区饿死）（已实现：2026-01-01）
- [ ] 资源模型：线程池/队列容量/批大小可配；安全默认值；压测基准与调优指南
- [ ] Window 运行时支持（Redis runtime）：事件时间/水位线/触发器/迟到数据处理（明确语义）

## P3：可观测与运维
- [ ] Metrics 全链路：吞吐、延迟、pending、重试、DLQ、state 读写、定时器队列等
- [ ] Trace/日志：可关联 messageId、jobName、operatorId；支持采样
- [ ] 运行时诊断：健康检查、指标导出、运行时配置 dump、死锁/卡顿定位
- [ ] 多环境部署：Docker/K8s 参考部署，滚动升级与回滚建议
- `RedisConfigCenter` - 0% (14 个方法未覆盖)
- `RedisConfigService` - 58% (3 个方法未覆盖)

**建议添加的测试类型：**
- [ ] **集成测试**（需要 Redis）
  - 配置发布和订阅测试
  - 配置版本管理测试
  - 配置变更通知测试
  - 配置历史查询测试
  - 配置权限测试

---

### 13. io.github.cuihairu.redis.streaming.registry.impl - 55%
**关键问题：** 服务注册实现测试不足
**未覆盖的关键类：**
- `RedisNamingService` - 44% (18 个方法未覆盖)
- `RedisServiceProvider` - 48% (13 个方法未覆盖)
- `RedisServiceConsumer` - 58% (16 个方法未覆盖)

**建议添加的测试类型：**
- [ ] **集成测试**
  - 服务注册和发现测试
  - 服务健康检查测试
  - 服务元数据管理测试
  - 服务实例过滤测试
  - 命名服务解析测试

---

### 14. io.github.cuihairu.redis.streaming.mq.config - 62%
**关键问题：** MQ 配置构建器分支覆盖不足
**未覆盖的关键类：**
- `MqOptions.Builder` - 49% (16 个方法未覆盖，22 个分支未覆盖)

**建议添加的测试类型：**
- [ ] **单元测试**
  - 配置参数校验测试
  - 默认值测试
  - Builder 模式各种组合测试
  - 必填参数缺失测试
  - 参数范围校验测试

---

## 优先级 4：覆盖率 65%-70%（需要小幅提升）

### 15. io.github.cuihairu.redis.streaming.table.impl - 65%
**关键问题：** KTable 实现测试不足
**未覆盖的关键类：**
- `RedisKGroupedTable` - 0% (7 个方法未覆盖)
- `RedisKTable` - 76% (6 个方法未覆盖)
- `InMemoryKTable` - 93% (1 个方法未覆盖)

**建议添加的测试类型：**
- [ ] **集成测试**
  - 分组表聚合测试
  - 表 Join 测试
  - 表更新传播测试
  - 表状态持久化测试
  - 表查询测试

---

## 其他零覆盖率包（多为示例代码）

以下包覆盖率为 0%，但多为示例代码，可选择性添加测试：

- `io.github.cuihairu.redis.streaming.examples.mq` - 0%
- `io.github.cuihairu.redis.streaming.examples.registry` - 0%
- `io.github.cuihairu.redis.streaming.examples.streaming` - 0%
- `io.github.cuihairu.redis.streaming.examples.aggregation` - 0%
- `io.github.cuihairu.redis.streaming.examples.ratelimit` - 0%
- `io.github.cuihairu.redis.streaming.starter.service` - 0%
- `io.github.cuihairu.redis.streaming.starter.processor` - 0%
- `io.github.cuihairu.redis.streaming.state` - 0%
- `io.github.cuihairu.redis.streaming.mq.broker.jdbc` - 0%
- `io.github.cuihairu.redis.streaming.starter.health` - 0%
- `io.github.cuihairu.redis.streaming.mq.admin.model` - 0%
- `io.github.cuihairu.redis.streaming.config.event` - 48%
- `io.github.cuihairu.redis.streaming.table` - 54%
- `io.github.cuihairu.redis.streaming.mq.admin` - 42%

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

### 阶段 1：核心功能测试（优先级 1）
- [ ] CDC 实现集成测试（预计 5-7 天）
- [ ] Spring Boot 自动配置测试（预计 2-3 天）
- [ ] Checkpoint 功能测试（预计 2-3 天）
- [ ] 窗口功能测试（预计 3-4 天）

**预计总计：12-17 天**

### 阶段 2：关键集成测试（优先级 2）
- [ ] 运行时核心测试（预计 3-4 天）
- [ ] Kafka/Redis Source/Sink 测试（预计 4-5 天）
- [ ] CDC 接口层测试（预计 2-3 天）
- [ ] MQ 核心实现测试（预计 4-5 天）

**预计总计：13-17 天**

### 阶段 3：完善测试覆盖（优先级 3-4）
- [ ] 服务注册测试（预计 2-3 天）
- [ ] 配置中心测试（预计 2-3 天）
- [ ] 死信队列测试（预计 2-3 天）
- [ ] MQ 配置测试（预计 1-2 天）
- [ ] KTable 测试（预计 2-3 天）

**预计总计：9-14 天**

### 总体时间估算：34-48 个工作日

---

## 成功标准

- [ ] 总体指令覆盖率 ≥ 70%
- [ ] 总体分支覆盖率 ≥ 60%
- [ ] 所有核心包（mq, registry, cdc）覆盖率 ≥ 70%
- [ ] 所有关键业务类覆盖率 ≥ 80%
- [ ] CI/CD 集成测试通过率 100%

---

## 附录：覆盖率报告位置

- HTML 报告：`build/reports/jacoco/jacocoRootReport/html/index.html`
- 生成命令：`./gradlew jacocoRootReport`
