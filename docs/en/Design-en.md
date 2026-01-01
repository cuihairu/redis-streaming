# Redis-Streaming Detailed Design

[中文](Design) | [English](Design-en)

---

## Project Overview

Redis-Streaming is a lightweight stream processing framework based on Redis, providing easy-to-use APIs for real-time data streams, message queues, service registration and discovery. Particularly suitable for small to medium-scale distributed systems.

## Core Modules

### 1. Redis MQ (Message Queue)

**Features**: Message queue implementation based on Redis Streams

**Key Features**:
- Producer/Consumer group pattern
- Message acknowledgment (ACK) mechanism
- Dead Letter Queue (DLQ) support
- Message retry strategy
- Auto-trim support

**Example Code**:
```java
// Producer
RedisMQProducer producer = new RedisMQProducer("order_events");
producer.sendAsync(JSON.toJson(orderEvent));

// Consumer (with DLQ handling)
RedisMQConsumer consumer = new RedisMQConsumer("order_events", "payment_group");
consumer.subscribe(event -> {
    if (processFailed(event)) {
        consumer.moveToDLQ(event); // Move to dead letter queue
    }
});
```

**Use Cases**: Order status changes, inventory deduction, async notifications

### 2. Registry (Service Registration & Discovery)

**Features**: Service registration and discovery based on Redis Hash + Pub/Sub

**Core Roles**:
- **Service Provider**: Register services, send heartbeats, deregister
- **Service Consumer**: Service discovery, subscription, load balancing
- **Registry Center**: Maintain service list, health check, push changes

**Example Code**:
```java
// Service registration
ServiceRegistry registry = new RedisRegistry("user-service");
registry.register("instance-1", "192.168.1.101:8080");

// Service discovery
List<InstanceInfo> instances = registry.discover("user-service");
```

**Use Cases**: Microservice dynamic scaling, service health monitoring

### 3. Aggregation (Stream Aggregation)

**Features**: Time window aggregation and real-time statistics

**Supported Scenarios**:
- PV/UV statistics
- Top-K leaderboard
- Sliding window averages

**Example Code**:
```java
// Count PV every 5 minutes
PVAggregator aggregator = new PVAggregator("user_clicks", Duration.ofMinutes(5));
aggregator.addListener(result -> System.out.println("PV: " + result));

// Trigger event
aggregator.onEvent(new UserClickEvent("user1", "/home"));
```

### 4. CDC (Change Data Capture)

**Features**: Capture database changes and convert to stream events

**Supported Databases**:
- MySQL Binlog
- PostgreSQL Logical Replication

**Example Code**:
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

### 5. Sink/Source (Connectors)

**Sink Connectors** (Data Output):
- Elasticsearch
- HBase
- Kafka
- Redis

**Source Connectors** (Data Input):
- IoT devices
- HTTP API
- File systems
- Kafka

### 6. Reliability

**Features**:
- Message retry mechanism
- Idempotency guarantee
- Dead letter queue
- Message deduplication

### 7. CEP (Complex Event Processing)

**Features**: Identify event patterns and sequences

**Example**:
```java
// Detect 3 consecutive login failures
Pattern pattern = Pattern.begin("start")
    .where(e -> e.getType().equals("LOGIN_FAILED"))
    .times(3)
    .within(Duration.ofMinutes(5));
```

### 8. Metrics (Monitoring Integration)

**Features**: Prometheus metrics exposure

**Monitoring Metrics**:
- Message backlog
- Processing latency
- Consumption rate
- Service health status

## Technology Stack

| Component | Purpose | Required |
|-----------|---------|----------|
| Redis 6.0+ | Stream/TimeSeries base storage | ✅ |
| Redisson 3.52.0 | Distributed locks, thread-safe connections | ✅ |
| Jackson 2.17.0 | JSON serialization | ✅ |
| Spring Boot 3.x | Starter module dependency | Optional |
| Lombok | Simplify POJO code | Recommended |

## Project Highlights

| Module | Unique Value |
|--------|--------------|
| Overall Framework | The only lightweight Java library focused on pure Redis stream processing |
| MQ Module | Fills gaps in Redis Stream native API (DLQ, async ACK) |
| Registry Center | Lighter than ZooKeeper, more resource-efficient than Consul |
| Admin Role | Complete management and monitoring capabilities |

---

**Version**: 0.1.0
**Last Updated**: 2025-10-13

🔗 Related Documentation:
- [[Overall Architecture|Architecture-en]]
- [[Registry Design|Registry-Design-en]]
- [[MQ Design|MQ-Design-en]]
- [[Spring Boot Integration|Spring-Boot-Starter-en]]
