# Registry Guide (EN)

Quick usage for service registration, discovery and change subscription.

## 1) Auto Registration
```yaml
streaming:
  registry:
    enabled: true
    auto-register: true
    instance:
      service-name: ${spring.application.name}
```

## 2) Discover Services
```java
List<ServiceInstance> list = serviceDiscovery.discoverHealthy("payment-service");
```

## 3) Listen for Changes
```java
@ServiceChangeListener(services = {"payment-service"})
public void onChange(String svc, String action, ServiceInstance inst, List<ServiceInstance> all) {
  // update client cache
}
```

## References
- docs: docs/redis-registry-usage.md
- design: Registry-Design-en.md
