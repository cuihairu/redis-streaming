# Config Center

Module: `config/`

Configuration publishing and subscription.

## Scope
- 配置发布/订阅、变更监听（Redis Pub/Sub）
- 配置历史与版本管理（详见实现）

## Key Classes
- `ConfigService` / `ConfigCenter`
- `impl.RedisConfigService` / `impl.RedisConfigCenter`

## Minimal Sample (RedisConfigService)
```java
import io.github.cuihairu.redis.streaming.config.impl.RedisConfigService;
import org.redisson.Redisson;
import org.redisson.config.Config;

Config cfg = new Config();
cfg.useSingleServer().setAddress("redis://127.0.0.1:6379");
var redisson = Redisson.create(cfg);

RedisConfigService configService = new RedisConfigService(redisson);
configService.start();
configService.addListener("business.rules", "production", (dataId, group, content, version) -> {
    // handle changes
});
configService.publishConfig("business.rules", "production", "{\"v\":1}");
```

## References
- Spring-Boot-Starter.md
