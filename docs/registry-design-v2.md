# Redis注册中心设计方案 v2.0

## 概述

本文档描述了基于Redis的服务注册中心的优化设计方案，采用三级存储结构和智能心跳策略，实现高性能、高可用的服务发现系统。

## 核心架构

### 1. 三级存储结构

#### 一级：服务索引
```
prefix:registry:services -> Set{user-service, order-service, payment-service}
```
- **数据结构**：Redis Set
- **用途**：存储所有注册的服务名称
- **操作**：`SADD`、`SMEMBERS`、`SREM`

#### 二级：实例心跳索引
```
prefix:registry:services:user-service:heartbeats -> ZSet{
    instance-1: 1634567890123,  // score = 心跳时间戳
    instance-2: 1634567890124,
    instance-3: 1634567890125
}
```
- **数据结构**：Redis Sorted Set
- **用途**：存储实例ID和心跳时间戳，支持按时间排序
- **优势**：
  - 高效的范围查询（`ZRANGEBYSCORE`）
  - 批量清理过期实例（`ZREMRANGEBYSCORE`）
  - 按心跳时间排序获取实例

#### 三级：实例详细信息
```
prefix:registry:services:user-service:instance-1 -> Hash{
    "host": "192.168.1.100",
    "port": "8080",
    "protocol": "HTTP",
    "heartbeat": "1634567890123",  // 冗余存储，确保单次读取完整性
    "healthy": "true",
    "metadata_memory_usagePercent": "65.5",
    "metadata_cpu_processCpuLoad": "45.2",
    "metadata_application_threadCount": "150"
}
```
- **数据结构**：Redis Hash
- **用途**：存储实例的完整信息
- **关键设计**：
  - 心跳时间冗余存储，支持单次读取获取完整信息
  - Metadata扁平化存储（`metadata_` 前缀）
  - 支持动态字段添加

### 2. 核心优势

#### 查询性能优化
- **批量查询**：通过ZSet一次获取所有活跃实例ID
- **单实例查询**：通过Hash一次获取完整实例信息
- **范围查询**：支持按心跳时间范围查询实例

#### 清理效率提升
- **统一清理**：Admin服务定期执行批量清理
- **原子操作**：Lua脚本保证清理操作的原子性
- **时间复杂度**：`O(log(N))` 的清理效率

#### 数据一致性保证
- **原子更新**：心跳更新同时操作ZSet和Hash
- **冗余策略**：关键数据（心跳时间）在多处存储
- **事务保证**：使用Lua脚本确保操作原子性

## 智能心跳策略

### 1. 分层更新机制

#### 更新类型
- **HEARTBEAT_ONLY**：仅更新心跳时间戳
- **METADATA_UPDATE**：更新metadata + 心跳时间戳

#### 决策逻辑
```java
public UpdateDecision shouldUpdate(String instanceId, Map<String, Object> currentMetadata) {
    long now = System.currentTimeMillis();
    InstanceState state = getInstanceState(instanceId);

    // 1. 检查metadata更新需求
    if (shouldUpdateMetadata(state, currentMetadata, now)) {
        // metadata更新会重置心跳时间
        return UpdateDecision.METADATA_UPDATE;
    }

    // 2. 检查纯心跳更新需求
    if (needsHeartbeat(state, now)) {
        return UpdateDecision.HEARTBEAT_ONLY;
    }

    return UpdateDecision.NO_UPDATE;
}
```

### 2. 配置化策略

#### 时间间隔配置
```yaml
registry:
  heartbeat:
    heartbeat-interval: 10s      # 纯心跳间隔
    metadata-interval: 60s       # metadata更新间隔（包含心跳）
```

#### 变化阈值配置
```yaml
registry:
  heartbeat:
    change-thresholds:
      memory.usagePercent: { threshold: 10.0, type: "absolute" }
      cpu.processCpuLoad: { threshold: 20.0, type: "absolute" }
      health: { threshold: 0, type: "any" }
```

### 3. 本地状态管理

#### 状态缓存
```java
private static class InstanceState {
    private long lastHeartbeatTime = 0;
    private long lastMetadataUpdateTime = 0;
    private Map<String, Object> lastMetadata = new HashMap<>();
    private volatile boolean pendingHeartbeat = false;
}
```

#### 时间重置策略
- metadata更新时重置心跳时间，避免重复更新
- 通过`pendingHeartbeat`标记避免并发更新

## 可配置指标收集系统

### 1. 指标收集器架构

#### 核心接口
```java
public interface MetricCollector {
    String getMetricType();
    Object collectMetric() throws Exception;
    boolean isAvailable();
    CollectionCost getCost();
}
```

#### 成本级别
- **LOW**：内存使用率、线程数等
- **MEDIUM**：CPU使用率、磁盘IO等
- **HIGH**：网络延迟测试、数据库连接等

### 2. 内置指标收集器

#### 系统指标
- **MemoryMetricCollector**：JVM内存使用情况
- **CpuMetricCollector**：CPU使用率和负载
- **DiskMetricCollector**：磁盘空间使用情况
- **NetworkMetricCollector**：网络连接状态

#### 应用指标
- **ApplicationMetricCollector**：线程数、运行时间等
- **DatabaseMetricCollector**：数据库连接状态（可选）

### 3. 指标收集配置

#### 启用配置
```yaml
registry:
  metrics:
    enabled-metrics:
      - memory
      - cpu
      - application
      - disk
```

#### 收集间隔
```yaml
registry:
  metrics:
    collection-intervals:
      memory: 30s
      cpu: 60s
      disk: 5m
      application: 2m
```

#### 变化检测
```yaml
registry:
  metrics:
    change-thresholds:
      memory.usagePercent: { threshold: 10.0, type: "absolute" }
      cpu.processCpuLoad: { threshold: 20.0, type: "absolute" }
    immediate-update-on-significant-change: true
```

## Lua脚本设计

### 1. 心跳更新脚本

#### 多模式支持
```lua
-- 支持 heartbeat_only 和 metadata_update 两种模式
local heartbeat_key = KEYS[1]    -- ZSet
local instance_key = KEYS[2]     -- Hash
local instance_id = ARGV[1]
local heartbeat_time = ARGV[2]
local update_mode = ARGV[3]      -- "heartbeat_only" | "metadata_update"
local metadata_json = ARGV[4]    -- 可选

-- 总是更新心跳时间戳
redis.call('ZADD', heartbeat_key, heartbeat_time, instance_id)
redis.call('HSET', instance_key, 'heartbeat', heartbeat_time)

-- 根据模式决定是否更新metadata
if update_mode == "metadata_update" and metadata_json and metadata_json ~= '' then
    local metadata = cjson.decode(metadata_json)
    for key, value in pairs(metadata) do
        redis.call('HSET', instance_key, 'metadata_' .. key, tostring(value))
    end
    redis.call('HSET', instance_key, 'lastMetadataUpdate', heartbeat_time)
end

return update_mode
```

### 2. 清理过期实例脚本

#### 批量清理
```lua
local heartbeat_key = KEYS[1]
local service_name = ARGV[1]
local current_time = ARGV[2]
local timeout_ms = ARGV[3]

local expired_threshold = current_time - timeout_ms
local expired_instances = redis.call('ZRANGEBYSCORE', heartbeat_key, 0, expired_threshold)

if #expired_instances > 0 then
    -- 从心跳索引中删除
    redis.call('ZREMRANGEBYSCORE', heartbeat_key, 0, expired_threshold)

    -- 删除实例详情
    for i = 1, #expired_instances do
        local instance_key = 'prefix:registry:services:' .. service_name .. ':instance:' .. expired_instances[i]
        redis.call('DEL', instance_key)
    end
end

return expired_instances
```

## 分布式清理策略

### 1. Admin服务统一清理

#### 定期清理任务
```java
@Scheduled(fixedDelay = 30000) // 30秒清理一次
public void cleanupExpiredInstances() {
    for (String serviceName : getAllServices()) {
        cleanupServiceInstances(serviceName);
    }
}
```

#### 全局超时配置
- 使用较长的全局超时时间（如2分钟）
- 避免消费者之间的清理冲突

### 2. 消费者读时过滤

#### 灵活超时支持
```java
public List<ServiceInstance> getActiveInstances(String serviceName, Duration consumerTimeout) {
    // 1. 获取所有实例（不清理）
    Set<ZSetOperations.TypedTuple<String>> allInstances =
        redisTemplate.opsForZSet().rangeWithScores(heartbeatKey, 0, -1);

    // 2. 客户端过滤（根据消费者自己的超时时间）
    long consumerTimeoutMs = consumerTimeout.toMillis();
    long currentTime = System.currentTimeMillis();

    return allInstances.stream()
        .filter(tuple -> currentTime - tuple.getScore().longValue() < consumerTimeoutMs)
        .map(tuple -> getInstanceDetails(serviceName, tuple.getValue()))
        .collect(toList());
}
```

## Admin服务支持

### 1. 管理接口设计

#### 服务级别查询
```java
GET /admin/registry/services
GET /admin/registry/services/{serviceName}
GET /admin/registry/services/{serviceName}/instances
```

#### 实例级别查询
```java
GET /admin/registry/services/{serviceName}/instances/{instanceId}
GET /admin/registry/services/{serviceName}/instances/{instanceId}/metrics
```

#### 健康状态监控
```java
GET /admin/registry/health
GET /admin/registry/services/{serviceName}/health
```

### 2. 监控指标展示

#### 实例指标
- 心跳时间和延迟
- 系统资源使用情况（CPU、内存、磁盘）
- 应用运行状态（线程数、运行时间）
- 自定义业务指标

#### 服务级别统计
- 实例总数和健康实例数
- 平均响应时间
- 资源使用分布
- 异常实例列表

## 性能优化

### 1. 网络开销优化
- 大部分时候只传输心跳时间戳，减少带宽占用
- 基于阈值的智能metadata更新
- 本地状态缓存避免重复网络调用

### 2. Redis操作优化
- 使用Lua脚本减少网络往返次数
- 批量操作提升性能
- 合理的数据结构选择（Set、ZSet、Hash）

### 3. 内存使用优化
- 冗余数据最小化（只有心跳时间冗余）
- 过期数据及时清理
- 配置化的指标收集间隔

## 扩展性设计

### 1. 自定义指标收集器
```java
@Component
public class CustomMetricCollector implements MetricCollector {
    @Override
    public String getMetricType() {
        return "custom";
    }

    @Override
    public Object collectMetric() throws Exception {
        // 自定义指标收集逻辑
        return customMetrics;
    }
}
```

### 2. 插件化配置
- 通过Spring Boot自动配置机制
- 支持条件化启用（`@ConditionalOnProperty`）
- 灵活的配置参数

### 3. 多协议支持
- 保持现有的Protocol枚举设计
- 支持自定义协议扩展
- 协议特定的健康检查器

## 配置示例

### 完整配置
```yaml
registry:
  # 心跳配置
  heartbeat:
    heartbeat-interval: 10s
    metadata-interval: 60s
    change-thresholds:
      memory.usagePercent: { threshold: 10.0, type: "absolute" }
      cpu.processCpuLoad: { threshold: 20.0, type: "absolute" }
      health: { threshold: 0, type: "any" }

  # 指标收集配置
  metrics:
    enabled-metrics: [memory, cpu, application, disk]
    collection-intervals:
      memory: 30s
      cpu: 60s
      disk: 5m
      application: 2m
    change-thresholds:
      memory.usagePercent: { threshold: 10.0, type: "absolute" }
      cpu.processCpuLoad: { threshold: 20.0, type: "absolute" }
    immediate-update-on-significant-change: true
    collection-timeout: 5s

  # 清理配置
  cleanup:
    interval: 30s
    global-timeout: 2m

  # Redis配置
  redis:
    key-prefix: "registry"
    connection-timeout: 3s
```

## 迁移指南

### 从v1.0迁移
1. **数据结构迁移**：将现有Hash结构迁移到新的三级结构
2. **配置更新**：更新配置文件以支持新的心跳策略
3. **客户端升级**：使用新的API接口和配置
4. **渐进式部署**：支持新旧版本共存的迁移策略

### 兼容性保证
- 保持核心API接口不变
- 配置参数向后兼容
- 渐进式功能启用

## 总结

本设计方案通过三级存储结构、智能心跳策略和可配置指标收集系统，实现了：

1. **高性能**：优化的数据结构和批量操作
2. **高可用**：分布式清理和容错机制
3. **高扩展性**：插件化的指标收集器和配置化策略
4. **易维护**：清晰的架构设计和完善的监控支持

该方案在保持简单易用的同时，提供了生产环境所需的性能、可靠性和可观测性。