# Reliability 模块

## 概述

Reliability 模块提供可靠性保障组件，包括消息去重、死信队列、限流控制等功能，确保流处理系统的可靠性和稳定性。

## 核心组件

### 1. 死信队列 (DLQ)

处理无法正常消费的消息，支持重试和人工介入。

**核心接口**:
- `DeadLetterService`: 死信队列服务
- `DeadLetterConsumer`: 死信队列消费者
- `DeadLetterAdmin`: 死信队列管理

**使用方式**:
```java
// 发送到死信队列
deadLetterService.send("topic", partitionId, payload, headers);

// 消费死信队列
deadLetterConsumer.consume("topic", (message) -> {
    System.out.println("处理死信: " + message);
});

// 查询死信队列
List<DeadLetter> letters = deadLetterAdmin.getDeadLetters("topic", 100);
```

### 2. 限流器

控制请求速率，防止系统过载。

**核心接口**:
- `RateLimiter`: 限流器接口
- `RateLimiterRegistry`: 限流器注册表

**实现类型**:
- `InMemorySlidingWindowRateLimiter`: 内存滑动窗口限流
- `RedisSlidingWindowRateLimiter`: Redis滑动窗口限流
- `InMemoryTokenBucketRateLimiter`: 内存令牌桶限流
- `RedisTokenBucketRateLimiter`: Redis令牌桶限流
- `InMemoryLeakyBucketRateLimiter`: 内存漏桶限流

**使用方式**:
```java
// 创建限流器
RateLimiter limiter = new InMemorySlidingWindowRateLimiter(
    1000,  // 窗口大小(ms)
    100   // 限流值
);

// 尝试获取许可
if (limiter.tryAcquire()) {
    // 处理请求
} else {
    // 限流
}
```

### 3. 消息去重

防止重复处理消息。

**使用方式**:
```java
// 基于Redis的去重
RedisDeduplicator deduplicator = new RedisDeduplicator(redisson, "dedup:", 3600);

// 检查并标记
if (!deduplicator.isDuplicate(messageId)) {
    deduplicator.markAsProcessed(messageId);
    // 处理消息
}
```

## Redis 数据结构

### 死信队列

- **队列存储**: List
  - Key: `dlq:{topic}:{partitionId}`
  - Elements: 序列化的消息

- **索引**: Sorted Set
  - Key: `dlq:{topic}:index`
  - Score: 时间戳
  - Member: messageId

### 限流器

- **滑动窗口**: Redis Sorted Set
- **令牌桶**: Redis String + Lua脚本

## Spring Boot 集成

**配置** (application.yml):
```yaml
redis-streaming:
  ratelimit:
    enabled: true
    window-ms: 1000
    limit: 100
    backend: redis  # or memory
    policies:
      api1:
        algorithm: sliding
        window-ms: 1000
        limit: 10
      api2:
        algorithm: token-bucket
        capacity: 100
        rate-per-second: 50
```

**使用**:
```java
@Autowired
private RateLimiterRegistry rateLimiterRegistry;

public void handleRequest(String apiKey) {
    RateLimiter limiter = rateLimiterRegistry.get(apiKey);
    if (limiter.tryAcquire()) {
        // 处理请求
    } else {
        throw new RateLimitExceededException();
    }
}
```

## 相关文档

- [MQ 模块](MQ.md) - 消息队列
- [Spring Boot Starter](Spring-Boot-Starter.md) - Spring Boot集成
