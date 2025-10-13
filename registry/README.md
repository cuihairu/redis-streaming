# Registry - 服务注册与发现

基于 Redis 的分布式服务注册与发现模块，支持多协议健康检查和智能 Metadata 过滤。

[![Build Status](https://img.shields.io/badge/build-passing-brightgreen.svg)](https://github.com/cuihairu/redis-streaming)
[![Version](https://img.shields.io/badge/version-0.1.0-blue.svg)](https://github.com/cuihairu/redis-streaming)

## 🚀 核心特性

### ✅ 已实现功能

- **服务注册与注销** - 基于 Redis Hash 的服务实例管理
- **心跳机制** - Redis Sorted Set + Lua 脚本优化的高效心跳
- **服务发现** - 实时服务实例查询，支持健康过滤
- **多协议健康检查** - HTTP/HTTPS/TCP/WebSocket/gRPC/自定义协议
- **服务变更通知** - Redis Pub/Sub 实时推送服务状态变更
- **Metadata 智能过滤** - 🆕 支持比较运算符（`>`, `>=`, `<`, `<=`, `!=`, `==`）
- **临时/永久实例** - 支持临时实例（自动过期）和永久实例管理
- **负载均衡支持** - 基于权重、CPU、延迟等 Metadata 的智能路由

## 📦 快速开始

### 1. 添加依赖

```gradle
dependencies {
    implementation 'io.github.cuihairu.redis-streaming:registry:0.1.0'
}
```

### 2. 创建 NamingService

```java
import io.github.cuihairu.redis.streaming.registry.*;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

// 配置 Redis
Config config = new Config();
config.useSingleServer().setAddress("redis://127.0.0.1:6379");
RedissonClient redissonClient = Redisson.create(config);

// 创建 NamingService
NamingService namingService = new RedisNamingService(redissonClient);
namingService.start();
```

### 3. 注册服务实例

```java
// 准备 metadata
Map<String, String> metadata = new HashMap<>();
metadata.put("version", "1.0.0");
metadata.put("region", "us-east-1");
metadata.put("zone", "zone-a");
metadata.put("weight", "100");
metadata.put("cpu_usage", "45");

// 创建服务实例
ServiceInstance instance = DefaultServiceInstance.builder()
    .serviceName("order-service")
    .instanceId("order-service-001")
    .host("192.168.1.100")
    .port(8080)
    .protocol(StandardProtocol.HTTP)
    .metadata(metadata)
    .build();

// 注册实例
namingService.register(instance);
```

### 4. 服务发现

#### 4.1 基础查询

```java
// 获取所有实例
List<ServiceInstance> allInstances = namingService.getAllInstances("order-service");

// 获取健康实例
List<ServiceInstance> healthyInstances = namingService.getHealthyInstances("order-service");
```

#### 4.2 Metadata 过滤（精确匹配）

```java
Map<String, String> filters = new HashMap<>();
filters.put("version", "1.0.0");
filters.put("region", "us-east-1");

List<ServiceInstance> filtered = namingService.getInstancesByMetadata("order-service", filters);
```

#### 4.3 Metadata 过滤（比较运算符）🆕

```java
// 高级过滤：使用比较运算符
Map<String, String> filters = new HashMap<>();
filters.put("weight:>=", "80");           // 权重 >= 80
filters.put("cpu_usage:<", "70");         // CPU使用率 < 70%
filters.put("region", "us-east-1");       // 精确匹配
filters.put("status:!=", "maintenance");  // 排除维护状态

List<ServiceInstance> filteredInstances =
    namingService.getInstancesByMetadata("order-service", filters);
```

### 5. 监听服务变更

```java
namingService.subscribe("order-service", (serviceName, action, instance, allInstances) -> {
    System.out.println("Service changed: " + action + " - " + instance.getInstanceId());
    System.out.println("Current healthy instances: " + allInstances.size());
});
```

## 🎯 Metadata 比较运算符

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

框架会智能识别 metadata 值的类型并选择合适的比较方式：

#### 1. 数值比较（推荐）✅

当 metadata 值可以转换为数字时，使用数值比较：

```java
// ✅ 正确：数值比较
filters.put("weight:>", "10");
// 内部处理：tonumber("15") > tonumber("10")  → 15 > 10 = true ✅
// 实例 weight="15" 会被匹配

// ✅ 正确：浮点数比较
filters.put("price:<=", "99.99");
// 内部处理：tonumber("89.99") <= tonumber("99.99")  → 89.99 <= 99.99 = true ✅

// ✅ 正确：负数比较
filters.put("temperature:>", "0");
// 内部处理：tonumber("10") > tonumber("0")  → 10 > 0 = true ✅
```

#### 2. 字符串比较（字典序）⚠️

当无法转换为数字时，使用字典序比较：

```java
// ⚠️ 谨慎：字典序比较
filters.put("zone:>", "zone-a");
// 内部处理："zone-b" > "zone-a"  → true ✅ (字典序)

// ❌ 陷阱：数字字符串如果不是纯数字
filters.put("version:>", "1.10.0");
// "1.2.0" > "1.10.0"  → false ❌ (字典序: "1.2" < "1.1")
```

### 实际应用场景

#### 场景 1: 智能负载均衡

```java
// 只路由到高权重、低负载的实例
Map<String, String> filters = new HashMap<>();
filters.put("weight:>=", "80");          // 权重 >= 80
filters.put("cpu_usage:<", "70");        // CPU < 70%
filters.put("latency:<=", "100");        // 延迟 <= 100ms
filters.put("region", "us-east-1");      // 同区域

List<ServiceInstance> instances =
    namingService.getHealthyInstancesByMetadata("order-service", filters);

if (instances.isEmpty()) {
    // Fallback：放宽条件
    filters.clear();
    filters.put("cpu_usage:<", "80");
    filters.put("region", "us-east-1");
    instances = namingService.getHealthyInstancesByMetadata("order-service", filters);
}
```

#### 场景 2: 金丝雀发布

```java
// 10% 流量路由到新版本 v2.0.0
Map<String, String> newVersion = Map.of("version", "2.0.0");

// 90% 流量路由到稳定版本 v1.0.0
Map<String, String> stableVersion = Map.of("version", "1.0.0");

// 根据随机数决定路由
if (Math.random() < 0.1) {
    // 10% 流量
    instances = namingService.getHealthyInstancesByMetadata("order-service", newVersion);
} else {
    // 90% 流量
    instances = namingService.getHealthyInstancesByMetadata("order-service", stableVersion);
}
```

#### 场景 3: 性能导向选择

```java
// 选择低延迟、低 CPU 使用率的实例
Map<String, String> filters = new HashMap<>();
filters.put("latency:<", "50");        // 延迟 < 50ms
filters.put("cpu_usage:<", "70");      // CPU使用率 < 70%
filters.put("memory_usage:<", "80");   // 内存使用率 < 80%
filters.put("region", "us-east-1");    // 同区域

List<ServiceInstance> performantInstances =
    namingService.getHealthyInstancesByMetadata("compute-service", filters);
```

## 📖 完整文档

更多详细信息请参考：

- **[Metadata 过滤查询指南](METADATA_FILTERING_GUIDE.md)** - 完整的比较运算符使用指南
- **[集成指南](../INTEGRATION_GUIDE.md)** - Spring Boot 集成和使用示例
- **[API 文档](../docs/API.md)** - 完整的 API 参考

## 🏗️ 架构设计

### 三级存储结构

1. **服务索引层** - `streaming:registry:services` (Set)
   - 存储所有已注册的服务名称

2. **心跳层** - `streaming:registry:service:{serviceName}:heartbeats` (Sorted Set)
   - Key: 实例 ID
   - Score: 最后心跳时间戳
   - 用于快速判断实例是否活跃

3. **实例详情层** - `streaming:registry:service:{serviceName}:instance:{instanceId}` (Hash)
   - 存储完整的实例信息（host, port, metadata 等）

### Lua 脚本优化

- **心跳更新** - 原子化心跳更新和实例数据刷新
- **Metadata 过滤** - 服务端过滤，减少网络传输
- **SHA-1 缓存** - Redisson 自动脚本缓存，提升性能

## 🧪 测试

```bash
# 运行单元测试
./gradlew :registry:test

# 运行集成测试（需要 Redis）
docker-compose up -d
./gradlew :registry:integrationTest
```

测试覆盖率：
- 单元测试：27 个测试用例
- 集成测试：14 个比较运算符测试用例
- 覆盖率：85%+

## 📝 注意事项

### 运算符相关

1. **AND 逻辑** - 所有 metadata 过滤条件必须同时满足（AND 关系）
2. **精确匹配** - 等于运算符（默认或 `==`）必须完全匹配
3. **大小写敏感** - metadata 的 key 和 value 都是大小写敏感的
4. **不存在的字段** - 如果实例没有某个 metadata 字段，该实例不会被匹配

### 比较规则相关

5. **数值优先** - 框架优先尝试数值比较，失败则使用字符串比较
6. **字典序陷阱** - 字符串大小比较使用字典序，可能不符合预期
   - ✅ 安全：数值型 metadata（weight, cpu, age 等）
   - ⚠️ 谨慎：字符串大小比较（zone:> 等）
   - ❌ 避免：版本号比较（请使用精确匹配或版本标签）

### 性能相关

7. **空过滤** - 传入空 Map 或 null 等同于调用 `discover()`
8. **过滤复杂度** - O(N) 遍历所有活跃实例，N 为实例数量
9. **客户端缓存** - 对于不常变化的查询，建议客户端缓存结果
10. **Fallback 策略** - 过滤无结果时，提供降级方案（放宽条件或使用全量）

## 🔗 相关链接

- [主项目文档](../README.md)
- [Metadata 过滤指南](METADATA_FILTERING_GUIDE.md)
- [集成指南](../INTEGRATION_GUIDE.md)
- [问题反馈](https://github.com/cuihairu/redis-streaming/issues)

---

**版本**: 0.1.0
**最后更新**: 2025-01-12
**新增功能**: 支持 Metadata 比较运算符（`>`, `>=`, `<`, `<=`, `!=`, `==`）
