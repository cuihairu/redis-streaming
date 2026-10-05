# MQ Usage Guide

[中文](../MQ-Guide.md) | [English](MQ-Guide-en.md)

---

This is the usage guide for the `mq/` module; every class and method signature can be checked against `mq/src/main/java/io/github/cuihairu/redis/streaming/mq` and `mq/src/test`. For design principles see [MQ-Design-en.md](MQ-Design-en.md); for the full configuration table see the Chinese [MQ.md](../MQ.md).

## 1) Assembly (MessageQueueFactory)

```java
RedissonClient client = Redisson.create(config);    // Redisson client

MessageQueueFactory factory = new MessageQueueFactory(client,
        MqOptions.builder()
                .defaultPartitionCount(4)
                .retryMaxAttempts(5)
                .build());

MessageProducer producer = factory.createProducer();
MessageConsumer consumer = factory.createConsumer("consumer-1");
MessageQueueAdmin admin   = factory.createAdmin();
```

Without `MqOptions` all defaults apply (partition count 1). A third constructor argument injects a `BrokerFactory` to swap the persistence backend: the default `RedisBrokerFactory` (Redis Streams), plus `JdbcBrokerFactory` (JDBC table `rs_messages`).

## 2) Producer

```java
// Option 1: a Message object (carries key/headers/retry parameters)
Message m = new Message("order_events", "order-1", Map.of("orderId", 123L)); // (topic, key, payload)
m.setHeaders(Map.of("trace", "t-1"));
producer.send(m).get(10, TimeUnit.SECONDS);          // returns the Redis stream entry id

// Option 2: convenience overloads
producer.send("order_events", "order-1", payload);   // topic + key + payload
producer.send("order_events", payload);              // topic + payload (null key -> random partition)
```

- Partition routing: `HashPartitioner` by default, `partition = key.hashCode() % P` (random when the key is null).
- Forced partition: header `MqHeaders.FORCE_PARTITION_ID` (`x-force-partition-id`, integer string) is respected in both the produce and route phases — DLQ replay relies on it to return to the original partition.
- Payloads over 64KB are automatically offloaded to a Redis key (`x-payload-hash-ref`) and only the reference stays in the stream; it is deleted on ACK.

## 3) Consumer (consumer groups)

```java
consumer.subscribe("order_events", "payment_group", msg -> {
    try {
        handle(msg);
        return MessageHandleResult.SUCCESS;          // handled, ACK
    } catch (Exception retryable) {
        return MessageHandleResult.RETRY;            // retry (DLQ once exhausted)
    }
});
consumer.start();
```

- `MessageHandler` returns a `MessageHandleResult`: `SUCCESS` / `RETRY` / `FAIL` / `DEAD_LETTER`.
- `subscribe(topic, handler)` uses the default group `default-group`; one consumer instance can subscribe to several topics, and within a group one partition is leased by a single instance at a time.
- Lifecycle: `start()` starts (synchronously rebalances once), `stop()` stops the workers, `close()` releases the thread pool (calls `stop()` first).
- `RedisMessageConsumer` also implements `PausableMessageConsumer`: `pause()` / `resume()` / `isPaused()` / `inFlight()`.

To override read parameters per subscription or pin a subset of partitions:

```java
consumer.subscribe("order_events", "payment_group", handler,
        SubscriptionOptions.builder()
                .batchCount(20)          // overrides consumerBatchCount
                .pollTimeoutMs(500)      // overrides consumerPollTimeoutMs
                .partitionModulo(2)      // combined with remainder: takes only partitions with i % 2 == 0
                .partitionRemainder(0)
                .build());
```

## 4) Handle-result semantics

| Return value | Behavior |
|---|---|
| `SUCCESS` | ACK; with header `x-defer-ack=true` the ACK is deferred (for checkpoint-coordinated runtimes) |
| `RETRY` | Re-enqueues first (≤50ms backoff redelivers directly; otherwise the message enters a ZSET delay bucket that Lua moves back when due), then ACKs the original; the retry cap is `min(message.maxRetries, retryMaxAttempts)`, exhausted messages go to the DLQ |
| `FAIL` / `DEAD_LETTER` | Writes the DLQ and ACKs on success; if the DLQ write fails the message stays pending |
| Thrown exception | Same path as `RETRY` (`requeueOrDeadLetter`) |

A retried message carries a new stream id; the original id is in header `x-original-message-id`. The default `maxRetries=3` is set by the `Message` constructor and can be overridden with `m.setMaxRetries(1)`.

## 5) Consuming the DLQ

```java
// DLQ consumer adapted to MessageConsumer (name gets the -dlq suffix automatically)
MessageConsumer dlqConsumer = factory.createDeadLetterConsumer("dlq-c1");
dlqConsumer.subscribe("order_events", "dlq_group", m -> {
    log(m);                                          // DeadLetterEntry fields mapped into Message (incl. partitionId)
    return MessageHandleResult.SUCCESS;              // SUCCESS=confirm; RETRY=replay to the original partition, confirm on success; FAIL=confirm and drop
});
dlqConsumer.start();
```

`factory.createDeadLetterConsumerForTopic(topic, group, consumerName, handler)` is the one-step convenience method: it creates, `subscribe`s and `start`s (start has side effects; the returned consumer is already running).

On `RETRY` the DLQ consumer replays to the original partition stream and ACKs only when that succeeds. Replay does not remove entries from the DLQ stream; cleanup goes through `delete`/`clear`/retention policies.

## 6) DLQ management and replay

Lightweight utility (`DeadLetterQueueManager` at the module root):

```java
DeadLetterQueueManager dlq = new DeadLetterQueueManager(client);

long size = dlq.getDeadLetterQueueSize("order_events");
Map<StreamMessageId, Map<String, Object>> msgs = dlq.getDeadLetterMessages("order_events", 10);
StreamMessageId firstId = msgs.keySet().iterator().next();
dlq.replayMessage("order_events", firstId);          // replays to the partition recorded in partitionId
dlq.deleteMessage("order_events", firstId);
dlq.clearDeadLetterQueue("order_events");
```

Full management API (`dlq.DeadLetterAdmin`, can list every DLQ topic):

```java
DeadLetterAdmin dlqAdmin = new RedisDeadLetterAdmin(client, new RedisDeadLetterService(client));
dlqAdmin.listTopics();                               // scans the {prefix}:{topic}:dlq pattern
dlqAdmin.list("order_events", 20);                   // List<DeadLetterEntry>
dlqAdmin.replay("order_events", id);
dlqAdmin.replayAll("order_events", 100);             // replays at most 100 entries, returns the success count
dlqAdmin.delete("order_events", id);
dlqAdmin.clear("order_events");
```

`RedisDeadLetterService` can also be used on its own to write dead letters (`DeadLetterRecord` is a public-field POJO):

```java
DeadLetterRecord rec = new DeadLetterRecord();
rec.originalTopic = "order_events";
rec.originalPartition = 0;
rec.payload = Map.of("orderId", 123L);
rec.headers = Map.of("trace", "t-1");
new RedisDeadLetterService(client).send(rec);
```

## 7) Operations (MessageQueueAdmin)

```java
admin.listAllTopics();
admin.getQueueInfo("order_events");                         // cross-partition length/groups/first/last aggregate
admin.getConsumerGroups("order_events");
admin.getConsumerGroupStats("order_events", "payment_group"); // pending/consumers/lag
admin.getPendingMessages("order_events", "payment_group", 20,
        PendingSort.IDLE, true, 60_000);                     // by idle descending, filtered to <60s
admin.resetConsumerGroupOffset("order_events", "payment_group", "0");  // "0"=beginning / "$"=latest
admin.trimQueue("order_events", 1000);                       // keep the newest 1000 (amortized over partitions)
admin.trimQueueByAge("order_events", Duration.ofHours(1));
admin.updatePartitionCount("order_events", 8);               // grow-only
admin.deleteConsumerGroup("order_events", "payment_group");
admin.deleteTopic("order_events");                           // removes partition streams + DLQ + meta + payload hashes
admin.listRecent("order_events", 10);                        // raw entry view (≤200 per partition)
admin.range("order_events", 0, "-", "+", 10, true);
```

## 8) Configuration and integration

- Every `MqOptions` field and default is listed in the Chinese [MQ.md](../MQ.md).
- Spring Boot: `spring-boot-starter` auto-configures `MessageQueueFactory` / `MessageQueueAdmin` / the DLQ beans under the `redis-streaming.mq.*` prefix, with Micrometer metrics and retention housekeeping (`StreamRetentionHousekeeper`) — see [Spring-Boot-Starter-en.md](Spring-Boot-Starter-en.md).
- Runtime integration (`RedisStreamExecutionEnvironment`): see the Chinese [runtime.md](../runtime.md).

## References

- Design: [MQ-Design-en.md](MQ-Design-en.md)
- Redis command interaction: [MQ-Broker-Interaction-en.md](MQ-Broker-Interaction-en.md)
- Module overview: the Chinese [MQ.md](../MQ.md)
