# Streaming 框架性能优化指南

## Redis 优化

### 1. 连接池配置

```java
Config config = new Config();
config.useSingleServer()
      .setAddress("redis://127.0.0.1:6379")
      .setConnectionPoolSize(50)        // 连接池大小
      .setConnectionMinimumIdleSize(10) // 最小空闲连接
      .setIdleConnectionTimeout(10000)  // 空闲超时
      .setConnectTimeout(10000)         // 连接超时
      .setTimeout(3000);                // 操作超时
```

### 2. Pipeline 批量操作

```java
// ❌ 不推荐 - 逐个操作
for (int i = 0; i < 1000; i++) {
    redisson.getBucket("key" + i).set("value" + i);
}

// ✅ 推荐 - 使用 Pipeline
RBatch batch = redisson.createBatch();
for (int i = 0; i < 1000; i++) {
    batch.getBucket("key" + i).setAsync("value" + i);
}
batch.execute();
```

### 3. 内存优化

```conf
# redis.conf
maxmemory 4gb
maxmemory-policy allkeys-lru

# 开启 key 过期
maxmemory-samples 5

# 压缩配置
hash-max-ziplist-entries 512
hash-max-ziplist-value 64
```

### 4. 持久化策略

```conf
# 方案1: RDB (快照)
save 900 1
save 300 10
save 60 10000

# 方案2: AOF (追加)
appendonly yes
appendfsync everysec

# 方案3: 混合模式 (推荐)
aof-use-rdb-preamble yes
```

## 应用层优化

### 1. 异步处理

```java
// ❌ 同步发送
producer.send(message);

// ✅ 异步发送
CompletableFuture<String> future = producer.sendAsync(message);
future.thenAccept(msgId -> log.info("Sent: {}", msgId));
```

### 2. 批量消费

```java
// ❌ 逐个消费
consumer.consume(message -> {
    process(message);
    return MessageHandleResult.SUCCESS;
});

// ✅ 批量消费
consumer.consumeBatch(messages -> {
    processBatch(messages);
    return BatchHandleResult.SUCCESS;
}, 100);  // 批量大小
```

### 3. 合理使用缓存

```java
// 本地缓存 + Redis
LoadingCache<String, User> cache = Caffeine.newBuilder()
    .maximumSize(10000)
    .expireAfterWrite(Duration.ofMinutes(5))
    .build(key -> getUserFromRedis(key));
```

### 4. 连接复用

```java
// ❌ 每次创建新连接
public void process() {
    RedissonClient client = Redisson.create(config);
    // ...
    client.shutdown();
}

// ✅ 使用单例
public class RedisManager {
    private static final RedissonClient instance = 
        Redisson.create(config);
    
    public static RedissonClient getInstance() {
        return instance;
    }
}
```

## 窗口优化

### 1. 限制窗口大小

```java
// ❌ 无限制窗口
WindowAggregator aggregator = new WindowAggregator(
    redisson, "metrics", new TumblingWindow(Duration.ofDays(1))
);

// ✅ 限制窗口大小
WindowAggregator aggregator = new WindowAggregator(
    redisson, "metrics",
    new TumblingWindow(Duration.ofMinutes(5))  // 小窗口
);
```

### 2. 及时清理过期数据

```java
// 设置 TTL
aggregator.setTTL(Duration.ofHours(1));

// 手动清理
aggregator.cleanup(timestamp - windowSize);
```

## 状态管理优化

### 1. 选择合适的状态类型

```java
// ❌ 大量小对象用 MapState
MapState<String, SmallObject> state = 
    stateBackend.getMapState(descriptor);

// ✅ 序列化后用 ValueState
ValueState<Map<String, SmallObject>> state = 
    stateBackend.getValueState(descriptor);
```

### 2. 检查点优化

```java
CheckpointConfig config = CheckpointConfig.builder()
    .checkpointInterval(Duration.ofMinutes(5))  // 不要太频繁
    .minPauseBetweenCheckpoints(Duration.ofMinutes(1))
    .maxConcurrentCheckpoints(1)
    .build();
```

## CEP 优化

### 1. 限制匹配数量

```java
// ✅ 设置合理的时间窗口
PatternSequence<Event> pattern = PatternSequence.<Event>begin()
    .where("A", patternA)
    .followedBy("B", patternB)
    .within(Duration.ofMinutes(5));  // 限制时间窗口
```

### 2. 清理过期状态

```java
// 定期清理部分匹配
matcher.cleanupExpired(currentTime);
```

## 监控指标

### 1. 关键指标

```java
// 吞吐量
metrics.incrementCounter("messages_processed");

// 延迟
Timer timer = metrics.startTimer();
try {
    process();
} finally {
    metrics.recordTimer("process_duration", timer);
}

// 错误率
metrics.incrementCounter("errors", tags);
```

### 2. Redis 指标

```bash
# 内存使用
redis-cli info memory | grep used_memory_human

# 命令统计
redis-cli info stats | grep instantaneous_ops_per_sec

# 慢查询
redis-cli slowlog get 10
```

## JVM 调优

### 1. 堆内存配置

```bash
# 设置堆大小
java -Xms4g -Xmx4g \
     -XX:MetaspaceSize=256m \
     -XX:MaxMetaspaceSize=512m \
     -jar app.jar
```

### 2. GC 优化

```bash
# G1GC (推荐)
java -XX:+UseG1GC \
     -XX:MaxGCPauseMillis=200 \
     -XX:G1HeapRegionSize=16m \
     -jar app.jar

# ZGC (低延迟)
java -XX:+UseZGC \
     -jar app.jar
```

### 3. GC 日志

```bash
java -Xlog:gc*:file=gc.log:time,uptime:filecount=10,filesize=100M \
     -jar app.jar
```

## 网络优化

### 1. 使用本地 Redis

```yaml
# 应用和 Redis 部署在同一机器
streaming:
  redis:
    url: redis://127.0.0.1:6379
```

### 2. 启用 TCP 优化

```conf
# redis.conf
tcp-backlog 511
tcp-keepalive 300
```

## 性能测试

### 1. 压力测试

```java
@Test
void performanceTest() {
    int messageCount = 100000;
    long start = System.currentTimeMillis();
    
    for (int i = 0; i < messageCount; i++) {
        producer.sendAsync(new Message("msg-" + i, data));
    }
    
    long duration = System.currentTimeMillis() - start;
    double tps = messageCount * 1000.0 / duration;
    
    System.out.println("TPS: " + tps);
}
```

### 2. 基准测试

```bash
# Redis 基准测试
redis-benchmark -t set,get -n 100000 -q

# 自定义测试
redis-benchmark -t set -n 100000 -d 1000 -q
```

## 性能指标参考

### 单机 Redis

| 操作 | QPS |
|------|-----|
| SET | 100K+ |
| GET | 100K+ |
| LPUSH | 80K+ |
| ZADD | 80K+ |
| PFADD | 60K+ |

### 框架性能

| 场景 | 吞吐量 |
|------|--------|
| 消息队列 | 10K+ msg/s |
| PV 统计 | 50K+ op/s |
| Top-K | 20K+ op/s |
| CEP 匹配 | 5K+ event/s |

## 故障定位

### 1. 慢查询分析

```bash
# 启用慢查询日志
redis-cli config set slowlog-log-slower-than 10000

# 查看慢查询
redis-cli slowlog get 10
```

### 2. CPU 分析

```bash
# 使用 jstack
jstack <pid> > thread_dump.txt

# 使用 async-profiler
./profiler.sh -d 60 -f cpu.html <pid>
```

### 3. 内存分析

```bash
# 堆转储
jmap -dump:format=b,file=heap.bin <pid>

# 使用 MAT 分析
mat heap.bin
```

---

**版本**: 0.1.0
**更新日期**: 2025-01-10
