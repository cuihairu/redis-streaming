# Service Registry

Module: `registry/` (package root `io.github.cuihairu.redis.streaming.registry`)

Redis-based service registration and discovery: register/deregister instances, heartbeats to maintain health state, service discovery and change subscription (Redis Pub/Sub), metadata/metrics filtering (with comparison operators), client-side load balancing, and multi-protocol health checks (the former `client` RPC invocation wrapper package was removed on 2026-10-12; the provider-side metrics collection `metrics` package was removed the same day, and the instance `metrics` field became data-driven — written on demand by external processes, while the consumer-side filtering and load-balancing read paths stay unchanged).

## Key Concepts

- Two business-role interfaces, both fully implemented by the single implementation `RedisNamingService`:
  - `ServiceProvider` (register/deregister/sendHeartbeat/batchSendHeartbeats)
  - `ServiceConsumer` (getAllInstances/getHealthyInstances/getInstancesByMetadata/subscribe)
- `NamingService extends ServiceProvider, ServiceConsumer`, plus two metadata-filter methods; the former technical-view interfaces `ServiceRegistry`/`ServiceDiscovery` were removed (2026-10-12), and their method names (`heartbeat`/`discover`, etc.) survive as `NamingService` default aliases
- Old vocabulary → new vocabulary: `heartbeat`→`sendHeartbeat`, `batchHeartbeat`→`batchSendHeartbeats`, `discover`→`getAllInstances`, `discoverHealthy`→`getHealthyInstances`, `discoverByMetadata`→`getInstancesByMetadata`, `discoverHealthyByMetadata`→`getHealthyInstancesByMetadata`
- Data structures: a service index Set + a heartbeat ZSet + an instance-detail Hash (keys are generated centrally by `RegistryKeys`)
- Change actions `ServiceChangeAction`: `ADDED` / `REMOVED` / `UPDATED` / `CURRENT` / `HEALTH_RECOVERY` / `HEALTH_FAILURE`

## Main Classes at a Glance

| Category | Classes | Notes |
|------|----|------|
| Data model | `ServiceIdentity` / `ServiceInstance` / `DefaultServiceInstance` | Instance identity and full details (host/port/protocol/weight/ephemeral/metadata) |
| Protocols | `Protocol` / `StandardProtocol` / `MessagingProtocol` | `StandardProtocol` covers HTTP/HTTPS/TCP/UDP/WS/WSS/KCP/GRPC/GRPCS/DUBBO/DUBBO2; `MessagingProtocol` only has the four Redis kinds (stream/pubsub and their TLS variants) |
| Implementations | `impl.RedisNamingService` / `impl.RedisServiceProvider` / `impl.RedisServiceConsumer` | Provider/Consumer roles; NamingService aggregates both |
| Key management | `keys.RegistryKeys` | Key templates, service name / instance ID sanitization and validation |
| Heartbeat | `heartbeat.HeartbeatConfig` / `HeartbeatStateManager` / `UpdateDecision` | Periodic heartbeat refresh + metadata change propagation (`HEARTBEAT_ONLY` / `METADATA_UPDATE`) |
| Health checks | `health.HealthChecker` plus `StandardHealthChecker`, `HttpHealthChecker`, `TcpHealthChecker`, `CustomHealthChecker`, `ClientHealthChecker`, `HealthCheckManager` | Consumer-side probing by protocol |
| Filtering | `NamingService.getInstancesByMetadata`, `RedisNamingService.getInstancesByFilters`, `filter.FilterBuilder` | Server-side Lua filtering, supporting `==` `!=` `>` `>=` `<` `<=` |
| Load balancing | `loadbalancer.LoadBalancer`, `WeightedRoundRobinLoadBalancer`, `WeightedRandomLoadBalancer`, `ConsistentHashLoadBalancer`, `ScoredLoadBalancer` | SPI + three built-in strategies |
| Administration | `admin.RegistryAdminService`, `ServiceDetails`, `InstanceDetails` | Service/instance details, aggregated metrics, manual cleanup of expired instances |
| Configuration | `BaseRedisConfig` / `NamingServiceConfig` / `ServiceProviderConfig` / `ServiceConsumerConfig` | See the table below |

## Configuration Keys (source-code defaults)

`NamingServiceConfig` (extends `registry.BaseRedisConfig`):

| Field | Type | Default | Notes |
|------|------|--------|------|
| keyPrefix | String | `redis_streaming_registry` | Redis key prefix |
| enableKeyPrefix | boolean | true | Whether the key prefix is enabled |
| enableHealthCheck | boolean | false | Whether the consumer actively probes health state |
| healthCheckInterval | long | 30 | Health check interval (combined with healthCheckTimeUnit) |
| healthCheckTimeUnit | TimeUnit | SECONDS | Interval unit |
| healthCheckTimeout | int | 5000 (ms) | Probe timeout; values ≤0 fall back to 5000 |
| enableAdminService | boolean | true | Whether admin capabilities are enabled |
| heartbeatTimeoutSeconds | int | 90 (from `ServiceProviderConfig`) | Heartbeat timeout (seconds); past it the instance expires |

`heartbeat.HeartbeatConfig`:

| Field | Type | Default |
|------|------|--------|
| heartbeatInterval | Duration | 3s |
| enableMetadataChangeDetection | boolean | false |
| metadataUpdateIntervalSeconds | int | 600 |

> The former provider-side metrics collection settings (`metricsInterval`, `changeThresholds`, `forceMetricsUpdateThreshold`, etc.) and the `metrics` package were removed on 2026-10-12; the instance Hash's `metrics` field is no longer written by the framework — external processes may read and write that field directly, while the consumer-side `getInstancesByFilters` and `ScoredLoadBalancer` read paths are unchanged.

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
naming.start();   // register throws IllegalStateException before start()

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

For full usage see [Registry-Guide](Registry-Guide); for key layout, heartbeat and cleanup mechanics see [Registry-Design](Registry-Design).

## References

- [Registry-Guide.md](Registry-Guide.md) — usage guide
- [Registry-Design.md](Registry-Design.md) — design and Redis key layout
