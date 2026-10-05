# Registry Usage Guide

[中文](../Registry-Guide.md) | [English](Registry-Guide-en.md)

---

Usage of service registration, discovery, change subscription, metadata/metrics filtering, and client-side load balancing. Every class name, method and default in this page is aligned with the current implementation under `registry/src/main/java` and `spring-boot-starter/src/main/java`.

## 1) Spring Boot auto-configuration

Dependency (the starter assembles registry, discovery, config and the rest):

```gradle
dependencies {
    implementation 'io.github.cuihairu.redis-streaming:spring-boot-starter:0.2.0'
}
```

Auto-registration is performed by `AutoServiceRegistration` (registers on `ApplicationReadyEvent`, deregisters on `@PreDestroy`):

```yaml
redis-streaming:
  redis:
    address: redis://127.0.0.1:6379
  registry:
    enabled: true            # default true
    auto-register: true      # default true
    heartbeat-interval: 30   # heartbeat interval (seconds), default 30
    heartbeat-timeout: 90    # heartbeat timeout (seconds), default 90
    instance:
      service-name: ${spring.application.name}
      protocol: http         # http | https | tcp, default http
      weight: 1
      ephemeral: true        # true = temporary (cleaned after heartbeat timeout); false = persistent (only marked unhealthy)
      metadata:
        region: us-east-1
  discovery:
    enabled: true            # default true
    healthy-only: true       # default true
  load-balancer:
    strategy: scored         # scored | wrr | weighted-random | consistent-hash
  invoker:
    max-attempts: 3          # default 3
    initial-delay-ms: 20
    backoff-factor: 2.0
    max-delay-ms: 200
    jitter-ms: 20
```

Inject and use:

```java
@Service
public class OrderClient {

    @Autowired
    private ServiceDiscovery serviceDiscovery;   // RedisNamingService, created and started by the starter

    public List<ServiceInstance> pick() {
        return serviceDiscovery.discoverHealthy("payment-service");
    }
}
```

Notes: auto-registered instance metadata additionally carries `application.name`, `server.port`, `startup.time`; persistent instances (`ephemeral=false`) do not start the heartbeat scheduler.

## 2) Manual NamingService setup

```java
NamingServiceConfig config = new NamingServiceConfig("myapp");   // optional custom key prefix
NamingService naming = new RedisNamingService(redissonClient, config);
naming.start();

ServiceInstance instance = DefaultServiceInstance.builder()
        .serviceName("order-service")
        .instanceId("order-service-001")
        .host("192.168.1.100")
        .port(8080)
        .protocol(StandardProtocol.HTTP)
        .weight(100)
        .ephemeral(true)
        .metadata(Map.of("region", "us-east-1", "version", "1.0.0"))
        .build();

naming.register(instance);
naming.sendHeartbeat(instance);          // temporary instances must send heartbeats on your own schedule
List<ServiceInstance> all = naming.getAllInstances("order-service");
List<ServiceInstance> healthy = naming.getHealthyInstances("order-service");
naming.deregister(instance);
naming.stop();
```

Role interfaces and their relation to `NamingService` (one implementation, four views):

| View | Interface | Methods |
|------|-----------|---------|
| Service provider | `ServiceProvider` | `register` / `deregister` / `sendHeartbeat` / `batchSendHeartbeats` / `start` / `stop` / `isRunning` |
| Technical registration | `ServiceRegistry` | `register` / `deregister` / `heartbeat` / `batchHeartbeat` (the last two are aliases of sendHeartbeat) |
| Service consumer | `ServiceConsumer` | `getAllInstances` / `getHealthyInstances` / `getInstances(name, healthy)` / `getInstancesByMetadata` / `getHealthyInstancesByMetadata` / `subscribe` / `unsubscribe` |
| Technical discovery | `ServiceDiscovery` | `discover` / `discoverHealthy` / `discoverByMetadata` / `discoverHealthyByMetadata` / `subscribe` / `unsubscribe` |

`getInstancesByFilters`, `getHealthyInstancesByFilters`, `chooseHealthyInstance`, `chooseHealthyInstanceByFilters` and `getConfig` live on `RedisNamingService` only (not on the `NamingService` interface) — declare the variable with the implementation type or cast explicitly.

## 3) Subscribing to service changes

### 3.1 Programmatic subscription (registry module's native interface)

```java
import io.github.cuihairu.redis.streaming.registry.listener.ServiceChangeListener;
import io.github.cuihairu.redis.streaming.registry.ServiceChangeAction;

naming.subscribe("order-service", (serviceName, action, instance, allInstances) -> {
    System.out.println(action + " " + instance.getInstanceId()
            + ", healthy=" + allInstances.size());
});
```

The callback signature is `onServiceChange(String serviceName, ServiceChangeAction action, ServiceInstance instance, List<ServiceInstance> allInstances)`; `action` values come from `ServiceChangeAction`: `ADDED`, `REMOVED`, `UPDATED`, `CURRENT`, `HEALTH_RECOVERY`, `HEALTH_FAILURE`. Right after a successful subscription each currently healthy instance triggers a `CURRENT` callback; with consumer-side health probing enabled (`enableHealthCheck=true`), verdict flips also fire `HEALTH_RECOVERY` / `HEALTH_FAILURE`.

### 3.2 Annotation style (spring-boot-starter)

```java
import io.github.cuihairu.redis.streaming.starter.annotation.ServiceChangeListener;
import io.github.cuihairu.redis.streaming.registry.ServiceChangeAction;
import io.github.cuihairu.redis.streaming.registry.ServiceInstance;

@Component
public class PaymentChangeHandler {

    // full parameter list (action as enum)
    @ServiceChangeListener(services = {"payment-service"})
    public void onChange(String service, ServiceChangeAction action,
                         ServiceInstance inst, List<ServiceInstance> all) {
        // update the client cache
    }

    // action can also be declared as String (lower-cased enum name)
    @ServiceChangeListener(services = {"payment-service"}, actions = {"health_failure"})
    public void onDown(String service, String action, ServiceInstance inst,
                       List<ServiceInstance> all) {
    }
}
```

Annotation attributes: `services()` defaults to an empty array; `actions()` defaults to `{"added", "removed", "updated"}` — health/current events are only delivered when listed explicitly. The processor accepts four parameter combinations: `(serviceName, action, instance, allInstances)`, `(action, instance)`, `(instance)`, where `action` may be either `ServiceChangeAction` or `String`.

## 4) Metadata / metrics filtering

Filtering runs in server-side Lua; conditions are ANDed; value comparison tries numeric first and falls back to lexicographic string comparison. Instances without the field never match.

```java
// metadata filtering (equality + comparison operators)
Map<String, String> filters = Map.of(
        "version", "1.0.0",            // equality (== by default)
        "status:!=", "maintenance",
        "weight:>=", "80",
        "cpu_usage:<", "70");

List<ServiceInstance> matched = naming.getInstancesByMetadata("order-service", filters);
List<ServiceInstance> healthy  = naming.getHealthyInstancesByMetadata("order-service", filters);

// same capability from the Discovery view
List<ServiceInstance> d = serviceDiscovery.discoverByMetadata("order-service", filters);
```

`FilterBuilder` produces the same filter maps and supports both metadata and metrics conditions:

```java
import io.github.cuihairu.redis.streaming.registry.filter.FilterBuilder;

Map<String, String> md = FilterBuilder.create()
        .metaEq("region", "us-east-1")
        .metaGte("weight", 10)
        .buildMetadata();

Map<String, String> mt = FilterBuilder.create()
        .metricLt("cpu", 70)
        .metricLte("latency", 50)
        .buildMetrics();

// requires a RedisNamingService instance
List<ServiceInstance> candidates =
        ((RedisNamingService) naming).getHealthyInstancesByFilters("order-service", md, mt);
```

Operator overview (key-suffix form, matching the `GET_INSTANCES_BY_METADATA` Lua script):

| Key form | Meaning |
|----------|---------|
| `field` | equal (default) |
| `field:==` | equal |
| `field:!=` | not equal |
| `field:>` / `field:>=` | greater than / greater or equal |
| `field:<` / `field:<=` | less than / less or equal |

Note: version numbers compare lexicographically (`"1.2.0" > "1.10.0"` is false), so filter versions by equality.

## 5) Client-side load balancing

```java
import io.github.cuihairu.redis.streaming.registry.loadbalancer.*;

// smooth weighted round robin (weight prefers metadata.weight when it parses as an integer, else the instance weight)
LoadBalancer wrr = new WeightedRoundRobinLoadBalancer();
ServiceInstance a = wrr.choose("order-service", candidates, Map.of());

// consistent hash (context must carry "hashKey"; without it the first instance is returned)
LoadBalancer ch = new ConsistentHashLoadBalancer(128);            // 128 virtual nodes by default
ServiceInstance b = ch.choose("order-service", candidates, Map.of("hashKey", userId));

// scored selection: weight × region preference × CPU/latency/memory/inflight/queue/error rate
LoadBalancerConfig cfg = new LoadBalancerConfig();
cfg.setPreferredRegion("us-east-1");     // a metadata.region hit multiplies the score by regionBoost (default 1.1)
cfg.setTargetLatencyMs(50.0);
cfg.setMaxCpuPercent(80);                // hard threshold, exceeded candidates are dropped; -1 disables
MetricsProvider mp = new RedisMetricsProvider(redissonClient, consumerConfig);  // 500ms local cache
ScoredLoadBalancer scored = new ScoredLoadBalancer(cfg, mp);
```

One-shot selection (a convenience method on `RedisNamingService`):

```java
ServiceInstance chosen = ((RedisNamingService) naming)
        .chooseHealthyInstanceByFilters("order-service", md, mt, scored, Map.of());
```

Metrics keys: `ScoredLoadBalancer` reads `cpu`, `latency`, `memory`, `inflight`, `queue`, `errorRate` by default (change with `cfg.setCpuKey(...)` etc.). The built-in collectors produce keys such as `processCpuLoad` (0~1), `heap_usagePercent`, `threadCount`; client-side reporting writes `clientInflight`, `clientLatencyMs`, `clientErrorRate`. The two sides do not share key names by default — align them through `LoadBalancerConfig.setXxxKey(...)` or supply data under matching names from your own code.

## 6) ClientSelector / ClientInvoker (selection and invocation wrapper)

```java
import io.github.cuihairu.redis.streaming.registry.client.*;
import io.github.cuihairu.redis.streaming.registry.client.metrics.RedisClientMetricsReporter;

// selection: strict filter (metadata+metrics) -> drop metrics filter -> drop metadata filter -> all healthy
// order and switches are controlled by ClientSelectorConfig, all enabled by default
ClientSelector selector = new ClientSelector(naming, new ClientSelectorConfig());
ServiceInstance picked = selector.select("order-service", md, mt,
        new WeightedRoundRobinLoadBalancer(), Map.of());     // null when nothing matches

// invocation: selection + per-instance circuit breaker + exponential-backoff retry + client metrics reporting
RetryPolicy retry = new RetryPolicy(3, 20, 2.0, 200, 20);    // attempts, initialDelayMs, factor, maxDelayMs, jitterMs
RedisClientMetricsReporter reporter =
        new RedisClientMetricsReporter(redissonClient, consumerConfig);

ClientInvoker invoker = new ClientInvoker(naming, scored, retry, reporter);

String body = invoker.invoke("order-service", md, mt, Map.of(), ins -> {
    String url = ins.getScheme() + "://" + ins.getHost() + ":" + ins.getPort() + "/api/orders";
    // issue the HTTP call and return a result; exceptions count toward retry/circuit-breaker stats
    return "ok";
});

// invocation counters: total + per service, keys attempts/successes/failures/retries/cbOpenSkips
Map<String, Map<String, Long>> stats = invoker.getMetricsSnapshot();
```

Implementation details: `ClientInvoker` keeps one `CircuitBreaker` per `serviceName:instanceId` (window 20, failure-rate threshold 0.5, open for 5s, one half-open probe); a null `RetryPolicy` falls back to `new RetryPolicy(3, 10, 2.0, 200, 10)`. `invoke` declares `throws Exception`.

## 7) Admin API

```java
import io.github.cuihairu.redis.streaming.registry.admin.RegistryAdminService;

RegistryAdminService admin = new RegistryAdminService(redissonClient, new NamingServiceConfig());

Set<String> services = admin.getAllServices();
ServiceDetails details = admin.getServiceDetails("order-service");   // default active window 2 minutes
List<InstanceDetails> active = admin.getActiveInstances("order-service", Duration.ofMinutes(2));
Map<String, Object> health = admin.getRegistryHealth();              // totalServices/totalInstances/healthyInstances/healthyRate
Map<String, Integer> cleaned = admin.cleanupExpiredInstances(Duration.ofMinutes(2));  // manual cleanup
```

## 8) Consumer-side health probing

With `enableHealthCheck=true`, `RedisServiceConsumer` registers probe tasks when instances are discovered:

```java
ServiceConsumerConfig consumerConfig = new ServiceConsumerConfig();
consumerConfig.setEnableHealthCheck(true);
consumerConfig.setHealthCheckInterval(30);        // default 30
consumerConfig.setHealthCheckTimeUnit(TimeUnit.SECONDS);
consumerConfig.setHealthCheckTimeout(5000);       // milliseconds, <=0 falls back to 5000
```

The probe implementation is chosen by protocol: HTTP/HTTPS uses `HttpHealthChecker` (GET `{uri}/health`, 2xx-3xx counts as healthy, request errors fall back to TCP connectivity); TCP/UDP uses `TcpHealthChecker` (connection test); WS/WSS uses `WebSocketHealthChecker` (TCP connectivity); everything else (including gRPC) goes through `StandardHealthChecker`'s default TCP connectivity probe. Custom logic extends `CustomHealthChecker` and implements `doCheck` (TCP connectivity first, 3s timeout). Probes share one daemon pool and fire `HEALTH_RECOVERY` / `HEALTH_FAILURE` on state changes.

## References

- Design: [Registry-Design-en.md](Registry-Design-en.md)
- Module overview: the Chinese [Registry.md](../Registry.md)
