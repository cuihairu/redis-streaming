# Config - 分布式配置中心

基于 Redis 的分布式配置中心，提供配置版本化、变更通知、历史记录与历史修剪。

[![Build Status](https://img.shields.io/badge/build-passing-brightgreen.svg)](https://github.com/cuihairu/redis-streaming)
[![Version](https://img.shields.io/badge/version-0.2.0-blue.svg)](https://github.com/cuihairu/redis-streaming)

## 核心特性

- 配置发布与获取用 Redis Hash 存储，Lua 脚本原子写入；Lua 失败时回退到 Java 批量写（同版本号，不重复写历史）
- 配置按 `dataId` + `group` 二元组隔离，键名中的 `:` 会被替换为 `_`
- 每次发布生成版本号（格式 `毫秒时间戳-序号`，JVM 内单调）
- 变更通知：发布端先同步派发本地监听器，再经 Redis Pub/Sub 通知其他实例；本实例的回环事件会被跳过，避免重复回调
- 通知丢失有补偿：后台按 `resyncIntervalMs`（默认 30 秒，0 关闭）轮询已订阅配置，Pub/Sub 丢包时按内容差异重新派发
- `addListener` 时若配置已存在，会立刻用当前内容回调一次
- 历史记录：每次发布/删除把旧版本压入 List 并按 `historySize` 修剪；`historySize=0` 表示不保存历史
- 历史可用 `trimHistoryBySize` / `trimHistoryByAge` 手动修剪
- `ConfigCenter.hasConfig` / `getConfigMetadata` 提供元数据查询（版本、描述、创建/更新时间、字节数）

## 快速开始

### 1. 添加依赖

```gradle
dependencies {
    implementation 'io.github.cuihairu.redis-streaming:config:0.2.0'
}
```

### 2. 创建 ConfigService

```java
import io.github.cuihairu.redis.streaming.config.*;
import io.github.cuihairu.redis.streaming.config.impl.RedisConfigService;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

Config config = new Config();
config.useSingleServer().setAddress("redis://127.0.0.1:6379");
RedissonClient redissonClient = Redisson.create(config);

// 默认键前缀 redis_streaming；也可 new ConfigServiceConfig("myapp", true)
ConfigService configService = new RedisConfigService(redissonClient);
configService.start();   // 未 start 时 getConfig/publishConfig 抛 IllegalStateException
```

### 3. 发布配置

```java
boolean ok = configService.publishConfig(
    "database.config",              // dataId
    "DEFAULT_GROUP",                // group
    "db.url=jdbc:mysql://localhost:3306/mydb\ndb.username=root");

// 带描述（推荐）
configService.publishConfig("database.config", "DEFAULT_GROUP", content, "调大连接池");
```

### 4. 获取配置

```java
String content = configService.getConfig("database.config", "DEFAULT_GROUP");

// 带默认值
String withDefault = configService.getConfig("database.config", "DEFAULT_GROUP", "db.url=...");
```

### 5. 监听配置变更

```java
configService.addListener("database.config", "DEFAULT_GROUP",
    (dataId, group, content, version) -> {
        System.out.println("updated: " + dataId + "@" + group + " version=" + version);
        reloadDatabaseConnection(content);
    });

configService.removeListener("database.config", "DEFAULT_GROUP", listener);
```

监听器接口是 `onConfigChange(String dataId, String group, String content, String version)`（4 个参数，`version` 为 String）。注意：注册时若配置已存在会立刻回调一次当前内容。

### 6. 查询历史版本

```java
List<ConfigHistory> history = configService.getConfigHistory("database.config", "DEFAULT_GROUP", 5);

for (ConfigHistory h : history) {
    System.out.println("Version: " + h.getVersion());
    System.out.println("Operation: " + h.getDescription());   // 历史记录的 description 存操作类型：UPDATED / DELETED
    System.out.println("Change Time: " + h.getChangeTime());  // LocalDateTime
    System.out.println("Content: " + h.getContent());
}
```

### 7. 删除配置

```java
boolean deleted = configService.removeConfig("database.config", "DEFAULT_GROUP");
```

删除会把旧内容以 `DELETED` 操作写入历史（受 `historySize` 限制），并向订阅者广播 content 为 null 的变更事件；配置不存在时返回 false。

## 实际应用场景

### 场景 1: 数据库配置热加载

```java
public class DatabaseConfigManager {
    private ConfigService configService;
    private volatile DataSource dataSource;   // javax.sql.DataSource

    public void init() {
        String config = configService.getConfig("database.config", "production");
        dataSource = createDataSource(config);

        configService.addListener("database.config", "production",
            (dataId, group, content, version) -> {
                DataSource old = dataSource;
                dataSource = createDataSource(content);
                old.close();     // 关闭旧连接池的动作由业务实现
            });
    }

    private DataSource createDataSource(String content) {
        Properties props = new Properties();
        // 按行解析 properties 文本（业务逻辑）
        return null; // 依据 props 构建数据源
    }
}
```

### 场景 2: 多环境配置管理

```java
configService.publishConfig("app.properties", "dev",        "log.level=DEBUG", "开发环境");
configService.publishConfig("app.properties", "test",       "log.level=INFO",  "测试环境");
configService.publishConfig("app.properties", "production", "log.level=WARN",  "生产环境");

String env = System.getenv("ENV");
String config = configService.getConfig("app.properties", env);
```

### 场景 3: 特性开关（Feature Flag）

```java
configService.publishConfig("feature.flags", "DEFAULT_GROUP",
    "feature.new_ui=true\nfeature.payment_v2=false", "开启新 UI");

configService.addListener("feature.flags", "DEFAULT_GROUP",
    (dataId, group, content, version) -> loadFeatures(content));   // 解析逻辑由业务实现
```

### 场景 4: 配置回滚

```java
public void rollbackConfig(String dataId, String group) {
    List<ConfigHistory> history = configService.getConfigHistory(dataId, group, 10);
    if (history.size() >= 2) {
        // history.get(0) 是当前版本，get(1) 是上一个版本
        ConfigHistory previous = history.get(1);
        configService.publishConfig(dataId, group, previous.getContent(),
                "回滚到版本 " + previous.getVersion());
    }
}
```

### 场景 5: 历史修剪

```java
// 只保留最近 100 条
int removedBySize = configService.trimHistoryBySize("app.config", "production", 100);

// 删除 30 天前的历史
int removedByAge = configService.trimHistoryByAge("app.config", "production",
        java.time.Duration.ofDays(30));
```

## 配置中心视图（ConfigCenter）

```java
import io.github.cuihairu.redis.streaming.config.impl.RedisConfigCenter;

ConfigCenter center = new RedisConfigCenter(redissonClient);   // 内部委托 RedisConfigService
center.start();

boolean exists = center.hasConfig("app.properties", "dev");
ConfigCenter.ConfigMetadata meta = center.getConfigMetadata("app.properties", "dev");
// meta.getVersion() / getDescription() / getCreateTime() / getLastModified() / getSize()
```

## 架构设计

### Redis 键结构

前缀默认 `redis_streaming`（`config.BaseRedisConfig.DEFAULT_KEY_PREFIX`），键模板见 `ConfigServiceConfig`：

| 用途 | 键 | 类型 |
|------|----|------|
| 配置存储 | `{prefix}:config:{group}:{dataId}` | Hash，字段：`content`、`version`、`description`、`createTime`、`updateTime` |
| 配置历史 | `{prefix}:config_history:{group}:{dataId}` | List，元素为 JSON 记录（dataId/group/content/version/operation/changeTime/operator），LPUSH + LTRIM 修剪 |
| 订阅者登记 | `{prefix}:config_subscribers:{group}:{dataId}` | Set，成员为实例 clientId，`stop()` 时移除 |
| 变更通知 | `{prefix}:config_change:{group}:{dataId}` | Pub/Sub，消息为 `ConfigChangeEvent` |

group 与 dataId 中的 `:` 会被替换为 `_`，避免键解析歧义。

### 版本生成

`RedisConfigService.generateVersion()` 产出 `毫秒时间戳-序号`（如 `1728000000000-0`），同一毫秒内序号递增，时钟回拨时延续最新毫秒的序号，保证 JVM 内不重复。

### 变更通知机制

1. `publishConfig` 用 Lua 原子写 Hash，并把旧版本 LPUSH 进历史（`historySize>0` 时 LTRIM 修剪）
2. 发布端同步派发给本 JVM 的监听器（`publish` 返回前可见）
3. 同时向 `{prefix}:config_change:{group}:{dataId}` 发布 `ConfigChangeEvent`；事件带 `publisherId`，发布实例自身的 Pub/Sub 回环会被跳过
4. 派发按 `(content, version)` 幂等，与后台重同步轮询并发时只投递一次
5. `start()` 起（`resyncIntervalMs>0` 时）后台线程按固定间隔重读所有已订阅配置，发现内容与最近一次派发不一致则重新派发——补偿连接抖动期间丢失的 Pub/Sub 通知

### 生命周期

`start()` 后才能读写；`stop()` 清理订阅、移除 `config_subscribers` 中的本实例成员并停止重同步线程。`isRunning()` 反映当前状态（`ConfigService` 接口上的默认实现返回 false）。

## API 参考

### ConfigService 接口

```java
public interface ConfigService extends ConfigManager {
    String getConfig(String dataId, String group);
    default String getConfig(String dataId, String group, String defaultValue);
    boolean publishConfig(String dataId, String group, String content);
    boolean publishConfig(String dataId, String group, String content, String description);
    boolean removeConfig(String dataId, String group);
    void addListener(String dataId, String group, ConfigChangeListener listener);
    void removeListener(String dataId, String group, ConfigChangeListener listener);
    List<ConfigHistory> getConfigHistory(String dataId, String group, int size);
    default int trimHistoryBySize(String dataId, String group, int maxSize);   // 返回删除条数
    default int trimHistoryByAge(String dataId, String group, Duration maxAge); // 返回删除条数
    void start();
    void stop();
    default boolean isRunning();
}
```

`ConfigManager` 是 publisher/subscriber 两个视角的父接口（方法与上表一致）；`ConfigCenter` 在 `ConfigManager` 之上追加 `hasConfig` 与 `getConfigMetadata`。

### ConfigChangeListener 接口

```java
public interface ConfigChangeListener {
    /**
     * @param dataId  配置ID
     * @param group   配置组
     * @param content 新配置内容（删除事件为 null）
     * @param version 版本号（删除事件为 null）
     */
    void onConfigChange(String dataId, String group, String content, String version);
}
```

### ConfigChangeEvent（Pub/Sub 载荷）

```java
public class ConfigChangeEvent {
    private String dataId;
    private String group;
    private String content;
    private String version;      // String
    private long timestamp;
    private String publisherId;  // 发布实例标识，用于跳过自身回环
}
```

### ConfigHistory / ConfigInfo

```java
public class ConfigHistory {
    private String dataId;
    private String group;
    private String content;
    private String version;         // String
    private String description;     // 历史记录中存操作类型：UPDATED / DELETED
    private LocalDateTime changeTime;
    private String operator;
}

public class ConfigInfo {
    private String dataId;
    private String group;
    private String content;
    private String version;
    private String description;
    private LocalDateTime updateTime;
    private LocalDateTime createTime;
}
```

### ConfigServiceConfig

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| keyPrefix | String | `redis_streaming` | Redis 键前缀 |
| enableKeyPrefix | boolean | true | 是否启用键前缀 |
| historySize | int | 10 | 每个配置保留的历史条数；0 表示不保存历史（负值归零） |
| resyncIntervalMs | long | 30000 | 重同步轮询间隔毫秒；0 关闭（负值归零） |

## Spring Boot 集成

```gradle
implementation 'io.github.cuihairu.redis-streaming:spring-boot-starter:0.2.0'
```

```yaml
redis-streaming:
  redis:
    address: redis://localhost:6379
  config:
    enabled: true              # 默认 true
    key-prefix: redis_streaming
    enable-key-prefix: true
    history-size: 10           # 默认 10
    default-group: DEFAULT_GROUP
```

starter 会创建并 `start()` 一个 `ConfigService` Bean，直接注入即可：

```java
@Service
public class SampleConfigUser {

    @Autowired
    private io.github.cuihairu.redis.streaming.config.ConfigService configService;

    @PostConstruct
    public void init() {
        configService.addListener("app.config", "DEFAULT_GROUP",
            (dataId, group, content, version) -> updateProperties(content));
    }

    private void updateProperties(String content) {
        // 解析并应用配置（业务逻辑）
    }
}
```

注意：starter 只映射 `key-prefix`、`enable-key-prefix`、`history-size` 三个属性；`resyncIntervalMs` 需要自行构造 `ConfigServiceConfig` 时设置。`default-group`、`refresh-interval`、`auto-refresh` 目前仅存在于 `RedisStreamingProperties` 中，未参与 Bean 装配逻辑（`default-group` 仅用于启动日志）。starter 中的 `@ConfigChangeListener` 注解（`dataId`/`group`/`autoRefresh`）没有对应的处理器扫描它，注解方法不会被回调（未见于实现），监听配置请用编程式 `addListener`。

## 测试

```bash
# 运行单元测试
./gradlew :config:test

# 运行集成测试（需要 Redis）
docker-compose up -d
./gradlew :config:integrationTest
```

测试覆盖（`config/src/test`）：
- 单元测试 267 个 `@Test` 方法（grep 统计），含并发、监听器竞态、历史修剪、重同步补偿等场景

## 最佳实践

### 1. 配置命名规范

```java
// 推荐：层级清晰的命名
configService.publishConfig("database.mysql.config", "production", content);
configService.publishConfig("feature.flags", "DEFAULT_GROUP", content);

// 避免：无语义命名
configService.publishConfig("config1", "group1", content);
```

### 2. 分组策略

```java
// 按环境分组
configService.publishConfig("app.properties", "dev", content);
configService.publishConfig("app.properties", "production", content);

// 或按应用分组
configService.publishConfig("database.config", "order-service", content);
```

### 3. 变更描述

```java
// 推荐：写清变更内容
configService.publishConfig("database.config", "production", content,
    "连接池从 10 调整为 20");
```

### 4. 监听器异常处理

```java
configService.addListener("app.config", "DEFAULT_GROUP",
    (dataId, group, content, version) -> {
        try {
            reloadConfiguration(content);
        } catch (Exception e) {
            logger.error("Failed to reload configuration", e);
        }
    });
```

监听器抛出的异常会被实现捕获并记录，不影响其他监听器，但业务侧仍应自行兜底。

### 5. 历史容量

`historySize` 控制每个配置的历史条数（默认 10）。历史过多的配置可用 `trimHistoryBySize` / `trimHistoryByAge` 主动清理。

## 相关链接

- [模块文档 docs/config.md](../docs/config.md)
- [Registry 模块](../docs/Registry.md)
- [主项目 README](../README.md)
- [集成指南 INTEGRATION_GUIDE.md](../INTEGRATION_GUIDE.md)
- [问题反馈](https://github.com/cuihairu/redis-streaming/issues)

---

版本 0.2.0，最后更新 2026-10-04
