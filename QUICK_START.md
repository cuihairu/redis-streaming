# Streaming 框架快速入门教程

## 目录
1. [环境准备](#环境准备)
2. [5分钟快速开始](#5分钟快速开始)
3. [核心概念](#核心概念)
4. [常用功能示例](#常用功能示例)
5. [最佳实践](#最佳实践)
6. [常见问题](#常见问题)

## 环境准备

### 系统要求
- Java 17 或更高版本
- Redis 6.0 或更高版本
- Gradle 7.0 或更高版本 (可选，使用 Gradle Wrapper)

### 安装 Redis

**使用 Docker (推荐)**:
```bash
docker run -d -p 6379:6379 --name redis redis:7-alpine
```

**使用 Docker Compose**:
```bash
cd redis-streaming
docker-compose up -d
```

**验证 Redis**:
```bash
redis-cli ping
# 应该返回: PONG
```

## 5分钟快速开始

### 1. 添加依赖

在你的 `build.gradle` 中添加：

```gradle
dependencies {
    // 方式1: 使用 Spring Boot Starter (推荐)
    implementation 'io.github.cuihairu.redis-streaming:spring-boot-starter:0.1.1'

    // 方式2: 按需添加模块
    implementation 'io.github.cuihairu.redis-streaming:mq:0.1.1'
    implementation 'io.github.cuihairu.redis-streaming:registry:0.1.1'
    implementation 'io.github.cuihairu.redis-streaming:aggregation:0.1.1'
    implementation 'io.github.cuihairu.redis-streaming:cep:0.1.1'
}
```

### 2. 配置 Redis 连接

```java
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

public class QuickStart {
    public static void main(String[] args) {
        // 配置 Redis
        Config config = new Config();
        config.useSingleServer()
              .setAddress("redis://127.0.0.1:6379");

        RedissonClient redisson = Redisson.create(config);

        // 你的业务代码

        redisson.shutdown();
    }
}
```

### 3. Hello World - 消息队列

```java
import io.github.cuihairu.redis.streaming.mq.*;

MessageQueueFactory mq = new MessageQueueFactory(redisson);
MessageProducer producer = mq.createProducer();

// 发送消息（topic=hello-queue）
producer.send(new Message("hello-queue", "Hello, Streaming!")).join();

// 创建消费者并订阅
MessageConsumer consumer = mq.createConsumer("consumer-1");
consumer.subscribe("hello-queue", "consumer-group", message -> {
    System.out.println("收到消息: " + message.getPayload());
    return MessageHandleResult.SUCCESS;
});

// 启动消费
consumer.start();
```

## 核心概念

### 1. 消息队列 (MQ)

基于 Redis Streams 的可靠消息队列。

**特点**:
- 支持消费者组
- 死信队列 (DLQ)
- 异步消息处理
- 自动重试

**使用场景**: 解耦服务、异步处理、削峰填谷

### 2. 服务注册发现 (Registry)

轻量级服务注册与发现中心。

**特点**:
- 服务自动注册/注销
- 健康检查 (HTTP/TCP/WebSocket)
- 配置管理
- 服务订阅通知

**使用场景**: 微服务架构、服务治理

### 3. 窗口聚合 (Aggregation)

实时数据聚合和统计。

**特点**:
- 滚动窗口 / 滑动窗口
- PV/UV 统计
- Top-K 分析
- 分位数计算

**使用场景**: 实时大屏、业务监控、数据分析

### 4. 复杂事件处理 (CEP)

模式匹配和复杂事件检测。

**特点**:
- Kleene closure (*, +, ?, {n})
- 多种连续性约束
- 时间窗口约束
- 模式组合

**使用场景**: 风控检测、业务告警、用户行为分析

### 5. 流表二元性 (Table)

可更新的 KTable 抽象。

**特点**:
- 内存版 / Redis 持久化版
- 支持 map、filter、join
- 分布式访问

**使用场景**: 用户画像、实时状态维护

## 常用功能示例

### 示例 1: PV/UV 统计

```java
import io.github.cuihairu.redis.streaming.aggregation.analytics.PVCounter;
import io.github.cuihairu.redis.streaming.aggregation.analytics.UVCounter;
import java.time.Duration;
import java.time.Instant;

// 近 10 分钟窗口
Duration window = Duration.ofMinutes(10);

PVCounter pvCounter = new PVCounter(redisson, "page_views", window);
UVCounter uvCounter = new UVCounter(redisson, "unique_visitors", window);

// 记录页面访问
String pageUrl = "/index";
String userId = "user_123";
Instant now = Instant.now();

pvCounter.recordPageView(pageUrl, now);
uvCounter.add(pageUrl, userId, now);

// 获取统计结果
long pv = pvCounter.getPageViewCount(pageUrl);
long uv = uvCounter.count(pageUrl);

System.out.println("PV: " + pv + ", UV: " + uv);
```

### 示例 2: Top-K 热榜

```java
import io.github.cuihairu.redis.streaming.aggregation.analytics.TopKAnalyzer;
import java.time.Duration;
import java.util.List;

Duration window = Duration.ofMinutes(10);
TopKAnalyzer topK = new TopKAnalyzer(redisson, "hot_products", 10, window);

// 添加数据
topK.recordItem("products", "iPhone15");
topK.recordItem("products", "MacBook");
topK.recordItem("products", "iPad");

// 获取排行榜
List<TopKAnalyzer.TopKItem> top10 = topK.getTopK("products");
System.out.println("热门商品: " + top10);
```

### 示例 3: 复杂事件检测

```java
import io.github.cuihairu.redis.streaming.cep.*;

// 定义模式: 连续3次登录失败
PatternSequence<LoginEvent> pattern = PatternSequence.<LoginEvent>begin()
    .where("FAILED", Pattern.of(e -> e.getStatus().equals("FAILED")))
    .times(3)
    .within(Duration.ofMinutes(5));

// 创建匹配器
PatternSequenceMatcher<LoginEvent> matcher =
    new PatternSequenceMatcher<>(pattern);

// 处理事件
for (LoginEvent event : events) {
    List<CompleteMatch<LoginEvent>> matches =
        matcher.process(event, event.getTimestamp());

    if (!matches.isEmpty()) {
        System.out.println("检测到异常登录: " + event.getUserId());
        // 触发告警
    }
}
```

### 示例 4: Redis KTable

```java
import io.github.cuihairu.redis.streaming.table.impl.RedisKTable;
import io.github.cuihairu.redis.streaming.table.KTable;

record User(String name, int age) {}
record Profile(String city) {}
record UserProfile(User user, Profile profile) {}

// 创建 KTable
RedisKTable<String, User> userTable = new RedisKTable<>(
    redisson, "users", String.class, User.class
);
RedisKTable<String, Profile> profileTable = new RedisKTable<>(
    redisson, "profiles", String.class, Profile.class
);

// 插入/更新数据
userTable.put("user1", new User("Alice", 30));
userTable.put("user2", new User("Bob", 25));
profileTable.put("user1", new Profile("Shanghai"));
profileTable.put("user2", new Profile("Beijing"));

// 查询
User user = userTable.get("user1");

// 转换
KTable<String, String> names = userTable.mapValues(User::getName);

// 过滤
KTable<String, User> adults = userTable.filter((k, v) -> v.getAge() >= 18);

// Join
KTable<String, UserProfile> enriched = userTable.join(
    profileTable,
    (user, profile) -> new UserProfile(user, profile)
);
```

### 示例 5: Prometheus 监控

```java
import io.github.cuihairu.redis.streaming.metrics.prometheus.*;
import java.util.Map;

// 启动 Exporter (暴露 /metrics)
try (PrometheusExporter exporter = new PrometheusExporter(9090)) {
    PrometheusMetricCollector metrics = new PrometheusMetricCollector("myapp");

    // 记录指标
    metrics.incrementCounter("requests_total");
    metrics.setGauge("active_connections", 42);
    metrics.recordHistogram("request_duration_seconds", 0.25);

    // 带标签的指标
    Map<String, String> tags = Map.of("method", "GET", "status", "200");
    metrics.incrementCounter("http_requests", tags);

    // 访问 http://localhost:9090/metrics 查看指标
}
```

### 示例 6: CDC 数据捕获

```java
import io.github.cuihairu.redis.streaming.cdc.*;

// 配置 MySQL Binlog CDC
CDCConfiguration config = CDCConfigurationBuilder.builder()
    .host("localhost")
    .port(3306)
    .database("mydb")
    .username("cdc_user")
    .password("password")
    .includeTables("users", "orders")
    .build();

// 创建 CDC 连接器
CDCConnector cdc = new MySQLBinlogCDCConnector(config);

cdc.setEventListener(new CDCEventListener() {
    @Override
    public void onEventsCapture(String connectorName, int eventCount) {
        System.out.println("捕获到变更事件数: " + eventCount);
    }
});

cdc.start().join();
List<ChangeEvent> events = cdc.poll();
```

## 最佳实践

### 1. Redis 连接管理

**推荐**: 使用单例模式管理 RedissonClient

```java
public class RedisManager {
    private static volatile RedissonClient instance;

    public static RedissonClient getInstance() {
        if (instance == null) {
            synchronized (RedisManager.class) {
                if (instance == null) {
                    Config config = new Config();
                    config.useSingleServer()
                          .setAddress("redis://127.0.0.1:6379")
                          .setConnectionPoolSize(20)
                          .setConnectionMinimumIdleSize(5);
                    instance = Redisson.create(config);
                }
            }
        }
        return instance;
    }
}
```

### 2. 异常处理

**推荐**: 使用 RetryExecutor 处理瞬时故障

```java
import io.github.cuihairu.redis.streaming.reliability.retry.*;

RetryPolicy policy = RetryPolicy.builder()
    .maxAttempts(3)
    .initialDelay(Duration.ofSeconds(1))
    .backoffMultiplier(2.0)
    .build();

RetryExecutor executor = new RetryExecutor(policy);

executor.execute(() -> {
    // 可能失败的操作
    sendToExternalAPI(data);
    return null;
});
```

### 3. 资源清理

**推荐**: 使用 try-with-resources 或 finally 块

```java
RedissonClient redisson = Redisson.create(config);
try {
    // 业务逻辑
} finally {
    redisson.shutdown();
}
```

### 4. 性能优化

**批量操作**:
```java
// ❌ 不推荐
for (String id : ids) {
    kvTable.put(id, getValue(id));
}

// ✅ 推荐
Map<String, Value> batch = new HashMap<>();
for (String id : ids) {
    batch.put(id, getValue(id));
}
// 批量写入 (如果支持)
```

**连接池配置**:
```java
config.useSingleServer()
      .setConnectionPoolSize(50)  // 根据并发量调整
      .setConnectionMinimumIdleSize(10)
      .setIdleConnectionTimeout(10000)
      .setConnectTimeout(10000)
      .setTimeout(3000);
```

### 5. 监控和可观测性

**推荐**: 启用 Prometheus 监控

```java
// 在应用启动时
PrometheusExporter exporter = new PrometheusExporter(9090);
PrometheusMetricCollector metrics = new PrometheusMetricCollector("myapp");

// 在关键路径记录指标
metrics.incrementCounter("business_operations");
Timer timer = metrics.startTimer();
try {
    // 业务逻辑
} finally {
    metrics.recordTimer("operation_duration", timer);
}
```

## 常见问题

### Q1: Redis 连接失败怎么办？

**A**: 检查以下几点：
1. Redis 服务是否启动: `redis-cli ping`
2. 地址和端口是否正确
3. 防火墙是否允许连接
4. 如果使用 Docker，检查网络配置

### Q2: 如何查看 Prometheus 指标？

**A**:
1. 启动 PrometheusExporter
2. 浏览器访问 `http://localhost:9090/metrics`
3. 或配置 Prometheus 服务器抓取该端点

### Q3: KTable 数据丢失怎么办？

**A**:
1. 检查 Redis 持久化配置 (RDB/AOF)
2. 使用 RedisKTable 而非 InMemoryKTable
3. 定期备份 Redis 数据

### Q4: CEP 模式不匹配？

**A**:
1. 检查时间窗口是否足够大
2. 检查模式定义是否正确
3. 启用日志查看详细匹配过程
4. 使用单元测试验证模式逻辑

### Q5: 如何提高性能？

**A**:
1. 增大 Redis 连接池大小
2. 使用批量操作
3. 合理设置 TTL 避免数据堆积
4. 使用 Redis Cluster 分散负载
5. 考虑使用 Redis 管道 (Pipeline)

### Q6: 生产环境部署建议？

**A**:
1. 使用 Redis 主从复制或 Cluster
2. 配置 Redis 持久化 (AOF + RDB)
3. 监控 Redis 内存使用
4. 设置合理的资源限制
5. 启用 Prometheus 监控
6. 配置日志聚合系统

## 下一步

- 查看 [完整文档](README.md)
- 运行 [示例程序](examples/)
- 阅读 [API 文档](docs/API.md)
- 加入 [社区讨论](https://github.com/cuihairu/redis-streaming/discussions)

## 获取帮助

- [文档: [README.md](README.md)]
- [问题反馈: [GitHub Issues](https://github.com/cuihairu/redis-streaming/issues)]
- [讨论: [GitHub Discussions](https://github.com/cuihairu/redis-streaming/discussions)]
- [邮件: chuihairu@gmail.com]

---

**祝你使用愉快！**
