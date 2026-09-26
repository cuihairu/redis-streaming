# 本地集成使用指南

## 已发布到本地 Maven 仓库

所有模块已成功发布到本地 Maven 仓库 (`~/.m2/repository`)：

```
io.github.cuihairu.redis-streaming:
  - core:0.1.0
  - config:0.1.0
  - registry:0.1.0
  - spring-boot-starter:0.1.0
  ... (其他模块)
```

## 在其他项目中使用

### 1. Spring Boot 项目集成（推荐）

#### **build.gradle**
```gradle
dependencies {
    // 引入 Spring Boot Starter（会自动引入必需的依赖）
    implementation 'io.github.cuihairu.redis-streaming:spring-boot-starter:0.1.0'

    // 可选：使用官方 Redisson Starter 支持集群/哨兵模式（生产环境推荐）
    // implementation 'org.redisson:redisson-spring-boot-starter:3.29.0'
}

repositories {
    mavenLocal()  // 使用本地 Maven 仓库
    mavenCentral()
}
```

#### **Redis 配置方式**

##### 方式1: 使用内置简化配置（开发/测试环境）
适合快速开发，仅支持单机模式：

**application.yml**
```yaml
redis-streaming:
  redis:
    address: redis://127.0.0.1:6379
    password: null
    database: 0
    timeout: 3000
    connect-timeout: 3000
    connection-pool-size: 64
    connection-minimum-idle-size: 10
```

##### 方式2: 使用官方 Redisson Starter（生产环境推荐）
支持集群、哨兵、SSL等完整功能：

**build.gradle**
```gradle
dependencies {
    implementation 'io.github.cuihairu.redis-streaming:spring-boot-starter:0.1.0'
    implementation 'org.redisson:redisson-spring-boot-starter:3.29.0'
}
```

**application.yml**
```yaml
# Redis Streaming 业务配置
redis-streaming:
  registry:
    enabled: true
    heartbeat-interval: 5
    heartbeat-timeout: 15

  discovery:
    enabled: true
    healthy-only: true

  config:
    enabled: true
    default-group: DEFAULT_GROUP

# Redisson 官方配置（支持集群、哨兵等）
spring:
  redis:
    redisson:
      config: |
        # 单机模式
        singleServerConfig:
          address: redis://127.0.0.1:6379
          password: your-password
          database: 0
          connectionPoolSize: 64
          connectionMinimumIdleSize: 10

        # 或集群模式
        # clusterServersConfig:
        #   nodeAddresses:
        #     - redis://127.0.0.1:7000
        #     - redis://127.0.0.1:7001
        #     - redis://127.0.0.1:7002

        # 或哨兵模式
        # sentinelServersConfig:
        #   masterName: mymaster
        #   sentinelAddresses:
        #     - redis://127.0.0.1:26379
        #     - redis://127.0.0.1:26380
```

> **注意**: 当同时存在 `redisson-spring-boot-starter` 时，框架会自动使用官方 Starter 创建的 `RedissonClient`，忽略 `redis-streaming.redis.*` 配置。

#### **Application.java**
```java
import io.github.cuihairu.redis.streaming.registry.ServiceInstance;
import io.github.cuihairu.redis.streaming.registry.ServiceChangeAction;
import io.github.cuihairu.redis.streaming.starter.annotation.EnableRedisStreaming;
import io.github.cuihairu.redis.streaming.starter.annotation.ServiceChangeListener;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Slf4j
@SpringBootApplication
@EnableRedisStreaming  // 启用 Redis Streaming 框架
@RestController
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

    /**
     * 使用注解监听服务变更（推荐方式）
     * 支持多种方法签名
     */
    @ServiceChangeListener(services = {"user-service", "order-service"},
                          actions = {"added", "removed", "updated"})
    public void onServiceChange(String serviceName,
                                ServiceChangeAction action,
                                ServiceInstance instance,
                                List<ServiceInstance> allInstances) {
        log.info("服务变更通知:");
        log.info("  服务名: {}", serviceName);
        log.info("  动作: {}", action);
        log.info("  实例: {}:{}", instance.getHost(), instance.getPort());
        log.info("  当前实例数: {}", allInstances.size());
    }

    /**
     * 简化签名 - 只关心实例
     */
    @ServiceChangeListener(services = {"payment-service"})
    public void onPaymentServiceChange(ServiceInstance instance) {
        log.info("支付服务变更: {}", instance.getInstanceId());
    }
}
```

### 2. 服务注册示例

```java
import io.github.cuihairu.redis.streaming.registry.*;
import io.github.cuihairu.redis.streaming.starter.service.AutoServiceRegistration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
public class MyService {

    @Autowired
    private NamingService namingService;

    public void registerService() {
        // 创建服务实例
        ServiceInstance instance = ServiceInstance.builder()
                .serviceName("my-service")
                .instanceId("my-service-192.168.1.10:8080")
                .host("192.168.1.10")
                .port(8080)
                .protocol(Protocol.HTTP)
                .enabled(true)
                .healthy(true)
                .weight(1.0)
                .ephemeral(true)  // 临时实例，停止心跳后自动删除
                .metadata(buildMetadata())
                .build();

        // 注册服务
        namingService.register(instance);
    }

    private Map<String, Object> buildMetadata() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("version", "1.0.0");
        metadata.put("region", "us-east-1");
        metadata.put("zone", "zone-a");
        return metadata;
    }
}
```

### 3. 服务发现示例

```java
import io.github.cuihairu.redis.streaming.registry.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ServiceConsumerExample {

    @Autowired
    private NamingService namingService;

    public void discoverServices() {
        // 获取所有健康实例
        List<ServiceInstance> instances = namingService.getInstances("user-service", true);

        for (ServiceInstance instance : instances) {
            System.out.println("发现服务实例: " + instance.getHost() + ":" + instance.getPort());
        }

        // 选择一个实例进行调用
        if (!instances.isEmpty()) {
            ServiceInstance instance = instances.get(0);
            String url = instance.getProtocol().getName() + "://" +
                        instance.getHost() + ":" + instance.getPort();
            // 调用服务...
        }
    }
}
```

### 4. Metadata 过滤查询（支持比较运算符）

```java
import io.github.cuihairu.redis.streaming.registry.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class MetadataFilteringExample {

    @Autowired
    private NamingService namingService;

    public void discoverWithFilters() {
        // 基础过滤：精确匹配
        Map<String, String> basicFilters = new HashMap<>();
        basicFilters.put("version", "1.0.0");
        basicFilters.put("region", "us-east-1");

        List<ServiceInstance> filtered = namingService.getInstancesByMetadata(
            "order-service", basicFilters
        );

        // 高级过滤：使用比较运算符
        Map<String, String> advancedFilters = new HashMap<>();
        advancedFilters.put("weight:>=", "80");           // 权重 >= 80
        advancedFilters.put("cpu_usage:<", "70");         // CPU使用率 < 70%
        advancedFilters.put("region", "us-east-1");       // 精确匹配
        advancedFilters.put("status:!=", "maintenance");  // 排除维护状态

        List<ServiceInstance> highPerfInstances = namingService
            .getHealthyInstancesByMetadata("order-service", advancedFilters);

        System.out.println("Found " + highPerfInstances.size() + " high-performance instances");
    }

    /**
     * 智能负载均衡场景
     */
    public ServiceInstance selectBestInstance(String serviceName) {
        // 优先选择高权重、低负载的实例
        Map<String, String> filters = new HashMap<>();
        filters.put("weight:>=", "80");
        filters.put("cpu_usage:<", "70");
        filters.put("latency:<=", "100");

        List<ServiceInstance> instances = namingService
            .getHealthyInstancesByMetadata(serviceName, filters);

        if (instances.isEmpty()) {
            // Fallback：放宽条件
            filters.clear();
            filters.put("cpu_usage:<", "80");
            instances = namingService.getHealthyInstancesByMetadata(serviceName, filters);
        }

        return instances.isEmpty() ? null : instances.get(0);
    }
}
```

**支持的比较运算符：**
- `==` 或不带符号 - 等于（默认）
- `!=` - 不等于
- `>` - 大于
- `>=` - 大于等于
- `<` - 小于
- `<=` - 小于等于

**详细文档：** 参考 [Metadata 过滤查询指南](registry/METADATA_FILTERING_GUIDE.md)

### 5. 配置中心使用

```java
import io.github.cuihairu.redis.streaming.config.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ConfigCenterExample {

    @Autowired
    private ConfigService configService;

    /**
     * 发布配置
     */
    public void publishConfiguration() {
        configService.publishConfig(
            "database.config",              // 配置 ID
            "production",                   // 配置组
            "db.url=jdbc:mysql://localhost:3306/mydb\ndb.username=root",
            "Initial database configuration" // 描述
        );
    }

    /**
     * 获取配置
     */
    public void getConfiguration() {
        String config = configService.getConfig("database.config", "production");
        System.out.println("Config: " + config);

        // 带默认值
        String configWithDefault = configService.getConfig(
            "database.config",
            "production",
            "db.url=jdbc:mysql://localhost:3306/default"
        );
    }

    /**
     * 监听配置变更（热加载）
     */
    public void listenConfigChanges() {
        configService.addListener("database.config", "production",
            (dataId, group, content) -> {
                System.out.println("Configuration updated: " + content);
                // 重新加载数据库连接池等
                reloadDatabaseConnection(content);
            }
        );
    }

    /**
     * 查询历史版本
     */
    public void queryHistory() {
        List<ConfigHistory> history = configService.getConfigHistory(
            "database.config",
            "production",
            5  // 获取最近 5 个版本
        );

        for (ConfigHistory h : history) {
            System.out.println("Version: " + h.getVersion());
            System.out.println("Description: " + h.getDescription());
            System.out.println("Change Time: " + h.getChangeTime());
        }
    }

    /**
     * 删除配置
     */
    public void deleteConfiguration() {
        boolean deleted = configService.removeConfig("database.config", "production");
        if (deleted) {
            System.out.println("Configuration deleted successfully");
        }
    }

    private void reloadDatabaseConnection(String content) {
        // 重新加载数据库连接池的实现
        System.out.println("Reloading database connection with new config");
    }
}
```

**配置中心特性：**
- [配置版本化：自动保存历史版本]
- [变更通知：实时推送配置变更]
- [历史记录：查询配置的历史版本]
- [热加载：监听器自动触发配置更新]

**详细文档：** 参考 [配置中心文档](config/README.md)

### 6. 传统方式监听服务变更

```java
import io.github.cuihairu.redis.streaming.registry.*;
import io.github.cuihairu.redis.streaming.registry.listener.ServiceChangeListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.List;

@Component
public class TraditionalListenerExample {

    @Autowired
    private NamingService namingService;

    @PostConstruct
    public void init() {
        // 手动注册监听器
        ServiceChangeListener listener = new ServiceChangeListener() {
            @Override
            public void onServiceChange(String serviceName,
                                       ServiceChangeAction action,
                                       ServiceInstance instance,
                                       List<ServiceInstance> allInstances) {
                System.out.println("服务变更: " + serviceName + " - " + action);
            }
        };

        namingService.subscribe("user-service", listener);
    }
}
```

## @ServiceChangeListener 注解特性

### 支持的方法签名

```java
// 1. 完整参数
@ServiceChangeListener(services = {"user-service"})
void method1(String serviceName, ServiceChangeAction action,
            ServiceInstance instance, List<ServiceInstance> allInstances)

// 2. 使用 String action
@ServiceChangeListener(services = {"user-service"})
void method2(String serviceName, String action,
            ServiceInstance instance, List<ServiceInstance> allInstances)

// 3. 只关心实例
@ServiceChangeListener(services = {"user-service"})
void method3(ServiceInstance instance)

// 4. 只关心动作和实例
@ServiceChangeListener(services = {"user-service"})
void method4(ServiceChangeAction action, ServiceInstance instance)

// 5. 只关心实例列表
@ServiceChangeListener(services = {"user-service"})
void method5(List<ServiceInstance> allInstances)
```

### 过滤选项

```java
// 监听多个服务
@ServiceChangeListener(services = {"user-service", "order-service", "payment-service"})

// 只监听特定动作
@ServiceChangeListener(
    services = {"user-service"},
    actions = {"added", "removed"}  // 忽略 updated
)

// 监听所有服务（不推荐，性能影响）
@ServiceChangeListener  // services 为空表示监听所有
```

## 完整示例项目

创建一个新的 Spring Boot 项目：

```bash
mkdir my-test-project
cd my-test-project

# 创建 build.gradle
cat > build.gradle << 'EOF'
plugins {
    id 'org.springframework.boot' version '3.2.0'
    id 'io.spring.dependency-management' version '1.1.4'
    id 'java'
}

group = 'com.example'
version = '0.0.1-SNAPSHOT'
sourceCompatibility = '17'

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation 'io.github.cuihairu.redis-streaming:spring-boot-starter:0.1.0'
    implementation 'org.springframework.boot:spring-boot-starter-web'
    compileOnly 'org.projectlombok:lombok:1.18.30'
    annotationProcessor 'org.projectlombok:lombok:1.18.30'
}
EOF

# 创建 application.yml
mkdir -p src/main/resources
cat > src/main/resources/application.yml << 'EOF'
server:
  port: 8080

redis-streaming:
  redis:
    address: redis://127.0.0.1:6379

  registry:
    enabled: true
    heartbeat-interval: 5

  discovery:
    enabled: true
EOF

# 创建 Application 类
mkdir -p src/main/java/com/example/demo
cat > src/main/java/com/example/demo/DemoApplication.java << 'EOF'
package com.example.demo;

import io.github.cuihairu.redis.streaming.registry.ServiceInstance;
import io.github.cuihairu.redis.streaming.starter.annotation.EnableRedisStreaming;
import io.github.cuihairu.redis.streaming.starter.annotation.ServiceChangeListener;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@Slf4j
@SpringBootApplication
@EnableRedisStreaming
public class DemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }

    @ServiceChangeListener(services = {"test-service"})
    public void onServiceChange(String serviceName, String action, ServiceInstance instance) {
        log.info("收到服务变更: {} - {} - {}", serviceName, action, instance.getInstanceId());
    }
}
EOF

# 运行
./gradlew bootRun
```

## 启动测试

1. **启动 Redis**
```bash
docker run -d -p 6379:6379 redis:latest
```

2. **运行你的应用**
```bash
./gradlew bootRun
```

3. **查看日志**
应该能看到：
```
Initializing RedissonClient with address: redis://127.0.0.1:6379
Initializing NamingService with heartbeat interval: 5s
Initializing ServiceChangeListenerProcessor for @ServiceChangeListener annotation
Registered service change listener: onServiceChange on bean: DemoApplication for service: test-service, actions: all
```

## 常见问题

### Q: 找不到依赖？
A: 确保 `repositories` 中包含 `mavenLocal()`

### Q: @ServiceChangeListener 不生效？
A: 检查是否添加了 `@EnableRedisStreaming` 注解

### Q: Redis 连接失败？
A: 检查 Redis 是否启动，地址配置是否正确

## 更多文档

- [Spring Boot Starter 使用指南](docs/spring-boot-starter-guide.md)
- [服务注册发现文档](registry/README.md)
- [Metadata 过滤查询指南](registry/METADATA_FILTERING_GUIDE.md) 🆕
- [配置管理文档](config/README.md)

---

**版本**: 0.1.0
**最后更新**: 2025-01-12
**新增**: Metadata 比较运算符过滤、配置中心示例
