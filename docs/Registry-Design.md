# Registry 模块设计

[中文](Registry-Design) | [English](en/Registry-Design-en)

---

## 概述

基于 Redis 的服务注册与发现模块。键结构与操作对齐 `registry/src/main/java` 的当前实现；配置中心能力已拆分到 `config` 模块（见 [config.md](config.md)）。

## 核心角色

### 1. Service Provider（服务提供者）

实现类 `RedisServiceProvider`（实现 `ServiceProvider` 与 `ServiceRegistry` 两个接口），负责：

- 服务注册：写实例 Hash、加入心跳 ZSet 与服务索引 Set，全部由 Lua 脚本原子完成
- 心跳处理：按 `HeartbeatStateManager` 的决策决定本次写什么（时间戳 / metrics / metadata）
- 过期清理：后台定时清理心跳过期的临时实例
- 优雅下线：`deregister` 原子删除并广播 `REMOVED`

### 2. Service Consumer（服务消费者）

实现类 `RedisServiceConsumer`（实现 `ServiceConsumer` 与 `ServiceDiscovery`），负责：

- 服务发现：按心跳窗口取活跃实例 ID，再逐个读实例 Hash 组装 `ServiceInstance`
- 变更订阅：`RTopic` 订阅 `{prefix}:services:{serviceName}:changes` 通道
- metadata/metrics 过滤：下发 Lua 脚本在服务端过滤
- 可选健康探测：`enableHealthCheck=true` 时按协议探测并回调 `HEALTH_RECOVERY` / `HEALTH_FAILURE`

`RedisNamingService` 聚合 Provider 与 Consumer，并同时实现 `NamingService`、`ServiceRegistry`、`ServiceDiscovery`。

### 3. Admin（管理）

`admin.RegistryAdminService`：查询服务列表、实例明细（含 metrics）、聚合指标、注册中心整体健康度，并可手动触发过期实例清理。

## 接口设计

四个接口是同一组操作的两个视角，`NamingService` 同时继承：

```java
// 业务角色视角
public interface ServiceProvider {
    void register(ServiceInstance instance);
    void deregister(ServiceInstance instance);
    void sendHeartbeat(ServiceInstance instance);
    void batchSendHeartbeats(List<ServiceInstance> instances);
    void start();
    void stop();
    boolean isRunning();
}

public interface ServiceConsumer {
    List<ServiceInstance> getAllInstances(String serviceName);
    List<ServiceInstance> getHealthyInstances(String serviceName);
    List<ServiceInstance> getInstances(String serviceName, boolean healthy);
    List<ServiceInstance> getInstancesByMetadata(String serviceName, Map<String, String> metadataFilters);
    List<ServiceInstance> getHealthyInstancesByMetadata(String serviceName, Map<String, String> metadataFilters);
    void subscribe(String serviceName, ServiceChangeListener listener);
    void unsubscribe(String serviceName, ServiceChangeListener listener);
    void start();
    void stop();
    boolean isRunning();
}

// 技术操作视角
public interface ServiceRegistry {
    void register(ServiceInstance instance);
    void deregister(ServiceInstance instance);
    void heartbeat(ServiceInstance instance);          // sendHeartbeat 别名
    void batchHeartbeat(List<ServiceInstance> instances); // batchSendHeartbeats 别名
    void start();
    void stop();
    boolean isRunning();
}

public interface ServiceDiscovery {
    List<ServiceInstance> discover(String serviceName);
    List<ServiceInstance> discoverHealthy(String serviceName);
    List<ServiceInstance> discoverByMetadata(String serviceName, Map<String, String> metadataFilters);
    List<ServiceInstance> discoverHealthyByMetadata(String serviceName, Map<String, String> metadataFilters);
    void subscribe(String serviceName, ServiceChangeListener listener);
    void unsubscribe(String serviceName, ServiceChangeListener listener);
    void start();
    void stop();
    boolean isRunning();
}

public interface NamingService extends ServiceProvider, ServiceConsumer, ServiceRegistry, ServiceDiscovery {
    default List<ServiceInstance> getHealthyInstances(String serviceName);   // getInstances(name, true) 别名
    List<ServiceInstance> getInstancesByMetadata(String serviceName, Map<String, String> metadataFilters);
    List<ServiceInstance> getHealthyInstancesByMetadata(String serviceName, Map<String, String> metadataFilters);
}
```

`RedisNamingService` 在接口之外追加：`getInstancesByFilters(name, metadataFilters, metricsFilters)`、`getHealthyInstancesByFilters(...)`、`chooseHealthyInstance(name, lb, context)`、`chooseHealthyInstanceByFilters(name, md, mt, lb, context)`、`getConfig()`。

## Redis 键结构（三级存储）

键模板由 `keys.RegistryKeys` 统一生成，前缀默认 `redis_streaming_registry`（`registry.BaseRedisConfig.DEFAULT_KEY_PREFIX`）：

```
{prefix}:services                                        # Set    服务索引（所有已注册服务名）
{prefix}:services:{serviceName}:heartbeats               # ZSet   心跳索引（score=最后心跳毫秒，member=instanceId）
{prefix}:services:{serviceName}:instance:{instanceId}    # Hash   实例详情
{prefix}:services:{serviceName}:changes                  # Pub/Sub 服务变更通知通道
```

实例 Hash 字段（`InstanceEntryCodec.buildInstanceData`）：`host`、`port`、`protocol`、`enabled`、`healthy`、`weight`、`ephemeral`、`registrationTime`、`lastHeartbeatTime`、`lastMetadataUpdate`、`metadata`（JSON 字符串）、`metrics`（JSON 字符串，客户端与服务端指标合并写入）、`lastMetricsUpdate`（心跳带 metrics 时写入）。

`RegistryKeys` 同时提供名称清洗与校验：service name / instance ID 中的 `:`、空格、制表符、换行会被替换为 `_` 或 `-`（`sanitizeServiceName` / `sanitizeInstanceId`）；注册与注销前统一走 `validateAndSanitizeXxx`，含 `:` 的 instance ID 会抛 `IllegalArgumentException`。

## 心跳机制

心跳不是框架后台定时发送的，而是由调用方（例如 spring-boot-starter 的 `AutoServiceRegistration`，默认间隔 30 秒）调用 `sendHeartbeat` / `batchSendHeartbeats` 触发。

每次心跳先由 `HeartbeatStateManager` 结合 `HeartbeatConfig` 决策，产出 `UpdateDecision`：

| 决策 | Lua update_mode | 写入内容 |
|------|-----------------|----------|
| `HEARTBEAT_ONLY` | `heartbeat_only` | 心跳时间戳（ZSet score + Hash `lastHeartbeatTime`） |
| `METRICS_UPDATE` | `metrics_update` | 时间戳 + metrics JSON（与已有 metrics 合并，不清空对方键） |
| `METADATA_UPDATE` | `metadata_update` | 时间戳 + metadata JSON |
| `FULL_UPDATE` | `full_update` | 时间戳 + metadata + metrics |
| `NO_UPDATE` | — | 完全跳过 |

决策依据（`HeartbeatConfig` 默认值）：距上次 metrics 更新不足 `metricsInterval`（60s）时只发时间戳；metrics 命中 `changeThresholds` 阈值（如 heap 内存变化 10%、进程 CPU 变化 0.20、磁盘用量变化 5%、线程数变化 100、健康状态任意变化）立即更新；连续 `heartbeat_only` 达 `forceMetricsUpdateThreshold`（20 次）强制刷一次 metrics；metadata 变更检测默认关闭（`enableMetadataChangeDetection=false`）。

临时实例每次心跳都会滑动续期实例 Hash 的 TTL（TTL = `heartbeatTimeoutSeconds`，默认 90 秒）；持久实例不设 TTL。

非 `heartbeat_only` 的更新会广播一次 `UPDATED` 事件。

## 过期清理

`RedisServiceProvider.start()` 启动守护线程池，首次延迟 60 秒、之后每 30 秒执行一轮 `cleanupExpiredInstances`：

- 对服务索引里的每个服务，执行 `CLEANUP_EXPIRED_INSTANCES_WITH_SNAPSHOTS` Lua：`heartbeat_time < now - timeout` 的实例中，`ephemeral=true`（或字段缺失）的被移出 ZSet 并删除实例 Hash；`ephemeral=false` 的持久实例只把 `healthy` 置为 `false`，不删除
- 清理后若某服务的心跳 ZSet 已空，则原子地从服务索引 Set 中移除该服务（Lua 内 `ZCARD` 判断 + `SREM`）
- 临时实例被清理时按快照广播 `REMOVED`；持久实例被标记不健康后，客户端活跃心跳会把 `healthy` 恢复为 `true`

## metadata / metrics 过滤

过滤在 `RegistryLuaScriptExecutor.executeGetInstancesByFilters` 的 Lua 中完成：

- 候选集 = 心跳窗口内的活跃实例（`ZRANGEBYSCORE heartbeats (now-timeout, +inf`）
- 过滤键解析为 `field` + 操作符（`==` `!=` `>` `>=` `<` `<=`，无后缀默认 `==`），`metadata` 与 `metrics` 两份 JSON 各自独立匹配，全部条件 AND
- 比较先 `tonumber` 数值比较，任一侧非数字则回退字典序字符串比较；字段缺失视为不匹配
- 空过滤条件等价于「返回全部活跃实例」

## 变更通知

变更通过 Redis Pub/Sub 通道 `{prefix}:services:{serviceName}:changes` 广播，载荷为 `event.ServiceChangeEvent`（`serviceName`、`action`、`instanceId`、`timestamp`、`instance` 快照）。动作由 `ServiceChangeAction` 定义：

- `ADDED` / `REMOVED` / `UPDATED`：注册、注销、属性更新（含非纯时间戳心跳）
- `CURRENT`：订阅时的当前状态通知
- `HEALTH_RECOVERY` / `HEALTH_FAILURE`：消费端健康探测结论翻转时（需 `enableHealthCheck=true`）

## 协议与健康检查

| 协议 | 探测器 | 方式 |
|------|--------|------|
| HTTP / HTTPS | `HttpHealthChecker` | GET `{uri}/health`，状态码 2xx~3xx 为健康；请求异常回退 TCP 连通性 |
| TCP / UDP | `TcpHealthChecker` | Socket 连接测试 |
| WS / WSS | `WebSocketHealthChecker` | TCP 连通性测试 |
| 其他（含 GRPC/GRPCS、DUBBO 等） | `StandardHealthChecker` 默认分支 | TCP 连通性测试 |
| 自定义 | 继承 `CustomHealthChecker` | 先 TCP 连通性（3 秒超时），再执行子类 `doCheck` |

默认超时 5000ms（各探测器的无参构造）；≤0 的超时会被归一化为 5000ms。`HealthCheckManager` 用一个共享守护线程池调度所有实例的探测任务，探测结果只在状态翻转时上报。

`StandardProtocol` 枚举值：HTTP、HTTPS、TCP、UDP、WS、WSS、KCP、GRPC、GRPCS、DUBBO、DUBBO2。`MessagingProtocol` 仅含 Redis 相关四种：`REDIS_STREAM`、`REDIS_STREAM_TLS`、`REDIS_PUBSUB`、`REDIS_PUBSUB_TLS`。

## 配置

| 配置类 | 关键字段（默认值） |
|--------|--------------------|
| `BaseRedisConfig`（registry） | keyPrefix=`redis_streaming_registry`、enableKeyPrefix=true |
| `NamingServiceConfig` | enableHealthCheck=false、healthCheckInterval=30（SECONDS）、healthCheckTimeout=5000ms、enableAdminService=true |
| `ServiceProviderConfig` | heartbeatTimeoutSeconds=90 |
| `ServiceConsumerConfig` | enableHealthCheck=false、healthCheckInterval=30、healthCheckTimeout=5000、heartbeatTimeoutSeconds=90、enableAdminService=true |
| `HeartbeatConfig` | heartbeatInterval=3s、metricsInterval=60s、enableMetadataChangeDetection=false、metadataUpdateIntervalSeconds=600、forceMetricsUpdateThreshold=20、forceUpdateOnHealthChange=true、forceUpdateOnStartup=true |
| `MetricsConfig` | enabledMetrics=[memory,cpu,application]、defaultCollectionInterval=1min、collectionTimeout=5s、immediateUpdateOnSignificantChange=true |
| `LoadBalancerConfig` | cpuWeight=1.0、latencyWeight=1.0、targetLatencyMs=50.0、regionBoost=1.1、zoneBoost=1.05、其余权重默认 0、硬阈值默认 -1（禁用） |
| `ClientSelectorConfig` | enableFallback 及三个分步开关均默认 true |
| `RetryPolicy` | 无默认构造；`ClientInvoker` 内部默认 (3, 10ms, 2.0, 200ms, 10ms) |

Lua 脚本缓存：`RegistryLuaScriptExecutor` 通过 Redisson `RScript` 执行脚本（注册、注销、心跳更新、过期清理、活跃实例查询、过滤查询）。

---

相关文档：

- [Registry.md](Registry.md) — 模块概览
- [Registry-Guide.md](Registry-Guide.md) — 使用指南
- [config.md](config.md) — 配置中心模块
