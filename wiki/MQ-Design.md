# MQ 模块设计

[中文](MQ-Design) | [English](MQ-Design-en)

---

## 模块目标

封装 Redis Stream 的原生能力,提供一个轻量级、易于使用的消息队列抽象,支持生产者/消费者模型、消费者组、消息确认 (ACK) 和死信队列 (DLQ) 机制。

## 核心概念

- **Stream (流)**: Redis 中的数据结构,用于存储消息序列。每个 Stream 对应一个 Topic
- **Message (消息)**: Stream 中的一条记录,包含 ID 和键值对数据
- **Producer (生产者)**: 负责向 Stream 发送消息
- **Consumer (消费者)**: 负责从 Stream 读取和处理消息
- **Consumer Group (消费者组)**: 允许多个消费者共同消费一个 Stream,每个消息只会被组内一个消费者处理
- **Pending Entry List (PEL)**: 消费者组中,已被消费者读取但尚未 ACK 的消息列表
- **Dead Letter Queue (DLQ)**: 用于存放处理失败、达到最大重试次数的消息

## 核心组件设计

### 1. MessageProducer (消息生产者)

**职责**: 将消息发送到指定的 Redis Stream

**核心方法**:
```java
public interface MessageProducer {
    // 同步发送消息
    String send(Message message);

    // 异步发送消息
    CompletableFuture<String> sendAsync(Message message);

    // 批量发送消息
    List<String> sendBatch(List<Message> messages);
}
```

**内部实现**:
- 使用 Redisson 的 `RStream` 接口进行 `XADD` 操作
- 支持消息序列化（默认 Jackson JSON）
- 自动注册 Topic 到 TopicRegistry

### 2. MessageConsumer (消息消费者)

**职责**: 从指定的 Redis Stream 消费消息,支持消费者组模式

**核心方法**:
```java
public interface MessageConsumer {
    // 订阅消息
    void subscribe(MessageHandler handler);

    // 确认消息
    void ack(String messageId);

    // 移动到死信队列
    void moveToDLQ(String messageId, String reason);

    // 重试消息
    void retry(String messageId);
}
```

**内部实现**:
- 使用 Redisson 的 `RStream` 接口进行 `XREADGROUP` 操作
- 自动创建消费者组（如果不存在）
- 循环拉取消息,每次拉取一定数量
- 配置最大重试次数
- PEL 检查: 定期检查 PEL 中长时间未 ACK 的消息
- Auto-trim: 可配置 Stream 的最大长度

### 3. MessageHandler 接口

```java
public interface MessageHandler {
    /**
     * 处理从 Redis Stream 接收到的消息
     * @param message 消息对象
     * @return true 表示处理成功, false 表示处理失败
     */
    boolean handle(Message message);
}
```

### 4. MessageQueueAdmin (管理接口)

**职责**: 提供消息队列的管理和监控能力

**核心方法**:
```java
public interface MessageQueueAdmin {
    // 队列信息
    QueueInfo getQueueInfo(String topic);
    List<String> listAllTopics();

    // 消费者组管理
    List<ConsumerGroupInfo> getConsumerGroups(String topic);
    boolean createConsumerGroup(String topic, String group);
    boolean deleteConsumerGroup(String topic, String group);

    // 消息管理
    List<PendingMessage> getPendingMessages(String topic, String group, int limit);
    List<Message> getMessages(String topic, int limit);

    // 队列操作
    long trimQueue(String topic, long maxLen);
    boolean deleteTopic(String topic);
    boolean resetConsumerGroupOffset(String topic, String group, String messageId);
}
```

**管理功能**:
- 查看队列长度、消息数
- 查看消费者组信息
- 查看 PEL 中的待确认消息
- 修剪队列、删除 Topic
- 重置消费者组偏移量

### 5. TopicRegistry (Topic 注册表)

**职责**: 维护所有 Topic 的索引,避免使用 `keys` 或 `scan` 命令

**实现方式**:
```redis
# 使用 Redis SET 存储所有 Topic
SADD streaming:mq:topics:registry "order_events"
SADD streaming:mq:topics:registry "payment_events"
```

**核心方法**:
```java
public class TopicRegistry {
    // 注册 Topic
    boolean registerTopic(String topic);

    // 注销 Topic
    boolean unregisterTopic(String topic);

    // 获取所有 Topic
    Set<String> getAllTopics();

    // 检查 Topic 是否存在
    boolean topicExists(String topic);
}
```

## 死信队列 (DLQ) 机制

### DLQ Stream

为每个主 Topic 创建一个对应的 DLQ Stream (例如 `topic_dlq`)

### 消息结构

DLQ 消息中包含:
- 原始消息内容
- 失败原因
- 失败时间
- 重试次数
- 原始 Topic 名称

### 处理流程

1. 消费者处理消息失败
2. 消息进入重试机制
3. 达到最大重试次数后,消费者将消息发送到 DLQ Stream
4. DLQ 消费者可以手动或自动处理这些死信消息

## 示例代码

### 生产者示例

```java
// 创建生产者
MessageProducer producer = new RedisMessageProducer(redissonClient, "order_events");

// 发送消息
Message message = Message.builder()
    .topic("order_events")
    .data(Map.of("orderId", "12345", "status", "created"))
    .build();

CompletableFuture<String> future = producer.sendAsync(message);
```

### 消费者示例

```java
// 创建消费者
MessageConsumer consumer = new RedisMessageConsumer(
    redissonClient,
    "order_events",
    "payment_group",
    "consumer-1"
);

// 订阅消息
consumer.subscribe(message -> {
    try {
        // 处理消息
        processOrder(message);
        return true; // 处理成功
    } catch (Exception e) {
        log.error("Failed to process message", e);
        return false; // 处理失败,将重试
    }
});
```

### Admin 示例

```java
// 创建 Admin
MessageQueueAdmin admin = new RedisMessageQueueAdmin(redissonClient);

// 查看所有 Topic
List<String> topics = admin.listAllTopics();

// 查看队列信息
QueueInfo info = admin.getQueueInfo("order_events");
System.out.println("Queue length: " + info.getLength());
System.out.println("Consumer groups: " + info.getConsumerGroups());

// 查看 PEL
List<PendingMessage> pending = admin.getPendingMessages("order_events", "payment_group", 10);
```

## 配置

```yaml
streaming:
  mq:
    # 消费者配置
    consumer:
      batch-size: 10              # 每次拉取消息数量
      poll-timeout: 5000          # 拉取超时时间(ms)
      max-retry: 3                # 最大重试次数
      retry-delay: 1000           # 重试间隔(ms)

    # 生产者配置
    producer:
      max-len: 10000              # Stream 最大长度
      auto-trim: true             # 自动修剪

    # DLQ 配置
    dlq:
      enabled: true               # 启用死信队列
      suffix: "_dlq"              # DLQ Topic 后缀
```

## 性能优化

### 1. 批量操作
- 批量发送消息
- 批量确认消息

### 2. Lua 脚本
- 使用 Lua 脚本实现原子操作
- 减少网络往返次数

### 3. 连接池
- 使用 Redisson 的连接池
- 复用 Redis 连接

### 4. 自动修剪
- 定期修剪 Stream
- 控制内存使用

---

**版本**: 0.1.0
**最后更新**: 2025-10-13

🔗 相关文档:
- [[整体架构|Architecture]]
- [[详细设计|Design]]
- [[Spring Boot 集成|Spring-Boot-Starter]]
