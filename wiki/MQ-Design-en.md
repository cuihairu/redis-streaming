# MQ Module Design

[中文](MQ-Design) | [English](MQ-Design-en)

---

## Module Goals

Encapsulate Redis Stream native capabilities to provide a lightweight, easy-to-use message queue abstraction, supporting producer/consumer model, consumer groups, message acknowledgment (ACK), and Dead Letter Queue (DLQ) mechanisms.

## Core Concepts

- **Stream**: Redis data structure for storing message sequences. Each Stream corresponds to a Topic
- **Message**: A record in Stream, containing ID and key-value data
- **Producer**: Responsible for sending messages to Stream
- **Consumer**: Responsible for reading and processing messages from Stream
- **Consumer Group**: Allows multiple consumers to consume a Stream together, each message is processed by only one consumer in the group
- **Pending Entry List (PEL)**: In consumer group, list of messages read by consumers but not yet ACKed
- **Dead Letter Queue (DLQ)**: Used to store messages that failed processing and reached max retry count

## Core Component Design

### 1. MessageProducer

**Responsibility**: Send messages to specified Redis Stream

**Core Methods**:
```java
public interface MessageProducer {
    // Synchronous send
    String send(Message message);

    // Asynchronous send
    CompletableFuture<String> sendAsync(Message message);

    // Batch send
    List<String> sendBatch(List<Message> messages);
}
```

**Internal Implementation**:
- Use Redisson's `RStream` interface for `XADD` operations
- Support message serialization (default Jackson JSON)
- Automatically register Topic to TopicRegistry

### 2. MessageConsumer

**Responsibility**: Consume messages from specified Redis Stream, supporting consumer group mode

**Core Methods**:
```java
public interface MessageConsumer {
    // Subscribe to messages
    void subscribe(MessageHandler handler);

    // Acknowledge message
    void ack(String messageId);

    // Move to dead letter queue
    void moveToDLQ(String messageId, String reason);

    // Retry message
    void retry(String messageId);
}
```

**Internal Implementation**:
- Use Redisson's `RStream` interface for `XREADGROUP` operations
- Automatically create consumer group (if not exists)
- Loop to pull messages, pull a certain number each time
- Configure max retry count
- PEL check: Periodically check messages in PEL that haven't been ACKed for a long time
- Auto-trim: Configurable Stream max length

### 3. MessageHandler Interface

```java
public interface MessageHandler {
    /**
     * Handle messages received from Redis Stream
     * @param message Message object
     * @return true for success, false for failure
     */
    boolean handle(Message message);
}
```

### 4. MessageQueueAdmin (Management Interface)

**Responsibility**: Provide management and monitoring capabilities for message queues

**Core Methods**:
```java
public interface MessageQueueAdmin {
    // Queue information
    QueueInfo getQueueInfo(String topic);
    List<String> listAllTopics();

    // Consumer group management
    List<ConsumerGroupInfo> getConsumerGroups(String topic);
    boolean createConsumerGroup(String topic, String group);
    boolean deleteConsumerGroup(String topic, String group);

    // Message management
    List<PendingMessage> getPendingMessages(String topic, String group, int limit);
    List<Message> getMessages(String topic, int limit);

    // Queue operations
    long trimQueue(String topic, long maxLen);
    boolean deleteTopic(String topic);
    boolean resetConsumerGroupOffset(String topic, String group, String messageId);
}
```

**Management Features**:
- View queue length, message count
- View consumer group information
- View pending messages in PEL
- Trim queue, delete Topic
- Reset consumer group offset

### 5. TopicRegistry

**Responsibility**: Maintain index of all Topics, avoiding `keys` or `scan` commands

**Implementation**:
```redis
# Use Redis SET to store all Topics
SADD streaming:mq:topics:registry "order_events"
SADD streaming:mq:topics:registry "payment_events"
```

**Core Methods**:
```java
public class TopicRegistry {
    // Register Topic
    boolean registerTopic(String topic);

    // Unregister Topic
    boolean unregisterTopic(String topic);

    // Get all Topics
    Set<String> getAllTopics();

    // Check if Topic exists
    boolean topicExists(String topic);
}
```

## Dead Letter Queue (DLQ) Mechanism

### DLQ Stream

Create a corresponding DLQ Stream for each main Topic (e.g., `topic_dlq`)

### Message Structure

DLQ messages contain:
- Original message content
- Failure reason
- Failure time
- Retry count
- Original Topic name

### Processing Flow

1. Consumer fails to process message
2. Message enters retry mechanism
3. After reaching max retry count, consumer sends message to DLQ Stream
4. DLQ consumer can manually or automatically process these dead letter messages

## Example Code

### Producer Example

```java
// Create producer
MessageProducer producer = new RedisMessageProducer(redissonClient, "order_events");

// Send message
Message message = Message.builder()
    .topic("order_events")
    .data(Map.of("orderId", "12345", "status", "created"))
    .build();

CompletableFuture<String> future = producer.sendAsync(message);
```

### Consumer Example

```java
// Create consumer
MessageConsumer consumer = new RedisMessageConsumer(
    redissonClient,
    "order_events",
    "payment_group",
    "consumer-1"
);

// Subscribe to messages
consumer.subscribe(message -> {
    try {
        // Process message
        processOrder(message);
        return true; // Success
    } catch (Exception e) {
        log.error("Failed to process message", e);
        return false; // Failure, will retry
    }
});
```

### Admin Example

```java
// Create Admin
MessageQueueAdmin admin = new RedisMessageQueueAdmin(redissonClient);

// View all Topics
List<String> topics = admin.listAllTopics();

// View queue info
QueueInfo info = admin.getQueueInfo("order_events");
System.out.println("Queue length: " + info.getLength());
System.out.println("Consumer groups: " + info.getConsumerGroups());

// View PEL
List<PendingMessage> pending = admin.getPendingMessages("order_events", "payment_group", 10);
```

## Configuration

```yaml
streaming:
  mq:
    # Consumer configuration
    consumer:
      batch-size: 10              # Number of messages to pull each time
      poll-timeout: 5000          # Poll timeout (ms)
      max-retry: 3                # Max retry count
      retry-delay: 1000           # Retry delay (ms)

    # Producer configuration
    producer:
      max-len: 10000              # Stream max length
      auto-trim: true             # Auto-trim

    # DLQ configuration
    dlq:
      enabled: true               # Enable dead letter queue
      suffix: "_dlq"              # DLQ Topic suffix
```

## Performance Optimization

### 1. Batch Operations
- Batch send messages
- Batch acknowledge messages

### 2. Lua Scripts
- Use Lua scripts for atomic operations
- Reduce network round trips

### 3. Connection Pool
- Use Redisson's connection pool
- Reuse Redis connections

### 4. Auto-trim
- Periodically trim Stream
- Control memory usage

---

**Version**: 0.1.0
**Last Updated**: 2025-10-13

🔗 Related Documentation:
- [[Overall Architecture|Architecture-en]]
- [[Detailed Design|Design-en]]
- [[Spring Boot Integration|Spring-Boot-Starter-en]]
