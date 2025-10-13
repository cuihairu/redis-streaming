# Redis MQ 模块设计 (redis-mq Module Design)

## 1. 模块目标

封装 Redis Stream 的原生能力，提供一个轻量级、易于使用的消息队列抽象，支持生产者/消费者模型、消费者组、消息确认 (ACK) 和死信队列 (DLQ) 机制。

## 2. 核心概念

*   **Stream (流):** Redis 中的数据结构，用于存储消息序列。每个 Stream 对应一个 Topic。
*   **Message (消息):** Stream 中的一条记录，包含 ID 和键值对数据。
*   **Producer (生产者):** 负责向 Stream 发送消息。
*   **Consumer (消费者):** 负责从 Stream 读取和处理消息。
*   **Consumer Group (消费者组):** 允许多个消费者共同消费一个 Stream，每个消息只会被组内一个消费者处理。
*   **Pending Entry List (PEL):** 消费者组中，已被消费者读取但尚未 ACK 的消息列表。
*   **Dead Letter Queue (DLQ):** 用于存放处理失败、达到最大重试次数的消息。

## 3. 核心组件设计

### 3.1 `RedisMQProducer` (消息生产者)

*   **职责:** 将消息发送到指定的 Redis Stream。
*   **核心方法:**
    *   `send(String topic, Map<String, String> messageData)`: 同步发送消息。
    *   `sendAsync(String topic, Map<String, String> messageData)`: 异步发送消息，返回 `CompletableFuture<String>` (消息ID)。
    *   `send(String topic, Object payload)`: 发送对象，内部进行 JSON 序列化。
    *   `sendAsync(String topic, Object payload)`: 异步发送对象。
*   **内部实现:**
    *   使用 Redisson 的 `RStream` 接口进行 `XADD` 操作。
    *   支持消息序列化（默认 Jackson JSON）。

### 3.2 `RedisMQConsumer` (消息消费者)

*   **职责:** 从指定的 Redis Stream 消费消息，支持消费者组模式。
*   **核心方法:**
    *   `subscribe(String topic, String group, String consumerName, MessageHandler handler)`: 订阅消息，启动消费者。
    *   `ack(String topic, String group, String messageId)`: 确认消息已处理。
    *   `moveToDLQ(String topic, String group, String messageId, String reason)`: 将消息移动到死信队列。
    *   `retry(String topic, String group, String messageId)`: 重新投递消息进行处理。
*   **内部实现:**
    *   使用 Redisson 的 `RStream` 接口进行 `XREADGROUP` 操作。
    *   **消费者组管理:** 自动创建消费者组（如果不存在）。
    *   **消息拉取:** 循环拉取消息，每次拉取一定数量。
    *   **消息处理:** 将拉取到的消息分发给 `MessageHandler` 处理。
    *   **错误处理与重试:**
        *   配置最大重试次数。
        *   如果消息处理失败，将其重新添加到 PEL，并记录失败次数。
        *   达到最大重试次数后，自动将消息移动到 DLQ。
    *   **消息确认:** 消费者处理完消息后，调用 `XACK`。
    *   **PEL 检查:** 定期检查 PEL 中长时间未 ACK 的消息，进行重试或移入 DLQ。
    *   **自动修剪 (Auto-trim):** 可配置 Stream 的最大长度，通过 `XTRIM` 自动修剪。

### 3.3 `MessageHandler` 接口

```java
public interface MessageHandler {
    /**
     * 处理从 Redis Stream 接收到的消息。
     * @param message 消息对象，包含消息ID和数据。
     * @return true 表示处理成功，false 表示处理失败。
     */
    boolean handle(RedisMessage message);
}
```

### 3.4 `RedisMessage` 类

封装 Redis Stream 消息的结构，包含消息 ID、原始数据（Map<String, String>）和反序列化后的 Payload 对象。

## 4. 死信队列 (DLQ) 机制

*   **DLQ Stream:** 为每个主 Topic 创建一个对应的 DLQ Stream (例如 `topic_dlq`)。
*   **DLQ 生产者/消费者:** 提供专门的 DLQ 生产者和消费者，用于发送和消费 DLQ 消息。
*   **消息结构:** DLQ 消息中应包含原始消息内容、失败原因、失败时间、重试次数等元数据。
*   **处理流程:**
    1.  消费者处理消息失败。
    2.  消息进入重试机制。
    3.  达到最大重试次数后，消费者将消息发送到 DLQ Stream。
    4.  DLQ 消费者可以手动或自动处理这些死信消息（例如告警、人工干预、修复后重放）。

## 5. 配置

通过 `application.yml` 或编程方式配置：

*   Redis 连接信息 (Redisson 配置)。
*   默认消费者组名。
*   消息拉取数量、超时时间。
*   消息重试策略 (最大重试次数、重试间隔)。
*   Stream 自动修剪策略 (最大长度)。
*   DLQ 相关配置。

## 6. 待实现功能 (TODO)

*   **消息过滤:** 支持消费者根据消息内容进行过滤。
*   **消息优先级:** 简单的优先级队列实现（如果 Redis Stream 支持）。
*   **批量发送/消费:** 优化性能。
*   **可观测性:** 集成 Micrometer 暴露消息堆积、处理延迟等指标。
*   **Spring Boot Starter 集成:** 提供自动配置和注解支持。

## 7. 示例代码 (参考 `DESIGN.md` 中的示例)

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
