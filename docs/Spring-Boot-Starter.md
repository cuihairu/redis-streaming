# Spring Boot Starter 使用指南

模块目录:`spring-boot-starter/`。把 core/runtime/mq/registry/config/reliability 组件装配成 Spring Bean。自动配置注册于 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`。

## 快速开始

### 1. 添加依赖

```groovy
implementation 'io.github.cuihairu.redis-streaming:spring-boot-starter:<version>'
```

### 2. 启用方式(二选一)

- **标准 Boot 应用**:什么都不用加,starter 在 classpath 即自动装配;
- 或显式标注 `@EnableRedisStreaming`(`/@Import(RedisStreamingAutoConfiguration.class)` 的别名注解)。

### 3. 配置文件(前缀固定为 `redis-streaming`,不是 `streaming`)

完整可运行样例:`examples/src/main/resources/application.yml`
启动示例:`./gradlew :examples:run -PmainClass=io.github.cuihairu.redis.streaming.examples.springboot.StarterExampleApplication`

## 自动配置结构(0.3 起拆分)

| 类 | 属性开关 | 职责 |
|---|---|---|
| `RedisStreamingAutoConfiguration` | 类级 `@ConditionalOnClass(RedissonClient)` | 共享 `RedissonClient` 兜底 Bean + 限流指标桥 |
| `RedisStreamingRegistryAutoConfiguration` | `redis-streaming.registry.enabled`(默 true) | `NamingService`、`ServiceChangeListenerProcessor`、`LoadBalancer`、`ClientSelector`、`RetryPolicy`、`ClientInvoker` |
| `RedisStreamingDiscoveryAutoConfiguration` | `redis-streaming.discovery.enabled`(默 true) | 仅当无 `NamingService` 时提供 `ServiceDiscovery` |
| `RedisStreamingConfigServiceAutoConfiguration` | `redis-streaming.config.enabled`(默 true) | `ConfigService` |
| `RedisStreamingMqAutoConfiguration` | `redis-streaming.mq.enabled`(默 true) | `MqOptions`、`BrokerFactory/BrokerRouter`、`MessageQueueFactory/Admin`、DLQ(service/admin/consumer/replay producer)、保留治理 `StreamRetentionHousekeeper`、Micrometer 桥 |
| `RedisStreamingRateLimitAutoConfiguration` | `redis-streaming.ratelimit.enabled`(默 **false**) | `RateLimiterRegistry`、`@Primary RateLimiter` |

**可选依赖守卫**(保证无 actuator/micrometer 也能启动):
- 所有 Micrometer collector/installer Bean 均为 `@ConditionalOnClass(MeterRegistry)` **且** `@ConditionalOnBean(MeterRegistry/collector)`;
- `MqHealthIndicator` 位于独立嵌套配置,以类级 `@ConditionalOnClass(HealthIndicator)` 守卫;
- Redisson 仅在有 `RedissonClient` Bean 缺失时创建简化单机客户端;生产集群/SSL 建议自置 `RedissonClient`(此时本 Bean 自动让位),空密码不会发送 `AUTH`。

## 配置项参考(实测前缀 `redis-streaming.`)

### redis
`address`(默认 `redis://127.0.0.1:6379`)、`password`、`database`、`connect-timeout`、`timeout`、`connection-pool-size`、`connection-minimum-idle-size`

### registry
`enabled`、`heartbeat-interval`(s)、`heartbeat-timeout`(s)、`auto-register`、`instance.{service-name,instance-id,host,port,weight,enabled,protocol,ephemeral,metadata.*}`、`metrics.{enabled,intervals,default-interval,immediate-update-on-significant-change,timeout}`

### discovery / config
- `discovery.{enabled,healthy-only,cache-time}`
- `config.{enabled,default-group,refresh-interval,auto-refresh,history-size,key-prefix,enable-key-prefix}`

### load-balancer / invoker
- `load-balancer.{strategy(scored|wrr|weighted-random|consistent-hash),preferred-region,preferred-zone,cpu-weight,latency-weight,...,max-*-percent}`
- `invoker.{max-attempts,initial-delay-ms,backoff-factor,max-delay-ms,jitter-ms}`

### mq(扁平键,对应 `MqProperties`)
`enabled`、`default-partition-count`、`worker-threads`、`scheduler-threads`、`consumer-batch-count`、`consumer-poll-timeout-ms`、`lease-ttl-seconds`、`rebalance-interval-sec`、`renew-interval-sec`、`pending-scan-interval-sec`、`claim-idle-ms`、`claim-batch-size`、`max-in-flight`、`max-leased-partitions-per-consumer`、`retry-max-attempts`、`retry-base-backoff-ms`、`retry-max-backoff-ms`、`retry-mover-batch`、`retry-mover-interval-sec`、`retry-lock-wait-ms`、`retry-lock-lease-ms`、`key-prefix`、`stream-key-prefix`、`consumer-name-prefix`、`dlq-consumer-suffix`、`default-consumer-group`、`default-dlq-group`、`retention-max-len-per-partition`、`retention-ms`、`trim-interval-sec`、`ack-delete-policy`、`ackset-ttl-sec`、`dlq-retention-max-len`、`dlq-retention-ms`、`broker.type(redis|jdbc)`、`broker.jdbc.{driver-class-name,url,username,password}`

### ratelimit
`enabled`、`backend(memory|redis)`、`window-ms`、`limit`、`key-prefix`、`default-name`、`policies.<名称>.{algorithm(sliding|token-bucket|leaky-bucket),backend,window-ms,limit,capacity,rate-per-second,key-prefix}`

## 注入示例

```java
@RestController
public class DemoController {
    private final MessageQueueFactory mq;
    private final ConfigService configService;
    private final RateLimiter rateLimiter; // ratelimit.enabled=true 时存在

    public DemoController(MessageQueueFactory mq, ConfigService configService,
                          @Autowired(required = false) RateLimiter rateLimiter) {
        this.mq = mq; this.configService = configService; this.rateLimiter = rateLimiter;
    }

    @PostMapping("/orders")
    public String publish(@RequestBody String body) throws Exception {
        return mq.createProducer().send("orders", "k1", body).get();
    }
}
```

## 指标导出

micrometer-core 为 `api` 依赖、actuator 为 optional。桥接链路:`RedisRuntimeMetrics/MqMetrics/RetentionMetrics/ReliabilityMetrics/RateLimitMetrics` 单例 → `*MicrometerCollector` → `MeterRegistry`(指标前缀 `redis_streaming_runtime_*`、`mq_*`、`retention_*`、`reliability_*`、`ratelimit_*`)。

## 常见问题

- **与 redisson-spring-boot-starter 共存**:对方提供 `RedissonClient` 即覆盖本 starter 的兜底客户端(注意其前缀是 `spring.data.redis*`)。
- **redis-streaming 与 spring.data 混淆**:本 starter 只读 `redis-streaming.*`;历史文档中的 `streaming.*` 前缀均已废弃。
- **actuator 缺失报 HealthIndicator 错**:已修复(见上文可选依赖守卫);若仍出现请升级到 ≥ 本版本。
- **限流不生效**:`ratelimit.enabled` 默认 false,需显式开启。

## 相关文档
[runtime.md](runtime.md) · [MQ.md](MQ.md) · [Registry.md](Registry.md) · [config.md](config.md)
