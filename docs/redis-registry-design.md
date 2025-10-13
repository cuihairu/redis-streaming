# Redis-based Service Registry & Configuration Center Design

## 概述

基于Redis重新设计的服务注册发现和配置管理系统，参考Nacos架构但针对Redis的特性进行优化，提供轻量级、高性能的微服务治理解决方案。

本设计明确区分了服务提供者、服务消费者、配置发布者和配置订阅者的角色，使系统架构更加清晰。

## 核心角色定义

### 1. Service Provider (服务提供者)
**核心职责：**
- 服务注册：启动时向Redis注册服务实例信息
- 心跳维护：定期发送心跳保持实例活跃状态
- 优雅下线：关闭时主动注销服务实例

**Redis实现策略：**
```java
// 服务实例信息存储 - Hash结构
HSET services:{serviceName}:{instanceId} "host" "192.168.1.100"
HSET services:{serviceName}:{instanceId} "port" "8080" 
HSET services:{serviceName}:{instanceId} "healthy" "true"
HSET services:{serviceName}:{instanceId} "timestamp" "1640995200000"

// 服务实例列表 - Set结构
SADD service_instances:{serviceName} "{instanceId}"

// 心跳时间戳 - Sorted Set结构
ZADD heartbeat:{serviceName} {timestamp} "{instanceId}"
```

### 2. Service Consumer (服务消费者)
**核心职责：**
- 服务发现：查询可用的服务实例列表
- 服务订阅：监听服务变更通知
- 负载均衡：从实例列表中选择目标实例

**Redis实现策略：**
```java
// 服务发现 - 从Set获取实例列表，从Hash获取实例详情
SMEMBERS service_instances:{serviceName}
HGETALL services:{serviceName}:{instanceId}

// 服务订阅 - Pub/Sub通道
SUBSCRIBE service_change:{serviceName}
SUBSCRIBE config_change:{dataId}
```

### 3. Registry Center (注册中心)
**核心职责：**
- 实例管理：维护服务实例的完整生命周期
- 健康检查：主动检测服务实例健康状态
- 变更通知：推送服务变更给订阅者
- 数据清理：自动清理过期和不健康的实例

**Redis实现策略：**
```java
// 健康检查定时任务 - 基于Sorted Set的TTL机制
ZREMRANGEBYSCORE heartbeat:{serviceName} 0 {expiredTimestamp}

// 变更通知 - Pub/Sub
PUBLISH service_change:{serviceName} "{action:'removed',instanceId:'instance-1'}"

// 实例清理 - Lua脚本保证原子性
local expiredInstances = redis.call('ZRANGEBYSCORE', heartbeat_key, 0, expired_time)
for i, instanceId in ipairs(expiredInstances) do
    redis.call('HDEL', service_key, instanceId)
    redis.call('SREM', instances_key, instanceId)
    redis.call('ZREM', heartbeat_key, instanceId)
end
```

### 4. Config Publisher (配置发布者)
**核心职责：**
- 配置存储：集中存储应用配置信息
- 配置管理：支持配置的CRUD操作和版本管理
- 动态推送：配置变更时实时通知订阅者
- 灰度发布：支持配置的分批次发布

**Redis实现策略：**
```java
// 配置数据存储 - Hash结构
HSET config:{group}:{dataId} "content" "{json_config}"
HSET config:{group}:{dataId} "version" "1.0.0"
HSET config:{group}:{dataId} "updateTime" "1640995200000"

// 配置历史版本 - List结构（支持版本回滚）
LPUSH config_history:{group}:{dataId} "{version:'1.0.0',content:'...',timestamp:1640995200000}"
```

### 5. Config Subscriber (配置订阅者)
**核心职责：**
- 配置获取：获取指定的配置内容
- 配置监听：监听配置变更并及时响应
- 本地缓存：缓存配置以提高访问性能
- 故障降级：在配置中心不可用时使用默认配置

**Redis实现策略：**
```java
// 配置获取 - Hash结构
HGET config:{group}:{dataId} "content"

// 配置订阅 - Pub/Sub通道
SUBSCRIBE config_change:{group}:{dataId}

// 配置订阅者列表 - Set结构
SADD config_subscribers:{group}:{dataId} "{clientId}"
```

## Redis键前缀配置

为了避免Redis键与其他应用程序发生冲突，系统支持自定义键前缀配置：

### 默认键模式
```
services:{serviceName}:{instanceId}
service_instances:{serviceName}
heartbeat:{serviceName}
service_change:{serviceName}
```

### 带前缀的键模式
当启用前缀配置时，键模式将变为：
```
{prefix}:services:{serviceName}:{instanceId}
{prefix}:service_instances:{serviceName}
{prefix}:heartbeat:{serviceName}
{prefix}:service_change:{serviceName}
```

### 配置示例
```java
// 使用默认前缀
RedisRegistryConfig config = new RedisRegistryConfig();

// 使用自定义前缀
RedisRegistryConfig config = new RedisRegistryConfig("myapp");

// 禁用前缀
RedisRegistryConfig config = new RedisRegistryConfig("myapp", false);
```

## 核心接口设计

### 1. ServiceProvider接口
```java
public interface ServiceProvider {
    // 服务注册
    void register(ServiceInstance instance);
    
    // 服务注销
    void deregister(ServiceInstance instance);
    
    // 发送心跳
    void sendHeartbeat(ServiceInstance instance);
    
    // 批量心跳（性能优化）
    void batchSendHeartbeats(List<ServiceInstance> instances);
    
    // 生命周期管理
    void start();
    void stop();
    boolean isRunning();
}
```

### 2. ServiceConsumer接口
```java
public interface ServiceConsumer {
    // 服务发现
    List<ServiceInstance> getAllInstances(String serviceName);
    
    // 健康实例发现
    List<ServiceInstance> getHealthyInstances(String serviceName);
    
    // 服务发现（带过滤条件）
    List<ServiceInstance> getInstances(String serviceName, boolean healthy);
    
    // 服务订阅
    void subscribe(String serviceName, ServiceChangeListener listener);
    
    // 取消订阅
    void unsubscribe(String serviceName, ServiceChangeListener listener);
    
    // 生命周期管理
    void start();
    void stop();
    boolean isRunning();
}
```

### 3. ConfigPublisher接口
```java
public interface ConfigPublisher {
    // 发布配置
    boolean publishConfig(String dataId, String group, String content);
    
    // 发布配置（带描述）
    boolean publishConfig(String dataId, String group, String content, String description);
    
    // 删除配置
    boolean removeConfig(String dataId, String group);
    
    // 获取配置历史
    List<ConfigHistory> getConfigHistory(String dataId, String group, int size);
}
```

### 4. ConfigSubscriber接口
```java
public interface ConfigSubscriber {
    // 获取配置
    String getConfig(String dataId, String group);
    
    // 获取配置（带默认值）
    default String getConfig(String dataId, String group, String defaultValue) {
        String config = getConfig(dataId, group);
        return config != null ? config : defaultValue;
    }
    
    // 配置监听
    void addListener(String dataId, String group, ConfigChangeListener listener);
    
    // 移除监听
    void removeListener(String dataId, String group, ConfigChangeListener listener);
}
```

### 5. NamingService接口（整合ServiceProvider和ServiceConsumer）
```java
public interface NamingService extends ServiceProvider, ServiceConsumer {
    // 提供统一的命名服务接口，同时支持服务提供者和服务消费者操作
}
```

### 6. ConfigCenter接口（整合ConfigPublisher和ConfigSubscriber）
```java
public interface ConfigCenter extends ConfigPublisher, ConfigSubscriber {
    // 提供统一的配置中心接口，同时支持配置发布者和订阅者操作
    
    // 检查配置是否存在
    boolean hasConfig(String dataId, String group);
    
    // 获取配置元数据
    ConfigMetadata getConfigMetadata(String dataId, String group);
}
```

## 实现方案

### 1. 核心组件结构
```
io.github.cuihairu.redis.streaming.registry/
├── RedisRegistryConfig.java          # Redis注册中心配置
├── ServiceProvider.java              # 服务提供者接口
├── ServiceConsumer.java              # 服务消费者接口
├── NamingService.java                # 命名服务接口（整合Provider和Consumer）
├── ConfigPublisher.java              # 配置发布者接口
├── ConfigSubscriber.java             # 配置订阅者接口
├── ConfigCenter.java                 # 配置中心接口（整合Publisher和Subscriber）
├── ServiceInstance.java              # 服务实例接口
├── ServiceIdentity.java              # 服务标识接口
├── Protocol.java                     # 协议接口
├── StandardProtocol.java             # 标准协议实现
├── impl/
│   ├── RedisNamingService.java       # Redis命名服务实现
│   ├── RedisServiceProvider.java     # Redis服务提供者实现
│   ├── RedisServiceConsumer.java     # Redis服务消费者实现
│   ├── RedisConfigService.java       # Redis配置服务实现
│   └── RedisConfigCenter.java        # Redis配置中心实现
├── config/
│   ├── ConfigService.java            # 配置服务接口（向后兼容）
│   ├── ConfigManager.java            # 配置管理接口
│   ├── ConfigInfo.java               # 配置信息模型
│   ├── ConfigHistory.java            # 配置历史模型
│   └── ConfigChangeListener.java     # 配置变更监听器
└── listener/
    └── ServiceChangeListener.java    # 服务变更监听器
```

### 2. RedisRegistryConfig设计

RedisRegistryConfig类集中管理所有Redis键模式和前缀配置：

```java
public class RedisRegistryConfig {
    // Redis键模式定义
    public static final String SERVICE_INSTANCE_KEY = "services:%s:%s";
    public static final String SERVICE_INSTANCES_KEY = "service_instances:%s";
    public static final String HEARTBEAT_KEY = "heartbeat:%s";
    public static final String SERVICE_CHANGE_CHANNEL = "service_change:%s";
    
    // 键格式化方法
    public String formatKey(String keyPattern, Object... args);
    
    // 特定键获取方法
    public String getServiceInstanceKey(String serviceName, String instanceId);
    public String getServiceInstancesKey(String serviceName);
    public String getHeartbeatKey(String serviceName);
    public String getServiceChangeChannelKey(String serviceName);
}
```

### 3. 关键实现细节

**心跳机制实现：**
```java
@Scheduled(fixedDelay = 30000) // 30秒心跳间隔
public void sendHeartbeat() {
    RScoredSortedSet<String> heartbeatSet = redisson.getScoredSortedSet(config.getHeartbeatKey(serviceName));
    heartbeatSet.add(System.currentTimeMillis(), instanceId);
}

@Scheduled(fixedDelay = 60000) // 60秒检查一次过期实例  
public void removeExpiredInstances() {
    long expiredTime = System.currentTimeMillis() - 90000; // 90秒未心跳视为过期
    RScoredSortedSet<String> heartbeatSet = redisson.getScoredSortedSet(config.getHeartbeatKey(serviceName));
    Collection<String> expiredInstances = heartbeatSet.valueRange(0, expiredTime);
    
    if (!expiredInstances.isEmpty()) {
        // 批量清理过期实例
        cleanupExpiredInstances(serviceName, expiredInstances);
        // 通知服务变更
        notifyServiceChange(serviceName, "removed", expiredInstances);
    }
}
```

**配置变更推送：**
```java
public boolean publishConfig(String dataId, String group, String content) {
    String configKey = config.formatKey("config:%s:%s", group, dataId);
    RMap<String, String> configMap = redisson.getMap(configKey);
    
    // 保存历史版本
    saveConfigHistory(dataId, group, configMap.get("content"), configMap.get("version"));
    
    // 更新配置
    String newVersion = generateVersion();
    configMap.put("content", content);
    configMap.put("version", newVersion);
    configMap.put("updateTime", String.valueOf(System.currentTimeMillis()));
    
    // 推送变更通知
    RTopic topic = redisson.getTopic(config.formatKey("config_change:%s:%s", group, dataId));
    ConfigChangeEvent event = new ConfigChangeEvent(dataId, group, content, newVersion);
    topic.publish(event);
    
    return true;
}
```

这个设计充分利用了Redis的数据结构特性，相比Nacos提供了更简单的部署方案和更高的性能表现，特别适合中小规模的微服务集群。通过明确的角色划分，开发者可以更容易地理解和使用服务注册发现及配置管理功能。同时，通过支持自定义键前缀，可以有效避免Redis键冲突问题。键模式的集中管理使得代码更加清晰和易于维护。