# Spring Boot Starter 使用指南

模块目录:`spring-boot-starter/`。职责:把 core/runtime/mq/registry/config/reliability 组件装配成 Spring Bean,提供兜底 `RedissonClient`、服务注册/发现/配置中心、MQ 与 DLQ、限流器、保留治理后台任务、健康检查与 Micrometer 指标桥。

Maven 坐标 `io.github.cuihairu.redis-streaming:spring-boot-starter`(传递依赖:`core`/`registry`/`config`/`mq`/`runtime`/`reliability`/`spring-boot-starter`;`micrometer-core` 为 `api`,`actuator` 为 `compileOnly`,不强制引入)。

## 快速开始

### 1. 添加依赖

```groovy
implementation 'io.github.cuihairu.redis-streaming:spring-boot-starter:0.2.0'
```

### 2. 启用方式(二选一)

- 标准 Boot 应用:starter 在 classpath 即自动装配(注册入口是 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`,目前只列了 `RedisStreamingAutoConfiguration`,由它 `@Import` 各功能域配置);
- 或在配置类/启动类上显式标注 `@EnableRedisStreaming`(`@Import(RedisStreamingAutoConfiguration.class)` 的别名注解)。

```java
@SpringBootApplication
@EnableRedisStreaming
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
```

### 3. 配置文件(前缀固定为 `redis-streaming`,不是 `streaming`)

完整可运行样例:`examples/src/main/resources/application.yml`
启动示例:`./gradlew :examples:run -PmainClass=io.github.cuihairu.redis.streaming.examples.springboot.StarterExampleApplication`

```yaml
redis-streaming:
  redis:
    address: redis://127.0.0.1:6379
    database: 0
  registry:
    enabled: true
    auto-register: true
    instance:
      service-name: ${spring.application.name}
      port: ${server.port}
  discovery:
    enabled: true

spring:
  application:
    name: user-service
server:
  port: 8080
```

## 自动配置结构

| 类 | 属性开关(默认值) | 装配的 Bean |
|---|---|---|
| `RedisStreamingAutoConfiguration` | 类级 `@ConditionalOnClass(RedissonClient)` | 兜底 `RedissonClient`(`@ConditionalOnMissingBean`;单机简化配置,`password` 为空/空白时不设置密码)、`RateLimitMicrometerCollector` + `installRateLimitCollector` |
| `RedisStreamingRegistryAutoConfiguration` | `redis-streaming.registry.enabled`(默认 `true`) | `NamingService`、`ServiceChangeListenerProcessor`、`LoadBalancer`、`ClientSelector`、`RetryPolicy`、`RedisClientMetricsReporter`、`ClientInvoker`、`ClientInvokerMetricsBinder` |
| `RedisStreamingDiscoveryAutoConfiguration` | `redis-streaming.discovery.enabled`(默认 `true`) | `ServiceDiscovery`——仅当 classpath 无 `NamingService`/`ServiceDiscovery` Bean 时兜底 |
| `RedisStreamingConfigServiceAutoConfiguration` | `redis-streaming.config.enabled`(默认 `true`) | `ConfigService`(启动即 `start()`) |
| `RedisStreamingMqAutoConfiguration` | `redis-streaming.mq.enabled`(默认 `true`) | `MqOptions`、`BrokerFactory`/`BrokerRouter`、`MessageQueueFactory`、`MessageQueueAdmin`、`dlqReplayProducer`、`dlqReplayHandler`、`DeadLetterService`/`DeadLetterAdmin`/`DeadLetterConsumer`/`DeadLetterQueueManager`、保留治理 `StreamRetentionHousekeeper`(destroyMethod=close)、Micrometer 桥(见「指标导出」)、`MqHealthIndicator` |
| `RedisStreamingRateLimitAutoConfiguration` | `redis-streaming.ratelimit.enabled`(默认 **`false`**) | `RateLimiterRegistry`、`@Primary RateLimiter`(按 `default-name` 取) |

`LoadBalancer` 按 `load-balancer.strategy` 选择实现:`wrr → WeightedRoundRobinLoadBalancer`、`weighted-random → WeightedRandomLoadBalancer`、`consistent-hash → ConsistentHashLoadBalancer`,其余(含默认 `scored`)→ `ScoredLoadBalancer` + `RedisMetricsProvider`。
`BrokerFactory` 按 `mq.broker.type` 选择:`jdbc` 且 classpath 有 `DataSource` Bean 时用 `JdbcBrokerFactory`,否则(无 DataSource 或 `redis`)用 `RedisBrokerFactory` 并记录告警。

可选依赖都有守卫,不引 actuator/micrometer 也能启动:
- 所有 Micrometer collector/binder Bean 均有 `@ConditionalOnClass(MeterRegistry)` 守卫,`install*Collector` 额外要求对应 collector Bean 已存在;
- `MqHealthIndicator` 位于 `RedisStreamingMqAutoConfiguration` 的独立嵌套配置类,以类级 `@ConditionalOnClass(HealthIndicator)` 守卫;
- Redisson 仅在项目里没有 `RedissonClient` Bean 时创建简化单机客户端;集群/哨兵/SSL 请自行提供 `RedissonClient`(本 Bean 自动让位)。

## 注解与组件

| 注解/类 | 作用 | 生效条件 |
|---|---|---|
| `@EnableRedisStreaming` | `@Import(RedisStreamingAutoConfiguration)`;含 `registry`/`discovery`/`config` 三个 boolean 属性(默认 `true`) | 属性目前只是声明(全仓没有代码读取它们),实际装配开关仍是 `redis-streaming.*.enabled` |
| `@ServiceChangeListener(services, actions)` | 标注方法为服务变更回调(`actions` 默认 `added,removed,updated`);由 `ServiceChangeListenerProcessor`(`BeanPostProcessor`)扫描注册 | 依赖 `NamingService` Bean 存在;支持的方法签名:`(String serviceName, ServiceChangeAction action, ServiceInstance instance, List<ServiceInstance> allInstances)`、`(ServiceInstance instance)`、`(ServiceChangeAction action, ServiceInstance instance)`、`(String serviceName, String action, ServiceInstance instance, List<ServiceInstance> allInstances)` |
| `@ConfigChangeListener(dataId, group, autoRefresh)` | 声明配置变更方法(`group` 默认 `DEFAULT_GROUP`,`autoRefresh` 默认 `true`);**当前 starter 内没有对应处理器,自动装配不会注册该注解的方法** | 若要监听配置变更,请直接调用 `ConfigService.addListener(dataId, group, ConfigChangeListener)`(`config.ConfigChangeListener` 是 `(dataId, group, content, version)` 函数式接口) |
| `AutoServiceRegistration` | 应用就绪后自动注册服务实例并(临时实例)按 `heartbeat-interval` 发心跳,`@PreDestroy` 注销;`@ConditionalOnProperty(redis-streaming.registry.auto-register=true)`,且 `NamingService` 存在、`registry.enabled=true` | 该类是 `@Component`,**不在自动装配 imports 中**——需要用户组件扫描覆盖 `io.github.cuihairu.redis.streaming.starter.service`(如 `@SpringBootApplication(scanBasePackages=...)`)或手动注册为 Bean 才生效;`instance.ephemeral` 未配置时按临时实例处理 |

实例解析规则(`AutoServiceRegistration`):`service-name` 未配置或含 `${}` 占位时用 `spring.application.name`;`instance-id` 未配置时按服务名+端口生成;`host` 未配置时取本机 IP;`port` 未配置时取 `server.port`(默认 8080);`protocol` 支持 `http/https/tcp`(未知值回退 `http`);注册时附加 metadata `application.name`/`server.port`/`startup.time`。

## 配置项参考(键名+类型+默认值,取自 `RedisStreamingProperties`)

### `redis-streaming.redis.*`(单机兜底 Redisson)

| 键 | 类型 | 默认 |
|---|---|---|
| `address` | String | `redis://127.0.0.1:6379` |
| `password` | String | `null`(空/空白不设置) |
| `database` | int | `0` |
| `connect-timeout` | int(ms) | `3000` |
| `timeout` | int(ms) | `3000` |
| `connection-pool-size` | int | `64` |
| `connection-minimum-idle-size` | int | `10` |

### `redis-streaming.registry.*`

`enabled`(boolean,`true`)、`heartbeat-interval`(int 秒,`30`)、`heartbeat-timeout`(int 秒,`90`)、`auto-register`(boolean,`true`)、`instance.*`:

| 键 | 类型 | 默认 |
|---|---|---|
| `instance.service-name` | String | `${spring.application.name}` |
| `instance.instance-id` | String | `null`(自动按服务名+端口生成) |
| `instance.host` | String | `null`(自动探测) |
| `instance.port` | Integer | `null`(取 `server.port`) |
| `instance.weight` | int | `1` |
| `instance.enabled` | boolean | `true` |
| `instance.protocol` | String | `http`(可选 `http/https/tcp`) |
| `instance.ephemeral` | Boolean | `null` → 按临时实例 |
| `instance.metadata.*` | `Map<String,String>` | `{}` |

`metrics.*`(provider 指标):`enabled`(`Set<String>`,默认 `memory,cpu,application,disk,network`)、`intervals`(`Map<String,Duration>`,`{}`)、`default-interval`(Duration,`PT1M`)、`immediate-update-on-significant-change`(boolean,`true`)、`timeout`(Duration,`PT5S`)。

### `redis-streaming.discovery.*`

`enabled`(boolean,`true`)、`healthy-only`(boolean,`true`)、`cache-time`(int 秒,`30`)。

### `redis-streaming.config.*`

`enabled`(boolean,`true`)、`default-group`(String,`DEFAULT_GROUP`)、`refresh-interval`(int 秒,`30`)、`auto-refresh`(boolean,`true`)、`history-size`(int,`10`)、`key-prefix`(String,`redis_streaming`)、`enable-key-prefix`(boolean,`true`)。

### `redis-streaming.load-balancer.*`

`strategy`(String,`scored`;可选 `scored|wrr|weighted-random|consistent-hash`)、`preferred-region`(String)、`preferred-zone`(String)、`cpu-weight`(double,`1.0`)、`latency-weight`(double,`1.0`)、`memory-weight`(double,`0.0`)、`inflight-weight`(double,`0.0`)、`queue-weight`(double,`0.0`)、`error-rate-weight`(double,`0.0`)、`target-latency-ms`(double,`50.0`)、`max-cpu-percent`/`max-latency-ms`/`max-memory-percent`/`max-inflight`/`max-queue`/`max-error-rate-percent`(double,`-1` = 不限,仅 `scored` 使用)。

### `redis-streaming.invoker.*`

`max-attempts`(int,`3`)、`initial-delay-ms`(long,`20`)、`backoff-factor`(double,`2.0`)、`max-delay-ms`(long,`200`)、`jitter-ms`(long,`20`)。

### `redis-streaming.mq.*`(扁平键,对应 `MqProperties`)

| 键 | 类型 | 默认 |
|---|---|---|
| `enabled` | boolean | `true` |
| `default-partition-count` | int | `1` |
| `worker-threads` | int | `8` |
| `scheduler-threads` | int | `2` |
| `consumer-batch-count` | int | `10` |
| `consumer-poll-timeout-ms` | long | `1000` |
| `lease-ttl-seconds` | int | `15` |
| `rebalance-interval-sec` | int | `5` |
| `renew-interval-sec` | int | `3` |
| `pending-scan-interval-sec` | int | `30` |
| `claim-idle-ms` | long | `300000` |
| `claim-batch-size` | int | `50` |
| `max-in-flight` | int | `0`(0=关闭) |
| `max-leased-partitions-per-consumer` | int | `0`(0=跟随 `worker-threads`) |
| `retry-max-attempts` | int | `5` |
| `retry-base-backoff-ms` | long | `1000` |
| `retry-max-backoff-ms` | long | `60000` |
| `retry-mover-batch` | int | `100` |
| `retry-mover-interval-sec` | int | `1` |
| `retry-lock-wait-ms` | long | `100` |
| `retry-lock-lease-ms` | long | `500` |
| `key-prefix` | String | `streaming:mq` |
| `stream-key-prefix` | String | `stream:topic` |
| `consumer-name-prefix` | String | `consumer-` |
| `dlq-consumer-suffix` | String | `-dlq` |
| `default-consumer-group` | String | `default-group` |
| `default-dlq-group` | String | `dlq-group` |
| `retention-max-len-per-partition` | int | `100000` |
| `retention-ms` | long | `0`(0=按长度截断,不按时长) |
| `trim-interval-sec` | int | `60` |
| `ack-delete-policy` | String | `none`(可选 `none|immediate|all-groups-ack`) |
| `ackset-ttl-sec` | int | `86400` |
| `dlq-retention-max-len` | int | `0` |
| `dlq-retention-ms` | long | `0` |
| `broker.type` | String | `redis`(可选 `redis|jdbc`) |
| `broker.jdbc.driver-class-name` | String | `com.mysql.cj.jdbc.Driver` |
| `broker.jdbc.url` / `username` / `password` | String | `null`(提供 `DataSource` Bean 时忽略) |

### `redis-streaming.ratelimit.*`

`enabled`(boolean,`false`)、`backend`(`memory|redis`,默认 `memory`)、`window-ms`(long,`1000`)、`limit`(int,`100`)、`key-prefix`(String,`streaming:rl`)、`default-name`(String,`default`),以及命名策略集合 `policies.<名称>.*`:`algorithm`(`sliding|token-bucket|leaky-bucket`,默认 `sliding`)、`backend`(`memory|redis`,默认 `memory`)、`window-ms`(long,`1000`)、`limit`(int,`100`)、`capacity`(double,`100.0`)、`rate-per-second`(double,`100.0`)、`key-prefix`(String,`streaming:rl`)。

组装规则:`policies` 为空时用顶层键构建单个默认限流器;非空时逐个构建,并保证 `default-name` 存在。算法与后端对应 `InMemory/Redis SlidingWindow`、`InMemory/Redis TokenBucket`、`InMemory LeakyBucket`——`leaky-bucket` 只有内存实现;请求 `redis` 后端但没有 `RedissonClient` 时回退内存实现并告警;未知算法回退 sliding。

## 注入示例

```java
@RestController
public class DemoController {
    private final MessageQueueFactory mq;
    private final ConfigService configService;
    private final RateLimiter rateLimiter; // 仅 ratelimit.enabled=true 时存在

    public DemoController(MessageQueueFactory mq, ConfigService configService,
                          @Autowired(required = false) RateLimiter rateLimiter) {
        this.mq = mq; this.configService = configService; this.rateLimiter = rateLimiter;
    }

    @PostMapping("/orders")
    public String publish(@RequestBody String body) throws Exception {
        // MessageQueueFactory.createProducer() → CompletableFuture<String> send(...)
        return mq.createProducer().send("orders", "k1", body).get();
    }
}
```

配置变更监听(当前唯一有处理器装配的监听方式):

```java
@Component
public class ConfigWatch {
    public ConfigWatch(ConfigService configService) {
        configService.addListener("database.config", "DEFAULT_GROUP",
                (dataId, group, content, version) -> { /* 读取到新内容 */ });
    }
}
```

## 指标导出

`micrometer-core` 为 `api` 依赖、`actuator` 为 `compileOnly`。桥接链路:模块内单例(`RedisRuntimeMetrics`/`MqMetrics`/`RetentionMetrics`/`ReliabilityMetrics`/`RateLimitMetrics`)→ `*MicrometerCollector` → `MeterRegistry`。实际指标名(取自各 collector 源码):

| 来源 | 指标名 |
|---|---|
| `RedisRuntimeMicrometerCollector` | `redis_streaming_runtime_*`:`job_started_total`/`job_canceled_total`、`pipeline_started_total`/`pipeline_start_failed_total`、`handle_success_total`/`handle_error_total`/`handle_latency_ms`、`checkpoint_triggered_total`/`checkpoint_completed_total`/`checkpoint_failed_total`、`checkpoint_duration_ms` 与 `checkpoint_drain/store/sink_commit_duration_ms`、`keyed_state_read/write/delete_total`、`keyed_state_read/write_latency_ms`、`keyed_state_size_fields`、`keyed_state_hot_key_total`、`event_time_timer_queue_size`、`watermark_ms`、`window_fired_total`/`window_late_dropped_total` |
| `MqMicrometerCollector` | `redis_streaming_mq_produced/consumed/acked/retried/dead/payload_missing_total`、`handle_latency_ms`、`inflight`/`max_inflight`、`backpressure_wait_total`/`backpressure_wait_ms`、`eligible_partitions`/`leased_partitions`/`max_leased_partitions` |
| `MqMetricsBinder` | `redis_streaming_mq_topics_total`/`messages_total`/`dlq_total`(Gauge) |
| `RetentionFrontierMetricsBinder` | `redis_streaming_mq_frontier_age_ms` |
| `RetentionMicrometerCollector` | `redis_streaming_mq_trim_attempts_total`/`trim_deleted_total` |
| `ReliabilityMicrometerCollector` | `redis_streaming_dlq_replay_success_total`/`replay_failure_total`/`replay_latency_ms`、`dlq_deleted_total`/`dlq_cleared_total` |
| `RateLimitMicrometerCollector` | `redis_streaming_rl_allowed_total`/`rl_denied_total` |
| `ClientInvokerMetricsBinder` | `client.invoker.total.{attempts,successes,failures,retries,cbOpenSkips}` |

经 Spring Boot actuator 导出时，自行引入任一 Micrometer registry 实现（版本交给 Boot BOM 管理），例如：

```gradle
implementation 'org.springframework.boot:spring-boot-starter-actuator'
runtimeOnly 'io.micrometer:micrometer-registry-prometheus'
```

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  metrics:
    export:
      prometheus:
        enabled: true
```

之后抓取 `/actuator/prometheus` 即可拿到上表 `redis_streaming_*` 指标（MQ 指标带 `topic`/`partition`/`consumer` 等 tag）。不走 actuator 的另一条路是 metrics 模块的 `PrometheusExporter`/`PrometheusMetricCollector`（基于 `io.prometheus:simpleclient`，在该模块是 `compileOnly`），见 [Metrics.md](Metrics.md)。

## 后台与健康组件

- `StreamRetentionHousekeeper`:按 `mq.trim-interval-sec` 周期执行 `XTRIM`(长度上限 `retention-max-len-per-partition`,可选 `retention-ms` 时长截断),同时清理 DLQ 与过期 commit frontier;`AutoCloseable`,上下文关闭时停止。
- `MqHealthIndicator`:调用 `MessageQueueAdmin.listAllTopics()`,成功为 `UP` 并带 `topics` 数量,异常为 `DOWN` 并携带原因。

## Redisson 集成与部署模式

starter 复用应用里已有的 `RedissonClient`,只有在没有时才创建简化单机客户端。

- 集群/哨兵/SSL 建议走 `redisson-spring-boot-starter`(例如 `implementation 'org.redisson:redisson-spring-boot-starter:4.7.0'`,与 `gradle/libs.versions.toml` 中的 Redisson 版本一致),用 `spring.redis.redisson.file` 指向 YAML。

集群(redisson-cluster.yaml):

```yaml
clusterServersConfig:
  nodeAddresses: ["redis://10.0.0.1:6379", "redis://10.0.0.2:6379"]
  password: your_pwd
  scanInterval: 2000
  connectTimeout: 10000
  timeout: 3000
```

```yaml
spring:
  redis:
    redisson:
      file: classpath:redisson-cluster.yaml
```

哨兵(redisson-sentinel.yaml):

```yaml
sentinelServersConfig:
  masterName: mymaster
  sentinelAddresses: ["redis://10.0.0.1:26379", "redis://10.0.0.2:26379"]
  password: your_pwd
  database: 0
  checkSentinelsList: true
```

```yaml
spring:
  redis:
    redisson:
      file: classpath:redisson-sentinel.yaml
```

引入 redisson-spring-boot-starter 后,`redis-streaming.redis.*` 可以不再配置——starter 探测到外部 `RedissonClient` 会跳过内部单机客户端。

## Codec 与 Lua 注意

registry/MQ 的 Lua 脚本以字符串/JSON 读写这些键空间:

- registry:`{prefix}:services`(Set)、`{prefix}:services:{service}:heartbeats`(ZSet)、`{prefix}:services:{service}:instance:{id}`(Hash)
- MQ 重试:`streaming:mq:retry:{topic}`(ZSet)、`streaming:mq:retry:item:{topic}:{uuid}`(Hash);重试锁键是字面常量 `streaming:mq:retry:lock:{topic}`,不随 `key-prefix` 变化

约定:

- 这些键用 `StringCodec` 访问,值保持字符串/JSON;用对象型 codec(如 Kryo)读字符串回复(SMEMBERS/HGET)可能反序列化失败。
- 自己的键用 Kryo/JSON 没问题,只要 Lua 脚本不碰它们。
- 最省事的做法:Redisson 配置里全局 `codec: !<org.redisson.codec.StringCodec>`。

## 典型用例

### 服务注册与发现

```java
// 服务提供方
@SpringBootApplication
@EnableRedisStreaming
public class UserServiceProvider {
    // registry.auto-register=true 时启动即注册 user-service
}
```

```yaml
redis-streaming:
  registry:
    auto-register: true
    instance:
      service-name: user-service
      weight: 2
      metadata:
        version: 2.0.0
```

手动注册外部服务(`NamingService` 同时实现 `ServiceRegistry`/`ServiceDiscovery`,两个视角都可注入):

```java
@Service
public class UserService {

    @Autowired
    private ServiceRegistry serviceRegistry;

    @Autowired
    private ServiceDiscovery serviceDiscovery;

    public void registerExternalService() {
        ServiceInstance instance = DefaultServiceInstance.builder()
                .serviceName("external-api")
                .instanceId("api-1")
                .host("api.example.com")
                .port(443)
                .protocol(StandardProtocol.HTTPS)
                .weight(3)
                .build();
        serviceRegistry.register(instance);
    }

    public List<ServiceInstance> findPaymentServices() {
        return serviceDiscovery.discoverHealthy("payment-service");
    }
}
```

### 配置发布

```java
@Service
public class ConfigPublisher {

    @Autowired
    private ConfigService configService;

    public void publishDatabaseConfig() {
        String config = """
            {
              "host": "db.example.com",
              "port": 3306,
              "database": "production",
              "maxConnections": 200
            }
            """;
        configService.publishConfig("database.config", "production", config);
    }
}
```

### 服务变更监听

```java
@Component
public class ServiceListener {

    @ServiceChangeListener(services = {"payment-service", "order-service"})
    public void onServiceChange(String serviceName, String action,
                                ServiceInstance instance,
                                List<ServiceInstance> allInstances) {
        if ("payment-service".equals(serviceName)) {
            updatePaymentServiceCache(allInstances);
        }
    }
}
```

支持的方法签名见「注解与组件」。

### 事件驱动

```java
// 订单服务——生产者
@Service
public class OrderService {
    @Autowired
    private MessageQueueFactory mq;

    public void createOrder(Order order) {
        orderRepository.save(order);
        mq.createProducer().send("order_created", String.valueOf(order.getId()), order);
    }
}
```

```java
// 支付服务——同一 topic 上自己的消费组
@Component
public class PaymentService {
    @Autowired
    private MessageQueueFactory mq;

    @PostConstruct
    public void subscribe() {
        MessageConsumer consumer = mq.createConsumer("payment-svc");
        consumer.subscribe("order_created", "payment", message -> {
            try {
                processPayment(message.getPayload());
                return MessageHandleResult.SUCCESS;
            } catch (Exception e) {
                log.error("Failed to process order", e);
                return MessageHandleResult.RETRY;
            }
        });
        consumer.start();
    }

    @PreDestroy
    public void stop() {
        consumer.stop();
    }
}
```

```java
// 库存服务——另一个独立消费组
@Component
public class InventoryService {
    @Autowired
    private MessageQueueFactory mq;

    @PostConstruct
    public void subscribe() {
        MessageConsumer consumer = mq.createConsumer("inventory-svc");
        consumer.subscribe("order_created", "inventory", message -> {
            reserveStock(message.getPayload());
            return MessageHandleResult.SUCCESS;
        });
        consumer.start();
    }

    @PreDestroy
    public void stop() {
        consumer.stop();
    }
}
```

`MessageHandleResult` 取值 `SUCCESS`/`RETRY`/`FAIL`/`DEAD_LETTER`;`subscribe()` 只登记处理器,真正拉取从 `start()` 开始。需要窗口/精确语义的流水线用运行时入口 `RedisStreamExecutionEnvironment.fromMqTopic(...)`,见 [runtime.md](runtime.md)。

MQ 调优示例(下面值仅作示意——所有键的默认值见「配置项参考」,前缀为 `redis-streaming.mq`,历史文档中的 `streaming.mq` 已废弃):

```yaml
redis-streaming:
  mq:
    enabled: true
    default-partition-count: 4
    worker-threads: 16
    consumer-batch-count: 32
    consumer-poll-timeout-ms: 500
    max-in-flight: 1024
    claim-idle-ms: 300000
```

## 注意事项

1. 启动前 Redis 必须可达;生产负载按需调大连接池。
2. 自动探测的 IP 在部分网络环境下会取错,必要时显式设置 `instance.host`。
3. 关闭时应用会注销服务并关闭连接。
4. 心跳间隔建议保持 30 秒及以上。

## 常见问题

**Q: 如何禁用自动注册?**
```yaml
redis-streaming:
  registry:
    auto-register: false
```

**Q: 如何使用自己的 RedissonClient?**
```java
@Bean
@Primary
public RedissonClient customRedissonClient() {
    // return your custom RedissonClient
}
```
starter 的兜底客户端会让位(`@ConditionalOnMissingBean`);若用 redisson-spring-boot-starter 提供客户端,其配置前缀以其版本为准,与 `redis-streaming.redis.*` 无关。

**Q: 为什么 `redis-streaming` 配置不生效?**
starter 只读 `redis-streaming.*`;历史文档中的 `streaming.*` 前缀均已废弃,`spring.data.redis` 属于 Spring Data,非本 starter。

**Q: 必须引入 actuator 吗?**
不需要。`MqHealthIndicator` 由类级 `@ConditionalOnClass(HealthIndicator)` 守卫,不引 actuator 时该嵌套配置不会被加载,`HealthIndicator` 缺失也不会导致启动失败。所有 Micrometer collector/binder Bean 同理。

**Q: 为什么限流不生效?**
`ratelimit.enabled` 默认 `false`,需显式开启;`leaky-bucket` 仅有内存后端。

**Q: 为什么自动注册不生效?**
`AutoServiceRegistration` 不在自动装配 imports 内,需要组件扫描覆盖它所在的包或手动注册(见「注解与组件」)。

**Q: 为什么 jdbc broker 没生效?**
`mq.broker.type=jdbc` 但没有 `DataSource` Bean 时会回退 `redis` 并打印告警。

**Q: 多环境配置?**
```yaml
# application-dev.yml
redis-streaming:
  redis:
    address: redis://dev-redis:6379

# application-prod.yml
redis-streaming:
  redis:
    address: redis://prod-redis:6379
```

## 相关文档

[runtime.md](runtime.md) · [MQ.md](MQ.md) · [Registry.md](Registry.md) · [config.md](config.md) · [Metrics.md](Metrics.md)