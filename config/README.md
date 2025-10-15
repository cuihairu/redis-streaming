# Config - 分布式配置中心

基于 Redis 的轻量级分布式配置中心，提供配置版本化、变更通知和历史记录功能。

[![Build Status](https://img.shields.io/badge/build-passing-brightgreen.svg)](https://github.com/cuihairu/redis-streaming)
[![Version](https://img.shields.io/badge/version-0.1.0-blue.svg)](https://github.com/cuihairu/redis-streaming)

## 🚀 核心特性

### ✅ 已实现功能

- **配置发布与获取** - 基于 Redis 的配置存储和读取
- **配置分组管理** - 支持多环境、多应用的配置隔离
- **配置版本化** - 自动保存配置历史版本，支持版本追溯
- **变更通知** - Redis Pub/Sub 实时推送配置变更
- **配置监听器** - 自动触发配置变更回调，支持热加载
- **历史记录查询** - 查询配置的历史版本和变更记录
- **默认值支持** - 配置不存在时返回默认值

## 📦 快速开始

### 1. 添加依赖

```gradle
dependencies {
    implementation 'io.github.cuihairu.redis-streaming:config:0.1.0'
}
```

### 2. 创建 ConfigService

```java
import io.github.cuihairu.redis.streaming.config.*;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

// 配置 Redis
Config config = new Config();
config.useSingleServer().setAddress("redis://127.0.0.1:6379");
RedissonClient redissonClient = Redisson.create(config);

// 创建 ConfigService
ConfigService configService = new RedisConfigService(redissonClient);
configService.start();
```

### 3. 发布配置

```java
// 基础发布
boolean success = configService.publishConfig(
    "database.config",              // 配置 ID
    "DEFAULT_GROUP",                // 配置组
    "db.url=jdbc:mysql://localhost:3306/mydb\ndb.username=root"
);

// 带描述发布（推荐）
configService.publishConfig(
    "database.config",              // 配置 ID
    "DEFAULT_GROUP",                // 配置组
    "db.url=jdbc:mysql://localhost:3306/mydb\ndb.username=root\ndb.password=secret",
    "Updated database password"     // 描述
);
```

### 4. 获取配置

```java
// 基础获取
String config = configService.getConfig("database.config", "DEFAULT_GROUP");
if (config != null) {
    System.out.println("Config: " + config);
}

// 带默认值获取
String config = configService.getConfig(
    "database.config",
    "DEFAULT_GROUP",
    "db.url=jdbc:mysql://localhost:3306/default"  // 默认值
);
```

### 5. 监听配置变更（热加载）

```java
// 添加监听器
configService.addListener("database.config", "DEFAULT_GROUP",
    (dataId, group, content) -> {
        System.out.println("Configuration updated!");
        System.out.println("DataId: " + dataId);
        System.out.println("Group: " + group);
        System.out.println("New Content: " + content);

        // 重新加载数据库连接池等
        reloadDatabaseConnection(content);
    }
);
```

### 6. 查询历史版本

```java
// 获取最近 5 个历史版本
List<ConfigHistory> history = configService.getConfigHistory(
    "database.config",
    "DEFAULT_GROUP",
    5
);

for (ConfigHistory h : history) {
    System.out.println("Version: " + h.getVersion());
    System.out.println("Description: " + h.getDescription());
    System.out.println("Change Time: " + h.getChangeTime());
    System.out.println("Content: " + h.getContent());
    System.out.println("---");
}
```

### 7. 删除配置

```java
boolean deleted = configService.removeConfig("database.config", "DEFAULT_GROUP");
if (deleted) {
    System.out.println("Configuration deleted successfully");
}
```

## 🎯 实际应用场景

### 场景 1: 数据库配置热加载

```java
public class DatabaseConfigManager {
    private DataSource dataSource;
    private ConfigService configService;

    public void init() {
        // 获取初始配置
        String config = configService.getConfig("database.config", "production");
        dataSource = createDataSource(config);

        // 监听配置变更，自动重建连接池
        configService.addListener("database.config", "production",
            (dataId, group, content) -> {
                System.out.println("Database config changed, rebuilding connection pool...");
                dataSource.close();
                dataSource = createDataSource(content);
                System.out.println("Connection pool rebuilt successfully");
            }
        );
    }

    private DataSource createDataSource(String config) {
        // 解析配置并创建 DataSource
        Properties props = parseConfig(config);
        return DataSourceFactory.create(props);
    }
}
```

### 场景 2: 多环境配置管理

```java
// 开发环境配置
configService.publishConfig(
    "app.properties",
    "dev",
    "log.level=DEBUG\napi.url=http://dev-api.example.com",
    "Development environment configuration"
);

// 测试环境配置
configService.publishConfig(
    "app.properties",
    "test",
    "log.level=INFO\napi.url=http://test-api.example.com",
    "Test environment configuration"
);

// 生产环境配置
configService.publishConfig(
    "app.properties",
    "production",
    "log.level=WARN\napi.url=https://api.example.com",
    "Production environment configuration"
);

// 根据环境获取配置
String env = System.getenv("ENV");  // dev, test, production
String config = configService.getConfig("app.properties", env);
```

### 场景 3: 特性开关（Feature Flag）

```java
// 发布特性开关配置
configService.publishConfig(
    "feature.flags",
    "DEFAULT_GROUP",
    "feature.new_ui=true\nfeature.payment_v2=false\nfeature.experimental=false",
    "Enable new UI, keep payment v2 disabled"
);

// 应用中使用特性开关
public class FeatureManager {
    private Map<String, Boolean> features = new HashMap<>();

    public void init(ConfigService configService) {
        // 加载初始配置
        loadFeatures(configService.getConfig("feature.flags", "DEFAULT_GROUP"));

        // 监听特性开关变更
        configService.addListener("feature.flags", "DEFAULT_GROUP",
            (dataId, group, content) -> {
                System.out.println("Feature flags updated");
                loadFeatures(content);
            }
        );
    }

    private void loadFeatures(String config) {
        // 解析配置并更新 features Map
        for (String line : config.split("\n")) {
            String[] parts = line.split("=");
            if (parts.length == 2) {
                features.put(parts[0], Boolean.parseBoolean(parts[1]));
            }
        }
    }

    public boolean isFeatureEnabled(String featureName) {
        return features.getOrDefault(featureName, false);
    }
}
```

### 场景 4: 配置回滚

```java
public void rollbackConfig(String dataId, String group) {
    // 获取历史版本
    List<ConfigHistory> history = configService.getConfigHistory(dataId, group, 10);

    if (history.size() >= 2) {
        // 获取前一个版本（当前版本是 history.get(0)）
        ConfigHistory previousVersion = history.get(1);

        // 回滚到前一个版本
        configService.publishConfig(
            dataId,
            group,
            previousVersion.getContent(),
            "Rollback to version " + previousVersion.getVersion()
        );

        System.out.println("Rolled back to version: " + previousVersion.getVersion());
    } else {
        System.out.println("No previous version available for rollback");
    }
}
```

## 🏗️ 架构设计

### 三级存储结构

1. **配置内容层** - `streaming:config:{group}:{dataId}` (String)
   - 存储当前配置内容

2. **配置元数据层** - `streaming:config:{group}:{dataId}:meta` (Hash)
   - 存储配置的版本号、描述、更新时间等元数据

3. **配置历史层** - `streaming:config:{group}:{dataId}:history` (List)
   - 存储配置的历史版本（保留最近 N 个版本）

### 变更通知机制

- **Redis Pub/Sub** - 配置发布时通过 Redis 发布变更事件
- **变更通知频道** - `streaming:config:change:{group}:{dataId}`
- **自动监听** - 客户端自动订阅配置变更频道
- **即时回调** - 配置变更时立即触发监听器回调

## 📖 API 参考

### ConfigService 接口

```java
public interface ConfigService {
    // 配置管理
    String getConfig(String dataId, String group);
    String getConfig(String dataId, String group, String defaultValue);
    boolean publishConfig(String dataId, String group, String content);
    boolean publishConfig(String dataId, String group, String content, String description);
    boolean removeConfig(String dataId, String group);

    // 监听器管理
    void addListener(String dataId, String group, ConfigChangeListener listener);
    void removeListener(String dataId, String group, ConfigChangeListener listener);

    // 历史记录
    List<ConfigHistory> getConfigHistory(String dataId, String group, int size);

    // 生命周期
    void start();
    void stop();
    boolean isRunning();
}
```

### ConfigChangeListener 接口

```java
@FunctionalInterface
public interface ConfigChangeListener {
    /**
     * 配置变更回调
     *
     * @param dataId 配置ID
     * @param group 配置组
     * @param content 新的配置内容
     */
    void onConfigChange(String dataId, String group, String content);
}
```

### ConfigHistory 类

```java
public class ConfigHistory {
    private String dataId;          // 配置ID
    private String group;           // 配置组
    private String content;         // 历史配置内容
    private String version;         // 历史版本号
    private String description;     // 变更描述
    private LocalDateTime changeTime; // 变更时间
    private String operator;        // 操作人
}
```

## 🧪 测试

```bash
# 运行单元测试
./gradlew :config:test

# 运行集成测试（需要 Redis）
docker-compose up -d
./gradlew :config:integrationTest
```

## 📝 最佳实践

### 1. 配置命名规范

```java
// ✅ 推荐：使用清晰的命名
configService.publishConfig("database.mysql.config", "production", content);
configService.publishConfig("redis.cache.config", "production", content);
configService.publishConfig("feature.flags", "DEFAULT_GROUP", content);

// ❌ 避免：模糊的命名
configService.publishConfig("config1", "group1", content);
```

### 2. 分组策略

```java
// ✅ 推荐：按环境分组
configService.publishConfig("app.properties", "dev", content);
configService.publishConfig("app.properties", "test", content);
configService.publishConfig("app.properties", "production", content);

// ✅ 推荐：按应用分组
configService.publishConfig("database.config", "order-service", content);
configService.publishConfig("database.config", "payment-service", content);
```

### 3. 变更描述

```java
// ✅ 推荐：提供清晰的变更描述
configService.publishConfig(
    "database.config",
    "production",
    content,
    "Updated connection pool size from 10 to 20"
);

// ❌ 避免：空描述或无意义描述
configService.publishConfig("database.config", "production", content, "update");
```

### 4. 监听器异常处理

```java
// ✅ 推荐：监听器中处理异常
configService.addListener("app.config", "DEFAULT_GROUP",
    (dataId, group, content) -> {
        try {
            reloadConfiguration(content);
        } catch (Exception e) {
            logger.error("Failed to reload configuration", e);
            // 可以发送告警或回滚
        }
    }
);
```

### 5. 配置版本管理

```java
// 定期检查历史版本数量
List<ConfigHistory> history = configService.getConfigHistory("app.config", "production", 100);
if (history.size() >= 50) {
    logger.warn("Configuration has {} versions, consider cleanup", history.size());
}
```

## 🔗 相关链接

- [主项目文档](../README.md)
- [集成指南](../INTEGRATION_GUIDE.md)
- [问题反馈](https://github.com/cuihairu/redis-streaming/issues)

---

**版本**: 0.1.0
**最后更新**: 2025-01-12
**功能**: 完整的配置中心实现，包括版本化、变更通知和历史记录
