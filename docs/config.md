# Config 模块

Module: `config/`（包根 `io.github.cuihairu.redis.streaming.config`）

## 概述

基于 Redis 的分布式配置中心：配置的发布、获取、删除、监听、版本化历史与历史修剪。发布走 Lua 原子脚本（失败回退 Java 批量写），变更通知走「本地同步派发 + Redis Pub/Sub」，并有后台重同步轮询补偿丢失的通知。

## 核心接口

### ConfigService

配置服务的完整生命周期接口，继承 `ConfigManager`（publisher/subscriber 两个视角的方法都声明在这里）。

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
    default int trimHistoryBySize(String dataId, String group, int maxSize);
    default int trimHistoryByAge(String dataId, String group, java.time.Duration maxAge);
    void start();
    void stop();
    default boolean isRunning();
}
```

行为要点（实现类 `impl.RedisConfigService`）：

- 未 `start()` 时读写方法抛 `IllegalStateException`
- `publishConfig` 每次生成新版本号（`毫秒时间戳-序号`），旧内容写入历史（`historySize>0` 时）
- `removeConfig` 配置不存在时返回 false；删除成功会广播 content 为 null 的事件
- `addListener` 在配置已存在时立即用当前 content/version 回调一次
- `getConfigHistory(size)` 中 `size<=0` 返回空列表
- 两个 `trimHistoryByXxx` 返回被删除的记录数

### ConfigChangeListener

```java
@FunctionalInterface
public interface ConfigChangeListener {
    void onConfigChange(String dataId, String group, String content, String version);
}
```

### ConfigChangeEvent（Pub/Sub 载荷）

```java
public class ConfigChangeEvent {
    private String dataId;
    private String group;
    private String content;     // 删除事件为 null
    private String version;     // String；删除事件为 null
    private long timestamp;
    private String publisherId; // 发布实例标识，用于跳过发布端自身的 Pub/Sub 回环
}
```

### ConfigCenter

在 `ConfigManager` 之上追加元数据查询，实现类 `impl.RedisConfigCenter`（内部委托 `RedisConfigService`）：

```java
public interface ConfigCenter extends ConfigManager {
    boolean hasConfig(String dataId, String group);
    ConfigMetadata getConfigMetadata(String dataId, String group);  // 需已 start

    interface ConfigMetadata {
        String getVersion();
        String getDescription();
        long getCreateTime();     // 毫秒
        long getLastModified();   // 毫秒
        long getSize();           // content 的 UTF-8 字节数
    }
}
```

### 数据模型

```java
// 历史记录
public class ConfigHistory {
    private String dataId;
    private String group;
    private String content;
    private String version;         // String
    private String description;     // 历史记录里存操作类型：UPDATED / DELETED
    private LocalDateTime changeTime;
    private String operator;
}

// 配置完整信息（getConfigMetadata 内部使用）
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

## 使用方式

### 1. 基本使用

```java
ConfigService configService = new RedisConfigService(redissonClient);
configService.start();

configService.publishConfig("app.properties", "DEFAULT", "timeout=5000");
String config = configService.getConfig("app.properties", "DEFAULT");

configService.addListener("app.properties", "DEFAULT",
    (dataId, group, content, version) ->
        System.out.println("配置已更新: " + content + " version=" + version));

configService.removeConfig("app.properties", "DEFAULT");
configService.stop();
```

构造也可传入配置：`new RedisConfigService(redissonClient, new ConfigServiceConfig("myapp", true))`。

### 2. 配置中心视图

```java
ConfigCenter center = new RedisConfigCenter(redissonClient);
center.start();
boolean exists = center.hasConfig("app.properties", "dev");
long size = center.getConfigMetadata("app.properties", "dev").getSize();
```

### 3. 配置历史查询

```java
List<ConfigHistory> history = configService.getConfigHistory("app.properties", "DEFAULT", 10);
for (ConfigHistory h : history) {
    System.out.println("版本: " + h.getVersion());
    System.out.println("时间: " + h.getChangeTime());
    System.out.println("操作: " + h.getDescription());
    System.out.println("内容: " + h.getContent());
}
```

### 4. 历史修剪

```java
configService.trimHistoryBySize("app.properties", "DEFAULT", 100);              // 保留最近 100 条
configService.trimHistoryByAge("app.properties", "DEFAULT", Duration.ofDays(30)); // 删除 30 天前
```

## 配置说明

### ConfigServiceConfig

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| keyPrefix | String | `redis_streaming` | Redis 键前缀 |
| enableKeyPrefix | boolean | true | 是否启用键前缀 |
| historySize | int | 10 | 每个配置保留的历史条数；0 表示不保存历史，负值按 0 处理 |
| resyncIntervalMs | long | 30000 | 重同步轮询间隔（毫秒），0 关闭，负值按 0 处理 |

键生成方法（`ConfigServiceConfig`，group/dataId 中的 `:` 会被替换为 `_`）：

| 方法 | 键模板 |
|------|--------|
| `getConfigKey(group, dataId)` | `{prefix}:config:{group}:{dataId}` |
| `getConfigHistoryKey(group, dataId)` | `{prefix}:config_history:{group}:{dataId}` |
| `getConfigSubscribersKey(group, dataId)` | `{prefix}:config_subscribers:{group}:{dataId}` |
| `getConfigChangeChannelKey(group, dataId)` | `{prefix}:config_change:{group}:{dataId}` |

### Spring Boot 属性

starter（`RedisStreamingProperties`，前缀 `redis-streaming`）：

| 属性 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| redis-streaming.config.enabled | boolean | true | 是否装配 ConfigService Bean |
| redis-streaming.config.key-prefix | String | `redis_streaming` | Redis 键前缀 |
| redis-streaming.config.enable-key-prefix | boolean | true | 是否启用键前缀 |
| redis-streaming.config.history-size | int | 10 | 历史记录条数 |
| redis-streaming.config.default-group | String | DEFAULT_GROUP | 仅用于启动日志，未参与装配逻辑 |

starter 未映射 `resyncIntervalMs`，需要时自行构造 `ConfigServiceConfig` 传入。

另注意：starter 里声明了 `@io.github.cuihairu.redis.streaming.starter.annotation.ConfigChangeListener` 注解（`dataId`/`group`/`autoRefresh`），但主源码中没有读取该注解的处理器，注解方法不会被回调（未见于实现）；监听配置请用编程式 `addListener`。

## 设计说明

### Redis 数据结构

1. 配置存储：Hash `{prefix}:config:{group}:{dataId}`，字段 `content`、`version`、`description`、`createTime`、`updateTime`
2. 历史记录：List `{prefix}:config_history:{group}:{dataId}`，元素为 JSON（dataId/group/content/version/operation/changeTime/operator），LPUSH 写入并 LTRIM 修剪到 `historySize`
3. 订阅者登记：Set `{prefix}:config_subscribers:{group}:{dataId}`，成员为实例 clientId，`stop()` 时移除
4. 发布订阅：Pub/Sub 通道 `{prefix}:config_change:{group}:{dataId}`，消息为 `ConfigChangeEvent`

### 配置变更流程

1. `publishConfig` 以 Lua 原子写配置 Hash，同时把旧内容 LPUSH 进历史并修剪
2. 版本号由 `generateVersion()` 生成：`毫秒时间戳-序号`，同毫秒内序号递增，时钟回拨时延续最新毫秒的序号
3. 发布端先把变更同步派发给本 JVM 的监听器（`publish` 返回前可见）
4. 再经 Pub/Sub 通知其他实例；事件带 `publisherId`，发布实例自身的回环消息被跳过
5. 监听器派发按 `(content, version)` 幂等，与重同步轮询并发时只投递一次
6. `start()` 之后（`resyncIntervalMs>0`）后台线程按固定间隔重读所有已订阅配置，内容与最近派发不一致时重新派发，补偿连接抖动期间丢失的 Pub/Sub 通知；轮询中的异常会被捕获，不会终止调度

### 历史修剪语义

- 发布/删除路径：`historySize>0` 时修剪到最新 N 条；`historySize=0` 时完全不写历史
- `trimHistoryBySize(maxSize<=0)`：清空该配置的历史列表
- `trimHistoryByAge`：从列表尾部（最旧）开始按 `changeTime` 与截止时间比较，修剪过期区间

## 注意事项

1. 先 start 再使用，未 `start()` 时读写抛 `IllegalStateException`
2. `historySize` 为 0 即关闭历史，发布与删除都不会再写历史记录
3. 监听器回调线程：本地派发在发布方调用线程执行，Pub/Sub 派发在 Redisson 消息线程执行；监听器应快速返回，重活交给业务线程池
4. 重复回调由实现层经 `publisherId` 与 `(content, version)` 幂等去重；跨实例仍会各收到一次回调
5. 监听器注册/注销/停止用同一把锁串行化，并发测试（`ConfigServiceConcurrencyTest`、`ConfigServiceConcurrentAddListenerRaceTest` 等）覆盖了这条路径

## 相关文档

- [Registry 模块](Registry.md) - 服务注册与发现
- [config 模块 README](../config/README.md) - 模块内说明
- [Spring Boot Starter](Spring-Boot-Starter.md) - Spring Boot 集成
