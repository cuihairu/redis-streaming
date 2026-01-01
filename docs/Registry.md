# Service Registry

Module: `registry/`

Service registration and discovery.

## Scope
- 服务注册/注销、心跳维持健康状态
- 服务发现与订阅变更通知（Redis Pub/Sub）
- 支持 metadata 过滤（比较运算符）与负载均衡策略

## Key Concepts
- 命名服务（`NamingService`）统一 Provider/Consumer/Registry/Discovery 视角
- 心跳与元数据存储、健康检查机制：见 `Registry-Design*`

## Minimal Sample
```java
import io.github.cuihairu.redis.streaming.registry.DefaultServiceInstance;
import io.github.cuihairu.redis.streaming.registry.NamingService;
import io.github.cuihairu.redis.streaming.registry.StandardProtocol;
import io.github.cuihairu.redis.streaming.registry.impl.RedisNamingService;
import org.redisson.Redisson;
import org.redisson.config.Config;

Config cfg = new Config();
cfg.useSingleServer().setAddress("redis://127.0.0.1:6379");
var redisson = Redisson.create(cfg);

NamingService naming = new RedisNamingService(redisson);
naming.start();

var instance = DefaultServiceInstance.builder()
        .serviceName("order-service")
        .instanceId("order-1")
        .host("127.0.0.1")
        .port(8080)
        .protocol(StandardProtocol.HTTP)
        .build();

naming.register(instance);
var healthy = naming.getHealthyInstances("order-service");
```

## References
- Registry-Design.md
- docs/redis-registry-usage.md
