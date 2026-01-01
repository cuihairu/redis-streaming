# Message Queue (MQ)

Module: `mq/`

Redis Streams based MQ with partitioning, retry/DLQ, admin ops and metrics.

## 1. 架构与键空间（概要）
- 分区流：`stream:topic:{topic}:p:{partition}`
- 组消费：`XGROUP`, `XREADGROUP >`, `XPENDING`, `XAUTOCLAIM`
- DLQ：`stream:topic:{topic}:dlq`

## 2. 快速上手（占位）
### 2.1 生产者
```java
Message m = Message.builder().topic("orders").data(Map.of("id", 1)).build();
producer.sendAsync(m);
```

### 2.2 消费者
```java
consumer.subscribe("orders", "group-A", msg -> {
  // return true to ack; false to retry/DLQ
  return handle(msg);
});
consumer.start();
```

## 3. 重试与 DLQ（占位）
- 最大重试后进入 DLQ：`stream:topic:{topic}:dlq`
- 回放路径：Admin/Service 触发回放到原分区；或由治理进程批量回放

## 4. 管理与治理（占位）
- Admin 能力：列举 topic、统计 size、回放 replayAll、删除 delete、清空 clear

## 5. 指标（占位）
- 生产/消费/ack/retry/dead counters，处理时延 timers；topic/partition 标签

## 6. FAQ（占位）
- `> (neverDelivered)` 只拉取组创建后写入的消息；组创建顺序与首条可见性
- 推荐使用治理线程扫描 XPENDING 与 XAUTOCLAIM 接管孤儿 pending

## References
- MQ-Design.md
- docs/redis-mq-broker-interaction.md
- docs/redis-mq-partitioning.md
