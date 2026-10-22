# Message Queue (MQ)

Module: `mq/` (package root `io.github.cuihairu.redis.streaming.mq`)

A Redis Streams-based message queue: partitioning, consumer groups with lease-based rebalancing, delayed retries, a DLQ, the admin surface, and metrics. The data plane is a Redis Stream; the control plane (metadata/leases/retries/commit frontiers) lives in Redis Hash/Set/ZSET/string keys.

## 1. Module Responsibility

- Producing: a `Message` is routed by key hash into a partition stream (`XADD`), with header-based partition forcing supported.
- Consuming: consumer groups + per-partition exclusive leases; one serial worker per partition; idle pending entries are taken over via `XPENDING + XCLAIM` and replayed.
- Retrying: failed messages re-queue with exponential backoff (small backoffs re-deliver directly; large ones go through a ZSET delay bucket + an atomic Lua move); past the cap they enter the DLQ.
- Dead letters: one DLQ stream per topic, with consume/replay/delete/clear.
- Admin: the topic registry, cross-partition aggregate stats, pending details, trimming (by length/age), group management, partition expansion, raw message inspection.
- Observability: a pluggable metrics collector (Noop by default; the spring-boot-starter ships a Micrometer implementation).

## 2. Architecture and Keyspace

Key names derive from `MqOptions.keyPrefix` (default `streaming:mq`) and `MqOptions.streamKeyPrefix` (default `stream:topic`); see `partition/StreamKeys`:

| Key | Structure | Notes |
|---|---|---|
| `{streamKeyPrefix}:{topic}:p:{i}` | Stream | partition stream (the i-th partition) |
| `{streamKeyPrefix}:{topic}:dlq` | Stream | the topic's dead-letter stream |
| `{keyPrefix}:topics:registry` | Set | the full topic registry |
| `{keyPrefix}:topic:{topic}:meta` | Hash | field `partitionCount` |
| `{keyPrefix}:topic:{topic}:partitions` | Set | the partition stream key set |
| `{keyPrefix}:lease:{topic}:{group}:{partitionId}` | String+TTL | the partition lease (value = the consumer name) |
| `{keyPrefix}:retry:{topic}` | ZSET | the retry bucket, score = due timestamp (ms) |
| `{keyPrefix}:retry:item:{topic}:{uuid}` | Hash | the retry entry envelope |
| `{keyPrefix}:commit:{topic}:p:{i}` | Hash | commit frontier (field = group, value = the max acked streamId) |
| `{keyPrefix}:acks:{topic}:p:{i}:{messageId}` | Set | the ack set of the all-groups-ack delete policy |
| `{keyPrefix}:payload:{topic}:p:{i}:{uuid}` | String | oversized (>64KB) payloads (JSON); the message header holds the reference |
| `{keyPrefix}:payload:idx:{topic}` / `{keyPrefix}:payload:ts` | Set / ZSET | the payload index (by topic / by time, for cleanup) |

Exception: the retry mover's distributed lock key is the literal constant `streaming:mq:retry:lock:{topic}` (hardcoded inside `RedisMessageConsumer.moveDueRetries`, unaffected by `keyPrefix`).

## 3. Public Interface (key classes and methods)

### 3.1 Assembly entry `MessageQueueFactory`
```java
MessageQueueFactory(RedissonClient redissonClient)
MessageQueueFactory(RedissonClient redissonClient, MqOptions options)
MessageQueueFactory(RedissonClient redissonClient, MqOptions options, BrokerFactory brokerFactory)

MessageProducer createProducer()                       // builds the Broker via BrokerFactory (Redis by default)
MessageConsumer createConsumer()                       // name = consumerNamePrefix + the first 8 chars of a UUID
MessageConsumer createConsumer(String consumerName)
MessageConsumer createDeadLetterConsumer()             // DLQ consumer (the name gets a -dlq suffix automatically)
MessageConsumer createDeadLetterConsumer(String consumerName)
MessageConsumer createDeadLetterConsumerForTopic(String topic, String group, String consumerName, MessageHandler handler) // subscribes and starts
MessageQueueAdmin createAdmin()
```

### 3.2 Producer `MessageProducer` (implemented by `impl.BrokerBackedProducer`)
```java
CompletableFuture<String> send(Message message)
CompletableFuture<String> send(String topic, String key, Object payload)
CompletableFuture<String> send(String topic, Object payload)
void close();  boolean isClosed()
```

### 3.3 Consumer `MessageConsumer` (implemented by `impl.RedisMessageConsumer`)
```java
void subscribe(String topic, MessageHandler handler)
void subscribe(String topic, String consumerGroup, MessageHandler handler)
void subscribe(String topic, String consumerGroup, MessageHandler handler, SubscriptionOptions options)
void unsubscribe(String topic)
void start();  void stop();  void close()
boolean isRunning();  boolean isClosed()
```
`RedisMessageConsumer` also implements `control.PausableMessageConsumer` (`pause()` / `resume()` / `isPaused()` / `inFlight()`) and `control.ReassignableMessageConsumer` (`updatePartitionAssignment(topic, modulo, remainder)` changes the partition assignment at runtime — dynamic scaling): partitions moved out of the assignment stop their worker and release the lease immediately (the new owner in the same group can take over without waiting for the TTL); newly added partitions are picked up by the periodic rebalance; subscribing to an unknown topic returns false.

`MessageHandler` (a functional interface) returns a `MessageHandleResult`:
- `SUCCESS` — processed; ACK by default (header `x-defer-ack=true` defers the ACK for checkpoint-style runtimes)
- `RETRY` — failed but retryable (re-queue or to the DLQ, see §5)
- `FAIL` / `DEAD_LETTER` — straight to the DLQ

### 3.4 Admin `admin.MessageQueueAdmin` (implemented by `admin.impl.RedisMessageQueueAdmin`)
```java
QueueInfo getQueueInfo(String topic)
List<String> listAllTopics()
boolean topicExists(String topic)
List<ConsumerGroupInfo> getConsumerGroups(String topic)
ConsumerGroupStats getConsumerGroupStats(String topic, String group)
boolean consumerGroupExists(String topic, String group)
List<PendingMessage> getPendingMessages(String topic, String group, int limit)
List<PendingMessage> getPendingMessages(String topic, String group, int limit, PendingSort sort, boolean descending, long minIdleMs)
long getPendingCount(String topic, String group)
long trimQueue(String topic, long maxLen)                 // keep the newest N (spread across partitions)
long trimQueueByAge(String topic, Duration maxAge)        // delete entries older than maxAge
boolean deleteTopic(String topic)                          // deletes the partition streams/DLQ/meta/partition set/payload hashes and unregisters
boolean deleteConsumerGroup(String topic, String group)
boolean resetConsumerGroupOffset(String topic, String group, String messageId) // "0"=beginning, "$"=latest
boolean updatePartitionCount(String topic, int newPartitionCount)              // grow-only
List<MessageEntry> listRecent(String topic, int perPartitionCount)             // 200 per-partition cap
List<MessageEntry> range(String topic, int partitionId, String fromId, String toId, int count, boolean reverse)
```

### 3.5 Dead letters
- `dlq.DeadLetterService` / `dlq.RedisDeadLetterService`: `send(DeadLetterRecord)`, `range(originalTopic, limit)`, `size`, `delete`, `clear`, `replay(originalTopic, id)`. A `ReplayHandler` (`publish(topic, partitionId, payload, headers, maxRetries)`) can be given at construction to customize the replay target; without one, replay goes back to the original partition stream.
- `dlq.DeadLetterAdmin` / `dlq.RedisDeadLetterAdmin`: `listTopics()`, `size`, `list(topic, limit)`, `replay`, `replayAll(topic, maxCount)`, `delete`, `clear`.
- `DeadLetterQueueManager` (module root): a lightweight compatibility DLQ utility — `getDeadLetterQueueSize` / `getDeadLetterMessages(topic, limit)` / `deleteMessage` / `clearDeadLetterQueue` / `replayMessage`.
- `dlq.DeadLetterConsumer` / `dlq.RedisDeadLetterConsumer`: the DLQ-specific consumption loop whose `HandleResult` is `SUCCESS / RETRY / FAIL`; normally used through the `MessageConsumer` adapter view of `factory.createDeadLetterConsumer(...)` (`impl.DlqConsumerAdapter`).

### 3.6 Header Constants
- `MqHeaders`: `x-force-partition-id` (forced partition), `partitionId`, `x-payload-missing` / `x-payload-missing-ref`, `x-original-message-id`, `x-defer-ack`.
- `impl.PayloadHeaders`: `x-payload-hash-ref`, `x-payload-original-size`, `x-payload-storage-type` (`inline` / `hash`); the threshold `MAX_INLINE_PAYLOAD_SIZE = 64KB`.

## 4. Configuration `config.MqOptions`

All fields and defaults (Builder methods share the names; `mq/src/main/java/.../config/MqOptions.java`):

| Field | Type | Default | Notes |
|---|---|---|---|
| defaultPartitionCount | int | 1 | the default partition count for topics |
| workerThreads | int | 8 | the worker pool; also the per-instance partition cap when `maxLeasedPartitionsPerConsumer=0` |
| schedulerThreads | int | 2 | the scheduler pool (rebalance/renewal/pending scan/retry moving) |
| consumerBatchCount | int | 10 | entries per XREADGROUP |
| consumerPollTimeoutMs | long | 1000 | the read blocking timeout |
| leaseTtlSeconds | int | 15 | lease TTL |
| rebalanceIntervalSec | int | 5 | rebalance period |
| renewIntervalSec | int | 3 | lease renewal period |
| pendingScanIntervalSec | int | 30 | orphan pending scan period |
| claimIdleMs | long | 300000 (5min) | minimum idle before a pending takeover |
| claimBatchSize | int | 50 | pending entries per scan |
| maxInFlight | int | 0 | per-instance concurrent processing cap; 0 = unlimited (semaphore backpressure) |
| maxLeasedPartitionsPerConsumer | int | 0 | per-instance leaseable partition cap; 0 = workerThreads |
| retryMaxAttempts | int | 5 | retry policy cap; the effective cap is min(this, the message's `maxRetries`) |
| retryBaseBackoffMs | long | 1000 | exponential backoff base |
| retryMaxBackoffMs | long | 60000 | per-attempt backoff cap |
| retryMoverBatch | int | 100 | retry entries fetched per mover round |
| retryMoverIntervalSec | int | 1 | retry mover period |
| retryLockWaitMs / retryLockLeaseMs | long | 100 / 500 | the mover's distributed lock wait/lease |
| keyPrefix | String | `streaming:mq` | control-key prefix |
| streamKeyPrefix | String | `stream:topic` | data-stream prefix |
| consumerNamePrefix | String | `consumer-` | prefix of auto-generated consumer names |
| dlqConsumerSuffix | String | `-dlq` | suffix of DLQ consumer names |
| defaultConsumerGroup | String | `default-group` | group used by `subscribe(topic, handler)` |
| defaultDlqGroup | String | `dlq-group` | the DLQ consumer's default group |
| retentionMaxLenPerPartition | int | 100000 | write-path length retention (`XADD ... MAXLEN = N`, exact trim); 0 = off |
| retentionMs | long | 0 | time retention (ms); 0 = off. Inside the mq module it only caps the big-payload TTL; periodic `XTRIM MINID` is run by the spring-boot-starter's `StreamRetentionHousekeeper` |
| trimIntervalSec | int | 60 | retention cleanup period (as above; the consumer lives in the starter) |
| dlqRetentionMaxLen | int | 0 | DLQ length-retention override; 0 = not enabled |
| dlqRetentionMs | long | 0 | DLQ time retention (ms); 0 = off (consumed by the starter housekeeper) |
| ackDeletePolicy | String | `none` | post-ACK delete policy: `none` \| `immediate` \| `all-groups-ack` |
| acksetTtlSec | int | 86400 | TTL of the all-groups-ack ack set |

Under Spring Boot these map to `redis-streaming.mq.*` (see `Spring-Boot-Starter.md`).

## 5. Retry and DLQ Behavior

- The effective retry cap = `min(message.maxRetries, retryMaxAttempts)`; `Message` defaults to `maxRetries=3`, `retryCount=0`.
- `RETRY`: re-queue first, then ACK the original (the reverse order loses messages). The backoff is `retryBaseBackoffMs * 2^(n-1)` capped at `retryMaxBackoffMs`; a backoff ≤ 50ms re-delivers straight into the partition stream, otherwise the entry goes into the retry Hash + ZSET and a Lua script atomically performs "fetch due → XADD back into the partition → ZREM → DEL the Hash". Re-queueing uses a new stream id; the original id is preserved in `x-original-message-id`.
- `FAIL` / `DEAD_LETTER` / retries exhausted: a `DeadLetterRecord` is written into `{streamKeyPrefix}:{topic}:dlq`; the DLQ write must succeed before the ACK; on write failure the entry stays pending for redelivery.
- Parse failures (including a lost big-payload reference) count as poison: straight to the DLQ with an ACK, avoiding endless re-claiming.
- DLQ replay (`replay`/`replayAll`/the DLQ consumer's RETRY) only re-delivers and ACKs; it does not delete the DLQ entry — cleanup needs explicit `delete`/`clear` or the DLQ retention policy.

## 6. Quick Start (matching the usage in `mq/src/test`)

```java
RedissonClient client = Redisson.create(config);           // org.redisson.Redisson
MessageQueueFactory factory = new MessageQueueFactory(client,
        MqOptions.builder().defaultPartitionCount(4).build());

MessageProducer producer = factory.createProducer();
MessageConsumer consumer = factory.createConsumer("c1");

consumer.subscribe("orders", "order-group", m -> {
    handle(m);                                             // business processing
    return MessageHandleResult.SUCCESS;                    // SUCCESS / RETRY / FAIL / DEAD_LETTER
});
consumer.start();

Message m = new Message("orders", "k-1", Map.of("id", 1));  // (topic, key, payload)
m.setHeaders(Map.of("trace", "t-1"));
producer.send(m).get(10, TimeUnit.SECONDS);                 // returns the Redis stream id

// ...

consumer.stop();
consumer.close();
producer.close();
```

Filtered subscription by partition (`SubscriptionOptions`):
```java
consumer.subscribe("orders", "order-group", handler, SubscriptionOptions.builder()
        .batchCount(20).pollTimeoutMs(500)
        .partitionModulo(2).partitionRemainder(0)   // only take partitions with i % 2 == 0
        .build());
```

Consuming the DLQ:
```java
MessageConsumer dlqConsumer = factory.createDeadLetterConsumer("c-dlq");
dlqConsumer.subscribe("orders", "g-dlq", m -> MessageHandleResult.SUCCESS);
dlqConsumer.start();
```

## 7. Administration and Governance

```java
MessageQueueAdmin admin = new RedisMessageQueueAdmin(client, options);
admin.listAllTopics();
admin.getQueueInfo("orders");                       // cross-partition aggregate length/groups/first/last
admin.getConsumerGroups("orders");
admin.getConsumerGroupStats("orders", "order-group");
admin.getPendingMessages("orders", "order-group", 20);
admin.resetConsumerGroupOffset("orders", "order-group", "0");
admin.trimQueue("orders", 1000);
admin.trimQueueByAge("orders", Duration.ofHours(1));
admin.updatePartitionCount("orders", 8);            // grow-only
admin.deleteConsumerGroup("orders", "order-group");
admin.deleteTopic("orders");
admin.listRecent("orders", 10);
admin.range("orders", 0, "-", "+", 10, true);
```

```java
DeadLetterAdmin dlqAdmin = new RedisDeadLetterAdmin(client, new RedisDeadLetterService(client));
dlqAdmin.listTopics();
dlqAdmin.list("orders", 20);
dlqAdmin.replayAll("orders", 100);
dlqAdmin.delete("orders", id);
dlqAdmin.clear("orders");
```

## 8. Metrics

`metrics.MqMetrics` / `metrics.MqMetricsCollector` (Noop by default; install with `MqMetrics.setCollector`; the starter ships `MqMicrometerCollector`):

- Counters: `incProduced` / `incConsumed` / `incAcked` / `incRetried` / `incDeadLetter` / `incPayloadMissing` / `incDlqDelete` / `incDlqClear`
- Gauges: `setInFlight` (backpressure), `setEligiblePartitions` / `setLeasedPartitions` / `setMaxLeasedPartitions`
- Timers: `recordHandleLatency`, `recordBackpressureWait`, `recordDlqReplay`
- Retention cleanup: `metrics.RetentionMetrics` / `RetentionMetricsCollector`'s `recordTrim` / `recordDlqTrim`

Tag dimensions: topic, partitionId, consumerName, consumerGroup, result/reason.

## 9. FAQ

- `XREADGROUP ... >` (never-delivered) means "entries delivered to this group but never consumed by it", not "messages written after the group was created". This implementation always creates groups with `XGROUP CREATE ... 0-0 MKSTREAM`, so pre-existing backlog is consumed too.
- Orphan pending entries are scanned by the scheduler thread every `pendingScanIntervalSec` via `XPENDING`, and entries idle beyond `claimIdleMs` are `XCLAIM`ed (Redisson `claim`) back for replay.
- At any instant, one partition of one group is consumed by exactly one instance (exclusive lease; released once `leaseTtlSeconds` expires); different groups are independent.
- Big payloads (> 64KB) are offloaded automatically to `{keyPrefix}:payload:...`, with the header carrying `x-payload-hash-ref`; deleted on ACK. Messages whose reference is lost go to the DLQ marked `x-payload-missing`.

## References

- Design and trade-offs: [MQ-Design.md](./MQ-Design.md)
- Redis command-level interaction and sequence diagrams: [MQ-Broker-Interaction.md](./MQ-Broker-Interaction.md)
- Usage guide: [MQ-Guide.md](./MQ-Guide.md)
- English docs: [MQ-Design.md](/MQ-Design), [MQ-Broker-Interaction.md](/MQ-Broker-Interaction)
