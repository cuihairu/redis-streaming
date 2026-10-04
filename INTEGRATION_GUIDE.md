# 本地集成使用指南

## 发布到本地 Maven 仓库

在仓库根目录执行以下命令，将所有可发布模块（`examples` 除外）发布到本地 Maven 仓库 (`~/.m2/repository`)：

```bash
./gradlew publishToMavenLocal
```

发布后的坐标格式为 `io.github.cuihairu.redis-streaming:<模块名>:<版本>`（`group` 见根 `build.gradle`，版本默认取自 Git tag，当前版本 0.2.0）。仓库共 20 个 Gradle 模块（见 `settings.gradle`），其中 `examples` 不参与发布（根 `build.gradle` 显式排除）：

```
io.github.cuihairu.redis-streaming:
  - core:0.2.0        # 核心 API（DataStream / State / Checkpoint / Window 等抽象）
  - runtime:0.2.0     # 运行时引擎（Redis 驱动 + 内存实现）
  - mq:0.2.0          # Redis Streams 消息队列（消费组 / 重试 / DLQ）
  - registry:0.2.0    # 服务注册与发现（心跳 / metadata 过滤）
  - config:0.2.0      # 配置中心（版本化 / 历史 / 监听）
  - state:0.2.0       # Redis 状态原语（Value/Map/List/Set）
  - checkpoint:0.2.0  # Checkpoint 协调与存储
  - watermark:0.2.0   # Watermark 策略与生成器
  - window:0.2.0      # 窗口（assigner / trigger）
  - aggregation:0.2.0 # 窗口聚合（PV/UV/TopK/分位数）
  - table:0.2.0       # KTable（内存 + Redis 实现）
  - join:0.2.0        # 时间窗口流流 Join
  - cdc:0.2.0         # CDC 连接器（MySQL binlog / PostgreSQL 逻辑复制 / 轮询）
  - sink:0.2.0        # 输出连接器（Kafka / Redis Stream/Hash/List 等）
  - source:0.2.0      # 输入连接器（Kafka / HTTP / Redis List/Stream 等）
  - reliability:0.2.0 # 重试 / DLQ / 去重 / 限流
  - cep:0.2.0         # 复杂事件处理
  - metrics:0.2.0     # 指标与 Prometheus 导出
  - spring-boot-starter:0.2.0  # Spring Boot 自动装配与注解
  - examples          # 可运行示例（不发布，仅模块依赖）
```

> 各模块构建脚本引用统一版本目录 `gradle/libs.versions.toml`（当前 Redisson 4.7.0、Jackson 2.17.0、Lombok 1.18.34、Spring Boot 3.2.0）。

## 在其他项目中使用

### 1. Spring Boot 项目集成（推荐）

#### build.gradle
```gradle
dependencies {
    // 引入 Spring Boot Starter（会自动引入必需的依赖）
    implementation 'io.github.cuihairu.redis-streaming:spring-boot-starter:0.2.0'

    // 可选：使用官方 Redisson Starter 支持集群/哨兵模式（生产环境推荐）
    // 版本应与本仓库依赖的 Redisson 对齐（gradle/libs.versions.toml 当前 redisson = 4.7.0）
    // implementation 'org.redisson:redisson-spring-boot-starter:4.7.0'
}

repositories {
    mavenLocal()  // 使用本地 Maven 仓库
    mavenCentral()
}
```

#### Redis 配置方式

##### 方式1: 使用内置简化配置（开发/测试环境）
适合快速开发，仅支持单机模式：

application.yml
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
支持集群、哨兵、SSL：

build.gradle
```gradle
dependencies {
    implementation 'io.github.cuihairu.redis-streaming:spring-boot-starter:0.2.0'
    // 版本与 gradle/libs.versions.toml 中的 redisson 保持一致（当前 4.7.0）
    implementation 'org.redisson:redisson-spring-boot-starter:4.7.0'
}
```

redisson.yaml（Redisson 官方配置，支持集群、哨兵等；示例为集群模式）
```yaml
clusterServersConfig:
  nodeAddresses:
    - redis://127.0.0.1:7000
    - redis://127.0.0.1:7001
    - redis://127.0.0.1:7002

# 或哨兵模式
# sentinelServersConfig:
#   masterName: mymaster
#   sentinelAddresses:
#     - redis://127.0.0.1:26379
#     - redis://127.0.0.1:26380
```

application.yml
```yaml
# Redis Streaming 业务配置
redis-streaming:
  registry:
    enabled: true
    heartbeat-interval: 30    # 秒
    heartbeat-timeout: 90     # 秒

  discovery:
    enabled: true
    healthy-only: true

  config:
    enabled: true
    default-group: DEFAULT_GROUP

# Redisson 官方 Starter 配置（键名以 redisson-spring-boot-starter 自身文档为准）
spring:
  redis:
    redisson:
      file: classpath:redisson.yaml
```

完整集群/哨兵示例见 [docs/Deployment.md](docs/Deployment.md)。

> 注意：本 starter 通过 `@ConditionalOnMissingBean` 创建兜底的 `RedissonClient`。当项目已提供 `RedissonClient`（如由 `redisson-spring-boot-starter` 创建）时会跳过，`redis-streaming.redis.*` 配置随之不生效。

#### 配置键与默认值

以下默认值取自 `spring-boot-starter` 的 `RedisStreamingProperties`（装配条件取自各 `RedisStreaming*AutoConfiguration` 的 `@ConditionalOnProperty`）：

| 键 | 默认值 | 说明 |
|---|---|---|
| `redis-streaming.redis.address` | `redis://127.0.0.1:6379` | 单机地址（仅内置简化配置生效） |
| `redis-streaming.redis.password` | 空 | 密码；`null` 或空串都不会发送 `AUTH` |
| `redis-streaming.redis.database` | `0` | 数据库序号 |
| `redis-streaming.redis.timeout` | `3000` | 命令超时（ms） |
| `redis-streaming.redis.connect-timeout` | `3000` | 连接超时（ms） |
| `redis-streaming.redis.connection-pool-size` | `64` | 连接池大小 |
| `redis-streaming.redis.connection-minimum-idle-size` | `10` | 最小空闲连接数 |
| `redis-streaming.registry.enabled` | `true` | 注册中心自动装配 |
| `redis-streaming.registry.heartbeat-interval` | `30` | 心跳间隔（秒） |
| `redis-streaming.registry.heartbeat-timeout` | `90` | 心跳超时（秒） |
| `redis-streaming.discovery.enabled` | `true` | 服务发现自动装配 |
| `redis-streaming.discovery.healthy-only` | `true` | 发现时只返回健康实例 |
| `redis-streaming.config.enabled` | `true` | 配置中心自动装配 |
| `redis-streaming.config.default-group` | `DEFAULT_GROUP` | 默认配置组 |
| `redis-streaming.config.history-size` | `10` | 配置历史版本保留数 |
| `redis-streaming.mq.enabled` | `true` | MQ 自动装配 |
| `redis-streaming.ratelimit.enabled` | `false` | 限流自动装配（需显式开启） |

#### Application.java
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
public class MyService {

    @Autowired
    private NamingService namingService;

    public void registerService() {
        // 创建服务实例（DefaultServiceInstance 提供 builder 实现）
        ServiceInstance instance = DefaultServiceInstance.builder()
                .serviceName("my-service")
                .instanceId("my-service-192.168.1.10:8080")
                .host("192.168.1.10")
                .port(8080)
                .protocol(StandardProtocol.HTTP)
                .enabled(true)
                .healthy(true)
                .weight(1)
                .ephemeral(true)  // 临时实例，依赖客户端心跳，超时后自动移除
                .metadata(buildMetadata())
                .build();

        // 注册服务
        namingService.register(instance);
    }

    private Map<String, String> buildMetadata() {
        Map<String, String> metadata = new HashMap<>();
        metadata.put("version", "1.0.0");
        metadata.put("region", "us-east-1");
        metadata.put("zone", "zone-a");
        return metadata;
    }
}
```

> `metadata` 类型为 `Map<String, String>`（见 `DefaultServiceInstance`）；`weight` 为 `int`；协议取枚举 `StandardProtocol`（`Protocol` 本身是接口）。

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
     * 按权重和负载筛选实例
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

支持的比较运算符：
- `==` 或不带符号 - 等于（默认）
- `!=` - 不等于
- `>` - 大于
- `>=` - 大于等于
- `<` - 小于
- `<=` - 小于等于

过滤针对实例 `metadata`（`Map<String, String>`）中已存在的键：两边都能转成数字时按数值比较，否则按字典序比较（实现见 `registry` 模块的 `GET_INSTANCES_BY_METADATA_SCRIPT` Lua 脚本）。因此上例中的 `weight`、`cpu_usage` 需要作为键写入实例 metadata 才能参与过滤。

详细文档见 [Registry 文档](docs/Registry.md)

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

配置中心特性：
- 配置版本化：自动保存历史版本（`history-size` 控制保留数，默认 10）
- 变更通知：监听器回调收到新内容（`addListener`）
- 历史记录：`getConfigHistory(dataId, group, size)` 查询最近 N 个版本
- 热加载：监听器在配置变更时触发

详细文档见 [配置中心文档](config/README.md) 与 [docs/config.md](docs/config.md)

### 6. 传统方式监听服务变更

```java
import io.github.cuihairu.redis.streaming.registry.*;
import io.github.cuihairu.redis.streaming.registry.listener.ServiceChangeListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
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
```

- `actions` 默认值为 `{"added", "removed", "updated"}`（见注解定义），不指定即接收全部动作。
- `services` 必须显式指定：当前实现不支持全局监听，`services` 为空时 `ServiceChangeListenerProcessor` 只打印 warn 日志（"Global service listener not fully supported yet"），不会注册任何订阅。

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
    implementation 'io.github.cuihairu.redis-streaming:spring-boot-starter:0.2.0'
    implementation 'org.springframework.boot:spring-boot-starter-web'
    compileOnly 'org.projectlombok:lombok:1.18.34'
    annotationProcessor 'org.projectlombok:lombok:1.18.34'
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

1. 启动 Redis
```bash
docker run -d -p 6379:6379 redis:latest
```

2. 运行你的应用
```bash
./gradlew bootRun
```

3. 查看日志
应该能看到（日志文本对应 `RedisStreamingAutoConfiguration`、`RedisStreamingRegistryAutoConfiguration`、`ServiceChangeListenerProcessor` 中的实际输出）：
```
Initializing RedissonClient with address: redis://127.0.0.1:6379 (Simple single-server mode)
Initializing NamingService with heartbeat interval: 5s
Initializing ServiceChangeListenerProcessor for @ServiceChangeListener annotation
Registered service change listener: onServiceChange on bean: DemoApplication for service: test-service, actions: [added, removed, updated]
```

## 常见问题

### Q: 找不到依赖？
A: 确保 `repositories` 中包含 `mavenLocal()`

### Q: @ServiceChangeListener 不生效？
A: 依次检查：
1. starter 依赖已引入（自动装配通过 `META-INF/spring/...AutoConfiguration.imports` 注册，无需注解；`@EnableRedisStreaming` 只是 `@Import(RedisStreamingAutoConfiguration.class)` 的别名，可选）
2. 监听方法所在的类是 Spring Bean（`@Component`/`@Service` 等）
3. `services` 已显式指定（为空不会注册订阅，见上文说明）
4. `redis-streaming.registry.enabled` 未被关闭（默认 `true`）

### Q: Redis 连接失败？
A: 检查 Redis 是否启动，地址配置是否正确

## 更多文档

- [Spring Boot Starter 使用指南](docs/spring-boot-starter-guide.md)
- [服务注册发现文档](registry/README.md)
- [Registry 文档（含 Metadata 过滤）](docs/Registry.md)
- [配置管理文档](config/README.md)

---

版本：0.2.0
最后更新：2026-10-05
