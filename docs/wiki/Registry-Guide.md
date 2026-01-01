# 注册中心指南

快速说明服务注册、发现与变更订阅的使用方法。

## 1) 自动注册
```yaml
streaming:
  registry:
    enabled: true
    auto-register: true
    instance:
      service-name: ${spring.application.name}
```

## 2) 服务发现
```java
List<ServiceInstance> list = serviceDiscovery.discoverHealthy("payment-service");
```

## 3) 变更订阅
```java
@ServiceChangeListener(services = {"payment-service"})
public void onChange(String service, String action,
                     ServiceInstance inst,
                     List<ServiceInstance> all) {
  // 更新客户端缓存
}
```

## 参考
- 使用文档: docs/redis-registry-usage.md
- 设计文档: Registry-Design.md
