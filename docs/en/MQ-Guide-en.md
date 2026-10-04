# MQ Guide (EN)

This page gives a quick usage guide for producers, consumers, and DLQ.

## 1) Producer (Minimal)
```java
Message m = Message.builder().topic("order_events")
    .data(Map.of("orderId", 123L, "status", "created"))
    .build();
producer.sendAsync(m);
```

## 2) Consumer (Group)
```java
consumer.subscribe("order_events", "payment_group", msg -> {
  try { handle(msg); return true; } catch (Exception e) { return false; }
});
consumer.start();
```

## 3) Retry & DLQ
- Failed beyond max attempts → written to DLQ: `stream:topic:{topic}:dlq`
- Replay via Admin/Service, back to original partition

## 4) Admin Ops (Examples)
- List topics, size, `replayAll`, delete, clear (see the admin interfaces in the MQ module and the Spring Boot starter)

## References
- Design and partitioning: [MQ-Design-en.md](MQ-Design-en.md)
- Broker interaction: [MQ-Broker-Interaction-en.md](MQ-Broker-Interaction-en.md)
