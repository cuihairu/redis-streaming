# Message Queue (MQ)

模块：`mq/`（包根 `io.github.cuihairu.redis.streaming.mq`）

基于 Redis Streams 的消息队列：分区、消费组与租约再均衡、延迟重试、DLQ、管理端（Admin）与指标。数据面是 Redis Stream，控制面（元数据/租约/重试/提交位点）是 Redis 的 Hash/Set/ZSET/字符串键。

## 1. 模块职责

- 生产：`Message` 按 key 哈希路由到分区流（`XADD`），支持 header 强制指定分区。
- 消费：消费组 + 分区独占租约；每分区一个串行 Worker；空闲 pending 通过 `XPENDING + XCLAIM` 接管重放。
- 重试：失败消息按指数退避重新入队（小退避直接重投，大退避走 ZSET 延迟桶 + Lua 原子搬运）；超过上限进 DLQ。
- 死信：每个 Topic 一个 DLQ 流，提供消费、回放、删除、清空。
- 管理：Topic 注册表、跨分区聚合统计、pending 明细、裁剪（长度/年龄）、组管理、分区扩容、原始消息查看。
- 观测：可插拔指标采集器（默认 Noop；spring-boot-starter 提供 Micrometer 实现）。

## 2. 架构与键空间

键名由 `MqOptions.keyPrefix`（默认 `streaming:mq`）与 `MqOptions.streamKeyPrefix`（默认 `stream:topic`）决定，见 `partition/StreamKeys`：

| 键 | 结构 | 说明 |
|---|---|---|
| `{streamKeyPrefix}:{topic}:p:{i}` | Stream | 分区流（第 i 个分区） |
| `{streamKeyPrefix}:{topic}:dlq` | Stream | 该 Topic 的死信流 |
| `{keyPrefix}:topics:registry` | Set | 全量 Topic 注册表 |
| `{keyPrefix}:topic:{topic}:meta` | Hash | 字段 `partitionCount` |
| `{keyPrefix}:topic:{topic}:partitions` | Set | 分区流键集合 |
| `{keyPrefix}:lease:{topic}:{group}:{partitionId}` | String+TTL | 分区租约（值为消费者名） |
| `{keyPrefix}:retry:{topic}` | ZSET | 重试桶，score=到期时间戳(ms) |
| `{keyPrefix}:retry:item:{topic}:{uuid}` | Hash | 重试条目 envelope |
| `{keyPrefix}:commit:{topic}:p:{i}` | Hash | 提交位点（field=组，value=已 ack 的最大 streamId） |
| `{keyPrefix}:acks:{topic}:p:{i}:{messageId}` | Set | all-groups-ack 删除策略的 ack 集合 |
| `{keyPrefix}:payload:{topic}:p:{i}:{uuid}` | String | 超 64KB 的大 payload（JSON），消息头持有引用 |
| `{keyPrefix}:payload:idx:{topic}` / `{keyPrefix}:payload:ts` | Set / ZSET | payload 索引（按 Topic / 按时间，供清理） |

例外：重试搬运的分布式锁键为字面常量 `streaming:mq:retry:lock:{topic}`（`RedisMessageConsumer.moveDueRetries` 内写死，不随 `keyPrefix` 变化）。

## 3. 对外接口（关键类与方法）

### 3.1 组装入口 `MessageQueueFactory`
```java
MessageQueueFactory(RedissonClient redissonClient)
MessageQueueFactory(RedissonClient redissonClient, MqOptions options)
MessageQueueFactory(RedissonClient redissonClient, MqOptions options, BrokerFactory brokerFactory)

MessageProducer createProducer()                       // 经 BrokerFactory 创建 Broker（默认 Redis）
MessageConsumer createConsumer()                       // 名称 = consumerNamePrefix + UUID 前 8 位
MessageConsumer createConsumer(String consumerName)
MessageConsumer createDeadLetterConsumer()             // DLQ 消费者（名称自动加 -dlq 后缀）
MessageConsumer createDeadLetterConsumer(String consumerName)
MessageConsumer createDeadLetterConsumerForTopic(String topic, String group, String consumerName, MessageHandler handler) // 订阅并启动
MessageQueueAdmin createAdmin()
```

### 3.2 生产者 `MessageProducer`（实现 `impl.BrokerBackedProducer`）
```java
CompletableFuture<String> send(Message message)
CompletableFuture<String> send(String topic, String key, Object payload)
CompletableFuture<String> send(String topic, Object payload)
void close();  boolean isClosed()
```

### 3.3 消费者 `MessageConsumer`（实现 `impl.RedisMessageConsumer`）
```java
void subscribe(String topic, MessageHandler handler)
void subscribe(String topic, String consumerGroup, MessageHandler handler)
void subscribe(String topic, String consumerGroup, MessageHandler handler, SubscriptionOptions options)
void unsubscribe(String topic)
void start();  void stop();  void close()
boolean isRunning();  boolean isClosed()
```
`RedisMessageConsumer` 同时实现 `control.PausableMessageConsumer`：`pause()` / `resume()` / `isPaused()` / `inFlight()`。

`MessageHandler`（函数式接口）返回 `MessageHandleResult`：
- `SUCCESS` 处理成功，默认 ACK（header `x-defer-ack=true` 可推迟 ACK，供 checkpoint 型运行时使用）
- `RETRY` 失败可重试（重新入队或进 DLQ，见 §5）
- `FAIL` / `DEAD_LETTER` 直接进 DLQ

### 3.4 管理 `admin.MessageQueueAdmin`（实现 `admin.impl.RedisMessageQueueAdmin`）
```java
QueueInfo getQueueInfo(String topic)
List<String> listAllTopics()
boolean topicExists(String topic)
List<ConsumerGroupInfo> getConsumerGroups(String topic)
ConsumerGroupStats getConsumerGroupStats(String topic, String group)
boolean consumerGroupExists(String topic, String group)
List<PendingMessage> getPendingMessages(String topic, String group, int limit)
List<PendingMessage> getPendingMessages(String topic, String group, int limit, PendingSort sort, boolean desc, long minIdleMs)
long getPendingCount(String topic, String group)
long trimQueue(String topic, long maxLen)                 // 保留最新 N 条（均摊到各分区）
long trimQueueByAge(String topic, Duration maxAge)        // 删除早于 maxAge 的条目
boolean deleteTopic(String topic)                          // 删分区流/DLQ/meta/分区集合/负载 hash，并移出注册表
boolean deleteConsumerGroup(String topic, String group)
boolean resetConsumerGroupOffset(String topic, String group, String messageId) // "0"=开头, "$"=最新
boolean updatePartitionCount(String topic, int newPartitionCount)              // 只允许增大
List<MessageEntry> listRecent(String topic, int perPartitionCount)             // 每分区上限 200
List<MessageEntry> range(String topic, int partitionId, String fromId, String toId, int count, boolean reverse)
```

### 3.5 死信
- `dlq.DeadLetterService` / `dlq.RedisDeadLetterService`：`send(DeadLetterRecord)`、`range(originalTopic, limit)`、`size`、`delete`、`clear`、`replay(originalTopic, id)`。构造时可传入 `ReplayHandler`（`publish(topic, partitionId, payload, headers, maxRetries)`）定制回放目标；不传则回放到原分区流。
- `dlq.DeadLetterAdmin` / `dlq.RedisDeadLetterAdmin`：`listTopics()`、`size`、`list(topic, limit)`、`replay`、`replayAll(topic, maxCount)`、`delete`、`clear`。
- `DeadLetterQueueManager`（模块根）：兼容的轻量 DLQ 工具，`getDeadLetterQueueSize` / `getDeadLetterMessages(topic, limit)` / `deleteMessage` / `clearDeadLetterQueue` / `replayMessage`。
- `dlq.DeadLetterConsumer` / `dlq.RedisDeadLetterConsumer`：DLQ 专用消费循环，`HandleResult` 为 `SUCCESS / RETRY / FAIL`；一般通过 `factory.createDeadLetterConsumer(...)` 的 `MessageConsumer` 适配视图使用（`impl.DlqConsumerAdapter`）。

### 3.6 Header 常量
- `MqHeaders`：`x-force-partition-id`（强制分区）、`partitionId`、`x-payload-missing` / `x-payload-missing-ref`、`x-original-message-id`、`x-defer-ack`。
- `impl.PayloadHeaders`：`x-payload-hash-ref`、`x-payload-original-size`、`x-payload-storage-type`（`inline` / `hash`）；阈值 `MAX_INLINE_PAYLOAD_SIZE = 64KB`。

## 4. 配置项 `config.MqOptions`

全部字段及默认值（与 Builder 方法同名，`mq/src/main/java/.../config/MqOptions.java`）：

| 字段 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| defaultPartitionCount | int | 1 | Topic 默认分区数 |
| workerThreads | int | 8 | Worker 线程池；`maxLeasedPartitionsPerConsumer=0` 时也是单实例分区上限 |
| schedulerThreads | int | 2 | 调度线程池（再均衡/续约/pending 扫描/重试搬运） |
| consumerBatchCount | int | 10 | 每次 XREADGROUP 条数 |
| consumerPollTimeoutMs | long | 1000 | 读取阻塞超时 |
| leaseTtlSeconds | int | 15 | 租约 TTL |
| rebalanceIntervalSec | int | 5 | 再均衡周期 |
| renewIntervalSec | int | 3 | 租约续约周期 |
| pendingScanIntervalSec | int | 30 | 孤儿 pending 扫描周期 |
| claimIdleMs | long | 300000 (5min) | 接管 pending 所需最小 idle |
| claimBatchSize | int | 50 | 每次扫描的 pending 条数 |
| maxInFlight | int | 0 | 单实例并发处理上限，0=不限制（信号量背压） |
| maxLeasedPartitionsPerConsumer | int | 0 | 单实例可租约分区上限，0=取 workerThreads |
| retryMaxAttempts | int | 5 | 重试策略上限；与消息 `maxRetries` 取小者为实际上限 |
| retryBaseBackoffMs | long | 1000 | 指数退避基数 |
| retryMaxBackoffMs | long | 60000 | 单次退避上限 |
| retryMoverBatch | int | 100 | 每轮搬运河取的重试条数 |
| retryMoverIntervalSec | int | 1 | 重试搬运周期 |
| retryLockWaitMs / retryLockLeaseMs | long | 100 / 500 | 搬运分布式锁等待/持有 |
| keyPrefix | String | `streaming:mq` | 控制键前缀 |
| streamKeyPrefix | String | `stream:topic` | 数据流前缀 |
| consumerNamePrefix | String | `consumer-` | 自动生成消费者名前缀 |
| dlqConsumerSuffix | String | `-dlq` | DLQ 消费者名后缀 |
| defaultConsumerGroup | String | `default-group` | `subscribe(topic, handler)` 用的组名 |
| defaultDlqGroup | String | `dlq-group` | DLQ 消费者默认组名 |
| retentionMaxLenPerPartition | int | 100000 | 写入路径按长度保留（`XADD ... MAXLEN = N` 精确裁剪），0=关闭 |
| retentionMs | long | 0 | 时间保留(ms)，0=关闭；mq 模块内仅用作大 payload TTL 上限，周期性 `XTRIM MINID` 由 spring-boot-starter 的 `StreamRetentionHousekeeper` 执行 |
| trimIntervalSec | int | 60 | 保留清理周期（同上，消费方在 starter） |
| dlqRetentionMaxLen | int | 0 | DLQ 长度保留覆盖，0=未启用 |
| dlqRetentionMs | long | 0 | DLQ 时间保留(ms)，0=关闭（starter housekeeper 消费） |
| ackDeletePolicy | String | `none` | ACK 后删除策略：`none` \| `immediate` \| `all-groups-ack` |
| acksetTtlSec | int | 86400 | all-groups-ack ack 集合 TTL |

Spring Boot 下以上字段经 `redis-streaming.mq.*` 映射（见 `Spring-Boot-Starter.md`）。

## 5. 重试与 DLQ 行为

- 实际重试上限 = `min(message.maxRetries, retryMaxAttempts)`；`Message` 默认 `maxRetries=3`、`retryCount=0`。
- `RETRY`：先重新入队再 ACK 原消息（顺序相反会丢消息）。退避 `retryBaseBackoffMs * 2^(n-1)` 封顶 `retryMaxBackoffMs`；退避 ≤ 50ms 直接重投分区流，否则写入重试 Hash + ZSET，由 Lua 原子执行「取到期 → XADD 回分区 → ZREM → DEL Hash」。重新入队使用新 stream id，原始 id 保留在 `x-original-message-id`。
- `FAIL` / `DEAD_LETTER` / 重试耗尽：`DeadLetterRecord` 写入 `{streamKeyPrefix}:{topic}:dlq`，DLQ 写入成功才 ACK；写入失败保持 pending 待重投。
- 解析失败（含大 payload 引用丢失）视为毒消息：直接进 DLQ 并 ACK，避免无限重认领。
- DLQ 回放（`replay`/`replayAll`/DLQ 消费者 RETRY）只重投并 ACK，不自动删除 DLQ 条目；清理需显式 `delete`/`clear` 或依赖 DLQ 保留策略。

## 6. 快速上手（与 `mq/src/test` 用法一致）

```java
RedissonClient client = Redisson.create(config);           // org.redisson.Redisson
MessageQueueFactory factory = new MessageQueueFactory(client,
        MqOptions.builder().defaultPartitionCount(4).build());

MessageProducer producer = factory.createProducer();
MessageConsumer consumer = factory.createConsumer("c1");

consumer.subscribe("orders", "order-group", m -> {
    handle(m);                                             // 业务处理
    return MessageHandleResult.SUCCESS;                    // SUCCESS / RETRY / FAIL / DEAD_LETTER
});
consumer.start();

Message m = new Message("orders", "k-1", Map.of("id", 1));  // (topic, key, payload)
m.setHeaders(Map.of("trace", "t-1"));
producer.send(m).get(10, TimeUnit.SECONDS);                 // 返回 Redis stream id

// ...

consumer.stop();
consumer.close();
producer.close();
```

按分区过滤订阅（`SubscriptionOptions`）：
```java
consumer.subscribe("orders", "order-group", handler, SubscriptionOptions.builder()
        .batchCount(20).pollTimeoutMs(500)
        .partitionModulo(2).partitionRemainder(0)   // 只接管满足 i % 2 == 0 的分区
        .build());
```

消费 DLQ：
```java
MessageConsumer dlqConsumer = factory.createDeadLetterConsumer("c-dlq");
dlqConsumer.subscribe("orders", "g-dlq", m -> MessageHandleResult.SUCCESS);
dlqConsumer.start();
```

## 7. 管理与治理

```java
MessageQueueAdmin admin = new RedisMessageQueueAdmin(client, options);
admin.listAllTopics();
admin.getQueueInfo("orders");                       // 跨分区聚合 length/组数/first/last
admin.getConsumerGroups("orders");
admin.getConsumerGroupStats("orders", "order-group");
admin.getPendingMessages("orders", "order-group", 20);
admin.resetConsumerGroupOffset("orders", "order-group", "0");
admin.trimQueue("orders", 1000);
admin.trimQueueByAge("orders", Duration.ofHours(1));
admin.updatePartitionCount("orders", 8);            // 只增不减
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

## 8. 指标

`metrics.MqMetrics` / `metrics.MqMetricsCollector`（默认 Noop，`MqMetrics.setCollector` 安装实现；starter 提供 `MqMicrometerCollector`）：

- 计数：`incProduced` / `incConsumed` / `incAcked` / `incRetried` / `incDeadLetter` / `incPayloadMissing` / `incDlqDelete` / `incDlqClear`
- 仪表：`setInFlight`（背压）、`setEligiblePartitions` / `setLeasedPartitions` / `setMaxLeasedPartitions`
- 计时：`recordHandleLatency`、`recordBackpressureWait`、`recordDlqReplay`
- 保留清理：`metrics.RetentionMetrics` / `RetentionMetricsCollector` 的 `recordTrim` / `recordDlqTrim`

标签维度：topic、partitionId、consumerName、consumerGroup、result/reason。

## 9. FAQ

- `XREADGROUP ... >`（never-delivered）的语义是「投递给本组但未被本组消费过的条目」，而不是「组创建之后写入的消息」。本实现的消费组一律以 `XGROUP CREATE ... 0-0 MKSTREAM` 创建，因此组创建前已存在的 backlog 也会被消费。
- 孤儿 pending 由调度线程按 `pendingScanIntervalSec` 扫描 `XPENDING`，对 idle 超过 `claimIdleMs` 的条目执行 `XCLAIM`（Redisson `claim`）接回重放。
- 同一组内同一分区同一时刻只有一个实例消费（租约独占，`leaseTtlSeconds` 过期即释放）；不同组相互独立。
- 大 payload（> 64KB）自动转存 `{keyPrefix}:payload:...`，消息头持 `x-payload-hash-ref`；ACK 时删除。引用丢失的消息进 DLQ 并带 `x-payload-missing` 标记。

## References

- 设计与取舍：[MQ-Design.md](./MQ-Design.md)
- Redis 命令级交互与序列图：[MQ-Broker-Interaction.md](./MQ-Broker-Interaction.md)
- 使用指南：[MQ-Guide.md](./MQ-Guide.md)
- 英文文档：[en/MQ-Design-en.md](./en/MQ-Design-en.md)、[en/MQ-Broker-Interaction-en.md](./en/MQ-Broker-Interaction-en.md)
