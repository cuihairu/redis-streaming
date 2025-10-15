# Streaming 项目设计文档

## 项目概述

Streaming 是一个基于 Redis 实现的轻量级流处理框架，旨在提供简单易用的 API 来处理实时数据流、消息队列、服务注册发现等功能。该框架特别适合中小规模的分布式系统，提供了比传统解决方案更轻量、更易于集成的替代方案。

## 1. 项目架构与模块划分

### 1.1 核心模块 (streaming-core)

| 模块名          | 功能描述                                                              | 使用场景                                                     |
| --------------- | --------------------------------------------------------------------- | ------------------------------------------------------------ |
| Redis MQ        | 封装 Redis Stream 的消息队列（生产者/消费者组、ACK、死信队列）        | 服务解耦、异步任务、事件驱动（如订单支付后触发通知）         |
| Registry        | 基于 Redis Hash + Pub/Sub 的服务注册与发现（心跳检测、节点变更通知）  | 微服务动态扩缩容、服务健康监控                               |
| TimeSeries      | 利用 Redis Sorted Set/TimeSeries 模块的时间窗口聚合（如滑动平均值统计） | 实时监控（API QPS）、用户行为分析（每分钟点击量）            |
| Log Aggregator  | 通过 Redis List/Stream 收集日志，支持转发到 ELK                       | 分布式系统日志统一收集、错误追踪                             |
| Config Center   | 动态加载和刷新配置（如 Redis 连接信息、MQ Topic 配置）               | 统一配置管理、服务动态调整                                   |
| Utils           | 连接池管理、JSON 序列化、重试策略等基础工具                           | 所有模块依赖的基础设施                                       |

### 1.2 扩展模块 (streaming-spring-boot-starter)

| 功能         | 说明                                                     |
| ------------ | -------------------------------------------------------- |
| 自动配置     | 根据 application.yml 自动初始化 Redis 连接和模块 Bean    |
| 注解支持     | @StreamListener 声明消息处理器、@EnableRedisRegistry 启用注册中心 |
| 健康检查     | 集成 Spring Actuator，暴露 Redis 连接状态和消息堆积数    |

### 1.3 示例模块 (streaming-examples)

| 示例             | 演示场景                                                     |
| ---------------- | ------------------------------------------------------------ |
| 电商订单流程     | 下单 → Redis MQ 异步扣库存 → 注册中心通知物流服务            |
| API 监控看板     | 收集接口调用 → TimeSeries 聚合 → 可视化展示（Grafana）       |
| 分布式日志收集   | 多服务日志 → Log Aggregator → Elasticsearch 存储             |

## 2. 核心模块详细设计

### 2.1 Redis MQ

Redis MQ 模块基于 Redis Stream 实现，提供轻量级、易于使用的消息队列抽象，支持生产者/消费者模型、消费者组、消息确认和死信队列机制。

**核心组件：**
- RedisMQProducer：消息生产者，负责将消息发送到指定的 Redis Stream
- RedisMQConsumer：消息消费者，从指定的 Redis Stream 消费消息，支持消费者组模式
- MessageHandler：消息处理接口，用于处理从 Redis Stream 接收到的消息

**示例代码：**
```java
// 生产者
RedisMQProducer producer = new RedisMQProducer("order_events");
producer.sendAsync(JSON.toJson(orderEvent));

// 消费者（带死信处理）
RedisMQConsumer consumer = new RedisMQConsumer("order_events", "payment_group");
consumer.subscribe(event -> {
    if (processFailed(event)) {
        consumer.moveToDLQ(event); // 转入死信队列
    }
});
```

**适用场景：** 订单状态变更、库存扣减、消息通知等异步流程。

### 2.2 Registry

Registry 模块参考 Nacos 设计，基于 Redis Hash + Pub/Sub 实现服务注册与发现，支持心跳检测和节点变更通知。

**核心角色：**
- 服务提供者：注册服务、发送心跳、服务下线
- 服务消费者：服务发现、服务订阅、负载均衡、服务调用
- 注册中心：接收注册请求、维护服务列表、处理心跳、健康检查、提供查询接口、推送服务变更

**示例代码：**
```java
// 服务注册
ServiceRegistry registry = new RedisRegistry("user-service");
registry.register("instance-1", "192.168.1.101:8080");

// 服务发现
List<InstanceInfo> instances = registry.discover("user-service");
```

**适用场景：** K8s 外自建微服务、边缘计算节点管理。

### 2.3 TimeSeries

TimeSeries 模块利用 Redis Sorted Set/TimeSeries 模块实现时间窗口聚合，用于实时数据分析和监控。

**示例代码：**
```java
// 记录API响应时间（时间窗口：1分钟）
TimeSeriesRecorder.record("api.login", 150); // 150ms

// 获取P99响应时间
double p99 = TimeSeriesAnalyzer.getPercentile("api.login", 99, Duration.ofMinutes(1));
```

**适用场景：** 实时业务指标监控、IoT 传感器数据分析。

### 2.4 Log Aggregator

Log Aggregator 模块通过 Redis List/Stream 收集日志，支持转发到 ELK，提供轻量级的日志收集解决方案。

**示例代码：**
```java
// 发送日志（自动附加服务名+IP）
LogAggregator.pushLog(LogLevel.ERROR, "用户支付失败", orderId);

// 后台线程异步转发到ELK
```

**适用场景：** 替代 Filebeat 轻量级日志收集、开发环境快速调试。

### 2.5 Config Center

Config Center 模块提供动态配置加载和刷新功能，支持灰度发布和流量切换。

| 功能     | 包名                                     | 说明                                                         |
| -------- | ---------------------------------------- | ------------------------------------------------------------ |
| 配置管理 | `com.cuihairu.streaming.config`          | 动态加载和刷新配置（如 Redis 连接信息、MQ Topic 配置）       |
| 灰度发布 | `com.cuihairu.streaming.config.gray`     | 基于配置实现服务灰度发布和流量切换                           |

## 3. 技术栈与依赖

| 组件         | 用途                       | 是否必需 |
| ------------ | -------------------------- | -------- |
| Redis 5.0+   | Stream/TimeSeries 基础存储 | ✅       |
| Redisson     | 分布式锁、线程安全连接     | ✅       |
| Jackson      | JSON 序列化                | ✅       |
| Spring Boot  | starter 模块依赖           | 可选     |
| Lombok       | 简化 POJO 代码             | 推荐     |

## 4. 实施路线图

### 4.1 优先级排序
1. 先实现 redis-mq 和 utils（高频需求）
2. 再补充 registry 和 timeseries（差异化能力）
3. 最后做 log-aggregator（需对接 ELK API）

### 4.2 关键优化点
- **内存控制**：为 RedisMQConsumer 添加 autoTrim() 方法，自动清理已处理消息
- **可观测性**：在 utils 中集成 Micrometer，暴露消息堆积数指标
- **多云适配**：通过 RedisURI 解析器支持阿里云/腾讯云 Redis 连接串

### 4.3 文档与示例
- 在 streaming-examples 中提供 Docker Compose 环境（包含 Redis + Grafana）
- 为每个模块编写单元测试（如模拟 Redis 宕机时的重试逻辑）

## 5. 项目亮点

| 模块       | 独特价值                                                   |
| ---------- | ---------------------------------------------------------- |
| 整体框架   | 唯一专注纯 Redis 流式处理的轻量级 Java 库                  |
| MQ 模块    | 弥补 Redis Stream 原生 API 的不足（死信队列、异步 ACK）    |
| 注册中心   | 比 ZooKeeper 更轻量，比 Consul 更省资源，适合中小规模集群  |
| 日志收集   | 5 行代码接入，无需 Logstash 复杂配置                       |

## 6. 未来功能扩展规划

### 6.1 实时计算与流处理

| 功能                 | 包名                                 | 说明                                                 |
| -------------------- | ------------------------------------ | ---------------------------------------------------- |
| 流式聚合计算         | `com.cuihairu.streaming.aggregation` | 窗口计数、求和、Top-K 等实时聚合（如每5分钟统计PV）  |
| 规则引擎             | `com.cuihairu.streaming.rules`       | 动态规则匹配（如风控规则触发告警）                   |
| 复杂事件处理（CEP）  | `com.cuihairu.streaming.cep`         | 识别事件模式（如"用户连续登录失败3次"）             |

### 6.2 数据同步与连接器

| 功能             | 包名                             | 说明                                                         |
| ---------------- | -------------------------------- | ------------------------------------------------------------ |
| 数据库 CDC       | `com.cuihairu.streaming.cdc`     | 捕获数据库变更（MySQL Binlog/Oracle LogMiner）并转为流事件   |
| Sink 连接器      | `com.cuihairu.streaming.sink`    | 将流数据写入外部系统（如 HBase、Elasticsearch、Snowflake）   |
| Source 连接器    | `com.cuihairu.streaming.source`  | 从外部系统（如 IoT 设备、HTTP API）拉取数据并转为流          |

### 6.3 可观测性与治理

| 功能           | 包名                               | 说明                                                         |
| -------------- | ---------------------------------- | ------------------------------------------------------------ |
| 分布式追踪     | `com.cuihairu.streaming.tracing`   | 集成 OpenTelemetry，追踪消息全链路（生产→消费→处理）         |
| 指标监控       | `com.cuihairu.streaming.metrics`   | 暴露 Prometheus 指标（如消息堆积数、处理延迟）               |
| 消息审计       | `com.cuihairu.streaming.audit`     | 记录消息的完整生命周期（谁发送、谁消费、何时处理）           |

### 6.4 安全与治理

| 功能       | 包名                               | 说明                                                         |
| ---------- | ---------------------------------- | ------------------------------------------------------------ |
| 消息加密   | `com.cuihairu.streaming.security`  | 支持 TLS 传输加密和消息内容加密（如 AES）                    |
| 权限控制   | `com.cuihairu.streaming.acl`       | 基于角色的 Topic 访问控制（如 Producer/Consumer 权限分离）   |
| 配额管理   | `com.cuihairu.streaming.quota`     | 限制每个客户端的发送/消费速率                                |

### 6.5 流式存储与回溯

| 功能                 | 包名                               | 说明                                                         |
| -------------------- | ---------------------------------- | ------------------------------------------------------------ |
| 流快照（Snapshot）   | `com.cuihairu.streaming.storage`   | 定期将流状态持久化到 S3/HDFS，支持故障恢复                   |
| 消息重放（Replay）   | `com.cuihairu.streaming.replay`    | 按时间戳或 Offset 重新消费历史数据（用于调试或数据修复）     |

### 6.6 智能化扩展

| 功能         | 包名                                 | 说明                                                         |
| ------------ | ------------------------------------ | ------------------------------------------------------------ |
| 异常检测     | `com.cuihairu.streaming.anomaly`     | 基于机器学习自动识别流量突增、消息延迟等异常                 |
| 自动扩缩容   | `com.cuihairu.streaming.autoscale`   | 根据负载动态调整消费者数量或分区数                           |

## 7. 包命名规范

### 7.1 命名一致性原则

1. **功能导向**：所有子包名均以 `streaming` 为根，体现"流式处理框架"的核心定位
2. **避免技术绑定**：如 `cdc` 而非 `mysql_cdc`，保持技术中立
3. **动词+名词结构**：如 `aggregation`（聚合）、`replay`（重放），直接表明功能行为

### 7.2 示例：流式聚合模块的代码结构

```java
// 统计每5分钟的PV
PVAggregator aggregator = new PVAggregator("user_clicks", Duration.ofMinutes(5));
aggregator.addListener(result -> System.out.println("PV: " + result));

// 事件触发
aggregator.onEvent(new UserClickEvent("user1", "/home"));
```

### 7.3 需避免的包名

| 不推荐命名           | 问题                                   |
| -------------------- | -------------------------------------- |
| `com.cuihairu.redis` | 技术绑定（Redis 只是实现之一）         |
| `com.cuihairu.utils` | 过于泛化，无法体现流式特性             |
| `com.cuihairu.queue` | 窄化为队列，忽略时间序列等场景         |