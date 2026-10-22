# 消息队列使用指南

本文是 `mq/` 模块的使用指南，所有类名与方法签名均可在 `mq/src/main/java/io/github/cuihairu/redis/streaming/mq` 与 `mq/src/test` 中核对。设计原理见 [MQ-Design.md](./MQ-Design.md)，配置项全表见 [MQ.md](./MQ.md)。

## 1) 组装（MessageQueueFactory）

```java
RedissonClient client = Redisson.create(config);    // Redisson 客户端

MessageQueueFactory factory = new MessageQueueFactory(client,
        MqOptions.builder()
                .defaultPartitionCount(4)
                .retryMaxAttempts(5)
                .build());

MessageProducer producer = factory.createProducer();
MessageConsumer consumer = factory.createConsumer("consumer-1");
MessageQueueAdmin admin   = factory.createAdmin();
```

不传 `MqOptions` 时使用全默认值（分区数 1）。第三个构造参数可注入 `BrokerFactory` 换持久化后端：默认 `RedisBrokerFactory`（Redis Streams），另有 `JdbcBrokerFactory`（JDBC 表 `rs_messages`）。

## 2) 生产者

```java
// 方式一：Message 对象（可带 key/headers/重试参数）
Message m = new Message("order_events", "order-1", Map.of("orderId", 123L)); // (topic, key, payload)
m.setHeaders(Map.of("trace", "t-1"));
producer.send(m).get(10, TimeUnit.SECONDS);          // 返回 Redis stream entry id

// 方式二：便捷重载
producer.send("order_events", "order-1", payload);   // topic + key + payload
producer.send("order_events", payload);              // topic + payload（key 为 null 时随机分区）
```

- 分区路由：默认 `HashPartitioner`，`partition = key.hashCode() % P`（key 为 null 时随机）。
- 强制分区：header `MqHeaders.FORCE_PARTITION_ID`（`x-force-partition-id`，整数字符串），生产与路由阶段都会尊重该 header（DLQ 回放即靠它回到原分区）。
- 超过 64KB 的 payload 自动转存 Redis 键（`x-payload-hash-ref`），流内只留引用；ACK 时删除。

## 3) 消费者（消费组）

```java
consumer.subscribe("order_events", "payment_group", msg -> {
    try {
        handle(msg);
        return MessageHandleResult.SUCCESS;          // 处理成功，ACK
    } catch (Exception retryable) {
        return MessageHandleResult.RETRY;            // 重试（耗尽后进 DLQ）
    }
});
consumer.start();
```

- `MessageHandler` 返回 `MessageHandleResult`：`SUCCESS` / `RETRY` / `FAIL` / `DEAD_LETTER`。
- `subscribe(topic, handler)` 使用默认组 `default-group`；一个消费者实例可订阅多个 topic，同组内一个分区只被一个实例租约独占。
- 生命周期：`start()` 启动（同步做一次再均衡）、`stop()` 停止 Worker、`close()` 释放线程池（内部会先 `stop()`）。
- `RedisMessageConsumer` 另实现 `PausableMessageConsumer`：`pause()` / `resume()` / `isPaused()` / `inFlight()`；及 `ReassignableMessageConsumer`：`updatePartitionAssignment(topic, modulo, remainder)` 运行期改分区指派（移出的分区即时释放租约，新纳入的由再均衡取回）。

按订阅覆盖读取参数或锁定分区子集：

```java
consumer.subscribe("order_events", "payment_group", handler,
        SubscriptionOptions.builder()
                .batchCount(20)          // 覆盖 consumerBatchCount
                .pollTimeoutMs(500)      // 覆盖 consumerPollTimeoutMs
                .partitionModulo(2)      // 与 remainder 组合：只接管 i % 2 == 0 的分区
                .partitionRemainder(0)
                .build());
```

## 4) 处理结果语义

| 返回值 | 行为 |
|---|---|
| `SUCCESS` | ACK；header `x-defer-ack=true` 时推迟 ACK（供 checkpoint 协调的运行时） |
| `RETRY` | 先重新入队（≤50ms 退避直接重投；否则进 ZSET 延迟桶由 Lua 按到期时间搬回），后 ACK 原消息；重试上限 = `min(message.maxRetries, retryMaxAttempts)`，耗尽进 DLQ |
| `FAIL` / `DEAD_LETTER` | 写 DLQ，成功后 ACK；DLQ 写失败则保持 pending |
| 抛异常 | 等价于 `RETRY` 路径（`requeueOrDeadLetter`） |

重试后的消息是新的 stream id；原始 id 在 header `x-original-message-id` 中。消息默认 `maxRetries=3`（`Message` 构造器设定），可用 `m.setMaxRetries(1)` 覆盖。

## 5) 消费 DLQ

```java
// 适配为 MessageConsumer 的 DLQ 消费者（名称自动加 -dlq 后缀）
MessageConsumer dlqConsumer = factory.createDeadLetterConsumer("dlq-c1");
dlqConsumer.subscribe("order_events", "dlq_group", m -> {
    log(m);                                          // DeadLetterEntry 字段映射进 Message（含 partitionId）
    return MessageHandleResult.SUCCESS;              // SUCCESS=确认；RETRY=回放原分区成功后确认；FAIL=确认并放弃
});
dlqConsumer.start();
```

`factory.createDeadLetterConsumerForTopic(topic, group, consumerName, handler)` 是一步到位的便捷方法：创建、`subscribe` 并 `start`（有启动副作用，返回值即已运行的消费者）。

DLQ 消费者对 `RETRY` 的处理是「回放到原分区流，成功才 ACK」。回放不会删除 DLQ 流中的条目，清理靠 `delete`/`clear`/保留策略。

## 6) DLQ 管理与回放

轻量工具（模块根 `DeadLetterQueueManager`）：

```java
DeadLetterQueueManager dlq = new DeadLetterQueueManager(client);

long size = dlq.getDeadLetterQueueSize("order_events");
Map<StreamMessageId, Map<String, Object>> msgs = dlq.getDeadLetterMessages("order_events", 10);
StreamMessageId firstId = msgs.keySet().iterator().next();
dlq.replayMessage("order_events", firstId);          // 回放到记录的 partitionId 对应分区
dlq.deleteMessage("order_events", firstId);
dlq.clearDeadLetterQueue("order_events");
```

完整管理接口（`dlq.DeadLetterAdmin`，可列全部 DLQ Topic）：

```java
DeadLetterAdmin dlqAdmin = new RedisDeadLetterAdmin(client, new RedisDeadLetterService(client));
dlqAdmin.listTopics();                               // 按 {prefix}:{topic}:dlq 模式扫描
dlqAdmin.list("order_events", 20);                   // List<DeadLetterEntry>
dlqAdmin.replay("order_events", id);
dlqAdmin.replayAll("order_events", 100);             // 最多回放 100 条，返回成功数
dlqAdmin.delete("order_events", id);
dlqAdmin.clear("order_events");
```

`RedisDeadLetterService` 也可单独用于写入死信（`DeadLetterRecord` 为公有字段 POJO）：

```java
DeadLetterRecord rec = new DeadLetterRecord();
rec.originalTopic = "order_events";
rec.originalPartition = 0;
rec.payload = Map.of("orderId", 123L);
rec.headers = Map.of("trace", "t-1");
new RedisDeadLetterService(client).send(rec);
```

## 7) 运维（MessageQueueAdmin）

```java
admin.listAllTopics();
admin.getQueueInfo("order_events");                         // 跨分区 length/组数/first/last 聚合
admin.getConsumerGroups("order_events");
admin.getConsumerGroupStats("order_events", "payment_group"); // pending/consumers/lag
admin.getPendingMessages("order_events", "payment_group", 20,
        PendingSort.IDLE, true, 60_000);                     // 按 idle 降序，过滤 <60s
admin.resetConsumerGroupOffset("order_events", "payment_group", "0");  // "0"=开头 / "$"=最新
admin.trimQueue("order_events", 1000);                       // 保留最新 1000 条（均摊到分区）
admin.trimQueueByAge("order_events", Duration.ofHours(1));
admin.updatePartitionCount("order_events", 8);               // 只允许增大
admin.deleteConsumerGroup("order_events", "payment_group");
admin.deleteTopic("order_events");                           // 分区流+DLQ+meta+payload hash 一起删
admin.listRecent("order_events", 10);                        // 原始条目查看（每分区 ≤200）
admin.range("order_events", 0, "-", "+", 10, true);
```

## 8) 配置与集成

- `MqOptions` 全部字段与默认值见 [MQ.md](./MQ.md)。
- Spring Boot：`spring-boot-starter` 自动装配 `MessageQueueFactory` / `MessageQueueAdmin` / DLQ 服务等 Bean，配置前缀 `redis-streaming.mq.*`，并提供 Micrometer 指标与保留清理（`StreamRetentionHousekeeper`），见 [Spring-Boot-Starter.md](./Spring-Boot-Starter.md)。
- 与 runtime 集成（`RedisStreamExecutionEnvironment`）见 [runtime.md](./runtime.md)。

## 参考

- 设计：[MQ-Design.md](./MQ-Design.md)
- Redis 命令交互：[MQ-Broker-Interaction.md](./MQ-Broker-Interaction.md)
- 模块总览：[MQ.md](./MQ.md)
