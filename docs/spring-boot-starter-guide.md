# Spring Boot Starter 使用指南

## 🚀 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>io.github.cuihairu.redis-streaming</groupId>
    <artifactId>spring-boot-starter</artifactId>
    <version>0.0.1-SNAPSHOT</version>
</dependency>
```

或者 Gradle：

```gradle
implementation 'io.github.cuihairu.redis-streaming:spring-boot-starter:0.0.1-SNAPSHOT'
```

### 2. 启用功能

```java
@SpringBootApplication
@EnableStreaming  // 启用所有功能
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
```

### 3. 配置文件

```yaml
# application.yml
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
      host: 192.168.1.100  # 可选，不设置自动获取
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
    
  # 配置服务
  config:
    enabled: true
    default-group: DEFAULT_GROUP
    auto-refresh: true
    refresh-interval: 30

spring:
  application:
    name: user-service
server:
  port: 8080
```

## 📋 按需启用功能

### 方式一：通过注解控制

```java
@EnableStreaming(
    registry = true,   // 启用服务注册
    discovery = false, // 禁用服务发现
    config = true      // 启用配置服务
)
```

### 方式二：通过配置文件控制

```yaml
streaming:
  registry:
    enabled: true     # 启用服务注册
  discovery:
    enabled: false    # 禁用服务发现
  config:
    enabled: true     # 启用配置服务
```

## 🔧 使用方式

### 1. 自动服务注册

应用启动后自动注册，无需编码：

```yaml
streaming:
  registry:
    auto-register: true  # 默认开启
    instance:
      service-name: user-service
      weight: 2
      metadata:
        version: 2.0.0
```

### 2. 手动服务操作

```java
@Service
public class UserService {
    
    @Autowired
    private ServiceRegistry serviceRegistry;
    
    @Autowired 
    private ServiceDiscovery serviceDiscovery;
    
    public void registerExternalService() {
        ServiceInstance instance = DefaultServiceInstance.builder("external-api", "api-1", "api.example.com", 443)
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

### 3. 服务变更监听

```java
@Component
public class ServiceListener {
    
    @ServiceChangeListener(services = {"payment-service", "order-service"})
    public void onServiceChange(String serviceName, String action, ServiceInstance instance, List<ServiceInstance> allInstances) {
        log.info("服务 {} 发生变更: {} - {}", serviceName, action, instance.getInstanceId());
        
        if ("payment-service".equals(serviceName)) {
            // 更新支付服务实例缓存
            updatePaymentServiceCache(allInstances);
        }
    }
}
```

### 4. 配置管理

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
        configService.publishConfig("database.config", "production", config, "生产数据库配置");
    }
    
    @ConfigChangeListener(dataId = "database.config", group = "production")
    public void onDatabaseConfigChange(String dataId, String group, String content, String version) {
        // 自动重新加载数据库配置
        reloadDatabaseConnection(content);
    }
}
```

## 📚 配置详解

### Redis配置

```yaml
streaming:
  redis:
    address: redis://127.0.0.1:6379  # Redis地址
    password: password                # 密码（可选）
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
      service-name: ${spring.application.name}  # 服务名
      instance-id: ""               # 实例ID(空则自动生成)
      host: ""                      # 主机地址(空则自动获取)
      port: ${server.port}          # 端口
      weight: 1                     # 权重
      enabled: true                 # 是否启用
      protocol: http                # 协议类型
      metadata:                     # 元数据
        version: 1.0.0
        region: us-east
```

### 服务发现配置

```yaml
streaming:
  discovery:
    enabled: true                   # 启用服务发现
    healthy-only: true             # 只发现健康实例
    cache-time: 30                 # 缓存时间(秒)
```

### 配置服务配置

```yaml
streaming:
  config:
    enabled: true                  # 启用配置服务
    default-group: DEFAULT_GROUP   # 默认配置组
    auto-refresh: true            # 自动刷新配置
    refresh-interval: 30          # 刷新间隔(秒)
    history-size: 10              # 历史版本保留数
```

## 🎯 使用场景

### 微服务注册发现

```java
// 服务提供者 - 自动注册
@SpringBootApplication
@EnableStreaming(discovery = false)  // 只要注册，不要发现
public class UserServiceProvider {
    // 启动即自动注册 user-service
}

// 服务消费者 - 服务发现
@SpringBootApplication 
@EnableStreaming(registry = false)   // 只要发现，不要注册
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

### 配置中心

```java
// 配置管理服务
@EnableStreaming(registry = false, discovery = false)
public class ConfigCenter {
    
    @Autowired
    private ConfigService configService;
    
    @PostMapping("/config/{group}/{dataId}")
    public String updateConfig(@PathVariable String group, 
                              @PathVariable String dataId,
                              @RequestBody String content) {
        boolean success = configService.publishConfig(dataId, group, content);
        return success ? "SUCCESS" : "FAILED";
    }
}

// 业务服务 - 配置消费
@EnableStreaming(config = true)
public class BusinessService {
    
    @ConfigChangeListener(dataId = "business.rules", group = "production")
    public void onBusinessRulesChange(String dataId, String group, String content, String version) {
        // 热更新业务规则
        reloadBusinessRules(JSON.parse(content));
    }
}
```

## 🔍 监控和调试

### Health Check集成

```java
@Component
public class StreamingHealthIndicator implements HealthIndicator {
    
    @Autowired(required = false)
    private ServiceRegistry registry;
    
    @Override
    public Health health() {
        if (registry != null && registry.isRunning()) {
            return Health.up()
                    .withDetail("registry", "running")
                    .build();
        }
        return Health.down()
                .withDetail("registry", "stopped")
                .build();
    }
}
```

### 日志配置

```yaml
logging:
  level:
    io.github.cuihairu.redis-streaming: DEBUG
    org.redisson: INFO
```

## ⚠️ 注意事项

1. **Redis连接**: 确保Redis服务可用，建议配置连接池
2. **网络环境**: 自动获取的IP可能不正确，建议显式配置
3. **资源清理**: 应用关闭时会自动注销服务和清理连接
4. **并发安全**: 所有组件都是线程安全的
5. **性能考虑**: 心跳间隔不要设置过短，建议30秒以上

## 🚧 常见问题

**Q: 如何禁用自动注册？**
```yaml
streaming:
  registry:
    auto-register: false
```

**Q: 如何使用已有的RedissonClient？**
```java
@Bean
@Primary
public RedissonClient customRedissonClient() {
    // 返回自定义的RedissonClient
}
```

**Q: 如何自定义服务实例ID？**
```yaml
streaming:
  registry:
    instance:
      instance-id: my-custom-instance-id
```

**Q: 多环境配置？**
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