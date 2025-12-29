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
