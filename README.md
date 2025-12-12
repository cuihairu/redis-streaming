# Redis-Streaming - 基于 Redis 的轻量级流处理框架

一个基于 Redis 的现代化流处理框架，提供完整的流数据处理、状态管理、窗口聚合、CDC、可靠性保证等企业级功能。

[![Java](https://img.shields.io/badge/Java-17+-orange.svg)](https://www.oracle.com/java/)
[![Redis](https://img.shields.io/badge/Redis-6.0+-red.svg)](https://redis.io/)
[![Version](https://img.shields.io/badge/Version-0.1.0-blue.svg)](https://github.com/cuihairu/redis-streaming)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

## 🚀 核心特性

### ✅ 已实现功能
- **📡 消息队列 (MQ)** - 基于 Redis Streams 的完整消息队列，支持消费者组、死信队列
- **🔍 服务注册发现 (Registry)** - 完整的服务注册与发现，支持多协议健康检查 (HTTP/TCP/WebSocket)，**支持 metadata 比较运算符过滤**
- **⚙️ 配置中心 (Config)** - 基于 Redis 的分布式配置管理，支持配置版本化、变更通知、历史记录
- **💾 状态管理 (State)** - Redis 支持的分布式状态存储，支持 ValueState、MapState、ListState、SetState
- **✅ 检查点机制 (Checkpoint)** - 分布式检查点协调，支持故障恢复
- **⏰ 窗口聚合 (Aggregation)** - 基于时间窗口的实时聚合，支持 PV/UV、TopK、分位数计算
- **🔗 流式 Join (Join)** - 时间窗口内的流-流 Join 操作
- **🔄 CDC 集成 (CDC)** - MySQL Binlog、PostgreSQL 逻辑复制、数据库轮询
- **🛡️ 可靠性保证 (Reliability)** - 重试机制、死信队列、Bloom Filter 去重、窗口去重
- **📤 Sink 连接器 (Sink)** - Kafka Sink、Redis Stream Sink、Redis Hash Sink
- **📥 Source 连接器 (Source)** - Kafka Source、HTTP API Source、Redis List Source
- **📊 Prometheus 监控 (Metrics)** - Prometheus Exporter、指标收集器
- **🔌 Spring Boot 集成** - 完整的自动配置和注解支持
- **📊 流表二元性 (Table)** - 内存版和 Redis 持久化版 KTable 已实现
- **🎯 CEP** - 完整的复杂事件处理，支持 Kleene closure、高级模式操作

### 🚧 部分实现
- **🌊 流处理运行时 (Runtime)** - 运行时引擎规划中，当前可使用独立模块（mq、state、aggregation、cep）
- **💧 Watermark** - 生成器已实现，可与 aggregation 模块配合使用
- **🪟 窗口分配器 (Window)** - 窗口逻辑已实现，已集成到 aggregation 模块

## 📦 模块架构

### **Tier 1: 核心抽象层**

#### **core** - 核心抽象与 API 定义
流处理的核心 API 和基础抽象，定义所有流处理操作的接口。

**实现状态**: ✅ 完成 - API 定义完整

**职责：**
- 流处理 API（DataStream, KeyedStream, WindowedStream）
- 状态管理抽象（State, ValueState, MapState, ListState, SetState）
- 检查点抽象（Checkpoint, CheckpointCoordinator）
- 水位线抽象（Watermark, WatermarkGenerator）
- 窗口抽象（WindowAssigner, Trigger, Evictor）
- 连接器抽象（StreamSource, StreamSink）
- 工具类（InstanceIdGenerator, SystemUtils）

**关键类**: `DataStream.java`, `KeyedStream.java`, `State.java` (23 个文件)

#### **runtime** - 流处理运行时引擎
统一流处理运行时执行引擎。

**实现状态**: 📋 规划中

**说明**: 统一的流处理运行时引擎是一个复杂的系统工程，类似于 Apache Flink 的运行时。当前框架采用模块化设计，各功能模块（mq、state、aggregation、cep等）可独立使用。详见 `runtime/README.md`。

### **Tier 2: 基础设施层**

#### **mq** - 消息队列
基于 Redis Stream 的完整消息队列实现，提供可靠的消息传递。

**实现状态**: ✅ 完成 - 生产可用

**职责：**
- 消息生产和消费（异步支持）
- 消费者组管理
- 死信队列 (DLQ)
- 消息重试机制
- 作为流处理的数据管道

**关键类**: `RedisMessageProducer.java`, `RedisMessageConsumer.java`, `DeadLetterQueueManager.java` (9 个文件)

Retention 与 ACK 删除策略（简述）
- 默认通过“保留 + 裁剪”控制内存：
  - 写时裁剪：每次写入后执行 `XTRIM MAXLEN ~`（低开销）
  - 后台裁剪：每 `trimIntervalSec` 执行 `XTRIM MAXLEN ~`，可选 `XTRIM MINID ~`；多组时按“最小提交前沿”做安全裁剪
- 可选 ACK 删除策略：
  - `none`（默认）：仅 ACK，不立刻删除；依赖保留策略
  - `immediate`：单组场景可用，ACK 后立刻 `XDEL`
  - `all-groups-ack`：多组逐条计数，所有活跃组都 ACK 后删除
- DLQ 可配置独立保留阈值（长度/时间）

详见：`wiki/docs/retention-and-ack-policy.md`

#### **registry** - 服务注册发现
基于 Redis 的服务注册与发现，支持微服务架构和健康检查。

**实现状态**: ✅ 完成 - 生产可用

**职责：**
- 服务注册与注销（心跳机制、Lua 脚本优化）
- 服务发现与订阅（Redis Pub/Sub 实时通知）
- 多协议健康检查（HTTP、HTTPS、TCP、WebSocket、gRPC、自定义）
- **Metadata 过滤查询**（支持比较运算符：`>`, `>=`, `<`, `<=`, `!=`, `==`）
- 负载均衡支持（基于权重、CPU、延迟等 metadata）
- 临时/永久实例管理

**关键类**: `RedisNamingService.java`, `RedisServiceProvider.java`, `RedisServiceConsumer.java`, `RegistryLuaScriptExecutor.java` (25 个文件)

**Metadata 过滤示例**:
```java
// 基于权重的智能负载均衡
Map<String, String> filters = new HashMap<>();
filters.put("weight:>=", "80");          // 权重 >= 80
filters.put("cpu_usage:<", "70");        // CPU < 70%
filters.put("region", "us-east-1");      // 精确匹配
filters.put("status:!=", "maintenance"); // 排除维护状态

List<ServiceInstance> instances =
    namingService.getInstancesByMetadata("order-service", filters);
```

#### **config** - 配置中心
基于 Redis 的分布式配置管理，提供配置版本化和变更通知。

**实现状态**: ✅ 完成 - 生产可用

**职责：**
- 配置发布与获取（支持分组管理）
- 配置版本化（历史记录、回滚支持）
- 配置变更通知（Redis Pub/Sub 实时推送）
- 配置监听器（自动更新、热加载）
- 配置历史查询（保留最近 N 个版本）

**关键类**: `RedisConfigService.java`, `ConfigManager.java`, `ConfigChangeListener.java` (10 个文件)

**配置管理示例**:
```java
// 发布配置
configService.publishConfig("app.properties", "DEFAULT_GROUP",
    "key=value\ndb.url=jdbc:mysql://localhost:3306/db",
    "Updated database configuration");

// 监听配置变更
configService.addListener("app.properties", "DEFAULT_GROUP", (dataId, group, content) -> {
    System.out.println("Configuration changed: " + content);
    // 自动重新加载配置
});

// 查询历史版本
List<ConfigHistory> history = configService.getHistory("app.properties", "DEFAULT_GROUP", 10);
```

#### **state** - 状态管理
基于 Redis 的分布式状态存储，提供多种状态类型。

**实现状态**: ✅ 完成 - 生产可用

**职责：**
- ValueState - 单值状态（Redis String）
- MapState - 键值对状态（Redis Hash）
- ListState - 列表状态（Redis List）
- SetState - 集合状态（Redis Set）
- 状态持久化和恢复

**关键类**: `RedisStateBackend.java`, `RedisValueState.java`, `RedisMapState.java` (7 个文件)

#### **checkpoint** - 检查点机制
分布式检查点协调和存储，提供容错保证。

**实现状态**: ✅ 完成 - 生产可用

**职责：**
- 检查点协调（分布式协调）
- 状态快照（异步快照）
- 故障恢复（从检查点恢复）
- 检查点存储（Redis 持久化）

**关键类**: `RedisCheckpointCoordinator.java` (197行), `RedisCheckpointStorage.java`, `DefaultCheckpoint.java` (5 个文件)

#### **watermark** - 水位线机制
事件时间处理，处理乱序数据。

**实现状态**: 🚧 部分完成 - 生成器已实现，待集成

**职责：**
- Watermark 生成（有序、乱序）
- 延迟数据处理
- 时间戳分配
- 多种 Watermark 策略

**关键类**: `AscendingTimestampWatermarkGenerator.java`, `BoundedOutOfOrdernessWatermarkGenerator.java` (3 个文件)

### **Tier 3: 功能模块层**

#### **window** - 窗口操作
各种窗口类型和触发器，支持基于时间和计数的窗口。

**实现状态**: 🚧 部分完成 - 窗口逻辑已实现，待集成到流处理运行时

**职责：**
- 滚动窗口（Tumbling）
- 滑动窗口（Sliding）
- 会话窗口（Session）
- 计数窗口（Count）
- 窗口触发器和淘汰器

**关键类**: `TumblingWindow.java`, `SlidingWindow.java`, `SessionWindow.java`, `EventTimeTrigger.java` (8 个文件)

#### **aggregation** - 聚合函数
丰富的聚合函数库和窗口聚合支持，基于 Redis 实现。

**实现状态**: ✅ 完成 - 生产可用

**职责：**
- 基础聚合（Sum, Count, Avg, Min, Max）
- PV/UV 统计（Redis HyperLogLog）
- TopK 排行榜（Redis Sorted Set）
- 分位数计算
- 窗口聚合（滚动窗口、滑动窗口）

**关键类**: `WindowAggregator.java` (177行), `PVCounter.java`, `TopKAnalyzer.java`, `SumFunction.java` (12 个文件)

#### **table** - 流表二元性
KTable 和 KStream，支持流表互转和表操作。

**实现状态**: 🚧 部分完成 - 内存版已实现，Redis 持久化版开发中

**职责：**
- KTable - 可更新的表
- KGroupedTable - 分组表
- 流表转换
- 表操作（map, filter, join）

**关键类**: `KTable.java` (127行), `InMemoryKTable.java` (157行), `StreamTableConverter.java` (5 个文件)

#### **join** - Join 操作
时间窗口内的流式 Join，支持多种 Join 类型。

**实现状态**: ✅ 完成 - 生产可用

**职责：**
- Stream-Stream Join（时间窗口）
- Join 类型（INNER, LEFT, RIGHT, FULL_OUTER）
- 状态缓冲（Redis 存储）
- Join 窗口管理

**关键类**: `StreamJoiner.java` (187行), `JoinConfig.java`, `JoinWindow.java` (6 个文件)

#### **cdc** - 变更数据捕获
从数据库捕获变更事件，支持多种数据源。

**实现状态**: ✅ 完成 - 生产可用

**职责：**
- MySQL Binlog CDC（实时捕获）
- PostgreSQL 逻辑复制
- 数据库轮询 CDC
- 变更事件路由和转换
- 健康监控和指标

**关键类**: `MySQLBinlogCDCConnector.java` (315行), `PostgreSQLLogicalReplicationCDCConnector.java`, `CDCManager.java` (13 个文件)

#### **sink** - 数据输出连接器
多种数据汇连接器。

**实现状态**: 🚧 部分完成 - 基础连接器已实现，企业级连接器开发中

**职责：**
- PrintSink - 控制台输出
- FileSink - 文件输出
- CollectionSink - 集合输出

**关键类**: `PrintSink.java` (91行), `FileSink.java`, `CollectionSink.java` (3 个文件)

**待开发**: Elasticsearch Sink, HBase Sink, Kafka Sink

#### **source** - 数据输入连接器
多种数据源连接器。

**实现状态**: 🚧 部分完成 - 基础连接器已实现，企业级连接器开发中

**职责：**
- CollectionSource - 集合数据源
- FileSource - 文件数据源
- GeneratorSource - 测试数据生成

**关键类**: `CollectionSource.java` (44行), `FileSource.java`, `GeneratorSource.java` (3 个文件)

**待开发**: IoT Device Source, HTTP API Source, Kafka Source

### **Tier 4: 高级功能层**

#### **reliability** - 可靠性保证
流处理的可靠性保证机制，提供重试和故障处理。

**实现状态**: ✅ 完成 - 生产可用

**职责：**
- 重试机制（指数退避、最大重试次数）
- 死信队列管理
- 故障策略（重试、跳过、DLQ）
- 失败元素追踪

**关键类**: `RetryExecutor.java` (94行), `RetryPolicy.java`, `DeadLetterQueue.java` (6 个文件)

**待开发**: Bloom Filter 去重、Exactly-once 语义、背压控制

#### **cep** - 复杂事件处理
模式匹配和复杂事件检测。

**实现状态**: 🚧 部分完成 - 基础模式匹配已实现，高级操作开发中

**职责：**
- 模式定义（Pattern Builder）
- 序列检测（简单模式）
- 时间约束（超时检测）
- 事件匹配

**关键类**: `PatternMatcher.java` (127行), `Pattern.java`, `PatternBuilder.java` (5 个文件)

**待开发**: Kleene closure、复杂条件组合、followedBy/within 操作符

### **Tier 5: 集成层**

#### **metrics** - 监控指标
监控指标收集和暴露。

**实现状态**: 🚧 部分完成 - 指标收集已实现，Prometheus 集成开发中

**职责：**
- 指标收集（Counter, Gauge, Histogram, Timer）
- 内存指标存储
- 指标注册管理
- 计时器支持

**关键类**: `MetricCollector.java` (93行), `InMemoryMetricCollector.java`, `MetricRegistry.java` (6 个文件)

**待开发**: Prometheus Exporter、Micrometer 集成、流处理指标暴露

#### **spring-boot-starter** - Spring Boot 集成
Spring Boot 自动配置和集成。

**实现状态**: ✅ 完成 - 生产可用

**职责：**
- 自动配置（Registry、Discovery、ConfigService）
- 配置属性绑定
- Bean 自动装配
- 注解支持（@EnableStreaming, @ServiceChangeListener）
- 自动服务注册

**关键类**: `StreamingAutoConfiguration.java` (106行), `StreamingProperties.java`, `@EnableStreaming.java` (6 个文件)

#### **examples** - 示例代码
各种使用示例和最佳实践。

**实现状态**: 🚧 部分完成 - 基础示例已提供

**职责：**
- 服务注册发现示例
- 消息队列示例
- 综合流处理示例

**关键类**: `ServiceRegistryExample.java`, `MessageQueueExample.java`, `ComprehensiveStreamingExample.java` (3 个文件)

## 🎯 快速开始

### 1. 环境要求

- Java 17+
- Redis 6.0+
- Gradle 7.0+

### 2. 添加依赖

**核心模块（根据需要选择）：**
```gradle
dependencies {
    // 消息队列
    implementation 'io.github.cuihairu.redis-streaming:mq:0.1.0'

    // 服务注册发现（支持 metadata 比较运算符过滤）
    implementation 'io.github.cuihairu.redis-streaming:registry:0.1.0'

    // 配置中心（版本化配置、变更通知）
    implementation 'io.github.cuihairu.redis-streaming:config:0.1.0'

    // 状态管理
    implementation 'io.github.cuihairu.redis-streaming:state:0.1.0'

    // 检查点
    implementation 'io.github.cuihairu.redis-streaming:checkpoint:0.1.0'

    // 窗口聚合
    implementation 'io.github.cuihairu.redis-streaming:aggregation:0.1.0'

    // CDC
    implementation 'io.github.cuihairu.redis-streaming:cdc:0.1.0'
}
```

**Spring Boot 集成（推荐）：**
```gradle
dependencies {
    implementation 'io.github.cuihairu.redis-streaming:spring-boot-starter:0.1.0'
    // 自动引入 registry、config、mq 等核心模块
}
```

### 3. 配置 Redis

```java
Config config = new Config();
config.useSingleServer()
    .setAddress("redis://127.0.0.1:6379")
    .setConnectionPoolSize(20)
    .setConnectionMinimumIdleSize(5);

RedissonClient redissonClient = Redisson.create(config);
```

### 4. 快速示例

#### 服务注册发现（支持 Metadata 过滤）
```java
import io.github.cuihairu.redis.streaming.registry.*;

// 创建服务注册
NamingService namingService = new RedisNamingService(redissonClient);
namingService.start();

// 注册服务（带 metadata）
Map<String, String> metadata = new HashMap<>();
metadata.put("version", "1.0.0");
metadata.put("weight", "100");
metadata.put("cpu_usage", "45");
metadata.put("region", "us-east-1");

ServiceInstance instance = DefaultServiceInstance.builder()
    .serviceName("order-service")
    .instanceId("order-service-001")
    .host("localhost")
    .port(8080)
    .protocol(StandardProtocol.HTTP)
    .metadata(metadata)
    .build();

namingService.register(instance);

// 基础服务发现
List<ServiceInstance> allInstances = namingService.getHealthyInstances("order-service");

// 高级过滤：使用比较运算符
Map<String, String> filters = new HashMap<>();
filters.put("weight:>=", "80");           // 权重 >= 80
filters.put("cpu_usage:<", "70");         // CPU使用率 < 70%
filters.put("region", "us-east-1");       // 区域精确匹配
filters.put("status:!=", "maintenance");  // 排除维护状态

List<ServiceInstance> filteredInstances =
    namingService.getInstancesByMetadata("order-service", filters);

// 监听服务变更
namingService.subscribe("order-service", (serviceName, action, instance, allInstances) -> {
    System.out.println("Service changed: " + action + " - " + instance.getInstanceId());
});
```

#### 配置中心
```java
import io.github.cuihairu.redis.streaming.config.*;

// 创建配置服务
ConfigService configService = new RedisConfigService(redissonClient);
configService.start();

// 发布配置
configService.publishConfig(
    "database.config",              // 配置 ID
    "DEFAULT_GROUP",                // 配置组
    "db.url=jdbc:mysql://localhost:3306/mydb\ndb.username=root",
    "Initial database configuration" // 描述
);

// 获取配置
String dbConfig = configService.getConfig("database.config", "DEFAULT_GROUP");
System.out.println("Database config: " + dbConfig);

// 监听配置变更（自动热加载）
configService.addListener("database.config", "DEFAULT_GROUP",
    (dataId, group, content) -> {
        System.out.println("Configuration updated: " + content);
        // 重新加载数据库连接池等
        reloadDatabaseConnection(content);
    }
);

// 查询历史版本
List<ConfigHistory> history = configService.getHistory("database.config", "DEFAULT_GROUP", 5);
for (ConfigHistory h : history) {
    System.out.println("Version " + h.getVersion() + ": " + h.getDescription());
}

// 删除配置
configService.removeConfig("database.config", "DEFAULT_GROUP");
```

#### 消息队列
```java
import io.github.cuihairu.redis.streaming.mq.*;

// 生产者
MessageProducer producer = MessageQueueFactory.createProducer(
    redissonClient, "order-events"
);
producer.send(new Message("order-123", orderData));

// 消费者
MessageConsumer consumer = MessageQueueFactory.createConsumer(
    redissonClient, "order-events", "order-processor-group"
);
consumer.consume(message -> {
    // 处理消息
    return MessageHandleResult.SUCCESS;
});
```

#### 窗口聚合
```java
import io.github.cuihairu.redis.streaming.aggregation.*;

// 创建窗口聚合器
WindowAggregator aggregator = new WindowAggregator(
    redissonClient,
    "page_views",
    new TumblingWindow(Duration.ofMinutes(5))
);

// 添加数据
aggregator.add("product-123", 1.0, System.currentTimeMillis());

// 获取聚合结果
Map<String, Double> result = aggregator.getResult("window-key");
```

#### CDC 数据捕获
```java
import io.github.cuihairu.redis.streaming.cdc.*;

// 配置 MySQL Binlog CDC
CDCConfiguration config = CDCConfigurationBuilder.builder()
    .host("localhost")
    .port(3306)
    .database("ecommerce")
    .username("cdc_user")
    .password("password")
    .includeTables("orders", "products")
    .build();

// 创建 CDC 连接器
CDCConnector connector = new MySQLBinlogCDCConnector(config);

// 监听变更事件
connector.addListener(event -> {
    System.out.println("Change detected: " + event);
});

connector.start();
```

## 📊 技术栈

### 核心依赖
- **Redisson 3.52.0** - Redis 客户端，用于分布式操作
- **Jackson 2.17.0** - JSON 序列化/反序列化
- **Lombok 1.18.34** - 代码生成，减少样板代码
- **SLF4J 1.7.36** - 日志抽象

### 测试框架
- **JUnit Jupiter 5.9.2** - 单元测试
- **Mockito 4.6.1** - Mock 框架

### 构建工具
- **Gradle 7.0+** - 构建工具
- **Java 17** - 编译目标版本

## 🗺️ 路线图

### 📊 模块完成情况总览

**已完成**: 17/20 模块（85.0%）✅
**部分完成**: 2/20 模块（10.0%）🚧
**未开始**: 1/20 模块（5.0%）📋

---

### ✅ 已完成模块（生产可用）

#### Tier 1: 核心抽象层
- [x] **core** - 核心 API 定义 (23 个文件)
  - 完整的流处理 API 抽象
  - 状态、检查点、水位线、窗口抽象

#### Tier 2: 基础设施层
- [x] **mq** - 消息队列 (9 个文件)
  - Redis Streams 完整实现
  - 消费者组、DLQ、异步支持
- [x] **registry** - 服务注册发现 (25 个文件)
  - 服务注册、发现、健康检查
  - 多协议支持（HTTP/HTTPS/TCP/WebSocket/gRPC）
  - **Metadata 比较运算符过滤**（`>`, `>=`, `<`, `<=`, `!=`, `==`）
  - 基于权重、CPU、延迟等智能负载均衡
- [x] **config** - 配置中心 (10 个文件)
  - 配置发布、获取、删除
  - 配置版本化和历史记录
  - 配置变更通知（Redis Pub/Sub）
  - 配置监听器和热加载
- [x] **state** - 状态管理 (7 个文件)
  - 4 种状态类型（Value、Map、List、Set）
  - Redis 持久化
- [x] **checkpoint** - 检查点 (5 个文件)
  - 分布式协调、快照、恢复
- [x] **watermark** - 水位线 (3 个文件)
  - 水位线生成器实现
- [x] **window** - 窗口分配 (8 个文件)
  - 滚动、滑动、会话窗口

#### Tier 3: 功能模块层
- [x] **aggregation** - 聚合函数 (12 个文件)
  - 窗口聚合、PV/UV、TopK
- [x] **table** - 流表二元性 (6 个文件)
  - 内存版 & Redis 持久化版 KTable
- [x] **join** - Join 操作 (6 个文件)
  - 时间窗口 Join、4 种 Join 类型
- [x] **cdc** - CDC (13 个文件)
  - MySQL、PostgreSQL、轮询 CDC
- [x] **sink** - 输出连接器 (6 个文件)
  - Kafka Sink、Redis Stream/Hash Sink
- [x] **source** - 输入连接器 (6 个文件)
  - Kafka Source、HTTP API Source、Redis List Source

#### Tier 4: 高级功能层
- [x] **cep** - 复杂事件处理 (9 个文件)
  - Kleene closure、高级模式操作
- [x] **reliability** - 可靠性保证 (10 个文件)
  - 重试机制、DLQ、Bloom Filter 去重

#### Tier 5: 集成层
- [x] **metrics** - 监控指标 (8 个文件)
  - Prometheus Exporter、指标收集器
- [x] **spring-boot-starter** - Spring Boot 集成 (6 个文件)
  - 完整自动配置、注解支持
- [x] **examples** - 示例代码 (3 个文件)
  - 服务注册发现、消息队列示例

---

### 🚧 部分完成模块

#### Tier 5: 集成层
- [ ] **examples** - 示例代码 (3 个文件)
  - ✅ 基础示例已实现
  - 🚧 需要更多综合示例

---

### 📋 未开始模块

#### Tier 1: 核心抽象层
- [ ] **runtime** - 流处理运行时引擎
  - 📋 架构规划已完成
  - 📋 详见 `runtime/README.md`
  - 📋 当前建议使用独立模块（mq、state、aggregation、cep）

---

### 🎯 下一步优先级

#### 高优先级（可选增强）
1. **Runtime 运行时引擎** - 统一流处理执行引擎
   - Phase 1: 简单内存运行时
   - Phase 2: 分布式调度
   - Phase 3: 与 Window、Watermark 完整集成
   - 注：当前独立模块已满足大部分使用场景

#### 中优先级（功能增强）
2. **企业级连接器扩展**
   - Elasticsearch Sink
   - HBase Sink
   - IoT Device Source

## 📚 文档

### 快速开始
- [快速入门教程](QUICK_START.md) - 5分钟上手指南
- [完成报告](COMPLETION_REPORT.md) - 项目开发完成报告

### 设计文档
- [架构设计](docs/ARCHITECTURE.md) - 整体架构设计
- [项目总结](PROJECT_SUMMARY.md) - 详细功能说明

### 部署运维
- [部署指南](docs/DEPLOYMENT.md) - 生产环境部署
- [性能优化](docs/PERFORMANCE.md) - 性能调优指南

### 开发指南
- [开发文档](CLAUDE.md) - 开发者指南
- [文档中心](docs/README.md) - 完整文档索引

## 🤝 贡献

欢迎贡献代码、报告问题或提出建议！

1. Fork 项目
2. 创建特性分支 (`git checkout -b feature/AmazingFeature`)
3. 提交更改 (`git commit -m 'Add some AmazingFeature'`)
4. 推送到分支 (`git push origin feature/AmazingFeature`)
5. 开启 Pull Request

## 📄 许可证

本项目采用 Apache License 2.0 许可证 - 查看 [LICENSE](LICENSE) 文件了解详情

## 📞 联系

- 项目地址: https://github.com/cuihairu/redis-streaming
- 问题反馈: https://github.com/cuihairu/redis-streaming/issues

---

**当前版本**: 0.1.0
**最后更新**: 2025-01-12
**完成度**: 17/20 模块完成（85.0%），2/20 模块部分完成（10.0%），1/20 模块规划中（5.0%）

### 📝 版本说明

**0.1.0** (2025-01-12) - 初始版本
- ✅ 核心 API 抽象：完整的流处理 API 定义（DataStream、KeyedStream、WindowedStream）
- ✅ 基础设施完成：MQ、Registry（含 Metadata 比较运算符）、Config、State、Checkpoint、Watermark、Window
- ✅ **服务注册发现增强**：支持 Metadata 比较运算符过滤（`>`, `>=`, `<`, `<=`, `!=`, `==`），智能负载均衡
- ✅ **配置中心完成**：配置版本化、变更通知、历史记录、监听器支持
- ✅ 功能模块完成：Aggregation、Table (含 Redis 持久化)、Join、CDC
- ✅ 可靠性模块：Reliability（含 Bloom Filter 去重）
- ✅ 连接器完成：Kafka/Redis Sink、Kafka/HTTP/Redis Source
- ✅ CEP 完成：复杂事件处理（含 Kleene closure、高级模式操作）
- ✅ 监控集成：Prometheus Exporter、指标收集器
- ✅ Spring Boot 自动配置（含 @ServiceChangeListener 注解支持）
- 📋 Runtime 模块：架构规划完成，建议使用独立模块（20个模块中17个已完成）
