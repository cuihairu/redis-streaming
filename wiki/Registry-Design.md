# Registry 模块设计

[中文](Registry-Design) | [English](Registry-Design-en)

---

## 概述

基于 Redis 的服务注册发现和配置管理系统,参考 Nacos 架构但针对 Redis 特性进行优化,提供轻量级、高性能的微服务治理解决方案。

## 核心角色

### 1. Service Provider (服务提供者)

**核心职责**:
- 服务注册: 启动时向 Redis 注册服务实例信息
- 心跳维护: 定期发送心跳保持实例活跃状态
- 优雅下线: 关闭时主动注销服务实例

**Redis 实现策略**:
```redis
# 服务实例信息存储 - Hash结构
HSET services:{serviceName}:{instanceId} "host" "192.168.1.100"
HSET services:{serviceName}:{instanceId} "port" "8080"
HSET services:{serviceName}:{instanceId} "healthy" "true"

# 服务实例列表 - Set结构
SADD service_instances:{serviceName} "{instanceId}"

# 心跳时间戳 - Sorted Set结构
ZADD heartbeat:{serviceName} {timestamp} "{instanceId}"
```

### 2. Service Consumer (服务消费者)

**核心职责**:
- 服务发现: 查询可用的服务实例列表
- 服务订阅: 监听服务变更通知
- 负载均衡: 从实例列表中选择目标实例

**Redis 实现策略**:
```redis
# 服务发现 - 从Set获取实例列表
SMEMBERS service_instances:{serviceName}
HGETALL services:{serviceName}:{instanceId}

# 服务订阅 - Pub/Sub通道
SUBSCRIBE service_change:{serviceName}
```

### 3. Registry Center (注册中心)

**核心职责**:
- 实例管理: 维护服务实例的完整生命周期
- 健康检查: 主动检测服务实例健康状态
- 变更通知: 推送服务变更给订阅者
- 数据清理: 自动清理过期和不健康的实例

## 核心接口设计

### ServiceProvider 接口

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

### ServiceConsumer 接口

```java
public interface ServiceConsumer {
    // 服务发现
    List<ServiceInstance> getAllInstances(String serviceName);

    // 健康实例发现
    List<ServiceInstance> getHealthyInstances(String serviceName);

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

### NamingService 接口

整合 ServiceProvider 和 ServiceConsumer,提供统一的命名服务接口:

```java
public interface NamingService extends ServiceProvider, ServiceConsumer {
    // 提供统一的命名服务接口
}
```

## 关键实现细节

### 心跳机制

```java
@Scheduled(fixedDelay = 30000) // 30秒心跳间隔
public void sendHeartbeat() {
    RScoredSortedSet<String> heartbeatSet = redisson.getScoredSortedSet(heartbeatKey);
    heartbeatSet.add(System.currentTimeMillis(), instanceId);
}

@Scheduled(fixedDelay = 60000) // 60秒检查一次过期实例
public void removeExpiredInstances() {
    long expiredTime = System.currentTimeMillis() - 90000; // 90秒未心跳视为过期
    Collection<String> expiredInstances = heartbeatSet.valueRange(0, expiredTime);

    if (!expiredInstances.isEmpty()) {
        // 批量清理过期实例
        cleanupExpiredInstances(serviceName, expiredInstances);
        // 通知服务变更
        notifyServiceChange(serviceName, "removed", expiredInstances);
    }
}
```

### Metadata 和 Metrics 存储

**存储格式**: JSON 字符串

```redis
# Metadata (静态业务标签)
HSET instance_key "metadata" "{\"version\":\"1.0.0\",\"region\":\"us-east\"}"

# Metrics (动态监控数据)
HSET instance_key "metrics" "{\"cpu\":45.5,\"memory\":2048,\"qps\":1000}"
```

### Lua 脚本优化

**心跳更新脚本** (支持 metadata 和 metrics 分离更新):
```lua
-- 多模式心跳更新
local update_mode = ARGV[3]  -- "heartbeat_only" | "metrics_update" | "metadata_update" | "full_update"

-- 总是更新心跳时间戳
redis.call('ZADD', heartbeat_key, heartbeat_time, instance_id)

-- 根据模式更新不同字段
if update_mode == 'metrics_update' then
    redis.call('HSET', instance_key, 'metrics', metrics_json)
end
```

**过滤查询脚本** (支持 metadata 和 metrics 过滤):
```lua
-- 同时支持 metadata 和 metrics 过滤
local metadata_match = check_filters(metadata_json, metadata_filters)
local metrics_match = check_filters(metrics_json, metrics_filters)

if metadata_match and metrics_match then
    table.insert(matched_instances, instance_id)
end
```

## Redis 键前缀配置

### 默认键模式
```
services:{serviceName}:{instanceId}
service_instances:{serviceName}
heartbeat:{serviceName}
service_change:{serviceName}
```

### 带前缀的键模式
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
```

## 协议支持

支持多种协议的健康检查:

| 协议 | 健康检查方式 |
|------|-------------|
| HTTP/HTTPS | HTTP GET 请求 |
| TCP | TCP 连接测试 |
| gRPC | gRPC 健康检查协议 |
| WebSocket | WebSocket 连接测试 |

---

**版本**: 0.1.0
**最后更新**: 2025-10-13

🔗 相关文档:
- [[整体架构|Architecture]]
- [[详细设计|Design]]
- [[Spring Boot 集成|Spring-Boot-Starter]]
