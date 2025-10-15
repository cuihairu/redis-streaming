# NamingService 命名服务

## 概述

NamingService 是服务注册中心的核心组件，提供了服务注册、发现、订阅等完整功能。它基于 Redis 实现，利用 Redis 的 Hash、Pub/Sub 和 Sorted Set 数据结构来实现高效的服务管理。

## 核心功能

1. **服务注册 (Service Registration)** - 服务启动时注册自身信息
2. **服务发现 (Service Discovery)** - 动态发现可用的服务实例
3. **服务订阅 (Service Subscription)** - 实时监听服务变化
4. **健康检查 (Health Check)** - 自动检测服务实例健康状态
5. **心跳机制 (Heartbeat)** - 维持服务实例活跃状态

## 架构设计

```
ServiceProvider ←→ Registry Center (Redis) ←→ ServiceDiscovery
       ↑                                        ↑
       |                                        |
       +-------- ServiceConsumer --------------+
                ↑
                |
         NamingService
```

## 主要接口

### NamingService
```java
public interface NamingService extends ServiceProvider, ServiceDiscovery {
    HealthCheckManager getHealthCheckManager();
    void start();
    void stop();
    boolean isRunning();
}
```

### ServiceProvider
```java
public interface ServiceProvider {
    void register(ServiceInstance serviceInstance);
    void deregister(ServiceInstance serviceInstance);
    void sendHeartbeat(ServiceInstance serviceInstance);
    HealthCheckManager getHealthCheckManager();
    void start();
    void stop();
    boolean isRunning();
}
```

### ServiceDiscovery
```java
public interface ServiceDiscovery {
    List<ServiceInstance> discover(String serviceName);
    void subscribe(String serviceName, Consumer<List<ServiceInstance>> listener);
    void unsubscribe(String serviceName);
    void start();
    void stop();
    boolean isRunning();
}
```

## 使用示例

### 1. 基本使用
```java
// 创建 Redisson 客户端
Config config = new Config();
config.useSingleServer().setAddress("redis://127.0.0.1:6379");
RedissonClient redissonClient = Redisson.create(config);

// 创建命名服务
NamingService namingService = new RedisNamingService(redissonClient);

// 启动服务
namingService.start();

// 创建服务实例
ServiceInstance serviceInstance = new RedisServiceInstance(
    "example-service",  // 服务名称
    "instance-1",       // 实例ID
    "localhost",        // 主机地址
    8080                // 端口
);

// 注册服务
namingService.register(serviceInstance);

// 发现服务
List<ServiceInstance> instances = namingService.discover("example-service");

// 发送心跳
namingService.sendHeartbeat(serviceInstance);

// 注销服务
namingService.deregister(serviceInstance);

// 停止服务
namingService.stop();
```

### 2. 服务订阅
```java
// 订阅服务变化
namingService.subscribe("example-service", instances -> {
    System.out.println("Service instances changed: " + instances.size());
    instances.forEach(instance -> 
        System.out.println("  - " + instance.getInstanceId())
    );
});
```

## 实现类

### RedisNamingService
基于 Redis 的 NamingService 实现，组合了 RedisServiceProvider 和 RedisServiceDiscovery。

### RedisNamingService
服务注册与发现的组合实现，同时实现了 ServiceRegistry, ServiceProvider, ServiceConsumer 和 NamingService 接口。

## 数据结构设计

1. **服务信息存储** - 使用 Redis Hash 存储服务实例的详细信息
2. **服务列表管理** - 使用 Redis Set 维护每个服务的所有实例
3. **服务变更通知** - 使用 Redis Pub/Sub 实现服务变化的实时通知
4. **心跳检测** - 使用 Redis Sorted Set 实现心跳时间和健康检查

## 配置说明

确保 Redis 服务器正常运行，并在代码中正确配置 Redisson 客户端连接信息。

## 最佳实践

1. **及时启停** - 使用完 NamingService 后应及时调用 stop() 方法释放资源
2. **异常处理** - 注意捕获和处理 IllegalStateException 等运行时异常
3. **资源管理** - 合理管理 RedissonClient 的生命周期
4. **心跳维护** - 定期发送心跳以维持服务实例的活跃状态