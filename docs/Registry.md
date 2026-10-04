# Service Registry

Module: `registry/`（包根 `io.github.cuihairu.redis.streaming.registry`）

基于 Redis 的服务注册与发现：注册/注销实例、心跳维持健康状态、服务发现与变更订阅（Redis Pub/Sub）、metadata/metrics 过滤（含比较运算符）、客户端负载均衡与调用封装、多协议健康检查。

## Key Concepts

- 四个视角的接口，同一个实现 `RedisNamingService` 全部实现：
  - 业务角色视角：`ServiceProvider`（注册/心跳/注销）、`ServiceConsumer`（发现/订阅/过滤）
  - 技术操作视角：`ServiceRegistry`（register/deregister/heartbeat/batchHeartbeat）、`ServiceDiscovery`（discover/discoverHealthy/discoverByMetadata/subscribe）
- `NamingService extends ServiceProvider, ServiceConsumer, ServiceRegistry, ServiceDiscovery`，另有两个 metadata 过滤方法
- 数据结构：服务索引 Set + 心跳 ZSet + 实例详情 Hash（键由 `RegistryKeys` 统一生成）
- 变更动作 `ServiceChangeAction`：`ADDED` / `REMOVED` / `UPDATED` / `CURRENT` / `HEALTH_RECOVERY` / `HEALTH_FAILURE`

## 主要类一览

| 类别 | 类 | 说明 |
|------|----|------|
| 数据模型 | `ServiceIdentity` / `ServiceInstance` / `DefaultServiceInstance` | 实例身份与完整信息（host/port/protocol/weight/ephemeral/metadata） |
| 协议 | `Protocol` / `StandardProtocol` / `MessagingProtocol` | `StandardProtocol` 含 HTTP/HTTPS/TCP/UDP/WS/WSS/KCP/GRPC/GRPCS/DUBBO/DUBBO2；`MessagingProtocol` 仅含 Redis 四种（stream/pubsub 及 TLS 变体） |
| 实现 | `impl.RedisNamingService` / `impl.RedisServiceProvider` / `impl.RedisServiceConsumer` | Provider/Consumer 角色，NamingService 聚合两者 |
| 键管理 | `keys.RegistryKeys` | 键模板、service name / instance ID 清洗与校验 |
| 心跳 | `heartbeat.HeartbeatConfig` / `HeartbeatStateManager` / `UpdateDecision` | 区分 metadata / metrics 的分级心跳更新决策 |
| 健康检查 | `health.HealthChecker` 及 `StandardHealthChecker`、`HttpHealthChecker`、`TcpHealthChecker`、`WebSocketHealthChecker`、`CustomHealthChecker`、`ClientHealthChecker`、`HealthCheckManager` | 消费端按协议探测 |
| 过滤 | `NamingService.getInstancesByMetadata`、`RedisNamingService.getInstancesByFilters`、`filter.FilterBuilder` | 服务端 Lua 过滤，支持 `==` `!=` `>` `>=` `<` `<=` |
| 负载均衡 | `loadbalancer.LoadBalancer`、`WeightedRoundRobinLoadBalancer`、`WeightedRandomLoadBalancer`、`ConsistentHashLoadBalancer`、`ScoredLoadBalancer` | SPI + 三种内置策略 |
| 客户端调用 | `client.ClientSelector`、`ClientInvoker`、`RetryPolicy`、`CircuitBreaker`、`client.metrics.RedisClientMetricsReporter` | 过滤 + 负载均衡 + 熔断重试 + 指标上报 |
| 管理 | `admin.RegistryAdminService`、`ServiceDetails`、`InstanceDetails` | 服务/实例明细、聚合指标、手动清理过期实例 |
| 配置 | `BaseRedisConfig` / `NamingServiceConfig` / `ServiceProviderConfig` / `ServiceConsumerConfig` | 见下表 |
| 指标采集 | `metrics.MetricsConfig` / `MetricsCollectionManager` / 各 `MetricCollector` | cpu/memory/application/gc/disk/network |

## 配置项（源码默认值）

`NamingServiceConfig`（继承 `registry.BaseRedisConfig`）：

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| keyPrefix | String | `redis_streaming_registry` | Redis 键前缀 |
| enableKeyPrefix | boolean | true | 是否启用键前缀 |
| enableHealthCheck | boolean | false | 消费端是否主动探测健康状态 |
| healthCheckInterval | long | 30 | 健康检查间隔（配合 healthCheckTimeUnit） |
| healthCheckTimeUnit | TimeUnit | SECONDS | 间隔单位 |
| healthCheckTimeout | int | 5000（毫秒） | 探测超时；设置 ≤0 时回退 5000 |
| enableAdminService | boolean | true | 是否启用 admin 管理能力 |
| heartbeatTimeoutSeconds | int | 90（来自 `ServiceProviderConfig`） | 心跳超时（秒），超过即过期 |

`heartbeat.HeartbeatConfig`：

| 字段 | 类型 | 默认值 |
|------|------|--------|
| heartbeatInterval | Duration | 3s |
| metricsInterval | Duration | 60s |
| enableMetadataChangeDetection | boolean | false |
| metadataUpdateIntervalSeconds | int | 600 |
| forceUpdateOnHealthChange | boolean | true |
| forceUpdateOnStartup | boolean | true |
| forceMetricsUpdateThreshold | int | 20 |

`metrics.MetricsConfig`：

| 字段 | 类型 | 默认值 |
|------|------|--------|
| enabledMetrics | Set&lt;String&gt; | memory, cpu, application |
| collectionIntervals | Map&lt;String,Duration&gt; | memory 30s / cpu 60s / disk 5min / network 10min |
| defaultCollectionInterval | Duration | 1min |
| immediateUpdateOnSignificantChange | boolean | true |
| collectionTimeout | Duration | 5s |

## Minimal Sample

```java
import io.github.cuihairu.redis.streaming.registry.DefaultServiceInstance;
import io.github.cuihairu.redis.streaming.registry.NamingService;
import io.github.cuihairu.redis.streaming.registry.StandardProtocol;
import io.github.cuihairu.redis.streaming.registry.impl.RedisNamingService;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

Config cfg = new Config();
cfg.useSingleServer().setAddress("redis://127.0.0.1:6379");
RedissonClient redisson = Redisson.create(cfg);

NamingService naming = new RedisNamingService(redisson);
naming.start();   // 未 start 时 register 会抛 IllegalStateException

var instance = DefaultServiceInstance.builder()
        .serviceName("order-service")
        .instanceId("order-1")
        .host("127.0.0.1")
        .port(8080)
        .protocol(StandardProtocol.HTTP)
        .build();

naming.register(instance);
var healthy = naming.getHealthyInstances("order-service");

naming.deregister(instance);
naming.stop();
```

完整用法见 [Registry-Guide](Registry-Guide)；键结构、心跳与清理机制见 [Registry-Design](Registry-Design)。

## References

- [Registry-Guide.md](Registry-Guide.md) — 使用指南
- [Registry-Design.md](Registry-Design.md) — 设计与 Redis 键结构
