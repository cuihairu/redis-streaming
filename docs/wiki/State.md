# State Management

Module: `state/`

Distributed state primitives: Value/Map/List/Set state and backends.

## Scope
- 提供 `core` 层状态抽象的具体实现（Redis-backed）
- 说明：`runtime` 目前的 keyed state 为 in-memory 实现；与 `state/` 的端到端集成仍在演进中

## Key Classes
- `io.github.cuihairu.redis.streaming.state.backend.StateBackend`
- `io.github.cuihairu.redis.streaming.state.redis.RedisStateBackend`

## Minimal Sample (Redis backend)
```java
import io.github.cuihairu.redis.streaming.api.state.StateDescriptor;
import io.github.cuihairu.redis.streaming.api.state.ValueState;
import io.github.cuihairu.redis.streaming.state.redis.RedisStateBackend;
import org.redisson.Redisson;
import org.redisson.config.Config;

Config cfg = new Config();
cfg.useSingleServer().setAddress("redis://127.0.0.1:6379");
var redisson = Redisson.create(cfg);

RedisStateBackend backend = new RedisStateBackend(redisson, "state:demo:");
ValueState<Integer> counter = backend.createValueState(new StateDescriptor<>("counter", Integer.class));

Integer v = counter.value();
counter.update(v == null ? 1 : v + 1);
```

## References
- Design.md
