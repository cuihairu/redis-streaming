# 消息队列指南

本页快速说明生产者/消费者与 DLQ 的使用方法。

## 1) 生产者（最小）
```java
Message m = Message.builder().topic("order_events")
    .data(Map.of("orderId", 123L, "status", "created"))
    .build();
producer.sendAsync(m);
```

## 2) 消费者（组）
```java
consumer.subscribe("order_events", "payment_group", msg -> {
  try { handle(msg); return true; } catch (Exception e) { return false; }
});
consumer.start();
```

## 3) 重试与 DLQ
- 超过最大重试进入 DLQ：`stream:topic:{topic}:dlq`
- 通过 Admin/Service 回放到原分区

## 4) 运维（示例）
- 列举/统计/回放/清理见 Admin 接口（或 Starter 暴露的 Bean）

## 参考
- 设计: MQ-Design.md
- 交互: docs/redis-mq-broker-interaction.md
- 分区: docs/redis-mq-partitioning.md
