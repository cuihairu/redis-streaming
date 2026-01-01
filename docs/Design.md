# Redis-Streaming 详细设计

[中文](Design) | [English](Design-en)

---

## 项目概述

Redis-Streaming 是一个基于 Redis 实现的轻量级流处理框架,提供简单易用的 API 来处理实时数据流、消息队列、服务注册发现等功能。特别适合中小规模的分布式系统。

## 核心模块

### 1. Redis MQ (消息队列)

**功能**: 基于 Redis Streams 的消息队列实现

**核心特性**:
- 生产者/消费者组模式
- 消息确认 (ACK) 机制
- 死信队列 (DLQ) 支持
- 消息重试策略
- 自动修剪 (Auto-trim)

**示例代码**:
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

**适用场景**: 订单状态变更、库存扣减、消息通知等异步流程

### 2. Registry (服务注册发现)

**功能**: 基于 Redis Hash + Pub/Sub 的服务注册与发现

**核心角色**:
- **服务提供者**: 注册服务、发送心跳、服务下线
- **服务消费者**: 服务发现、服务订阅、负载均衡
- **注册中心**: 维护服务列表、健康检查、推送变更

**示例代码**:
```java
// 服务注册
ServiceRegistry registry = new RedisRegistry("user-service");
registry.register("instance-1", "192.168.1.101:8080");

// 服务发现
List<InstanceInfo> instances = registry.discover("user-service");
```

**适用场景**: 微服务动态扩缩容、服务健康监控

### 3. Aggregation (流式聚合)

**功能**: 时间窗口聚合和实时统计

**支持场景**:
- PV/UV 统计
- Top-K 排行榜
- 滑动窗口平均值

**示例代码**:
```java
// 统计每5分钟的PV
PVAggregator aggregator = new PVAggregator("user_clicks", Duration.ofMinutes(5));
aggregator.addListener(result -> System.out.println("PV: " + result));

// 事件触发
aggregator.onEvent(new UserClickEvent("user1", "/home"));
```

### 4. CDC (变更数据捕获)

**功能**: 捕获数据库变更并转为流事件

**支持数据库**:
- MySQL Binlog
- PostgreSQL Logical Replication

**示例代码**:
```java
CDCSource cdcSource = MySQLCDCSource.builder()
    .hostname("localhost")
    .port(3306)
    .databaseList("inventory")
    .build();

cdcSource.subscribe(event -> {
    System.out.println("DB Change: " + event);
});
```

### 5. Sink/Source (连接器)

**Sink 连接器** (数据输出):
- Elasticsearch
- HBase
- Kafka
- Redis

**Source 连接器** (数据输入):
- IoT 设备
- HTTP API
- 文件系统
- Kafka

### 6. Reliability (可靠性保证)

**功能**:
- 消息重试机制
- 幂等性保证
- 死信队列
- 消息去重

### 7. CEP (复杂事件处理)

**功能**: 识别事件模式和序列

**示例**:
```java
// 检测用户连续登录失败3次
Pattern pattern = Pattern.begin("start")
    .where(e -> e.getType().equals("LOGIN_FAILED"))
    .times(3)
    .within(Duration.ofMinutes(5));
```

### 8. Metrics (监控集成)

**功能**: Prometheus 指标暴露

**监控指标**:
- 消息堆积数
- 处理延迟
- 消费速率
- 服务健康状态

## 技术栈

| 组件 | 用途 | 是否必需 |
|------|------|---------|
| Redis 6.0+ | Stream/TimeSeries 基础存储 | ✅ |
| Redisson 3.52.0 | 分布式锁、线程安全连接 | ✅ |
| Jackson 2.17.0 | JSON 序列化 | ✅ |
| Spring Boot 3.x | Starter 模块依赖 | 可选 |
| Lombok | 简化 POJO 代码 | 推荐 |

## 项目亮点

| 模块 | 独特价值 |
|------|---------|
| 整体框架 | 唯一专注纯 Redis 流式处理的轻量级 Java 库 |
| MQ 模块 | 弥补 Redis Stream 原生 API 的不足（死信队列、异步 ACK） |
| 注册中心 | 比 ZooKeeper 更轻量，比 Consul 更省资源 |
| Admin 角色 | 提供完整的管理和监控能力 |

---

**版本**: 0.1.0
**最后更新**: 2025-10-13

🔗 相关文档:
- [[整体架构|Architecture]]
- [[Registry 设计|Registry-Design]]
- [[MQ 设计|MQ-Design]]
- [[Spring Boot 集成|Spring-Boot-Starter]]
