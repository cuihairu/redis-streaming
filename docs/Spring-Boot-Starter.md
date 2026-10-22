# Spring Boot Starter Guide

Module directory: `spring-boot-starter/`. It assembles the core/runtime/mq/registry/config/reliability components as Spring beans and provides a fallback `RedissonClient`, service registration/discovery, a configuration center, MQ with DLQ, rate limiters, retained housekeeping background tasks, health checks, and the Micrometer metrics bridge.

Maven coordinates: `io.github.cuihairu.redis-streaming:spring-boot-starter` (transitive: `core`/`registry`/`config`/`mq`/`runtime`/`reliability`/`spring-boot-starter`; `micrometer-core` is `api`, `actuator` is `compileOnly` and is not pulled in forcibly).

## Quick Start

### 1. Add Dependency

Maven:
```xml
<dependency>
    <groupId>io.github.cuihairu.redis-streaming</groupId>
    <artifactId>spring-boot-starter</artifactId>
    <version>0.2.4</version>
</dependency>
```

Gradle:
```groovy
implementation 'io.github.cuihairu.redis-streaming:spring-boot-starter:0.2.4'
```

### 2. Enable Features (pick one)

- Standard Boot application: with the starter on the classpath, auto-configuration kicks in by itself (the registration entry is `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`, which currently lists only `RedisStreamingAutoConfiguration`; it `@Import`s the per-domain configuration classes);
- Or annotate your configuration/main class with `@EnableRedisStreaming` (an alias for `@Import(RedisStreamingAutoConfiguration.class)`):

```java
@SpringBootApplication
@EnableRedisStreaming
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
```

### 3. Configuration (prefix is fixed as `redis-streaming`, not `streaming`)

A complete runnable sample: `examples/src/main/resources/application.yml`.
Run the example with:
```bash
./gradlew :examples:run -PmainClass=io.github.cuihairu.redis.streaming.examples.springboot.StarterExampleApplication
```

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

## Auto-Configuration Structure

| Class | Property switch (default) | Beans assembled |
|---|---|---|
| `RedisStreamingAutoConfiguration` | class-level `@ConditionalOnClass(RedissonClient)` | fallback `RedissonClient` (`@ConditionalOnMissingBean`; simplified single-server config, `password` blank/empty is not set), `RateLimitMicrometerCollector` + `installRateLimitCollector` |
| `RedisStreamingRegistryAutoConfiguration` | `redis-streaming.registry.enabled` (default `true`) | `NamingService`, `ServiceChangeListenerProcessor`, `LoadBalancer` |
| `RedisStreamingDiscoveryAutoConfiguration` | `redis-streaming.discovery.enabled` (default `true`) | `ServiceConsumer` (`RedisNamingService`) — only as a fallback when no `NamingService`/`ServiceConsumer` bean is present |
| `RedisStreamingConfigServiceAutoConfiguration` | `redis-streaming.config.enabled` (default `true`) | `ConfigService` (started at `start()`) |
| `RedisStreamingMqAutoConfiguration` | `redis-streaming.mq.enabled` (default `true`) | `MqOptions`, `BrokerFactory`/`BrokerRouter`, `MessageQueueFactory`, `MessageQueueAdmin`, `dlqReplayProducer`, `dlqReplayHandler`, `DeadLetterService`/`DeadLetterAdmin`/`DeadLetterConsumer`/`DeadLetterQueueManager`, the retained housekeeping `StreamRetentionHousekeeper` (destroyMethod=close), the Micrometer bridge (see "Metrics Export"), `MqHealthIndicator` |
| `RedisStreamingRateLimitAutoConfiguration` | `redis-streaming.ratelimit.enabled` (default **`false`**) | `RateLimiterRegistry`, the `@Primary` `RateLimiter` (selected by `default-name`) |
| `RedisStreamingRuntimeAutoConfiguration` | `redis-streaming.runtime.control-plane.enabled` (default **`false`**); the agent additionally requires `redis-streaming.runtime.agent.enabled` (default **`false`**) | `ControlPlaneAuthorizer` (allowAll by default, a user bean takes precedence), `RedisJobControlPlane` (i.e. `JobControlPlane`), and with the agent enabled: `JobLauncher` (default `RedisJobLauncher`, auto-registers every `JobPipelineFactory` bean in the context), `JobAgent` (initMethod=`start`, destroyMethod=`close`) |

`LoadBalancer` picks its implementation by `load-balancer.strategy`: `wrr → WeightedRoundRobinLoadBalancer`, `weighted-random → WeightedRandomLoadBalancer`, `consistent-hash → ConsistentHashLoadBalancer`; anything else (including the default `scored`) → `ScoredLoadBalancer` + `RedisMetricsProvider`.
`BrokerFactory` is selected by `mq.broker.type`: `jdbc` with a `DataSource` bean on the classpath uses `JdbcBrokerFactory`, otherwise (no `DataSource`, or `redis`) it falls back to `RedisBrokerFactory` with a warning.

Optional dependencies are guarded, so the application starts even without actuator/micrometer:
- every Micrometer collector/binder bean is guarded by `@ConditionalOnClass(MeterRegistry)`, and `install*Collector` additionally requires the corresponding collector bean to exist;
- `MqHealthIndicator` lives in a separate nested configuration class inside `RedisStreamingMqAutoConfiguration`, guarded by a class-level `@ConditionalOnClass(HealthIndicator)`;
- the Redisson client is created only when the application has no `RedissonClient` bean; for cluster/sentinel/SSL, provide your own `RedissonClient` (this bean stands down automatically).

## Annotations & Components

| Annotation / class | Purpose | Effective when |
|---|---|---|
| `@EnableRedisStreaming` | `@Import(RedisStreamingAutoConfiguration)`; carries three boolean attributes (`registry`/`discovery`/`config`, default `true`) | the attributes are declared only (no code in the repository reads them); the real assembly switches remain `redis-streaming.*.enabled` |
| `@ServiceChangeListener(services, actions)` | Marks a method as a service-change callback (`actions` defaults to `added,removed,updated`); scanned and registered by `ServiceChangeListenerProcessor` (a `BeanPostProcessor`) | requires a `NamingService` bean; supported method signatures: `(String serviceName, ServiceChangeAction action, ServiceInstance instance, List<ServiceInstance> allInstances)`, `(ServiceInstance instance)`, `(ServiceChangeAction action, ServiceInstance instance)`, `(String serviceName, String action, ServiceInstance instance, List<ServiceInstance> allInstances)` |
| `@ConfigChangeListener(dataId, group, autoRefresh)` | Declares a configuration-change method (`group` defaults to `DEFAULT_GROUP`, `autoRefresh` defaults to `true`); **the starter currently has no processor for it — auto-configuration never registers methods carrying this annotation** | to listen for configuration changes, call `ConfigService.addListener(dataId, group, ConfigChangeListener)` directly (`config.ConfigChangeListener` is a functional interface of `(dataId, group, content, version)`) |
| `AutoServiceRegistration` | Registers the service instance after the application is ready, heartbeats it (temporary instances) at `heartbeat-interval`, and deregisters via `@PreDestroy`; guarded by `@ConditionalOnProperty(redis-streaming.registry.auto-register=true)` and requires `NamingService` with `registry.enabled=true` | this class is a `@Component` and is **not in the auto-configuration imports** — your component scan must cover `io.github.cuihairu.redis.streaming.starter.service` (e.g. `@SpringBootApplication(scanBasePackages=...)`) or you must register it as a bean manually; an unconfigured `instance.ephemeral` is treated as a temporary instance |

Instance resolution rules (`AutoServiceRegistration`): an unset `service-name` or one containing `${}` placeholders falls back to `spring.application.name`; an unset `instance-id` is derived from service name + port; an unset `host` resolves to the local IP; an unset `port` resolves to `server.port` (default 8080); `protocol` accepts `http/https/tcp` (unknown values fall back to `http`); registration attaches the metadata `application.name`/`server.port`/`startup.time`.

## Configuration Reference (key, type, default — taken from `RedisStreamingProperties`)

### `redis-streaming.redis.*` (fallback single-server Redisson)

| Key | Type | Default |
|---|---|---|
| `address` | String | `redis://127.0.0.1:6379` |
| `password` | String | `null` (blank/empty is not set) |
| `database` | int | `0` |
| `connect-timeout` | int (ms) | `3000` |
| `timeout` | int (ms) | `3000` |
| `connection-pool-size` | int | `64` |
| `connection-minimum-idle-size` | int | `10` |

### `redis-streaming.registry.*`

`enabled` (boolean, `true`), `heartbeat-interval` (int seconds, `30`), `heartbeat-timeout` (int seconds, `90`), `auto-register` (boolean, `true`), `tenant` (String, `default`), and `instance.*`:

A non-default `tenant` inserts a `:tenant` segment right after the key prefix (`redis_streaming_registry:acme:services:...`), so several tenants sharing one Redis never see each other's instances; `default` keeps the legacy keys unchanged. Names must match `[A-Za-z0-9._-]{1,64}` (no colon) — an illegal name fails startup.

| Key | Type | Default |
|---|---|---|
| `instance.service-name` | String | `${spring.application.name}` |
| `instance.instance-id` | String | `null` (generated from service name + port) |
| `instance.host` | String | `null` (auto-detected) |
| `instance.port` | Integer | `null` (takes `server.port`) |
| `instance.weight` | int | `1` |
| `instance.enabled` | boolean | `true` |
| `instance.protocol` | String | `http` (one of `http/https/tcp`) |
| `instance.ephemeral` | Boolean | `null` → treated as a temporary instance |
| `instance.metadata.*` | `Map<String,String>` | `{}` |

`metrics.*` (provider metrics): `enabled` (`Set<String>`, default `memory,cpu,application,disk,network`), `intervals` (`Map<String,Duration>`, `{}`), `default-interval` (Duration, `PT1M`), `immediate-update-on-significant-change` (boolean, `true`), `timeout` (Duration, `PT5S`).

### `redis-streaming.discovery.*`

`enabled` (boolean, `true`), `healthy-only` (boolean, `true`), `cache-time` (int seconds, `30`).

### `redis-streaming.config.*`

`enabled` (boolean, `true`), `default-group` (String, `DEFAULT_GROUP`), `refresh-interval` (int seconds, `30`), `auto-refresh` (boolean, `true`), `history-size` (int, `10`), `key-prefix` (String, `redis_streaming`), `enable-key-prefix` (boolean, `true`), `tenant` (String, `default`), `secure-key-prefix` (String, `secure.`).

`tenant` follows the same naming rule as the registry one: a non-default value inserts a `:tenant` segment after `key-prefix` (`redis_streaming:acme:config:...`), so the same group/dataId stays invisible across tenants; `default` keeps the legacy keys unchanged. See the Chinese multi-tenancy design for the whole keyspace.

### `redis-streaming.load-balancer.*`

`strategy` (String, `scored`; one of `scored|wrr|weighted-random|consistent-hash`), `preferred-region` (String), `preferred-zone` (String), `cpu-weight` (double, `1.0`), `latency-weight` (double, `1.0`), `memory-weight` (double, `0.0`), `inflight-weight` (double, `0.0`), `queue-weight` (double, `0.0`), `error-rate-weight` (double, `0.0`), `target-latency-ms` (double, `50.0`), `max-cpu-percent`/`max-latency-ms`/`max-memory-percent`/`max-inflight`/`max-queue`/`max-error-rate-percent` (double, `-1` = unlimited, used by `scored` only).

### `redis-streaming.runtime.*` (job control plane, both **opt-in**)

`runtime.control-plane.*`:

| Key | Type | Default |
|---|---|---|
| `enabled` | boolean | `false` (the control-plane beans are assembled only when explicitly enabled) |
| `prefix` | String | `streaming:runtime:control:` (key families: jobs/versions/history:job/status:job/audit) |
| `audit-max-entries` | int | `1000` (approximate cap on the audit stream) |
| `history-max-entries` | int | `10` (old spec versions kept per job, for rollback) |
| `max-jobs-per-tenant` | int | `0` (unlimited; cap on jobs registered per tenant, submit/upgrade rejected once reached) |
| `max-total-parallelism-per-tenant` | int | `0` (unlimited; cap on the sum of spec parallelism per tenant) |
| `max-total-in-flight-per-tenant` | int | `0` (unlimited; cap on the tenant's declared in-flight capacity, Σ(parallelism × maxInFlight)) |

`runtime.agent.*` (meaningful only with the control plane enabled):

| Key | Type | Default |
|---|---|---|
| `enabled` | boolean | `false` (run the reconciliation agent in this process only when explicitly enabled) |
| `instance-id` | String | `""` (auto-generated `hostname-8 random chars` when empty) |
| `poll-interval` | Duration | `PT5S` (the first round runs immediately) |
| `claim-prefix` | String | `null` (defaults to `<control-plane prefix>claim:`; set to the empty string to disable claiming — single-agent setups only) |

### `redis-streaming.mq.*` (flat keys, mapping to `MqProperties`)

| Key | Type | Default |
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
| `max-in-flight` | int | `0` (0 = disabled) |
| `max-leased-partitions-per-consumer` | int | `0` (0 = follows `worker-threads`) |
| `retry-max-attempts` | int | `5` |
| `retry-base-backoff-ms` | long | `1000` |
| `retry-max-backoff-ms` | long | `60000` |
| `retry-mover-batch` | int | `100` |
| `retry-mover-interval-sec` | int | `1` |
| `retry-lock-wait-ms` | long | `100` |
| `retry-lock-lease-ms` | long | `500` |
| `key-prefix` | String | `streaming:mq` |
| `stream-key-prefix` | String | `stream:topic` |
| `tenant` | String | `default` (a non-default value inserts a `:tenant` segment after both prefixes) |
| `metrics-tenant-tag` | boolean | `false` (adds a `tenant` tag to the MQ counter/timer series; off keeps existing dashboards unchanged — see Metrics.md) |
| `consumer-name-prefix` | String | `consumer-` |
| `dlq-consumer-suffix` | String | `-dlq` |
| `default-consumer-group` | String | `default-group` |
| `default-dlq-group` | String | `dlq-group` |
| `retention-max-len-per-partition` | int | `100000` |
| `retention-ms` | long | `0` (0 = trim by length, not by age) |
| `trim-interval-sec` | int | `60` |
| `ack-delete-policy` | String | `none` (one of `none|immediate|all-groups-ack`) |
| `ackset-ttl-sec` | int | `86400` |
| `dlq-retention-max-len` | int | `0` |
| `dlq-retention-ms` | long | `0` |
| `broker.type` | String | `redis` (one of `redis|jdbc`) |
| `broker.jdbc.driver-class-name` | String | `com.mysql.cj.jdbc.Driver` |
| `broker.jdbc.url` / `username` / `password` | String | `null` (ignored when a `DataSource` bean is provided) |

### `redis-streaming.ratelimit.*`

`enabled` (boolean, `false`), `backend` (`memory|redis`, default `memory`), `window-ms` (long, `1000`), `limit` (int, `100`), `key-prefix` (String, `streaming:rl`), `default-name` (String, `default`), plus the named policy set `policies.<name>.*`: `algorithm` (`sliding|token-bucket|leaky-bucket`, default `sliding`), `backend` (`memory|redis`, default `memory`), `window-ms` (long, `1000`), `limit` (int, `100`), `capacity` (double, `100.0`), `rate-per-second` (double, `100.0`), `key-prefix` (String, `streaming:rl`).

Assembly rules: with an empty `policies` map, one default limiter is built from the top-level keys; otherwise each policy is built in turn and `default-name` is guaranteed to exist. Algorithms/backends map to `InMemory/Redis SlidingWindow`, `InMemory/Redis TokenBucket`, `InMemory LeakyBucket` — `leaky-bucket` has an in-memory implementation only; requesting the `redis` backend without a `RedissonClient` falls back to the in-memory implementation with a warning; unknown algorithms fall back to sliding.

## Injection Examples

```java
@RestController
public class DemoController {
    private final MessageQueueFactory mq;
    private final ConfigService configService;
    private final RateLimiter rateLimiter; // present only when ratelimit.enabled=true

    public DemoController(MessageQueueFactory mq, ConfigService configService,
                          @Autowired(required = false) RateLimiter rateLimiter) {
        this.mq = mq; this.configService = configService; this.rateLimiter = rateLimiter;
    }

    @PostMapping("/orders")
    public String publish(@RequestBody String body) throws Exception {
        // MessageQueueFactory.createProducer() -> CompletableFuture<String> send(...)
        return mq.createProducer().send("orders", "k1", body).get();
    }
}
```

Configuration-change listening (the only listener wiring the starter currently assembles):

```java
@Component
public class ConfigWatch {
    public ConfigWatch(ConfigService configService) {
        configService.addListener("database.config", "DEFAULT_GROUP",
                (dataId, group, content, version) -> { /* new content received */ });
    }
}
```

## Metrics Export

`micrometer-core` is an `api` dependency and `actuator` is `compileOnly`. Bridge chain: per-module singletons (`RedisRuntimeMetrics`/`MqMetrics`/`RetentionMetrics`/`RateLimitMetrics`) → `*MicrometerCollector` → `MeterRegistry`. The actual meter names (taken from each collector's source):

| Source | Meter names |
|---|---|
| `RedisRuntimeMicrometerCollector` | `redis_streaming_runtime_*`: `job_started_total`/`job_canceled_total`, `pipeline_started_total`/`pipeline_start_failed_total`, `handle_success_total`/`handle_error_total`/`handle_latency_ms`, `checkpoint_triggered_total`/`checkpoint_completed_total`/`checkpoint_failed_total`, `checkpoint_duration_ms` plus `checkpoint_drain/store/sink_commit_duration_ms`, `keyed_state_read/write/delete_total`, `keyed_state_read/write_latency_ms`, `keyed_state_size_fields`, `keyed_state_hot_key_total`, `event_time_timer_queue_size`, `watermark_ms`, `window_fired_total`/`window_late_dropped_total` |
| `MqMicrometerCollector` | `redis_streaming_mq_produced/consumed/acked/retried/dead/payload_missing_total`, `handle_latency_ms`, `inflight`/`max_inflight`, `backpressure_wait_total`/`backpressure_wait_ms`, `eligible_partitions`/`leased_partitions`/`max_leased_partitions`, DLQ replay `redis_streaming_dlq_replay_success_total`/`replay_failure_total`/`replay_latency_ms`, `redis_streaming_dlq_deleted_total`/`dlq_cleared_total` (tags: `topic`, `partition`) |
| `MqMetricsBinder` | `redis_streaming_mq_topics_total`/`messages_total`/`dlq_total` (Gauge) |
| `RetentionFrontierMetricsBinder` | `redis_streaming_mq_frontier_age_ms` |
| `RetentionMicrometerCollector` | `redis_streaming_mq_trim_attempts_total`/`trim_deleted_total` |
| `CDCMetricsMicrometerBinder` | `redis.streaming.cdc.*` (11 gauges, tag `connector=<name>`; requires the cdc module on the classpath and a user-registered `CDCManager` bean) |
| `RateLimitMicrometerCollector` | `redis_streaming_rl_allowed_total`/`rl_denied_total` |

To export them through Spring Boot actuator, add a Micrometer registry implementation of your choice (version managed by the Spring Boot BOM), for example:

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

Scrape `/actuator/prometheus` for the `redis_streaming_*` meters above. Tags include `topic`/`partition`/`consumer` on MQ meters. A separate non-actuator route — a self-managed `PrometheusMeterRegistry` from `micrometer-registry-prometheus` — is documented in Metrics.md.

## Background & Health Components

- `StreamRetentionHousekeeper`: periodically runs `XTRIM` on the `mq.trim-interval-sec` schedule (length cap `retention-max-len-per-partition`, optional time-based trim via `retention-ms`), and cleans DLQs and expired commit frontiers along the way; `AutoCloseable`, stops when the context closes.
- `MqHealthIndicator`: calls `MessageQueueAdmin.listAllTopics()`; success reports `UP` with a `topics` count, an exception reports `DOWN` with the cause.

## Redisson Integration & Deployment Modes

The starter reuses an existing `RedissonClient` in your application and only creates a simple single-server client if none is present.

- Recommended: configure cluster/sentinel/SSL via `redisson-spring-boot-starter` (for example `implementation 'org.redisson:redisson-spring-boot-starter:4.7.0'`, the Redisson version in `gradle/libs.versions.toml`) and point `spring.redis.redisson.file` at your YAML.

Cluster (redisson-cluster.yaml)
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

Sentinel (redisson-sentinel.yaml)
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

Tip: once redisson-spring-boot-starter is in place you can drop `redis-streaming.redis.*`; the starter detects your `RedissonClient` and skips its internal single-server client.

## Codec & Lua Best Practices

Lua scripts in registry/MQ read and write strings/JSON in these keyspaces:

- Registry: `{prefix}:services` (Set), `{prefix}:services:{service}:heartbeats` (ZSet), `{prefix}:services:{service}:instance:{id}` (Hash)
- MQ retry: `streaming:mq:retry:{topic}` (ZSet), `streaming:mq:retry:item:{topic}:{uuid}` (Hash); the retry lock key is the literal `streaming:mq:retry:lock:{topic}` (it does not follow `key-prefix`)

Guidelines:

- Access these keys with `StringCodec`; values should be strings/JSON. Reading string replies (SMEMBERS/HGET) with object codecs (e.g. Kryo) can fail deserialization.
- Kryo/JSON codecs are fine for your own keys as long as no Lua script touches them.
- Simplest: set a global `codec: !<org.redisson.codec.StringCodec>` in the Redisson config.

## Typical Use Cases

### Service registration & discovery

```java
// service provider
@SpringBootApplication
@EnableRedisStreaming
public class UserServiceProvider {
    // registers user-service at startup when registry.auto-register=true
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

Manually register external services (`NamingService` implements both `ServiceProvider` and `ServiceConsumer`; either view can be injected):

```java
@Service
public class UserService {

    @Autowired
    private ServiceProvider serviceProvider;

    @Autowired
    private ServiceConsumer serviceConsumer;

    public void registerExternalService() {
        ServiceInstance instance = DefaultServiceInstance.builder()
                .serviceName("external-api")
                .instanceId("api-1")
                .host("api.example.com")
                .port(443)
                .protocol(StandardProtocol.HTTPS)
                .weight(3)
                .build();
        serviceProvider.register(instance);
    }

    public List<ServiceInstance> findPaymentServices() {
        return serviceConsumer.getHealthyInstances("payment-service");
    }
}
```

### Config publishing

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

### Service change listening

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

Supported method signatures: see "Annotations & Components".

### Event-driven

```java
// order service — producer
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
// payment service — its own consumer group on the same topic
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
// inventory service — another independent consumer group
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

`MessageHandleResult` values are `SUCCESS`/`RETRY`/`FAIL`/`DEAD_LETTER`; `subscribe()` only registers the handler — actual pulling starts at `start()`. Pipelines that need windows/exactly-once semantics use the runtime entry point `RedisStreamExecutionEnvironment.fromMqTopic(...)`, see runtime.md.

MQ tuning example (values below are illustrative — defaults for every key are in "Configuration Reference"; prefix is `redis-streaming.mq`, the historical `streaming.mq` is deprecated):

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

## Job Control Plane (runtime)

The starter can optionally wire the Redis job control plane (declarative spec + executor-side reconciliation; design and Redis key layout in the Chinese Control-Plane-Design.md):

```yaml
redis-streaming:
  runtime:
    control-plane:
      enabled: true
    agent:
      enabled: true        # this process acts as an executor-side agent
      instance-id: node-a  # optional, auto-generated when omitted
```

Pipeline factories register into the default `JobLauncher` **by Spring bean name**; `JobSpec.pipelineFactory` must equal it:

```java
@Bean
public JobPipelineFactory pvAgg() {
    return (spec, env) -> {
        String topic = spec.getConfig().getOrDefault("topic", "pv");
        env.fromMqTopic(topic, "grp-" + spec.getJobName())
           .map(m -> 1L)
           .addSink(new MyRedisCounterSink(spec));
    };
}
```

Then submit/upgrade/stop through the injected `JobControlPlane` (jobs take effect at the agent's next reconciliation round):

```java
plane.submit(JobSpec.builder()
        .jobName("pv-agg")
        .pipelineFactory("pvAgg")          // = bean name
        .config(Map.of("topic", "pv"))
        .parallelism(2)
        .build(), "alice");

plane.upgrade("pv-agg", s -> { s.setParallelism(4); return s; }, "alice"); // parallelism-only change takes the scale fast path
plane.stop("pv-agg", "alice");   // the agent cancels the local instance and confirms DESIRED_STOPPED
plane.resume("pv-agg", "alice"); // redeploy
```

Key points:

- The control plane only writes desired state and never touches running jobs directly; the agent handles claim (`SET NX EX`) → launch → status report, reporting FAILED on failure and retrying next round.
- When multiple application instances enable `agent.enabled`, one claim key arbitrates so the same job deploys on exactly one instance; when an instance dies its claim expires with the TTL and other instances need an explicit `resume` to take over (no automatic failover).
- On application shutdown `JobAgent.close()` (`destroyMethod`) cancels all local jobs of this instance; to keep jobs running after the app exits, create the agent yourself and only call `start()`.
- User-provided `ControlPlaneAuthorizer`/`JobLauncher` beans win (`@ConditionalOnMissingBean`); with a custom `JobLauncher`, factory registration is your responsibility.
- An application context with zero factory beans is valid (pure control-plane usage): submit/upgrade/rollback work as usual and a separate process runs the agent side.

## Notes

1. Redis must be reachable before startup; size the connection pool for production loads.
2. The auto-detected IP can be wrong on some networks; set `instance.host` explicitly when needed.
3. On shutdown the application deregisters services and closes connections.
4. Keep the heartbeat interval at 30 seconds or longer.

## FAQ

**Q: How do I disable auto-registration?**
```yaml
redis-streaming:
  registry:
    auto-register: false
```

**Q: How do I use my own RedissonClient?**
```java
@Bean
@Primary
public RedissonClient customRedissonClient() {
    // return your custom RedissonClient
}
```
The starter's fallback client stands down (`@ConditionalOnMissingBean`). If redisson-spring-boot-starter provides the client, its own configuration prefix applies — `redis-streaming.redis.*` is unrelated to it.

**Q: Why is my `redis-streaming` config not picked up?**
The starter only reads `redis-streaming.*`. The historical `streaming.*` prefix is deprecated, and `spring.data.redis` belongs to Spring Data, not this starter.

**Q: Do I have to add actuator?**
No. `MqHealthIndicator` is guarded by a class-level `@ConditionalOnClass(HealthIndicator)`, so without actuator the nested configuration is never loaded and the absence of `HealthIndicator` classes cannot break startup. All Micrometer collector/binder beans carry the same kind of guard.

**Q: Why is rate limiting not working?**
`ratelimit.enabled` defaults to `false`; enable it explicitly. `leaky-bucket` has an in-memory backend only.

**Q: Why is auto-registration not working?**
`AutoServiceRegistration` is not in the auto-configuration imports; your component scan must cover its package or you must register it as a bean (see "Annotations & Components").

**Q: Why is the JDBC broker not taking effect?**
With `mq.broker.type=jdbc` but no `DataSource` bean, the starter falls back to `redis` and logs a warning.

**Q: Multi-environment configuration?**
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

---

**Version**: 0.2.4
**Last Updated**: 2026-10-05

Related documentation: