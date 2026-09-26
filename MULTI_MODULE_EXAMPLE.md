# 多模块系统集成示例

## 项目结构

```
microservices-demo/
├── settings.gradle
├── build.gradle (根)
├── common/
│   ├── build.gradle
│   └── src/main/java/
│       └── com/example/common/
│           ├── dto/
│           └── constants/
├── user-service/
│   ├── build.gradle
│   ├── src/main/java/
│   │   └── com/example/user/
│   │       ├── UserServiceApplication.java
│   │       ├── controller/
│   │       └── service/
│   └── src/main/resources/
│       └── application.yml
├── order-service/
│   ├── build.gradle
│   └── src/.../
└── payment-service/
    ├── build.gradle
    └── src/.../
```

## 配置文件

### 1. 根 build.gradle

```gradle
plugins {
    id 'java'
}

allprojects {
    group = 'com.example'
    version = '1.0.0'

    repositories {
        mavenLocal()  // 使用本地 Redis Streaming
        mavenCentral()
    }
}

subprojects {
    apply plugin: 'java'

    java {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    ext {
        springBootVersion = '3.2.0'
        redisStreamingVersion = '0.1.0'
        lombokVersion = '1.18.30'
    }
}
```

### 2. settings.gradle

```gradle
rootProject.name = 'microservices-demo'

include 'common'
include 'user-service'
include 'order-service'
include 'payment-service'
```

### 3. common/build.gradle

```gradle
dependencies {
    // 只依赖基础库，不需要 Redis Streaming
    compileOnly "org.projectlombok:lombok:${lombokVersion}"
    annotationProcessor "org.projectlombok:lombok:${lombokVersion}"

    implementation 'com.fasterxml.jackson.core:jackson-databind:2.15.0'
}
```

### 4. user-service/build.gradle

```gradle
plugins {
    id 'org.springframework.boot' version '3.2.0'
    id 'io.spring.dependency-management' version '1.1.4'
}

dependencies {
    // 依赖 common 模块
    implementation project(':common')

    // Spring Boot
    implementation 'org.springframework.boot:spring-boot-starter-web'

    // Redis Streaming - 只在需要的服务模块添加
    implementation "io.github.cuihairu.redis-streaming:spring-boot-starter:${redisStreamingVersion}"

    // Lombok
    compileOnly "org.projectlombok:lombok:${lombokVersion}"
    annotationProcessor "org.projectlombok:lombok:${lombokVersion}"
}

springBoot {
    mainClass = 'com.example.user.UserServiceApplication'
}
```

### 5. order-service/build.gradle

```gradle
plugins {
    id 'org.springframework.boot' version '3.2.0'
    id 'io.spring.dependency-management' version '1.1.4'
}

dependencies {
    implementation project(':common')
    implementation 'org.springframework.boot:spring-boot-starter-web'

    // 同样添加 Redis Streaming
    implementation "io.github.cuihairu.redis-streaming:spring-boot-starter:${redisStreamingVersion}"

    compileOnly "org.projectlombok:lombok:${lombokVersion}"
    annotationProcessor "org.projectlombok:lombok:${lombokVersion}"
}

springBoot {
    mainClass = 'com.example.order.OrderServiceApplication'
}
```

## 应用代码

### user-service/UserServiceApplication.java

```java
package com.example.user;

import io.github.cuihairu.redis.streaming.registry.*;
import io.github.cuihairu.redis.streaming.starter.annotation.EnableRedisStreaming;
import io.github.cuihairu.redis.streaming.starter.annotation.ServiceChangeListener;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.*;

import javax.annotation.PostConstruct;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@SpringBootApplication
@EnableRedisStreaming  // 启用 Redis Streaming
@RestController
public class UserServiceApplication {

    @Autowired
    private NamingService namingService;

    public static void main(String[] args) {
        SpringApplication.run(UserServiceApplication.class, args);
    }

    @PostConstruct
    public void registerService() {
        // 自动注册当前服务
        ServiceInstance instance = ServiceInstance.builder()
                .serviceName("user-service")
                .instanceId("user-service-localhost:8081")
                .host("localhost")
                .port(8081)
                .protocol(Protocol.HTTP)
                .enabled(true)
                .healthy(true)
                .weight(1.0)
                .ephemeral(true)
                .metadata(buildMetadata())
                .build();

        namingService.register(instance);
        log.info("✅ User Service 注册成功");
    }

    /**
     * 监听订单服务和支付服务的变更
     */
    @ServiceChangeListener(services = {"order-service", "payment-service"})
    public void onDependencyServiceChange(String serviceName,
                                         String action,
                                         ServiceInstance instance) {
        log.info("🔔 依赖服务变更通知:");
        log.info("   服务: {}", serviceName);
        log.info("   动作: {}", action);
        log.info("   实例: {}:{}", instance.getHost(), instance.getPort());
    }

    /**
     * REST API - 获取用户信息
     */
    @GetMapping("/api/users/{id}")
    public Map<String, Object> getUser(@PathVariable String id) {
        // 发现订单服务
        List<ServiceInstance> orderInstances =
            namingService.getInstances("order-service", true);

        Map<String, Object> response = new HashMap<>();
        response.put("userId", id);
        response.put("userName", "User " + id);
        response.put("orderServiceAvailable", !orderInstances.isEmpty());
        response.put("orderServiceInstances", orderInstances.size());

        return response;
    }

    private Map<String, Object> buildMetadata() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("version", "1.0.0");
        metadata.put("team", "user-team");
        return metadata;
    }
}
```

### user-service/application.yml

```yaml
server:
  port: 8081

spring:
  application:
    name: user-service

redis-streaming:
  redis:
    address: redis://127.0.0.1:6379
    password: null
    database: 0

  registry:
    enabled: true
    heartbeat-interval: 5
    heartbeat-timeout: 15

  discovery:
    enabled: true
    healthy-only: true

logging:
  level:
    io.github.cuihairu.redis.streaming: DEBUG
```

### order-service/OrderServiceApplication.java

```java
package com.example.order;

import io.github.cuihairu.redis.streaming.registry.*;
import io.github.cuihairu.redis.streaming.starter.annotation.EnableRedisStreaming;
import io.github.cuihairu.redis.streaming.starter.annotation.ServiceChangeListener;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.*;

import javax.annotation.PostConstruct;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@SpringBootApplication
@EnableRedisStreaming
@RestController
public class OrderServiceApplication {

    @Autowired
    private NamingService namingService;

    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }

    @PostConstruct
    public void registerService() {
        ServiceInstance instance = ServiceInstance.builder()
                .serviceName("order-service")
                .instanceId("order-service-localhost:8082")
                .host("localhost")
                .port(8082)
                .protocol(Protocol.HTTP)
                .enabled(true)
                .healthy(true)
                .weight(1.0)
                .ephemeral(true)
                .metadata(buildMetadata())
                .build();

        namingService.register(instance);
        log.info("✅ Order Service 注册成功");
    }

    /**
     * 监听用户服务和支付服务
     */
    @ServiceChangeListener(services = {"user-service", "payment-service"})
    public void onServiceChange(String serviceName, ServiceInstance instance) {
        log.info("🔔 服务变更: {} - {}", serviceName, instance.getInstanceId());
    }

    @GetMapping("/api/orders/{id}")
    public Map<String, Object> getOrder(@PathVariable String id) {
        Map<String, Object> response = new HashMap<>();
        response.put("orderId", id);
        response.put("orderStatus", "PROCESSING");
        return response;
    }

    private Map<String, Object> buildMetadata() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("version", "1.0.0");
        metadata.put("team", "order-team");
        return metadata;
    }
}
```

### order-service/application.yml

```yaml
server:
  port: 8082

spring:
  application:
    name: order-service

redis-streaming:
  redis:
    address: redis://127.0.0.1:6379

  registry:
    enabled: true
    heartbeat-interval: 5
```

## 启动和测试

### 1. 启动 Redis

```bash
docker run -d -p 6379:6379 redis:latest
```

### 2. 启动各个服务

```bash
# 终端 1 - User Service
cd user-service
./gradlew bootRun

# 终端 2 - Order Service
cd order-service
./gradlew bootRun

# 终端 3 - Payment Service (如果有)
cd payment-service
./gradlew bootRun
```

### 3. 测试服务发现

```bash
# 访问 User Service
curl http://localhost:8081/api/users/123

# 响应示例：
{
  "userId": "123",
  "userName": "User 123",
  "orderServiceAvailable": true,
  "orderServiceInstances": 1
}
```

### 4. 观察日志

**User Service 启动时：**
```
✅ User Service 注册成功
Registered service change listener: onDependencyServiceChange for service: order-service
```

**Order Service 启动时：**
```
✅ Order Service 注册成功

# User Service 会收到通知：
🔔 依赖服务变更通知:
   服务: order-service
   动作: added
   实例: localhost:8082
```

## 关键要点总结

### 依赖添加原则

1. **只在应用启动模块添加** `spring-boot-starter`
2. **公共模块不需要添加**（除非定义抽象接口）
3. **每个微服务独立配置**

### 配置原则

1. 每个服务有独立的 `application.yml`
2. 都连接到同一个 Redis 实例
3. 服务名必须唯一（通过 `spring.application.name` 或手动指定）

### 最佳实践

1. **服务注册**：在 `@PostConstruct` 中注册
2. **服务发现**：通过依赖注入 `NamingService`
3. **变更监听**：使用 `@ServiceChangeListener` 注解
4. **版本管理**：在根 `build.gradle` 统一管理版本号

## 常见问题

### Q1: common 模块需要添加依赖吗？
**A**: 不需要，除非你想在 common 中定义抽象接口

### Q2: 每个服务都要配置 Redis 地址吗？
**A**: 是的，每个服务独立配置（也可以通过配置中心统一管理）

### Q3: 服务名称会冲突吗？
**A**: 不会，只要确保每个服务的 `serviceName` 唯一即可

### Q4: 可以部分服务使用吗？
**A**: 可以！只在需要服务注册/发现的服务中添加依赖

---

**完整示例代码**: 参考 `examples/` 目录
**更多文档**: 查看 `INTEGRATION_GUIDE.md`
