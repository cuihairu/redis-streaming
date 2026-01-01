# Spring Boot Starter Guide

[中文](Spring-Boot-Starter) | [English](Spring-Boot-Starter-en)

## Redisson Integration & Deployment Modes

The starter reuses an existing `RedissonClient` in your app and only creates a simple single-server client if none is present.

- Recommended: configure cluster/sentinel/SSL via `redisson-spring-boot-starter` (e.g. `implementation 'org.redisson:redisson-spring-boot-starter:3.29.0'`) and point `spring.redisson.file` to your YAML.

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
  redisson:
    file: classpath:redisson-sentinel.yaml
```

> Tip: with redisson-spring-boot-starter in place, you can remove `redis-streaming.redis.*`. The starter detects your `RedissonClient` and skips its internal single-server client.

## Codec & Lua Best Practices

Lua scripts in registry/MQ read/write string/JSON in these keyspaces:

- Registry: `{prefix}:services` (Set), `{prefix}:services:{service}:heartbeats` (ZSet), `{prefix}:services:{service}:instance:{id}` (Hash)
- MQ Retry: `streaming:mq:retry:{topic}` (ZSet), `streaming:mq:retry:item:{topic}:{uuid}` (Hash)

Guidelines:

- Access these keys with `StringCodec`; values should be strings/JSON. Using object codecs (e.g. Kryo) to read string replies (SMEMBERS/HGET) may cause deserialization errors.
- You can still use Kryo/JSON for your own keys, as long as those keys are not touched by Lua.
- Easiest: set global `codec: !<org.redisson.codec.StringCodec>` in Redisson config.

## Metrics & Historical Curves (Micrometer/Prometheus)

The starter ships Micrometer binders (MQ/Retention/Reliability). In your app:

```gradle
implementation 'org.springframework.boot:spring-boot-starter-actuator'
runtimeOnly 'io.micrometer:micrometer-registry-prometheus:1.12.5'
```

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,env,prometheus
  metrics:
    export:
      prometheus:
        enabled: true
```

Then scrape `/actuator/prometheus` (Prometheus/Grafana) for `mq_*`, `retention_*`, `reliability_*` metrics.

---

## 🚀 Quick Start

### 1. Add Dependency

Maven:
```xml
<dependency>
    <groupId>io.github.cuihairu.redis-streaming</groupId>
    <artifactId>spring-boot-starter</artifactId>
    <version>0.1.0</version>
</dependency>
```

Gradle:
```gradle
implementation 'io.github.cuihairu.redis-streaming:spring-boot-starter:0.1.0'
```

### 2. Enable Features

```java
@SpringBootApplication
@EnableRedisStreaming  // Enable all features
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
```

### 3. Configuration

```yaml
streaming:
  # Redis connection
  redis:
    address: redis://127.0.0.1:6379
    password: your-password
    database: 0
    connection-pool-size: 64

  # Service registry
  registry:
    enabled: true
    auto-register: true
    heartbeat-interval: 30
    heartbeat-timeout: 90
    instance:
      service-name: ${spring.application.name}
      host: 192.168.1.100
      port: ${server.port}
      weight: 1
      protocol: http
      metadata:
        version: 1.0.0
        region: us-east

  # Service discovery
  discovery:
    enabled: true
    healthy-only: true
    cache-time: 30

spring:
  application:
    name: user-service
server:
  port: 8080
```

## MQ Auto-Configuration (New)

> Note: the new configuration prefix is `redis-streaming.mq` (replacing older `streaming.mq`).

```yaml
redis-streaming:
  mq:
    enabled: true
    default-partition-count: 4
    worker-threads: 16
    scheduler-threads: 2
    consumer-batch-count: 32
    consumer-poll-timeout-ms: 500
    lease-ttl-seconds: 15
    rebalance-interval-sec: 5
    renew-interval-sec: 3
    pending-scan-interval-sec: 30
    claim-idle-ms: 300000
    claim-batch-size: 100
    max-in-flight: 1024
    max-leased-partitions-per-consumer: 16
    retry-max-attempts: 5
    retry-base-backoff-ms: 1000
    retry-max-backoff-ms: 60000
    retry-mover-batch: 100
    retry-mover-interval-sec: 1
```

- Beans: `MessageQueueFactory`, `MessageQueueAdmin`, `DeadLetterQueueManager`
- Metrics: Micrometer counters/timers (tags: `topic`, `partition`) and aggregate gauges
- Health: HealthIndicator (topic count); can be extended for lease/mover backlog

## 📋 Feature Modules

### Service Registration & Discovery

#### Auto Registration

Automatically register on startup, no coding required:

```yaml
streaming:
  registry:
    auto-register: true
    instance:
      service-name: user-service
      weight: 2
      metadata:
        version: 2.0.0
```

#### Manual Operations

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

#### Service Change Listener

```java
@Component
public class ServiceListener {

    @ServiceChangeListener(services = {"payment-service", "order-service"})
    public void onServiceChange(String serviceName, String action,
                                ServiceInstance instance,
                                List<ServiceInstance> allInstances) {
        log.info("Service {} changed: {} - {}",
                serviceName, action, instance.getInstanceId());

        if ("payment-service".equals(serviceName)) {
            updatePaymentServiceCache(allInstances);
        }
    }
}
```

### Message Queue

#### Producer

```java
@Service
public class OrderService {

    @Autowired
    private MessageProducer producer;

    public void createOrder(Order order) {
        // Save order
        orderRepository.save(order);

        // Send message
        Message message = Message.builder()
                .topic("order_events")
                .data(Map.of("orderId", order.getId(), "status", "created"))
                .build();

        producer.sendAsync(message);
    }
}
```

#### Consumer

```java
@Component
public class OrderEventConsumer {

    @StreamListener(topic = "order_events", group = "payment_group")
    public boolean handleOrderEvent(Message message) {
        try {
            // Process order event
            processOrder(message.getData());
            return true;
        } catch (Exception e) {
            log.error("Failed to process order", e);
            return false; // Will retry
        }
    }
}
```

### Configuration Management

#### Publish Config

```java
@Service
public class ConfigManager {

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

#### Config Listener

```java
@Component
public class DatabaseConfigListener {

    @ConfigChangeListener(dataId = "database.config", group = "production")
    public void onDatabaseConfigChange(String dataId, String group,
                                       String content, String version) {
        // Reload database configuration
        reloadDatabaseConnection(content);
    }
}
```

## 🔧 Configuration Details

### Redis Configuration

```yaml
streaming:
  redis:
    address: redis://127.0.0.1:6379  # Redis address
    password: password                # Password (optional)
    database: 0                      # Database index
    connect-timeout: 3000            # Connect timeout (ms)
    timeout: 3000                    # Response timeout (ms)
    connection-pool-size: 64         # Connection pool size
    connection-minimum-idle-size: 10 # Minimum idle connections
```

### Registry Configuration

```yaml
streaming:
  registry:
    enabled: true                    # Enable service registry
    auto-register: true             # Auto-register this service
    heartbeat-interval: 30          # Heartbeat interval (seconds)
    heartbeat-timeout: 90           # Heartbeat timeout (seconds)
    instance:
      service-name: ${spring.application.name}
      instance-id: ""               # Instance ID (auto-generated if empty)
      host: ""                      # Host address (auto-detected if empty)
      port: ${server.port}
      weight: 1
      enabled: true
      protocol: http
      metadata:
        version: 1.0.0
        region: us-east
```

### Message Queue Configuration

```yaml
streaming:
  mq:
    consumer:
      batch-size: 10              # Messages to pull per batch
      poll-timeout: 5000          # Poll timeout (ms)
      max-retry: 3                # Max retry count
      retry-delay: 1000           # Retry delay (ms)
    producer:
      max-len: 10000              # Stream max length
      auto-trim: true             # Auto-trim
    dlq:
      enabled: true               # Enable dead letter queue
      suffix: "_dlq"              # DLQ Topic suffix
```

## 🎯 Use Cases

### Microservice Registration & Discovery

```java
// Service Provider
@SpringBootApplication
@EnableRedisStreaming
public class UserServiceProvider {
    // Auto-register user-service on startup
}

// API Gateway
@SpringBootApplication
@EnableRedisStreaming
public class ApiGateway {

    @Autowired
    private ServiceDiscovery discovery;

    @RequestMapping("/api/users/**")
    public ResponseEntity<?> proxyToUserService(HttpServletRequest request) {
        List<ServiceInstance> instances = discovery.discoverHealthy("user-service");
        ServiceInstance instance = loadBalance(instances);
        return forwardRequest(instance, request);
    }
}
```

### Event-Driven Architecture

```java
// Order Service
@Service
public class OrderService {
    @Autowired
    private MessageProducer producer;

    public void createOrder(Order order) {
        orderRepository.save(order);
        producer.sendAsync(Message.of("order_created", order));
    }
}

// Payment Service
@Component
public class PaymentService {
    @StreamListener(topic = "order_created", group = "payment")
    public boolean processPayment(Message message) {
        // Process payment
        return true;
    }
}

// Inventory Service
@Component
public class InventoryService {
    @StreamListener(topic = "order_created", group = "inventory")
    public boolean reserveStock(Message message) {
        // Reserve stock
        return true;
    }
}
```

## ⚠️ Notes

1. **Redis Connection**: Ensure Redis service is available, recommend configuring connection pool
2. **Network Environment**: Auto-detected IP may be incorrect, recommend explicit configuration
3. **Resource Cleanup**: Application will auto-deregister services and cleanup connections on shutdown
4. **Thread Safety**: All components are thread-safe
5. **Performance**: Don't set heartbeat interval too short, recommend 30+ seconds

## 🚧 FAQ

**Q: How to disable auto-registration?**
```yaml
streaming:
  registry:
    auto-register: false
```

**Q: How to use existing RedissonClient?**
```java
@Bean
@Primary
public RedissonClient customRedissonClient() {
    // Return custom RedissonClient
}
```

**Q: Multi-environment configuration?**
```yaml
# application-dev.yml
streaming:
  redis:
    address: redis://dev-redis:6379

# application-prod.yml
streaming:
  redis:
    address: redis://prod-redis:6379
```

---

**Version**: 0.1.0
**Last Updated**: 2025-10-13

🔗 Related Documentation:
- [[Overall Architecture|Architecture-en]]
- [[Detailed Design|Design-en]]
- [[Registry Design|Registry-Design-en]]
- [[MQ Design|MQ-Design-en]]
