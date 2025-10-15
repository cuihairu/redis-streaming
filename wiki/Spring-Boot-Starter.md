# Spring Boot Starter 使用指南

[中文](Spring-Boot-Starter) | [English](Spring-Boot-Starter-en)

---

## 🚀 快速开始

### 1. 添加依赖

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

### 2. 启用功能

```java
@SpringBootApplication
@EnableRedisStreaming  // 启用所有功能
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
```

### 3. 配置文件

```yaml
streaming:
  # Redis连接配置
  redis:
    address: redis://127.0.0.1:6379
    password: your-password
    database: 0
    connection-pool-size: 64

  # 服务注册配置
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

  # 服务发现配置
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

## 📋 功能模块

### 服务注册发现

#### 自动服务注册

应用启动后自动注册,无需编码:

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

#### 手动服务操作

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

#### 服务变更监听

```java
@Component
public class ServiceListener {

    @ServiceChangeListener(services = {"payment-service", "order-service"})
    public void onServiceChange(String serviceName, String action,
                                ServiceInstance instance,
                                List<ServiceInstance> allInstances) {
        log.info("服务 {} 发生变更: {} - {}",
                serviceName, action, instance.getInstanceId());

        if ("payment-service".equals(serviceName)) {
            updatePaymentServiceCache(allInstances);
        }
    }
}
```

### 消息队列

#### 生产者

```java
@Service
public class OrderService {

    @Autowired
    private MessageProducer producer;

    public void createOrder(Order order) {
        // 保存订单
        orderRepository.save(order);

        // 发送消息
        Message message = Message.builder()
                .topic("order_events")
                .data(Map.of("orderId", order.getId(), "status", "created"))
                .build();

        producer.sendAsync(message);
    }
}
```

#### 消费者

```java
@Component
public class OrderEventConsumer {

    @StreamListener(topic = "order_events", group = "payment_group")
    public boolean handleOrderEvent(Message message) {
        try {
            // 处理订单事件
            processOrder(message.getData());
            return true;
        } catch (Exception e) {
            log.error("Failed to process order", e);
            return false; // 将重试
        }
    }
}
```

### 配置管理

#### 发布配置

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

#### 配置监听

```java
@Component
public class DatabaseConfigListener {

    @ConfigChangeListener(dataId = "database.config", group = "production")
    public void onDatabaseConfigChange(String dataId, String group,
                                       String content, String version) {
        // 自动重新加载数据库配置
        reloadDatabaseConnection(content);
    }
}
```

## 🔧 配置详解

### Redis 配置

```yaml
streaming:
  redis:
    address: redis://127.0.0.1:6379  # Redis地址
    password: password                # 密码(可选)
    database: 0                      # 数据库索引
    connect-timeout: 3000            # 连接超时(ms)
    timeout: 3000                    # 响应超时(ms)
    connection-pool-size: 64         # 连接池大小
    connection-minimum-idle-size: 10 # 最小空闲连接
```

### 服务注册配置

```yaml
streaming:
  registry:
    enabled: true                    # 启用服务注册
    auto-register: true             # 自动注册本服务
    heartbeat-interval: 30          # 心跳间隔(秒)
    heartbeat-timeout: 90           # 心跳超时(秒)
    instance:
      service-name: ${spring.application.name}
      instance-id: ""               # 实例ID(空则自动生成)
      host: ""                      # 主机地址(空则自动获取)
      port: ${server.port}
      weight: 1
      enabled: true
      protocol: http
      metadata:
        version: 1.0.0
        region: us-east
```

### 消息队列配置

```yaml
streaming:
  mq:
    consumer:
      batch-size: 10              # 每次拉取消息数量
      poll-timeout: 5000          # 拉取超时(ms)
      max-retry: 3                # 最大重试次数
      retry-delay: 1000           # 重试间隔(ms)
    producer:
      max-len: 10000              # Stream 最大长度
      auto-trim: true             # 自动修剪
    dlq:
      enabled: true               # 启用死信队列
      suffix: "_dlq"              # DLQ Topic 后缀
```

## 🎯 使用场景

### 微服务注册发现

```java
// 服务提供者
@SpringBootApplication
@EnableRedisStreaming
public class UserServiceProvider {
    // 启动即自动注册 user-service
}

// API 网关
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

### 事件驱动架构

```java
// 订单服务
@Service
public class OrderService {
    @Autowired
    private MessageProducer producer;

    public void createOrder(Order order) {
        orderRepository.save(order);
        producer.sendAsync(Message.of("order_created", order));
    }
}

// 支付服务
@Component
public class PaymentService {
    @StreamListener(topic = "order_created", group = "payment")
    public boolean processPayment(Message message) {
        // 处理支付
        return true;
    }
}

// 库存服务
@Component
public class InventoryService {
    @StreamListener(topic = "order_created", group = "inventory")
    public boolean reserveStock(Message message) {
        // 预留库存
        return true;
    }
}
```

## ⚠️ 注意事项

1. **Redis 连接**: 确保 Redis 服务可用,建议配置连接池
2. **网络环境**: 自动获取的 IP 可能不正确,建议显式配置
3. **资源清理**: 应用关闭时会自动注销服务和清理连接
4. **并发安全**: 所有组件都是线程安全的
5. **性能考虑**: 心跳间隔不要设置过短,建议 30 秒以上

## 🚧 常见问题

**Q: 如何禁用自动注册?**
```yaml
streaming:
  registry:
    auto-register: false
```

**Q: 如何使用已有的 RedissonClient?**
```java
@Bean
@Primary
public RedissonClient customRedissonClient() {
    // 返回自定义的 RedissonClient
}
```

**Q: 多环境配置?**
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

## 💬 消息队列自动装配（新）

> 说明：从当前版本起，MQ 配置前缀为 `redis-streaming.mq`（与旧文档中的 `streaming.mq` 有所不同），请按下列示例配置。

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
    retry-max-attempts: 5
    retry-base-backoff-ms: 1000
    retry-max-backoff-ms: 60000
    retry-mover-batch: 100
    retry-mover-interval-sec: 1
```

- 暴露 Bean：`MessageQueueFactory`、`MessageQueueAdmin`、`DeadLetterQueueManager`
- 指标：Micrometer 细粒度 Counter/Timer（带 `topic/partition` 标签）与聚合 Gauge
- 健康检查：`HealthIndicator`（topics 计数）；可扩展为租约/搬运积压探测

---

**版本**: 0.1.0
**最后更新**: 2025-10-13

🔗 相关文档:
- [[整体架构|Architecture]]
- [[详细设计|Design]]
- [[Registry 设计|Registry-Design]]
- [[MQ 设计|MQ-Design]]
