# Reliability

Module: `reliability/`

Retry / Dead Letter Queue / Deduplication / Rate Limiting

## 1. 目标与范围
- 提供处理可靠性保障：瞬时失败重试、异常送入 DLQ、重复数据抑制、速率限制等能力
- 支持纯内存与 Redis 两种实现（去重/限流）

## 2. 核心类与包
- 重试：`RetryExecutor`, `RetryPolicy`
- DLQ：`dlq.DeadLetterService`, `DeadLetterAdmin`, `RedisDeadLetterService`, `RedisDeadLetterAdmin`, `RedisDeadLetterConsumer`, `DeadLetterRecord`, `DeadLetterEntry`
- 去重：`deduplication.BloomFilterDeduplicator`, `SetDeduplicator`, `WindowedDeduplicator`, `DeduplicatorFactory`
- 限流：`ratelimit.InMemorySlidingWindowRateLimiter`, `InMemoryTokenBucketRateLimiter`, `RedisSlidingWindowRateLimiter`, `RedisTokenBucketRateLimiter`, `RateLimitingSink`, `NamedRateLimiter`
- 指标：`reliability.metrics.ReliabilityMetrics*`, `RateLimitMetrics*`

## 3. 最小示例（占位）
### 3.1 重试
```java
RetryPolicy p = RetryPolicy.builder()
    .maxAttempts(3)
    .initialDelay(Duration.ofMillis(100))
    .build();
RetryExecutor ex = new RetryExecutor(p);
String r = ex.execute(x -> callExternal((String)x), "input");
```

### 3.2 DLQ 发送与回放
```java
DeadLetterService svc = new RedisDeadLetterService(redisson);
DeadLetterRecord rec = new DeadLetterRecord();
rec.originalTopic = "topicA"; rec.payload = "oops"; rec.maxRetries = 3;
svc.send(rec);
// admin 回放/清理
DeadLetterAdmin admin = new RedisDeadLetterAdmin(redisson, svc);
admin.replayAll("topicA", 100);
```

### 3.3 去重
```java
Deduplicator<String> d = DeduplicatorFactory.createSet(redisson, "dedup:orders", x -> x);
boolean dup = d.checkAndMark("order-1");
```

### 3.4 限流 + Sink 包裹
```java
RateLimiter rl = new RedisSlidingWindowRateLimiter(redisson, "streaming:rl", 1000, 100);
StreamSink<String> wrapped = RateLimitingSink.drop(rl, k -> k, originalSink);
```

## 4. 指标（占位）
- Reliability：`dlq_replay_total`/`dlq_replay_duration_nanos`、`dlq_delete_total`、`dlq_clear_total`
- RateLimit：`ratelimit_allowed_total`、`ratelimit_denied_total`

## 5. 常见问题（占位）
- 组消费 ack 语义：仅回放成功才 ack；失败/异常不 ack
- headers 为字符串 JSON：已在 `DeadLetterCodec` 中兼容解析
- 测试模式钩子：`reliability.dlq.test.*` 系统属性仅用于 IT 稳定，不影响生产

## References
- Design.md
