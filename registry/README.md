# Registry - 服务注册与发现

基于 Redis 的分布式服务注册与发现模块，支持多协议健康检查与 metadata/metrics 过滤。

[![Build Status](https://img.shields.io/badge/build-passing-brightgreen.svg)](https://github.com/cuihairu/redis-streaming)
[![Version](https://img.shields.io/badge/version-0.2.0-blue.svg)](https://github.com/cuihairu/redis-streaming)

## 核心特性

- 服务注册与注销基于 Redis Hash，注册/注销走 Lua 原子脚本
- 心跳用 ZSet + 实例 Hash，按 metadata/metrics 分级更新（Lua 脚本原子执行）
- 临时/永久实例：`ephemeral=true`（默认）心跳超时清理并带滑动 TTL；`ephemeral=false` 只标记 unhealthy 不删除
- 服务发现支持健康过滤的实时实例查询
- metadata/metrics 过滤在服务端 Lua 完成，支持比较运算符（`>`, `>=`, `<`, `<=`, `!=`, `==`）
- 多协议健康检查覆盖 HTTP/HTTPS（GET /health）、TCP/UDP、WS/WSS，其他协议走 TCP 连通性，`CustomHealthChecker` 可自定义
- 服务变更经 Redis Pub/Sub 推送 `ADDED`/`REMOVED`/`UPDATED`/`CURRENT`/`HEALTH_RECOVERY`/`HEALTH_FAILURE`
- 客户端负载均衡提供加权轮询、加权随机、一致性哈希、按权重+地域+指标评分
- 调用封装：`ClientSelector` 过滤降级回退、`ClientInvoker` 熔断+重试+指标上报

## 快速开始

### 1. 添加依赖

```gradle
dependencies {
    implementation 'io.github.cuihairu.redis-streaming:registry:0.2.0'
}
```

### 2. 创建 NamingService

```java
import io.github.cuihairu.redis.streaming.registry.*;
import io.github.cuihairu.redis.streaming.registry.impl.RedisNamingService;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

Config config = new Config();
config.useSingleServer().setAddress("redis://127.0.0.1:6379");
RedissonClient redissonClient = Redisson.create(config);

// 自定义键前缀时传入 NamingServiceConfig("myapp")，默认前缀 redis_streaming_registry
NamingService namingService = new RedisNamingService(redissonClient);
namingService.start();   // 未 start 时 register/discover 抛 IllegalStateException
```

### 3. 注册服务实例

```java
Map<String, String> metadata = new HashMap<>();
metadata.put("version", "1.0.0");
metadata.put("region", "us-east-1");
metadata.put("weight", "100");

ServiceInstance instance = DefaultServiceInstance.builder()
    .serviceName("order-service")
    .instanceId("order-service-001")
    .host("192.168.1.100")
    .port(8080)
    .protocol(StandardProtocol.HTTP)
    .weight(100)
    .metadata(metadata)
    .build();

namingService.register(instance);
```

注意：`serviceName` 与 `instanceId` 不能包含 `:`（注册前会被 `RegistryKeys.validateAndSanitizeXxx` 校验，含 `:` 抛异常）。

### 4. 服务发现

```java
// 所有实例（心跳窗口内）
List<ServiceInstance> allInstances = namingService.getAllInstances("order-service");

// 健康实例
List<ServiceInstance> healthyInstances = namingService.getHealthyInstances("order-service");

// metadata 过滤（等值）
Map<String, String> filters = new HashMap<>();
filters.put("version", "1.0.0");
filters.put("region", "us-east-1");
List<ServiceInstance> filtered = namingService.getInstancesByMetadata("order-service", filters);

// metadata 过滤（比较运算符）
Map<String, String> cmp = new HashMap<>();
cmp.put("weight:>=", "80");            // weight >= 80
cmp.put("cpu_usage:<", "70");          // cpu_usage < 70
cmp.put("region", "us-east-1");        // 等值
cmp.put("status:!=", "maintenance");
List<ServiceInstance> filteredInstances =
    namingService.getHealthyInstancesByMetadata("order-service", cmp);
```

### 5. 监听服务变更

```java
namingService.subscribe("order-service", (serviceName, action, instance, allInstances) -> {
    System.out.println(action + " - " + instance.getInstanceId());
    System.out.println("current instances: " + allInstances.size());
});
```

`action` 是 `ServiceChangeAction` 枚举（`toString()` 返回小写值）。Spring Boot 下也可用 `@ServiceChangeListener` 注解，见 [docs/Registry-Guide.md](../docs/Registry-Guide.md)。

## 客户端负载均衡

推荐做法：先用服务端过滤缩小候选集，再在客户端按 metadata/metrics 选优。

### 1) 构建过滤条件（可选）

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

// getHealthyInstancesByFilters 定义在 RedisNamingService 上
RedisNamingService impl = (RedisNamingService) namingService;
List<ServiceInstance> candidates = impl.getHealthyInstancesByFilters("order-service", md, mt);
```

### 2) 选择策略

```java
import io.github.cuihairu.redis.streaming.registry.loadbalancer.*;

// 加权轮询（平滑）：权重优先取 metadata.weight（能解析为整数时），否则取 instance weight
LoadBalancer wrr = new WeightedRoundRobinLoadBalancer();
ServiceInstance chosen1 = wrr.choose("order-service", candidates, Map.of());

// 一致性哈希：context 需带 "hashKey"，缺失时回退第一个实例；默认 128 虚拟节点
LoadBalancer ch = new ConsistentHashLoadBalancer(128);
ServiceInstance chosen2 = ch.choose("order-service", candidates, Map.of("hashKey", userId));

// 评分选优：权重 × 地域偏好 × CPU/延迟等指标，硬阈值超限直接剔除
LoadBalancerConfig cfg = new LoadBalancerConfig();
cfg.setPreferredRegion("us-east-1");   // 命中 metadata.region 时分数乘 regionBoost（默认 1.1）
cfg.setCpuWeight(1.0);
cfg.setLatencyWeight(1.0);

// 从实例 Hash 的 metrics JSON 读指标（本地 500ms 缓存）
MetricsProvider mp = new RedisMetricsProvider(redissonClient, new ServiceConsumerConfig());
LoadBalancer scored = new ScoredLoadBalancer(cfg, mp);
ServiceInstance chosen3 = scored.choose("order-service", candidates, Map.of());
```

一步到位：

```java
ServiceInstance chosen = impl.chooseHealthyInstanceByFilters("order-service", md, mt, scored, Map.of());
```

过滤结果为空时可回退到放宽条件或全量健康实例再做负载均衡。

### ClientSelector 一站式选择（含降级回退）

```java
import io.github.cuihairu.redis.streaming.registry.client.*;

ClientSelector selector = new ClientSelector(namingService, new ClientSelectorConfig());

// 严格过滤 (metadata+metrics) 无候选时依次回退：
// 去掉 metrics 过滤 -> 去掉 metadata 过滤 -> 全量健康实例；仍无候选返回 null
// 顺序与开关由 ClientSelectorConfig 的三个 fallback* 开关控制，默认全开
ServiceInstance picked = selector.select(
  "order-service", md, mt, new WeightedRoundRobinLoadBalancer(), Map.of());
```

## 客户端调用封装（熔断 + 重试 + 指标上报）

```java
import io.github.cuihairu.redis.streaming.registry.client.*;
import io.github.cuihairu.redis.streaming.registry.client.metrics.RedisClientMetricsReporter;

ServiceConsumerConfig consumerConfig = new ServiceConsumerConfig();
LoadBalancer lb = new ScoredLoadBalancer(new LoadBalancerConfig(),
        new RedisMetricsProvider(redissonClient, consumerConfig));
RetryPolicy retry = new RetryPolicy(3, 20, 2.0, 200, 20);
RedisClientMetricsReporter reporter = new RedisClientMetricsReporter(redissonClient, consumerConfig);

ClientInvoker invoker = new ClientInvoker(namingService, lb, retry, reporter);

Map<String, String> md = Map.of("region", "us-east-1");
Map<String, String> mt = Map.of("cpu:<", "80");
String body = invoker.invoke("order-service", md, mt, Map.of(), ins -> {
    String url = ins.getScheme() + "://" + ins.getHost() + ":" + ins.getPort() + "/api/orders";
    // 发起 HTTP 调用；抛异常会触发重试与熔断计数
    return "ok";
});
```

- 重试：指数回退 + 抖动，失败后重新选择实例；`RetryPolicy` 传 null 时 `ClientInvoker` 默认 `(3, 10, 2.0, 200, 10)`
- 熔断：每个 `serviceName:instanceId` 一个 `CircuitBreaker`（窗口 20 次、失败率阈值 0.5、打开 5 秒、半开 1 次探测），打开期间直接跳过该实例
- 指标上报：实例 Hash `metrics` JSON 中的 `clientInflight` / `clientLatencyMs` / `clientErrorRate`，与服务端心跳写入的指标合并、互不覆盖

### 观测接口

```java
// ClientInvoker 计数快照（total + per service）
// 键：attempts, successes, failures, retries, cbOpenSkips
Map<String, Map<String, Long>> stats = invoker.getMetricsSnapshot();
```

## 生产建议配置

- 目标与阈值
  - `ScoredLoadBalancer` 建议按业务设置 `targetLatencyMs`（如 50~100ms）
  - 硬阈值（超出即剔除，默认 -1 关闭）：`maxCpuPercent`、`maxLatencyMs`、`maxMemoryPercent`、`maxInflight`、`maxQueue`、`maxErrorRatePercent`，例如 `maxCpuPercent=80`、`maxLatencyMs=200`、`maxErrorRatePercent=5`

- 地域与分区偏好
  - `preferredRegion` / `preferredZone` 配合 `regionBoost`（默认 1.1）/ `zoneBoost`（默认 1.05）
  - 需要在实例 metadata 中维护 `region` / `zone`

- metrics 键名对齐
  - `ScoredLoadBalancer` 默认读 `cpu`、`latency`、`memory`、`inflight`、`queue`、`errorRate`，可用 `setCpuKey(...)` 等修改
  - 内置采集器产出的是 `processCpuLoad`（0~1）、`heap_usagePercent`、`threadCount`、`rxBytes`/`txBytes` 等键；客户端上报写 `clientInflight`/`clientLatencyMs`/`clientErrorRate`。二者与 LB 默认键不一致，需用 `setXxxKey` 对齐或由业务方补充同键名指标

- 回退策略
  - 用 `ClientSelector` 统一「严格过滤 → 放宽 → 全量健康」，保证高峰/抖动时平滑退化

## Metadata 比较运算符

### 支持的运算符

| 运算符 | 语法 | 说明 | 示例 |
|--------|------|------|------|
| 等于（默认） | `"field"` 或 `"field:=="` | 精确匹配 | `"version": "1.0.0"` |
| 不等于 | `"field:!="` | 不等于指定值 | `"status:!=": "down"` |
| 大于 | `"field:>"` | 大于指定值 | `"weight:>": "10"` |
| 大于等于 | `"field:>="` | 大于或等于 | `"cpu:>=": "50"` |
| 小于 | `"field:<"` | 小于指定值 | `"latency:<": "100"` |
| 小于等于 | `"field:<="` | 小于或等于 | `"memory:<=": "80"` |

### 比较规则

过滤在服务端 Lua 执行，先尝试把两侧转为数字做数值比较；任一侧无法转数字时回退字典序比较：

```java
// 数值比较
filters.put("weight:>", "10");     // weight="15" -> 15 > 10，匹配
filters.put("price:<=", "99.99");  // "89.99" <= "99.99"，匹配

// 字典序比较（谨慎）
filters.put("zone:>", "zone-a");   // "zone-b" > "zone-a"，按字典序匹配

// 版本号陷阱：非纯数字串走字典序
filters.put("version:>", "1.10.0"); // "1.2.0" > "1.10.0" 为 false
```

### 应用场景

```java
// 场景 1：只路由到高权重、低负载的实例；无结果时放宽条件
Map<String, String> filters = new HashMap<>();
filters.put("weight:>=", "80");
filters.put("cpu_usage:<", "70");
List<ServiceInstance> instances =
    namingService.getHealthyInstancesByMetadata("order-service", filters);
if (instances.isEmpty()) {
    filters.clear();
    filters.put("cpu_usage:<", "80");
    instances = namingService.getHealthyInstancesByMetadata("order-service", filters);
}

// 场景 2：按版本分流（等值匹配，不要用范围运算符比较版本号）
Map<String, String> newVersion = Map.of("version", "2.0.0");
List<ServiceInstance> canary =
    namingService.getHealthyInstancesByMetadata("order-service", newVersion);
```

## 架构设计

### 三级存储结构

前缀默认 `redis_streaming_registry`（`registry.BaseRedisConfig.DEFAULT_KEY_PREFIX`），键模板见 `keys.RegistryKeys`：

1. 服务索引层 `{prefix}:services`（Set）：存储所有已注册的服务名
2. 心跳层 `{prefix}:services:{serviceName}:heartbeats`（ZSet）：score=最后心跳时间戳、member=instanceId
3. 实例详情层 `{prefix}:services:{serviceName}:instance:{instanceId}`（Hash）：字段含 host/port/protocol/enabled/healthy/weight/ephemeral/metadata(JSON)/metrics(JSON)/registrationTime/lastHeartbeatTime 等

另有通知通道 `{prefix}:services:{serviceName}:changes`（Pub/Sub）。

### Lua 脚本

注册、注销、心跳更新、过期清理、活跃实例查询、metadata/metrics 过滤均由 `lua.RegistryLuaScriptExecutor` 中的 Lua 脚本原子执行（Redisson `RScript`）。

## 测试

```bash
# 运行单元测试（无需 Redis）
./gradlew :registry:test

# 运行集成测试（需要 Redis）
docker-compose up -d
./gradlew :registry:integrationTest
```

当前规模（统计自源码）：
- 单元测试：1271 个 `@Test` 方法（`registry/src/test` 下 grep 统计）
- 比较运算符专项：`ComparisonOperatorTest` 15 个用例
- 覆盖率：`./gradlew :registry:jacocoTestReport` 生成的最近一份报告为行覆盖 97.5%、分支覆盖 89.8%（仅单元测试口径）

## 注意事项

1. `start()` 之后才能 `register`/`discover`，否则抛 `IllegalStateException`
2. 心跳由调用方驱动，模块不内置心跳定时器；临时实例需调用方按间隔调用 `sendHeartbeat`（Spring Boot starter 默认 30 秒）
3. 所有过滤条件同时满足（AND）；字段不存在的实例不会被匹配
4. metadata 的 key 与 value 均区分大小写
5. 比较先数值后字典序；版本号请用等值匹配
6. 传空 Map 等价于返回全部活跃实例；过滤遍历为 O(活跃实例数)
7. service name / instance ID 中的 `:`、空白字符会被清洗为 `_`/`-`；含 `:` 的 instance ID 在注册时抛异常
8. 不常变化的过滤查询建议在客户端缓存结果

## 相关链接

- [模块文档 docs/Registry.md](../docs/Registry.md)
- [使用指南 docs/Registry-Guide.md](../docs/Registry-Guide.md)
- [设计文档 docs/Registry-Design.md](../docs/Registry-Design.md)
- [主项目 README](../README.md)
- [集成指南 INTEGRATION_GUIDE.md](../INTEGRATION_GUIDE.md)
- [问题反馈](https://github.com/cuihairu/redis-streaming/issues)

---

版本 0.2.0，最后更新 2026-10-04
