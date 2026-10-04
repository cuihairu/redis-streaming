# 注册中心使用指南

服务注册、发现、变更订阅、metadata/metrics 过滤与客户端负载均衡的用法。文中所有类名、方法与默认值均对齐 `registry/src/main/java` 与 `spring-boot-starter/src/main/java` 的当前实现。

## 1) Spring Boot 自动接入

依赖（starter 自动装配 registry、discovery、config 等功能）：

```gradle
dependencies {
    implementation 'io.github.cuihairu.redis-streaming:spring-boot-starter:0.2.0'
}
```

自动注册由 `AutoServiceRegistration` 完成（`ApplicationReadyEvent` 时注册，`@PreDestroy` 时注销）：

```yaml
redis-streaming:
  redis:
    address: redis://127.0.0.1:6379
  registry:
    enabled: true            # 默认 true
    auto-register: true      # 默认 true
    heartbeat-interval: 30   # 心跳间隔（秒），默认 30
    heartbeat-timeout: 90    # 心跳超时（秒），默认 90
    instance:
      service-name: ${spring.application.name}
      protocol: http         # http | https | tcp，默认 http
      weight: 1
      ephemeral: true        # true=临时实例（心跳超时被清理）；false=持久实例（仅标记 unhealthy）
      metadata:
        region: us-east-1
  discovery:
    enabled: true            # 默认 true
    healthy-only: true       # 默认 true
  load-balancer:
    strategy: scored         # scored | wrr | weighted-random | consistent-hash
  invoker:
    max-attempts: 3          # 默认 3
    initial-delay-ms: 20
    backoff-factor: 2.0
    max-delay-ms: 200
    jitter-ms: 20
```

注入使用：

```java
@Service
public class OrderClient {

    @Autowired
    private ServiceDiscovery serviceDiscovery;   // starter 自动创建并 start 的 RedisNamingService

    public List<ServiceInstance> pick() {
        return serviceDiscovery.discoverHealthy("payment-service");
    }
}
```

说明：自动注册的实例 metadata 会附加 `application.name`、`server.port`、`startup.time`；持久实例（`ephemeral=false`）不启动心跳调度器。

## 2) 手动接入 NamingService

```java
NamingServiceConfig config = new NamingServiceConfig("myapp");   // 自定义键前缀，可选
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
naming.sendHeartbeat(instance);          // 临时实例需自行按间隔发心跳
List<ServiceInstance> all = naming.getAllInstances("order-service");
List<ServiceInstance> healthy = naming.getHealthyInstances("order-service");
naming.deregister(instance);
naming.stop();
```

角色接口与 `NamingService` 的对应关系（同一实现）：

| 视角 | 接口 | 方法 |
|------|------|------|
| 服务提供者 | `ServiceProvider` | `register` / `deregister` / `sendHeartbeat` / `batchSendHeartbeats` / `start` / `stop` / `isRunning` |
| 技术注册 | `ServiceRegistry` | `register` / `deregister` / `heartbeat` / `batchHeartbeat`（后两个是 sendHeartbeat 的别名） |
| 服务消费者 | `ServiceConsumer` | `getAllInstances` / `getHealthyInstances` / `getInstances(name, healthy)` / `getInstancesByMetadata` / `getHealthyInstancesByMetadata` / `subscribe` / `unsubscribe` |
| 技术发现 | `ServiceDiscovery` | `discover` / `discoverHealthy` / `discoverByMetadata` / `discoverHealthyByMetadata` / `subscribe` / `unsubscribe` |

`getInstancesByFilters`、`getHealthyInstancesByFilters`、`chooseHealthyInstance`、`chooseHealthyInstanceByFilters`、`getConfig` 只在 `RedisNamingService` 上（不在 `NamingService` 接口里），按实现类型声明变量或显式转型。

## 3) 订阅服务变更

### 3.1 编程式订阅（registry 模块原生接口）

```java
import io.github.cuihairu.redis.streaming.registry.listener.ServiceChangeListener;
import io.github.cuihairu.redis.streaming.registry.ServiceChangeAction;

naming.subscribe("order-service", (serviceName, action, instance, allInstances) -> {
    System.out.println(action + " " + instance.getInstanceId()
            + ", healthy=" + allInstances.size());
});
```

回调签名为 `onServiceChange(String serviceName, ServiceChangeAction action, ServiceInstance instance, List<ServiceInstance> allInstances)`，`action` 取值见 `ServiceChangeAction`：`ADDED`、`REMOVED`、`UPDATED`、`CURRENT`、`HEALTH_RECOVERY`、`HEALTH_FAILURE`。订阅成功后会立即用当前健康实例列表逐个触发 `CURRENT` 回调；开启消费端健康探测（`enableHealthCheck=true`）时，健康状态翻转也会回调 `HEALTH_RECOVERY` / `HEALTH_FAILURE`。

### 3.2 注解方式（spring-boot-starter）

```java
import io.github.cuihairu.redis.streaming.starter.annotation.ServiceChangeListener;
import io.github.cuihairu.redis.streaming.registry.ServiceChangeAction;
import io.github.cuihairu.redis.streaming.registry.ServiceInstance;

@Component
public class PaymentChangeHandler {

    // 完整参数（action 为枚举）
    @ServiceChangeListener(services = {"payment-service"})
    public void onChange(String service, ServiceChangeAction action,
                         ServiceInstance inst, List<ServiceInstance> all) {
        // 更新客户端缓存
    }

    // action 也可声明为 String（值为小写枚举名）
    @ServiceChangeListener(services = {"payment-service"}, actions = {"health_failure"})
    public void onDown(String service, String action, ServiceInstance inst,
                       List<ServiceInstance> all) {
    }
}
```

注解属性：`services()` 默认空数组；`actions()` 默认 `{"added", "removed", "updated"}`（health/current 事件需显式列入才会回调）。处理器支持四种方法参数组合：`(serviceName, action, instance, allInstances)`、`(action, instance)`、`(instance)`，其中 `action` 可为 `ServiceChangeAction` 或 `String`。

## 4) metadata / metrics 过滤

过滤在服务端 Lua 完成，条件之间是 AND；值比较先尝试数值，失败回退字典序字符串比较。字段不存在的实例不会被匹配。

```java
// metadata 过滤（等值 + 比较运算符）
Map<String, String> filters = Map.of(
        "version", "1.0.0",            // 等值（默认 ==）
        "status:!=", "maintenance",
        "weight:>=", "80",
        "cpu_usage:<", "70");

List<ServiceInstance> matched = naming.getInstancesByMetadata("order-service", filters);
List<ServiceInstance> healthy  = naming.getHealthyInstancesByMetadata("order-service", filters);

// Discovery 视角的等价方法
List<ServiceInstance> d = serviceDiscovery.discoverByMetadata("order-service", filters);
```

`FilterBuilder` 生成同样的过滤串，并支持 metadata 与 metrics 两套条件：

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

// 需要 RedisNamingService 实例
List<ServiceInstance> candidates =
        ((RedisNamingService) naming).getHealthyInstancesByFilters("order-service", md, mt);
```

运算符一览（键后缀形式，与 Lua 脚本 `GET_INSTANCES_BY_METADATA` 一致）：

| 键写法 | 含义 |
|--------|------|
| `field` | 等于（默认） |
| `field:==` | 等于 |
| `field:!=` | 不等于 |
| `field:>` / `field:>=` | 大于 / 大于等于 |
| `field:<` / `field:<=` | 小于 / 小于等于 |

注意：版本号比较走字典序（`"1.2.0" > "1.10.0"` 为 false），版本过滤请用等值。

## 5) 客户端负载均衡

```java
import io.github.cuihairu.redis.streaming.registry.loadbalancer.*;

// 平滑加权轮询（权重优先取 metadata.weight，能解析为整数时；否则取 instance weight）
LoadBalancer wrr = new WeightedRoundRobinLoadBalancer();
ServiceInstance a = wrr.choose("order-service", candidates, Map.of());

// 一致性哈希（context 必须带 "hashKey"，缺失时回退第一个实例）
LoadBalancer ch = new ConsistentHashLoadBalancer(128);            // 默认 128 虚拟节点
ServiceInstance b = ch.choose("order-service", candidates, Map.of("hashKey", userId));

// 评分选优：权重 × 地域偏好 × CPU/延迟/内存/并发/队列/错误率
LoadBalancerConfig cfg = new LoadBalancerConfig();
cfg.setPreferredRegion("us-east-1");     // 命中 metadata.region 时分数乘 regionBoost（默认 1.1）
cfg.setTargetLatencyMs(50.0);
cfg.setMaxCpuPercent(80);                // 硬阈值，超出直接剔除；-1 表示不启用
MetricsProvider mp = new RedisMetricsProvider(redissonClient, consumerConfig);  // 500ms 本地缓存
ScoredLoadBalancer scored = new ScoredLoadBalancer(cfg, mp);
```

一步到位（`RedisNamingService` 上的便捷方法）：

```java
ServiceInstance chosen = ((RedisNamingService) naming)
        .chooseHealthyInstanceByFilters("order-service", md, mt, scored, Map.of());
```

metrics 键说明：`ScoredLoadBalancer` 默认读 `cpu`、`latency`、`memory`、`inflight`、`queue`、`errorRate`（可用 `cfg.setCpuKey(...)` 等改）。内置采集器产出的是 `processCpuLoad`（0~1）、`heap_usagePercent`、`threadCount` 等键；客户端上报写入 `clientInflight`、`clientLatencyMs`、`clientErrorRate`。两边键名默认不一致，需要通过 `LoadBalancerConfig.setXxxKey(...)` 对齐或由业务方补充同键名数据。

## 6) ClientSelector / ClientInvoker（选择与调用封装）

```java
import io.github.cuihairu.redis.streaming.registry.client.*;
import io.github.cuihairu.redis.streaming.registry.client.metrics.RedisClientMetricsReporter;

// 选择：严格过滤(metadata+metrics) → 去掉 metrics 过滤 → 去掉 metadata 过滤 → 全量健康实例
// 顺序与开关由 ClientSelectorConfig 控制，均默认开启
ClientSelector selector = new ClientSelector(naming, new ClientSelectorConfig());
ServiceInstance picked = selector.select("order-service", md, mt,
        new WeightedRoundRobinLoadBalancer(), Map.of());     // 无候选时返回 null

// 调用：选择 + 单实例熔断 + 指数回退重试 + 客户端指标上报
RetryPolicy retry = new RetryPolicy(3, 20, 2.0, 200, 20);    // attempts, initialDelayMs, factor, maxDelayMs, jitterMs
RedisClientMetricsReporter reporter =
        new RedisClientMetricsReporter(redissonClient, consumerConfig);

ClientInvoker invoker = new ClientInvoker(naming, scored, retry, reporter);

String body = invoker.invoke("order-service", md, mt, Map.of(), ins -> {
    String url = ins.getScheme() + "://" + ins.getHost() + ":" + ins.getPort() + "/api/orders";
    // 发起 HTTP 调用并返回结果；抛异常会触发重试/熔断计数
    return "ok";
});

// 调用计数快照：total + per service，键为 attempts/successes/failures/retries/cbOpenSkips
Map<String, Map<String, Long>> stats = invoker.getMetricsSnapshot();
```

实现细节：`ClientInvoker` 为每个 `serviceName:instanceId` 维护一个 `CircuitBreaker`（窗口 20 次、失败率阈值 0.5、打开 5s、半开 1 次探测）；`RetryPolicy` 为 null 时使用 `new RetryPolicy(3, 10, 2.0, 200, 10)`。`invoke` 声明 `throws Exception`。

## 7) 管理接口（admin）

```java
import io.github.cuihairu.redis.streaming.registry.admin.RegistryAdminService;

RegistryAdminService admin = new RegistryAdminService(redissonClient, new NamingServiceConfig());

Set<String> services = admin.getAllServices();
ServiceDetails details = admin.getServiceDetails("order-service");   // 默认活跃窗口 2 分钟
List<InstanceDetails> active = admin.getActiveInstances("order-service", Duration.ofMinutes(2));
Map<String, Object> health = admin.getRegistryHealth();              // totalServices/totalInstances/healthyInstances/healthyRate
Map<String, Integer> cleaned = admin.cleanupExpiredInstances(Duration.ofMinutes(2));  // 手动清理
```

## 8) 消费端健康探测

`enableHealthCheck=true` 时，`RedisServiceConsumer` 在发现实例时注册探测任务：

```java
ServiceConsumerConfig consumerConfig = new ServiceConsumerConfig();
consumerConfig.setEnableHealthCheck(true);
consumerConfig.setHealthCheckInterval(30);        // 默认 30
consumerConfig.setHealthCheckTimeUnit(TimeUnit.SECONDS);
consumerConfig.setHealthCheckTimeout(5000);       // 毫秒，≤0 回退 5000
```

探测实现按协议选择：HTTP/HTTPS 用 `HttpHealthChecker`（GET `{uri}/health`，2xx~3xx 视为健康，请求异常回退 TCP 连通性）；TCP/UDP 用 `TcpHealthChecker`（连接测试）；WS/WSS 用 `WebSocketHealthChecker`（TCP 连通性）；其余协议（含 gRPC）走 `StandardHealthChecker` 的默认 TCP 连通性探测。自定义逻辑继承 `CustomHealthChecker` 实现 `doCheck`（先 TCP 连通性，3 秒超时）。探测共享一个守护线程池，状态变化时回调 `HEALTH_RECOVERY` / `HEALTH_FAILURE`。

## 参考

- 设计文档: [Registry-Design.md](Registry-Design.md)
- 模块说明: [Registry.md](Registry.md)
