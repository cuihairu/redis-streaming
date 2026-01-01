# Config 模块

## 概述

Config 模块提供基于 Redis 的分布式配置中心功能，支持配置的发布、订阅、监听和历史管理。适用于微服务架构中的动态配置管理场景。

## 核心功能

### 1. 配置管理

- **配置发布**: 动态发布配置到 Redis
- **配置获取**: 实时获取最新配置
- **配置删除**: 删除不再使用的配置
- **配置历史**: 保留配置变更历史，支持回滚

### 2. 配置监听

- **实时推送**: 配置变更时实时推送到订阅者
- **精确匹配**: 支持按 dataId 和 group 精确订阅
- **自动重连**: Redis 连接断开时自动重连

### 3. 版本管理

- **配置版本**: 自动维护配置版本号
- **历史记录**: 保存配置变更历史
- **时间戳**: 记录每次变更的时间戳

## 核心接口

### ConfigService

配置服务核心接口，提供配置的完整生命周期管理。

```java
public interface ConfigService extends ConfigManager {
    // 获取配置
    String getConfig(String dataId, String group);
    String getConfig(String dataId, String group, String defaultValue);

    // 发布配置
    boolean publishConfig(String dataId, String group, String content);
    boolean publishConfig(String dataId, String group, String content, String description);

    // 删除配置
    boolean removeConfig(String dataId, String group);

    // 监听配置变更
    void addListener(String dataId, String group, ConfigChangeListener listener);
    void removeListener(String dataId, String group, ConfigChangeListener listener);

    // 配置历史
    List<ConfigHistory> getConfigHistory(String dataId, String group, int size);

    // 生命周期管理
    void start();
    void stop();
    boolean isRunning();
}
```

### ConfigChangeListener

配置变更监听器接口，接收配置变更通知。

```java
@FunctionalInterface
public interface ConfigChangeListener {
    void onConfigChange(ConfigChangeEvent event);
}
```

### ConfigChangeEvent

配置变更事件，包含变更的详细信息。

```java
public class ConfigChangeEvent {
    private String dataId;      // 配置ID
    private String group;        // 配置组
    private String content;      // 配置内容
    private long version;        // 版本号
    private long timestamp;      // 时间戳
    private String description;  // 描述
}
```

### ConfigHistory

配置历史记录。

```java
public class ConfigHistory {
    private long version;        // 版本号
    private String content;      // 配置内容
    private long timestamp;      // 时间戳
    private String description;  // 描述
}
```

## 使用方式

### 1. 基本使用

```java
// 创建配置服务
ConfigServiceConfig config = new ConfigServiceConfig("config:", true);
ConfigService configService = new RedisConfigService(redissonClient, config);
configService.start();

// 发布配置
configService.publishConfig("app.properties", "DEFAULT", "timeout=5000");

// 获取配置
String config = configService.getConfig("app.properties", "DEFAULT");

// 监听配置变更
configService.addListener("app.properties", "DEFAULT", event -> {
    System.out.println("配置已更新: " + event.getContent());
});

// 删除配置
configService.removeConfig("app.properties", "DEFAULT");

// 停止服务
configService.stop();
```

### 2. Spring Boot 集成

**依赖**:
```gradle
implementation 'io.github.cuihairu.redis-streaming:spring-boot-starter:0.2.0'
```

**配置** (application.yml):
```yaml
redis-streaming:
  redis:
    address: redis://localhost:6379
  config:
    enabled: true
    key-prefix: config:
    enable-key-prefix: true
    history-size: 10
```

**使用**:
```java
@Service
public class ConfigService {

    @Autowired
    private io.github.cuihairu.redis.streaming.config.ConfigService configService;

    @PostConstruct
    public void init() {
        // 监听配置变更
        configService.addListener("app.config", "DEFAULT", event -> {
            updateProperties(event.getContent());
        });
    }

    private void updateProperties(String content) {
        // 解析配置并更新应用
        Properties props = new Properties();
        // ... 更新逻辑
    }
}
```

### 3. 配置历史查询

```java
// 查询最近 10 条历史
List<ConfigHistory> history = configService.getConfigHistory(
    "app.properties",
    "DEFAULT",
    10
);

for (ConfigHistory h : history) {
    System.out.println("版本: " + h.getVersion());
    System.out.println("时间: " + new Date(h.getTimestamp()));
    System.out.println("内容: " + h.getContent());
}
```

### 4. 动态刷新配置

```java
// 监听配置变更并动态刷新
configService.addListener("database.config", "DEFAULT", event -> {
    // 解析新配置
    Properties newConfig = parseConfig(event.getContent());

    // 动态更新数据源
    updateDataSource(newConfig);
});

private void updateDataSource(Properties config) {
    String url = config.getProperty("url");
    String username = config.getProperty("username");
    String password = config.getProperty("password");

    // 重新创建数据源
    DataSource newDataSource = createDataSource(url, username, password);
    // ... 更新应用中的数据源引用
}
```

## 配置说明

### ConfigServiceConfig

配置服务的配置参数：

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| keyPrefix | String | "config:" | Redis 键前缀 |
| enableKeyPrefix | boolean | true | 是否启用键前缀 |
| historySize | int | 10 | 保存的历史记录数量 |

### Spring Boot 属性配置

| 属性 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| redis-streaming.config.enabled | boolean | true | 是否启用配置中心 |
| redis-streaming.config.key-prefix | String | "config:" | Redis 键前缀 |
| redis-streaming.config.enable-key-prefix | boolean | true | 是否启用键前缀 |
| redis-streaming.config.history-size | int | 10 | 历史记录数量 |

## 设计说明

### Redis 数据结构

配置服务使用以下 Redis 数据结构：

1. **配置存储**: Hash
   - Key: `{keyPrefix}{group}:{dataId}`
   - Fields: content, version, timestamp, description

2. **历史记录**: List
   - Key: `{keyPrefix}{group}:{dataId}:history`
   - 每个元素是一个历史记录的序列化结果

3. **发布订阅**: Pub/Sub
   - Channel: `{keyPrefix}{group}:{dataId}:change`
   - Message: 配置变更事件

### 配置变更流程

1. 发布者调用 `publishConfig()` 发布配置
2. 配置写入 Redis Hash
3. 版本号自动递增
4. 历史记录追加到 List
5. 通过 Redis Pub/Sub 推送变更事件
6. 订阅者收到通知，触发 `ConfigChangeListener`

## 注意事项

1. **配置大小**: 单个配置内容不宜过大，建议控制在 1MB 以内
2. **历史数量**: 历史记录数量不宜过多，避免占用过多内存
3. **监听器数量**: 避免在同一配置上注册过多监听器
4. **异常处理**: 监听器中应妥善处理异常，避免影响其他监听器
5. **线程安全**: `ConfigService` 实现是线程安全的

## 相关文档

- [Registry 模块](Registry.md) - 服务注册与发现
- [MQ 模块](MQ.md) - 消息队列
- [Spring Boot Starter](Spring-Boot-Starter.md) - Spring Boot 集成
